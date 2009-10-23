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

package ca.sqlpower.wabit.dao;

import java.awt.Font;
import java.io.IOException;

import junit.framework.TestCase;

import org.apache.log4j.Logger;

import ca.sqlpower.sql.DataSourceCollection;
import ca.sqlpower.sql.PlDotIni;
import ca.sqlpower.util.UserPrompter;
import ca.sqlpower.util.UserPrompter.UserPromptOptions;
import ca.sqlpower.util.UserPrompter.UserPromptResponse;
import ca.sqlpower.wabit.StubWabitSession;
import ca.sqlpower.wabit.StubWabitSessionContext;
import ca.sqlpower.wabit.WabitObject;
import ca.sqlpower.wabit.WabitSessionContext;
import ca.sqlpower.wabit.WabitUtils;
import ca.sqlpower.wabit.WabitWorkspace;
import ca.sqlpower.wabit.dao.WabitPersister.DataType;
import ca.sqlpower.wabit.dao.session.FontConverter;
import ca.sqlpower.wabit.enterprise.client.User;
import ca.sqlpower.wabit.report.Page;
import ca.sqlpower.wabit.report.Report;

public class WabitSessionPersisterTest extends TestCase {

	private static final Logger logger = Logger
			.getLogger(WabitSessionPersisterTest.class);

	private WabitSessionPersister wsp;

    private StubWabitSession session;

	public void setUp() {
		final PlDotIni defaultPlIni = new PlDotIni();
		try {
			defaultPlIni.read(ClassLoader
							.getSystemResourceAsStream("ca/sqlpower/sql/default_database_types.ini"));
			defaultPlIni.read(ClassLoader
							.getSystemResourceAsStream("ca/sqlpower/demodata/example_database.ini"));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		WabitSessionContext context = new StubWabitSessionContext() {
			@Override
			public UserPrompter createUserPrompter(String question,
					UserPromptType responseType, UserPromptOptions optionType,
					UserPromptResponse defaultResponseType,
					Object defaultResponse, String... buttonNames) {
				fail("Loading the example workspace should not prompt the user, it should just work."
						+ " Prompt was: " + question);
				throw new IllegalStateException();
			}

			@Override
			public DataSourceCollection getDataSources() {
				return defaultPlIni;
			}
		};

		session = new StubWabitSession(context);
        wsp = new WabitSessionPersister("testing persister", session);

	}

	public void testForwardReferenceFollowedByNestedTransaction() throws Exception {
		wsp.begin();
		wsp.begin();
		wsp.persistObject("w6bbe2735-dfb4-496a-b1b5-32006e6ea3bd", "WabitDataSource", "w96184b5a-b847-4d44-9bc1-961a83d63dd0", 0);
		wsp.begin();
		wsp.commit();
	}
	
	public void testChangePropNullToNonNull() throws Exception {
	    WabitWorkspace workspace = session.getWorkspace();
	    workspace.setUUID(WabitWorkspace.SYSTEM_WORKSPACE_UUID);
        User user = new User("name", "pass");
	    workspace.addUser(user);
	    user.setEmail(null);
	    
	    assertNotNull(WabitUtils.findByUuid(workspace, user.getUUID(), WabitObject.class));
	    
        wsp.begin();
        wsp.persistProperty(
                user.getUUID(), "email", DataType.STRING, null, "new@email.com");
        wsp.commit();
        
        assertEquals("new@email.com", user.getEmail());
    }
	
    public void testChangePropNonNullToNull() throws Exception {
        WabitWorkspace workspace = session.getWorkspace();
        workspace.setUUID(WabitWorkspace.SYSTEM_WORKSPACE_UUID);
        User user = new User("name", "pass");
        workspace.addUser(user);
        user.setEmail("not@null");

        assertNotNull(WabitUtils.findByUuid(workspace, user.getUUID(), WabitObject.class));

        wsp.begin();
        wsp.persistProperty(
                user.getUUID(), "email", DataType.STRING, "not@null", null);
        wsp.commit();
        
        assertNull(user.getEmail());
    }

    public void testChangePropNullToNull() throws Exception {
        WabitWorkspace workspace = session.getWorkspace();
        workspace.setUUID(WabitWorkspace.SYSTEM_WORKSPACE_UUID);
        User user = new User("name", "pass");
        workspace.addUser(user);
        user.setEmail(null);

        assertNotNull(WabitUtils.findByUuid(workspace, user.getUUID(), WabitObject.class));

        wsp.begin();
        wsp.persistProperty(
                user.getUUID(), "email", DataType.STRING, null, null);
        wsp.commit();
        
        assertNull(user.getEmail());
    }

}