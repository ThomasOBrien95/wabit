/*
 * Copyright (c) 2009, SQL Power Group Inc.
 *
 * This file is part of Wabit.
 *
 * Wabit is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Wabit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.wabit.enterprise.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.swing.SwingUtilities;

import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ca.sqlpower.sql.DataSourceCollection;
import ca.sqlpower.sql.PlDotIni;
import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.util.UserPrompter.UserPromptOptions;
import ca.sqlpower.util.UserPrompter.UserPromptResponse;
import ca.sqlpower.util.UserPrompterFactory.UserPromptType;
import ca.sqlpower.wabit.WabitSession;
import ca.sqlpower.wabit.WabitSessionContext;
import ca.sqlpower.wabit.WabitSessionImpl;
import ca.sqlpower.wabit.WabitWorkspace;
import ca.sqlpower.wabit.dao.MessageSender;
import ca.sqlpower.wabit.dao.WabitPersistenceException;
import ca.sqlpower.wabit.dao.WabitSessionPersister;
import ca.sqlpower.wabit.dao.json.JSONHttpMessageSender;
import ca.sqlpower.wabit.dao.json.WabitJSONMessageDecoder;
import ca.sqlpower.wabit.dao.json.WabitJSONPersister;
import ca.sqlpower.wabit.dao.session.WorkspacePersisterListener;
import ca.sqlpower.wabit.http.WabitHttpResponseHandler;
import ca.sqlpower.wabit.swingui.WabitSwingSessionContext;

/**
 * A special kind of session that binds itself to a remote Wabit Enterprise
 * Server. Provides database connection information and file storage capability
 * based on the remote server.
 */
public class WabitServerSession extends WabitSessionImpl {
    
    private static final Logger logger = Logger.getLogger(WabitServerSession.class);
    
    private final Updater updater;

    /**
     * This workspace's location information.
     */
	private final WorkspaceLocation workspaceLocation;

	private final HttpClient outboundHttpClient;
	
	/**
	 * Handles output Wabit persistence calls for this WabitServerSession
	 */
	private final WabitJSONPersister jsonPersister;

	/**
	 * Applies Wabit persistence calls coming from a Wabit server to this WabitServerSession
	 */
	private final WabitSessionPersister sessionPersister;
	
	private static CookieStore cookieStore = new BasicCookieStore();
	
    public WabitServerSession(
    		@Nonnull WorkspaceLocation workspaceLocation,
    		@Nonnull WabitSessionContext context) {
        super(context);
		this.workspaceLocation = workspaceLocation;
        if (workspaceLocation == null) {
        	throw new NullPointerException("workspaceLocation must not be null");
        }

        outboundHttpClient = createHttpClient(workspaceLocation.getServiceInfo());
        
        getWorkspace().setUUID(workspaceLocation.getUuid());
        getWorkspace().setName("Loading Workspace...");
        getWorkspace().setSession(this); // XXX leaking a reference to partially-constructed session!
        
        
        sessionPersister = new WabitSessionPersister(
        		"inbound-" + workspaceLocation.getUuid(),
        		WabitServerSession.this);
        // Whatever updates come from the server, it can override the user's stuff.
        sessionPersister.setGodMode(true);
        updater = new Updater(workspaceLocation.getUuid(), new WabitJSONMessageDecoder(sessionPersister));
        
        MessageSender<JSONObject> httpSender = new JSONHttpMessageSender(outboundHttpClient, workspaceLocation.getServiceInfo(),
        		workspaceLocation.getUuid());
		jsonPersister = new WabitJSONPersister(httpSender);
    }

	public static HttpClient createHttpClient(WabitServerInfo serviceInfo) {
		HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setConnectionTimeout(params, 2000);
        DefaultHttpClient httpClient = new DefaultHttpClient(params);
        httpClient.setCookieStore(cookieStore);
        httpClient.getCredentialsProvider().setCredentials(
            new AuthScope(serviceInfo.getServerAddress(), AuthScope.ANY_PORT), 
            new UsernamePasswordCredentials(serviceInfo.getUsername(), serviceInfo.getPassword()));
        return httpClient;
	}

    @Override
    public boolean close() {
    	logger.debug("Closing Client Session");
    	try {
    		HttpUriRequest request = new HttpDelete(getServerURI(workspaceLocation.getServiceInfo(), 
    				"session/" + getWorkspace().getUUID()));
			outboundHttpClient.execute(request, new BasicResponseHandler());
		} catch (Exception e) {
			try {
				logger.error(e);
				getContext().createUserPrompter("Cannot access the server to close the server session", 
						UserPromptType.MESSAGE, UserPromptOptions.OK, UserPromptResponse.OK, UserPromptResponse.OK, "OK");
			} catch (Throwable t) {
				//do nothing here because we failed on logging the error.
			}
		}
        outboundHttpClient.getConnectionManager().shutdown();
        updater.interrupt();
        return super.close();
    }

    /**
     * Returns the location this workspace was loaded from.
     */
    public WorkspaceLocation getWorkspaceLocation() {
        return workspaceLocation;
    }
    
    /**
     * Retrieves the data source list from the server.
     * <p>
     * Future plans: In the future, the server will probably be a proxy for all
     * database operations, and we won't actually send the connection
     * information to the client. This has the advantage that it can work over
     * an HTTP firewall or proxy, where the present method would fail.
     */
    @Override
    public DataSourceCollection<SPDataSource> getDataSources() {
        ResponseHandler<DataSourceCollection<SPDataSource>> plIniHandler = 
            new ResponseHandler<DataSourceCollection<SPDataSource>>() {
            public DataSourceCollection<SPDataSource> handleResponse(HttpResponse response)
                    throws ClientProtocolException, IOException {
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new IOException(
                            "Server error while reading data sources: " + response.getStatusLine());
                }
                PlDotIni plIni;
                try {
                    plIni = new PlDotIni(getServerURI(workspaceLocation.getServiceInfo(), "jdbc/"));
                    plIni.read(response.getEntity().getContent());
                    logger.debug("Data source collection has URI " + plIni.getServerBaseURI());
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                return plIni;
            }
        };
        try {
            return executeServerRequest(outboundHttpClient, workspaceLocation.getServiceInfo(), "data-sources/", plIniHandler);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    
    /**
     * List all the workspaces on this context's server.
     * 
     * @param serviceInfo
     * @return
     * @throws IOException
     * @throws URISyntaxException
     * @throws JSONException 
     */
    public static List<WorkspaceLocation> getWorkspaceNames(WabitServerInfo serviceInfo) throws IOException, URISyntaxException, JSONException {
    	HttpClient httpClient = createHttpClient(serviceInfo);
    	try {
    		HttpUriRequest request = new HttpGet(getServerURI(serviceInfo, "workspaces"));
    		String responseBody = httpClient.execute(request, new BasicResponseHandler());
    		JSONArray response;
    		List<WorkspaceLocation> workspaces = new ArrayList<WorkspaceLocation>();
    		response = new JSONArray(responseBody);
    		logger.debug("Workspace list:\n" + responseBody);
    		for (int i = 0; i < response.length(); i++) {
    			JSONObject workspace = (JSONObject) response.get(i);
    			workspaces.add(new WorkspaceLocation(
    					workspace.getString("name"),
    					workspace.getString("UUID"),
    					serviceInfo));
    		}
    		return workspaces;
    	} finally {
    		httpClient.getConnectionManager().shutdown();
    	}
    }

	/**
	 * Sends an HTTP request to a Wabit Enterprise Server to create a new remote
	 * Wabit Workspace on that server.
	 * 
	 * @param serviceInfo
	 *            A {@link WabitServerInfo} containing the connection
	 *            information for that server
	 * @return The {@link WorkspaceLocation} of the newly created remote
	 *         WabitWorkspace
	 * @throws URISyntaxException
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @throws JSONException
	 */
    public static WorkspaceLocation createNewServerSession(WabitServerInfo serviceInfo) throws URISyntaxException, ClientProtocolException, IOException, JSONException {
    	HttpClient httpClient = createHttpClient(serviceInfo);
    	try {
    		HttpUriRequest request = new HttpPost(getServerURI(serviceInfo, "workspaces"));
    		String responseBody = httpClient.execute(request, new BasicResponseHandler());
    		JSONObject response = new JSONObject(responseBody);
    		logger.debug("New Workspace:" + responseBody);
    		return new WorkspaceLocation(
    					response.getString("name"),
    					response.getString("UUID"),
    					serviceInfo);
    	} finally {
    		httpClient.getConnectionManager().shutdown();
    	}
    }

    public void deleteServerWorkspace() throws URISyntaxException, ClientProtocolException, IOException {
    	WabitServerInfo serviceInfo = workspaceLocation.getServiceInfo();
    	HttpClient httpClient = createHttpClient(serviceInfo);
    	try {
    		HttpUriRequest request = new HttpDelete(getServerURI(serviceInfo, "workspaces/" + getWorkspace().getUUID()));
    		httpClient.execute(request, new WabitHttpResponseHandler());
    	} finally {
    		httpClient.getConnectionManager().shutdown();
    	}
    }
    
	/**
	 * Finds and opens a specific Wabit Workspace from the given
	 * {@link WorkspaceLocation}. The new session will keep itself up-to-date by
	 * polling the server for new state. Likewise, local changes to the session will be pushed its own
	 * updates back to the server.
	 * 
	 * @param context
	 *            The context to register the new remote WabitSession with
	 * @param workspaceLoc
	 *            A {@link WorkspaceLocation} detailing the location of the
	 *            remote workspace to be opened
	 * @return A remote WabitSession based on the given workspace
	 */
    public static WabitServerSession openServerSession(WabitSessionContext context, WorkspaceLocation workspaceLoc) {
    	final WabitServerSession session = new WabitServerSession(workspaceLoc, context);
		context.registerChildSession(session);
		session.startUpdaterThread();
		return session;
    }
	
	/**
	 * Finds and opens all visible Wabit workspaces on the given Wabit Enterprise Server.
	 * Calling this method essentially constitutes "logging in" to the given server.
	 * 
	 * @param context the context to add the newly-retrieved sessions to
	 * @param serverInfo The server to contact.
	 * @return the list of sessions that were opened.
	 * @throws JSONException 
	 * @throws URISyntaxException 
	 * @throws IOException 
	 */
	public static List<WabitServerSession> openServerSessions(WabitSessionContext context, WabitServerInfo serverInfo) throws IOException, URISyntaxException, JSONException {
		List<WabitServerSession> openedSessions = new ArrayList<WabitServerSession>();
		for (WorkspaceLocation workspaceLoc : WabitServerSession.getWorkspaceNames(serverInfo)) {
			openedSessions.add(openServerSession(context, workspaceLoc));
		}
        return openedSessions;
    }

    private static <T> T executeServerRequest(HttpClient httpClient, WabitServerInfo serviceInfo, 
            String contextRelativePath, ResponseHandler<T> responseHandler)
    throws IOException, URISyntaxException {
        HttpUriRequest request = new HttpGet(getServerURI(serviceInfo, contextRelativePath));
        return httpClient.execute(request, responseHandler);
    }
    
    private static URI getServerURI(WabitServerInfo serviceInfo, String contextRelativePath) throws URISyntaxException {
        logger.debug("Getting server URI for: " + serviceInfo);
        String contextPath = serviceInfo.getPath();
        URI serverURI = new URI("http", null, serviceInfo.getServerAddress(), serviceInfo.getPort(),
                contextPath + contextRelativePath, null, null);
        logger.debug("Created URI " + serverURI);
        return serverURI;
    }

	public void startUpdaterThread() {
		updater.start();
		WorkspacePersisterListener.attachListener(this, jsonPersister, sessionPersister);
	}

	public void persistWorkspaceToServer() throws WabitPersistenceException {
		WorkspacePersisterListener tempListener = new WorkspacePersisterListener(this, jsonPersister);
		tempListener.persistObject(this.getWorkspace());
	}
	
	/**
	 * Polls this session's server for updates until interrupted. There should
	 * be exactly one instance of this class per WabitServerSession.
	 */
	private class Updater extends Thread {
		
		/**
		 * How long we will pause after an update error before attempting to
		 * contact the server again.
		 */
		private long retryDelay = 1000;
		
		private final WabitJSONMessageDecoder jsonDecoder;

		/**
		 * Used by the Updater to handle inbound HTTP updates
		 */
		private final HttpClient inboundHttpClient;

		private volatile boolean cancelled;
		
		/**
		 * Creates, but does not start, the updater thread.
		 * 
		 * @param workspaceUUID
		 *            the ID of the workspace this updater is responsible for. This is
		 *            used in creating the thread's name.
		 */
		Updater(String workspaceUUID, WabitJSONMessageDecoder jsonDecoder) {
			super("updater-" + workspaceUUID);
			this.jsonDecoder = jsonDecoder;
			inboundHttpClient = createHttpClient(workspaceLocation.getServiceInfo());
		}
		
		public void interrupt() {
			logger.debug("Updater Thread interrupt sent");
			super.interrupt();
			cancelled = true;
		}
        
		@Override
		public void run() {
			logger.info("Updater thread starting");
			
			// the path to contact on the server for update events
			final String contextRelativePath = "workspaces/" + getWorkspace().getUUID();
			
			try {
				while (!this.isInterrupted() && !cancelled) {
					try {
						final String jsonArray = executeServerRequest(
								inboundHttpClient, workspaceLocation.getServiceInfo(),
								contextRelativePath, new BasicResponseHandler());
		                runInForeground(new Runnable() {
							public void run() {
								try {
									jsonDecoder.decode(jsonArray);
								} catch (WabitPersistenceException e) {
									logger.error("Update from server failed!", e);
									createUserPrompter(
											"Wabit failed to apply an update that was just received from the Enterprise Server.\n"
											+ "The error was:"
											+ "\n" + e.getMessage(),
											UserPromptType.MESSAGE, UserPromptOptions.OK,
											UserPromptResponse.OK, UserPromptResponse.OK, "OK");
									// TODO discard session and reload
								}
							}
						});
					} catch (Exception ex) {
						logger.error("Failed to contact server. Will retry in " + retryDelay + " ms.", ex);
						Thread.sleep(retryDelay);
					}
				}
			} catch (InterruptedException ex) {
				logger.info("Updater thread exiting normally due to interruption.");
			}
			
			inboundHttpClient.getConnectionManager().shutdown();
		}
	}

	/**
	 * Fetches the system workspace from the same server as this session.
	 * Returns null if the user doesn't have access to a given workspace.
	 */
	public WabitWorkspace getSystemWorkspace() {
		for (WabitSession session : this.getContext().getSessions()) {
			if (session.getWorkspace().getUUID().equals("system")) {
				return session.getWorkspace();
			}
		}
		return null;
	}

	@Override
	public void runInForeground(Runnable runner) {
		// If we're in a SwingContext, run on the Swing Event Dispatch thread.
		// XXX: This is a bit of a quickfix and I think a better way to possibly fix
		// this could be to have WabitServerSession implement WabitSession, and
		// use a delegate session to delegate most of the server calls (instead
		// of extending WabitSessionImpl). Then if it's in a swing context, it would
		// have a WabitSwingSession instead.
		if (getContext() instanceof WabitSwingSessionContext) {
			SwingUtilities.invokeLater(runner);
		} else {
			super.runInForeground(runner);
		}
	}
	
	@Override
	public boolean isEnterpriseServerSession() {
		return true;
	}
}