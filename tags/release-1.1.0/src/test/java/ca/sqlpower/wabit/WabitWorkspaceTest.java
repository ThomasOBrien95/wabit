/*
 * Copyright (c) 2008, SQL Power Group Inc.
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

package ca.sqlpower.wabit;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import javax.naming.NamingException;

import org.olap4j.OlapConnection;

import ca.sqlpower.object.ObjectDependentException;
import ca.sqlpower.object.SPObject;
import ca.sqlpower.sql.JDBCDataSource;
import ca.sqlpower.sql.Olap4jDataSource;
import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.sqlobject.SQLDatabase;
import ca.sqlpower.sqlobject.SQLDatabaseMapping;
import ca.sqlpower.sqlobject.StubSQLDatabaseMapping;
import ca.sqlpower.testutil.StubDataSourceCollection;
import ca.sqlpower.wabit.report.ChartRenderer;
import ca.sqlpower.wabit.report.ContentBox;
import ca.sqlpower.wabit.report.Report;
import ca.sqlpower.wabit.report.ResultSetRenderer;
import ca.sqlpower.wabit.report.chart.Chart;
import ca.sqlpower.wabit.rs.olap.OlapQuery;
import ca.sqlpower.wabit.rs.query.QueryCache;
import ca.sqlpower.wabit.swingui.StubWabitSwingSession;
import ca.sqlpower.wabit.swingui.WabitSwingSession;

public class WabitWorkspaceTest extends AbstractWabitObjectTest {

    private WabitWorkspace workspace;
    
    /**
     * We are not persisting the WabitWorkspace object because it is created by
     * the session.
     */
    @Override
    public void testPersisterAddsNewObject() throws Exception {
    	//no-op
    }

	/**
	 * We are not persisting the WabitWorkspace because it is created by the
	 * session. This test therefore cannot pass.
	 */
    @Override
    public void testPersistsObjectAsChild() throws Exception {
    	//no-op
    }
    
    /**
     * The workspace cannot be persisted as a new child of a WabitObject as it is
     * the root object.
     */
    @Override
    public void testPersisterCommitCanRollbackNewChild() throws Exception {
    	//no-op
    }
    
    /**
     * The workspace cannot be removed as a new child of a WabitObject as it is
     * the root object.
     */
    @Override
    public void testPersisterCommitCanRollbackRemovedChild() throws Exception {
    	//no-op
    }
    
    @Override
    public Set<String> getPropertiesToIgnoreForPersisting() {
    	Set<String> ignored = super.getPropertiesToIgnoreForPersisting();
    	
    	//This property defines the editor and changing this would force all users of each
    	//workspace to view the same editor all the time.
    	ignored.add("editorPanelModel");
    	return ignored;
    }
    
    @Override
    public Set<String> getPropertiesToNotPersistOnObjectPersist() {
    	Set<String> notPersisting = super.getPropertiesToNotPersistOnObjectPersist();
    	notPersisting.add("charts");
    	notPersisting.add("connections");
    	notPersisting.add("dataSources");
    	notPersisting.add("images");
    	notPersisting.add("olapQueries");
    	notPersisting.add("queries");
    	notPersisting.add("reports");
    	notPersisting.add("session");
    	notPersisting.add("templates");
    	notPersisting.add("reportTasks");
    	
    	// These are system workspace specific children and properties
    	notPersisting.add("systemWorkspace");
    	notPersisting.add("groups");
    	notPersisting.add("users");
    	
    	// These are currently not supported.
    	notPersisting.add("dataSourceTypes");
    	notPersisting.add("serverBaseURI");
    	
    	return notPersisting;
    }
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        workspace = getWorkspace();
        workspace.setName("ws");
    }
    
    @Override
    public Set<String> getPropertiesToIgnoreForEvents() {
    	Set<String> ignore = new HashSet<String>();
        ignore.add("dataSourceTypes");
        ignore.add("serverBaseURI");
        ignore.add("mondrianServerBaseURI");
        ignore.add("session");
        ignore.add("magicDisabled");
        
        //workspace parents cannot be set as they are always null.
        ignore.add("parent");
    	return ignore;
    }
    
    @Override
    public WabitObject getObjectUnderTest() {
        return workspace;
    }
    
    /**
     * Regression test for bug 1976. If a query cache is selected and is
     * removed the wabit object being edited should be changed.
     */
    public void testRemovingSelectedQueryCacheChangesSelection() throws Exception {
        WabitSession session = new StubWabitSession(new StubWabitSessionContext());
        QueryCache query = new QueryCache(new SQLDatabaseMapping() {
            public SQLDatabase getDatabase(JDBCDataSource ds) {
                return null;
            }
        });
        workspace.setSession(session);
        workspace.addQuery(query, session);
        workspace.setEditorPanelModel(query);
        assertEquals(query, workspace.getEditorPanelModel());
        
        workspace.removeChild(query);
        assertNotSame(query, workspace.getEditorPanelModel());
    }
    
    /**
     * Regression test for bug 1976. If an OLAP query is selected and is
     * removed the wabit object being edited should be changed.
     */
    public void testRemovingSelectedOlapQueryChangesSelection() throws Exception {
        OlapQuery query = new OlapQuery(new OlapConnectionMapping() {
            public OlapConnection createConnection(Olap4jDataSource dataSource)
                    throws SQLException, ClassNotFoundException, NamingException {
                return null;
            }
        });
        workspace.addOlapQuery(query);
        workspace.setEditorPanelModel(query);
        assertEquals(query, workspace.getEditorPanelModel());
        
        workspace.removeChild(query);
        assertNotSame(query, workspace.getEditorPanelModel());
    }
    
    /**
     * Regression test for bug 1976. If a layout is selected and is
     * removed the wabit object being edited should be changed.
     */
    public void testRemovingSelectedLayoutChangesSelection() throws Exception {
        Report layout = new Report("Layout");
        workspace.addReport(layout);
        workspace.setEditorPanelModel(layout);
        assertEquals(layout, workspace.getEditorPanelModel());
        
        workspace.removeChild(layout);
        assertNotSame(layout, workspace.getEditorPanelModel());
    }

    /**
     * Removing a child object that is a dependency should throw an exception
     * when the remove operation occurs.
     */
    public void testRemovingQueryWithDependency() throws Exception {
        WabitSession session = new StubWabitSession(new StubWabitSessionContext());
        workspace.setSession(session);
        QueryCache query = new QueryCache(new SQLDatabaseMapping() {
            public SQLDatabase getDatabase(JDBCDataSource ds) {
                return null;
            }
        });
        workspace.addQuery(query, session);
        Chart chart = new Chart();
        chart.setName("chart");
        chart.setQuery(query);
        workspace.addChart(chart);
        
        try {
            workspace.removeChild(query);
            fail("The child was removed while there was a chart dependent on it. " +
            		"Now the chart depends on an unparented object.");
        } catch (ObjectDependentException e) {
            //successfully caught the exception.
        }
    }
    
    /**
     * A simple test of merging some WabitObjects from one workspace into another.
     */
    public void testMergeIntoSession() throws Exception {
        WabitSwingSession startingSession = new StubWabitSwingSession();
        WabitWorkspace startingWorkspace = new WabitWorkspace();
        startingWorkspace.setSession(startingSession);
        
        QueryCache query = new QueryCache(new StubWabitSessionContext());
        startingWorkspace.addQuery(query, startingSession);
        Chart chart = new Chart();
        chart.setName("chart");
        chart.setQuery(query);
        startingWorkspace.addChart(chart);
        Report report = new Report("Report");
        startingWorkspace.addReport(report);
        ContentBox chartContentBox = new ContentBox();
        chartContentBox.setContentRenderer(new ChartRenderer(chart));
        report.getPage().addContentBox(chartContentBox);
        ContentBox queryContentBox = new ContentBox();
        queryContentBox.setContentRenderer(new ResultSetRenderer(query));
        report.getPage().addContentBox(queryContentBox);
        
        WabitSwingSession finishingSession = new StubWabitSwingSession();
        WabitWorkspace finishingWorkspace = new WabitWorkspace();
        finishingWorkspace.setSession(finishingSession);
        
        assertEquals(3, startingWorkspace.getChildren().size());
        assertTrue(startingWorkspace.getChildren().contains(query));
        assertTrue(startingWorkspace.getChildren().contains(chart));
        assertTrue(startingWorkspace.getChildren().contains(report));
        assertEquals(0, finishingWorkspace.getChildren().size());
        
        startingWorkspace.mergeIntoWorkspace(finishingWorkspace);
        
        assertEquals(0, startingWorkspace.getChildren().size());
        assertEquals(3, finishingWorkspace.getChildren().size());
        assertTrue(finishingWorkspace.getChildren().contains(query));
        assertTrue(finishingWorkspace.getChildren().contains(chart));
        assertTrue(finishingWorkspace.getChildren().contains(report));
    }
    
    /**
     * A test of merging some WabitObjects from one workspace into another changes
     * the UUIDs. This prevents the workspace from gaining duplicate UUIDs.
     */
    public void testMergingUpdatesUUIDs() throws Exception {
        WabitSwingSession startingSession = new StubWabitSwingSession();
        WabitWorkspace startingWorkspace = new WabitWorkspace();
        startingWorkspace.setSession(startingSession);
        
        Set<String> uniqueUUIDs = new HashSet<String>() {
            @Override
            public boolean add(String o) {
                if (contains(o)) {
                    fail("The uuid " + o + " already exists " + "and is therefore not unique.");
                }
                return super.add(o);
            }
        };
        
        QueryCache query = new QueryCache(new StubWabitSessionContext());
        startingWorkspace.addQuery(query, startingSession);
        Chart chart = new Chart();
        chart.setName("chart");
        chart.setQuery(query);
        startingWorkspace.addChart(chart);
        Report report = new Report("Report");
        startingWorkspace.addReport(report);
        ContentBox chartContentBox = new ContentBox();
        final ChartRenderer chartContentRenderer = new ChartRenderer(chart);
        chartContentBox.setContentRenderer(chartContentRenderer);
        report.getPage().addContentBox(chartContentBox);
        ContentBox queryContentBox = new ContentBox();
        final ResultSetRenderer resultSetContentRenderer = new ResultSetRenderer(query);
        queryContentBox.setContentRenderer(resultSetContentRenderer);
        report.getPage().addContentBox(queryContentBox);
        
        WorkspaceGraphModel graph = new WorkspaceGraphModel(startingWorkspace, 
                startingWorkspace, false, false);
        for (SPObject o : graph.getNodes()) {
            System.out.println("Adding object of type " + o.getClass() + " with UUID " + o.getUUID());
            uniqueUUIDs.add(o.getUUID());
        }
        
        WabitSwingSession finishingSession = new StubWabitSwingSession();
        WabitWorkspace finishingWorkspace = new WabitWorkspace();
        finishingWorkspace.setSession(finishingSession);
        
        assertEquals(3, startingWorkspace.getChildren().size());
        assertTrue(startingWorkspace.getChildren().contains(query));
        assertTrue(startingWorkspace.getChildren().contains(chart));
        assertTrue(startingWorkspace.getChildren().contains(report));
        assertEquals(0, finishingWorkspace.getChildren().size());
        
        startingWorkspace.mergeIntoWorkspace(finishingWorkspace);
        
        assertEquals(0, startingWorkspace.getChildren().size());
        assertEquals(3, finishingWorkspace.getChildren().size());
        assertTrue(finishingWorkspace.getChildren().contains(query));
        assertTrue(finishingWorkspace.getChildren().contains(chart));
        assertTrue(finishingWorkspace.getChildren().contains(report));
        
        WorkspaceGraphModel endGraph = new WorkspaceGraphModel(finishingWorkspace, 
                finishingWorkspace, false, false);
        for (SPObject o : endGraph.getNodes()) {
            uniqueUUIDs.add(o.getUUID());
        }
        
    }
    
    /**
     * Test a query can be added to and removed from a workspace with the
     * addChild method. Also checks that the index is correct.
     * @throws Exception
     */
    public void testAddAndRemoveQuery() throws Exception {
        final JDBCDataSource spds = new JDBCDataSource(new StubDataSourceCollection<SPDataSource>());
        spds.setName("ds");
        WabitDataSource ds = new WabitDataSource(spds);
        workspace.addDataSource(ds);
        
        QueryCache q = new QueryCache(new StubSQLDatabaseMapping());
        q.setName("query");
        
        workspace.addChild(q, 0);
        
        assertTrue(workspace.getChildren().contains(q));
        
        workspace.removeChild(q);
        
        assertFalse(workspace.getChildren().contains(q));
    }

}