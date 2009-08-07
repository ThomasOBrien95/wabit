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

package ca.sqlpower.wabit.dao;

import java.awt.geom.Point2D;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import junit.framework.TestCase;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.PropertyUtils;

import ca.sqlpower.query.Container;
import ca.sqlpower.query.Query;
import ca.sqlpower.query.SQLGroupFunction;
import ca.sqlpower.query.StringItem;
import ca.sqlpower.query.TableContainer;
import ca.sqlpower.query.Query.OrderByArgument;
import ca.sqlpower.sql.DataSourceCollection;
import ca.sqlpower.sql.JDBCDataSource;
import ca.sqlpower.sql.PlDotIni;
import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.sqlobject.SQLDatabase;
import ca.sqlpower.wabit.QueryCache;
import ca.sqlpower.wabit.StubWabitSessionContext;
import ca.sqlpower.wabit.WabitDataSource;
import ca.sqlpower.wabit.WabitObject;
import ca.sqlpower.wabit.WabitSession;
import ca.sqlpower.wabit.WabitSessionContext;
import ca.sqlpower.wabit.WabitSessionContextImpl;
import ca.sqlpower.wabit.WabitWorkspace;
import ca.sqlpower.wabit.report.Layout;

public class WorkspaceXMLDAOTest extends TestCase {
	
	/**
	 * This is a fake database to be used in testing.
	 */
	private SQLDatabase db;
	private PlDotIni plIni;
	private StubWabitSessionContext context;
	private Connection con;
	private Statement stmt;

	private List<String> getPropertiesToIgnore() {
		List<String> ignoreList = new ArrayList<String>();
		ignoreList.add("parent");
		ignoreList.add("session");
		ignoreList.add("DBMapping"); //Similar to the session
        ignoreList.add("dataSourceTypes"); //Currently unsupported.
        ignoreList.add("serverBaseURI"); //Currently unsupported.
		return ignoreList;
	}
	
	private void setAllSetters(Object object, List<String> propertiesThatAreNotPersisted) throws Exception {
		
		List<PropertyDescriptor> settableProperties;
		settableProperties = Arrays.asList(PropertyUtils.getPropertyDescriptors(object.getClass()));
		for (PropertyDescriptor property : settableProperties) {
			if (propertiesThatAreNotPersisted.contains(property.getName())) continue;
			Object oldVal;

			try {
				oldVal = PropertyUtils.getSimpleProperty(object, property.getName());
				// check for a setter
				if (property.getWriteMethod() != null)
				{
					Object newVal; // don't init here so compiler can warn if the
					// following code doesn't always give it a value
					if (property.getPropertyType() == Integer.TYPE
							|| property.getPropertyType() == Integer.class) {
						if (oldVal == null) {
							newVal = new Integer(0);
						} else {
							newVal = ((Integer) oldVal) + 1;
						}
					} else if (property.getPropertyType() == String.class) {
						// make sure it's unique
                        if (oldVal == null) {
                            newVal = "string";
                        } else {
                            newVal = "new " + oldVal;
                        }
					} else if (property.getPropertyType() == Boolean.TYPE ||
							property.getPropertyType() == Boolean.class) {
                        if(oldVal == null){
                            newVal = new Boolean(false);
                        } else {
                            newVal = new Boolean(!((Boolean) oldVal).booleanValue());
                        }
					} else if (property.getPropertyType() == Long.class) {
						if (oldVal == null) {
							newVal = new Long(0L);
						} else {
							newVal = new Long(((Long) oldVal).longValue() + 1L);
						}
					} else if (property.getPropertyType() == BigDecimal.class) {
						if (oldVal == null) {
							newVal = new BigDecimal(0);
						} else {
							newVal = new BigDecimal(((BigDecimal) oldVal).longValue() + 1L);
						}
					} else if (property.getPropertyType() == SPDataSource.class) {
						newVal = db.getDataSource();
					} else if (property.getPropertyType() == Point2D.class) {
						if (oldVal == null) {
							newVal = new Point2D.Double(0, 0);
						} else {
							newVal = new Point2D.Double(((Point2D) oldVal).getX(), ((Point2D) oldVal).getY());
						}
					} else if (property.getPropertyType() == WabitObject.class) {
						newVal = new QueryCache(new StubWabitSessionContext());
					} else if (property.getPropertyType() == SQLDatabase.class) {
						newVal = new SQLDatabase();
					} else if (property.getPropertyType() == SQLGroupFunction.class) {
					    if (oldVal == SQLGroupFunction.GROUP_BY) {
					        newVal = SQLGroupFunction.COUNT;
					    } else {
					        newVal = SQLGroupFunction.GROUP_BY;
					    }
					} else if (property.getPropertyType() == OrderByArgument.class) {
					    if (oldVal == OrderByArgument.ASC) {
					        newVal = OrderByArgument.DESC;
					    } else {
					        newVal = OrderByArgument.ASC;
					    }
					} else {
						throw new RuntimeException("This test case lacks a value for "
								+ property.getName() + " (type "
								+ property.getPropertyType().getName() + ") from "
								+ object.getClass());
					}
					
					assertNotNull("Ooops we should have set "+property.getName() + " to a value in "+object.getClass().getName(),newVal);
					
					try {
						BeanUtils.copyProperty(object, property.getName(), newVal);
					} catch (InvocationTargetException e) {
						System.out.println("(non-fatal) Failed to write property '"+property.getName()+" to type "+object.getClass().getName());
					}
				}
			} catch (NoSuchMethodException e) {
				System.out.println("Skipping non-settable property " + property.getName() + " on " + object.getClass().getName());
			}
		}
	}
	
	private void assertPropertiesEqual(
            Object expected,
            Object actual,
            String ... additionalPropertiesToIgnore)
        throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        
        List<PropertyDescriptor> properties;
        properties = Arrays.asList(PropertyUtils.getPropertyDescriptors(expected.getClass()));

        // list all the readable properties
        List<PropertyDescriptor> gettableProperties = new ArrayList<PropertyDescriptor>();
        for (PropertyDescriptor d: properties){
            if( d.getReadMethod() != null ) {
                gettableProperties.add(d);
            }
        }

        // compare the values of each readable property
        Set<String> ignore = new HashSet<String>(getPropertiesToIgnore());
        ignore.addAll(Arrays.asList(additionalPropertiesToIgnore));
        for (PropertyDescriptor d: gettableProperties){
            if (!ignore.contains(d.getName())) {
                try {
                    Object old = BeanUtils.getSimpleProperty(expected, d.getName());
                    Object newItem = BeanUtils.getSimpleProperty(actual, d.getName());
                    assertEquals(
                    		"The property "+d.getName() + " was not persisted for type "+expected.getClass().getName(),
                    		String.valueOf(old),
                    		String.valueOf(newItem));

                } catch (Exception e) {
                    throw new RuntimeException("Error accessing property "+d.getName(), e);
                }
            }
        }

    }
	
	@Override
	protected void setUp() throws Exception {
		plIni = new PlDotIni();
        plIni.read(new File("src/test/java/pl.regression.ini"));
        JDBCDataSource ds = plIni.getDataSource("regression_test", JDBCDataSource.class);

        db = new SQLDatabase(ds);
        
        con = db.getConnection();
        stmt = con.createStatement();
        stmt.execute("create table wabit_table1 (pk integer, string_col varchar, number_col numeric, date_col timestamp, bool_col boolean)");
        
        context = new StubWabitSessionContext() {
            @Override
            public DataSourceCollection<SPDataSource> getDataSources() {
                return plIni;
            }
            
            @Override
            public SQLDatabase getDatabase(JDBCDataSource ds) {
                if (ds == db.getDataSource()) return db;
                return null;
            }
            
        };
	}
	
	@Override
	protected void tearDown() throws Exception {
		if (stmt != null) {
			stmt.close();
		}
		if (con != null) {
			con.close();
		}
	}
	
	public void testSaveAndLoad() throws Exception {
	    final WabitSessionContext beforeSaveContext = new WabitSessionContextImpl(false, false);
	    final WabitSession session = beforeSaveContext.createSession();
	    beforeSaveContext.setActiveSession(session);
		WabitWorkspace p = session.getWorkspace();
		p.setName("Workspace");
		setAllSetters(p, getPropertiesToIgnore());
		p.addDataSource(db.getDataSource());

		QueryCache query = new QueryCache(beforeSaveContext);
		p.addQuery(query, session);
		setAllSetters(query.getQuery(), getPropertiesToIgnore());
		query.setDataSource(db.getDataSource());
		
		Container constantsContainer = query.getQuery().getConstantsContainer();
		StringItem constantItem = new StringItem("Constant");
		constantsContainer.addItem(constantItem);
		query.getQuery().addItem(constantItem);
		setAllSetters(constantItem, getPropertiesToIgnore());

		//TODO: implement the rest of this test case for the commented out sections of 
		//a query and layouts.
		TableContainer container = new TableContainer(query.getQuery().getDatabase(), db.getTableByName("wabit_table1"));
		query.getQuery().addTable(container);
		setAllSetters(container, getPropertiesToIgnore());
//		query.addJoin(join);
		
//		assertTrue("Grouping must be enabled to check the group by aggregate and having text", query.isGroupingEnabled());
//		query.setGrouping(column, groupByAggregate);
//		query.setHavingClause(item, havingText);
//		query.setSortOrder(item, arg);
		
// ========================= Now the save and load =========================

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WorkspaceXMLDAO saveDAO = new WorkspaceXMLDAO(out, beforeSaveContext);
		saveDAO.saveActiveWorkspace();
        System.out.println(out.toString("utf-8"));
        
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        OpenWorkspaceXMLDAO loadDAO = new OpenWorkspaceXMLDAO(context, in);
        
        WabitSession loadedSession = loadDAO.openWorkspaces().get(0);
        
        assertNotNull(loadedSession.getWorkspace());
        assertPropertiesEqual(p, loadedSession.getWorkspace(), "UUID", "children", "dataSources", "queries", "layouts", "editorPanelModel");
        
        assertEquals(p.getDataSources().size(), loadedSession.getWorkspace().getDataSources().size());
        for (WabitDataSource ds : p.getDataSources()) {
        	assertPropertiesEqual(db.getDataSource(), ((WabitDataSource) ds).getSPDataSource());
        }
        
        assertEquals(p.getQueries().size(), loadedSession.getWorkspace().getQueries().size());
        for (int i = 0; i < p.getQueries().size(); i++) {
        	Query oldQuery = (Query) ((QueryCache) p.getQueries().get(i)).getQuery();
			Query newQuery = (Query) ((QueryCache) loadedSession.getWorkspace().getQueries().get(i)).getQuery();
			assertPropertiesEqual(oldQuery, newQuery, new String[]{"session", "streaming", "streamingStatement", "streamingConnection", "timerListener"});
			assertEquals(oldQuery.getConstantsContainer().getItems().size(), newQuery.getConstantsContainer().getItems().size());
			for (int j = 0; j < oldQuery.getConstantsContainer().getItems().size(); j++) {
				assertPropertiesEqual(oldQuery.getConstantsContainer().getItems().get(j), newQuery.getConstantsContainer().getItems().get(j), "item", "columnWidth");
			}
			assertEquals(oldQuery.getFromTableList(), newQuery.getFromTableList());
			for (int j = 0; j < oldQuery.getFromTableList().size(); j++) {
				Container oldContainer = oldQuery.getFromTableList().get(j);
				Container newContainer = newQuery.getFromTableList().get(j);
				assertPropertiesEqual(oldContainer, newContainer);
				assertEquals(oldContainer.getItems(), newContainer.getItems());
				for (int k = 0; k < oldContainer.getItems().size(); k++) {
					assertPropertiesEqual(oldContainer.getItems().get(k), newContainer.getItems().get(k));
				}
			}
			
			assertEquals(oldQuery.getJoins().size(), newQuery.getJoins().size());
			for (int j = 0; j < oldQuery.getJoins().size(); j++) {
				assertPropertiesEqual(oldQuery.getJoins().toArray()[j], newQuery.getJoins().toArray()[j]);
			}
        }
        
        assertEquals(p.getLayouts().size(), loadedSession.getWorkspace().getLayouts().size());
        for (Layout l : p.getLayouts()) {
        	//XXX Need to finish testing layouts
        }
        
	}

}