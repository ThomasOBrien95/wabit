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

package ca.sqlpower.wabit.query;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import ca.sqlpower.graph.DepthFirstSearch;
import ca.sqlpower.graph.GraphModel;
import ca.sqlpower.sql.CachedRowSet;
import ca.sqlpower.sql.RowSetChangeEvent;
import ca.sqlpower.sql.RowSetChangeListener;
import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.sql.SQLGroupFunction;
import ca.sqlpower.sqlobject.SQLDatabase;
import ca.sqlpower.sqlobject.SQLObjectException;
import ca.sqlpower.sqlobject.SQLObjectRuntimeException;
import ca.sqlpower.sqlobject.SQLTable;
import ca.sqlpower.swingui.query.StatementExecutor;
import ca.sqlpower.wabit.AbstractWabitObject;
import ca.sqlpower.wabit.JDBCDataSource;
import ca.sqlpower.wabit.Query;
import ca.sqlpower.wabit.WabitChildEvent;
import ca.sqlpower.wabit.WabitChildListener;
import ca.sqlpower.wabit.WabitDataSource;
import ca.sqlpower.wabit.WabitObject;
import ca.sqlpower.wabit.WabitSession;
import ca.sqlpower.wabit.swingui.ExceptionHandler;

/**
 * This class will cache all of the parts of a select
 * statement and also listen to everything that could
 * change the select statement.
 */
public class QueryCache extends AbstractWabitObject implements Query, StatementExecutor {
	
	private static final Logger logger = Logger.getLogger(QueryCache.class);
	
	/**
	 * A property name that is thrown in PropertyChangeListeners when part of
	 * the query has changed. This is a generic default change to a query
	 * rather than a specific query change.
	 */
	private static final String PROPERTY_QUERY = "query";
	
	
	public static final String GROUPING_CHANGED = " GROUPING_CHANGED";
	
	
	/**
	 * A property name that is thrown when the Table is removed.
	 */
	public static final String PROPERTY_TABLE_REMOVED = "PROPERTY_TABLE_REMOVED"; 
	
	/**
	 * The grouping function defined on a group by event if the column is
	 * to be grouped by and not aggregated on.
	 */
	public static final String GROUP_BY = "(GROUP BY)";

	/**
	 * A property change of this type is fired if the user defined
	 * text of the query is modified. Property changes to the objects
	 * maintained and monitored by this query will not contain this
	 * type.
	 */
	public static final String PROPERTY_QUERY_TEXT = "propertyQueryText";

	/**
	 * If the row limit changes causing the result set cache to become empty
	 * a change event will fire with this property.
	 */
	protected static final String PROPERTY_ROW_LIMIT = "propertyRowLimit";
	
	/**
	 * The arguments that can be added to a column in the 
	 * order by clause.
	 */
	public enum OrderByArgument {
		ASC,
		DESC
	}
	
	/**
	 * This graph represents the tables in the SQL statement. Each table in
	 * the statement is a vertex in the graph. Each join is an edge in the 
	 * graph coming from the left table and moving towards the right table.
	 */
	private class TableJoinGraph implements GraphModel<Container, SQLJoin> {

		public Collection<Container> getAdjacentNodes(Container node) {
			List<Container> adjacencyNodes = new ArrayList<Container>();
			if (joinMapping.get(node) != null) {
				for (SQLJoin join : joinMapping.get(node)) {
					if (join.getLeftColumn().getContainer() == node) {
						adjacencyNodes.add(join.getRightColumn().getContainer());
					}
				}
			}
			return adjacencyNodes;
		}

		public Collection<SQLJoin> getEdges() {
			List<SQLJoin> edgesList = new ArrayList<SQLJoin>();
			for (List<SQLJoin> joinList : joinMapping.values()) {
				for (SQLJoin join : joinList) {
					edgesList.add(join);
				}
			}
			return edgesList;
		}

		public Collection<SQLJoin> getInboundEdges(Container node) {
			List<SQLJoin> inboundEdges = new ArrayList<SQLJoin>();
			if (joinMapping.get(node) != null) {
				for (SQLJoin join : joinMapping.get(node)) {
					if (join.getRightColumn().getContainer() == node) {
						inboundEdges.add(join);
					}
				}
			}
			return inboundEdges;
		}

		public Collection<Container> getNodes() {
			return fromTableList;
		}

		public Collection<SQLJoin> getOutboundEdges(Container node) {
			List<SQLJoin> outboundEdges = new ArrayList<SQLJoin>();
			if (joinMapping.get(node) != null) {
				for (SQLJoin join : joinMapping.get(node)) {
					if (join.getLeftColumn().getContainer() == node) {
						outboundEdges.add(join);
					}
				}
			}
			return outboundEdges;
		}
		
	}

	
	
	/**
	 * Tracks if there are groupings added to this select statement.
	 * This will affect when columns are added to the group by collections.
	 */
	private boolean groupingEnabled = false;

	/**
	 * This maps the SQLColumns in the select statement to their group by
	 * functions. If the column is in the GROUP BY clause and is not being
	 * aggregated on it should appear in the group by list.
	 */
	private Map<Item, SQLGroupFunction> groupByAggregateMap;
	
	/**
	 * A list of columns we are grouping by. These are not
	 * being aggregated on but are in the GROUP BY clause. 
	 */
	private List<Item> groupByList;

	/**
	 * This maps SQLColumns to having clauses. The entry with a null key
	 * contains the generic having clause that is not defined for a specific
	 * column.
	 */
	private Map<Item, String> havingMap;
	
	/**
	 * The columns in the SELECT statement that will be returned.
	 * These columns are stored in the order they will be returned
	 * in.
	 */
	private final List<Item> selectedColumns;
	
	/**
	 * This map contains the columns that have an ascending
	 * or descending argument and is in the order by clause.
	 */
	private final Map<Item, OrderByArgument> orderByArgumentMap;
	
	/**
	 * The order by list keeps track of the order that columns were selected in.
	 */
	private final List<Item> orderByList;
	
	/**
	 * The list of tables that we are selecting from.
	 */
	private final List<Container> fromTableList;
	
	/**
	 * This maps each table to a list of SQLJoin objects.
	 * These column pairs defines a join in the select statement.
	 */
	private final Map<Container, List<SQLJoin>> joinMapping;
	
	/**
	 * This is the global where clause that is for all non-column-specific where
	 * entries.
	 */
	private String globalWhereClause;
	
	/**
	 * This is the level of the zoom in the query.
	 */
	private int zoomLevel;
	
	/**
	 * Listens for changes to the alias on the item and fires events to its 
	 * listeners if the alias was changed.
	 */
	private PropertyChangeListener aliasListener = new PropertyChangeListener() {
		public void propertyChange(PropertyChangeEvent e) {
			if (e.getPropertyName().equals(Item.PROPERTY_ALIAS)) {
				if (!compoundEdit) {
					firePropertyChange(PROPERTY_QUERY, e.getOldValue(), e.getNewValue());
				}
			}
		}
	};
	
	/**
	 * Listens for changes to the select checkbox on the column of a table in 
	 * the query pen.
	 */
	private PropertyChangeListener selectedColumnListener = new PropertyChangeListener() {
		public void propertyChange(PropertyChangeEvent e) {
			if (e.getPropertyName().equals(Item.PROPERTY_SELECTED)) {
				selectionChanged((Item)e.getSource(), (Boolean)e.getNewValue());
			}
		}
	};
	
	/**
	 * This property change support object is used to forward timer events from the 
	 * worker this class is running in to classes listening for the timer event.
	 */
	private final PropertyChangeSupport timerPCS = new PropertyChangeSupport(this);

	/**
	 * This listener is used to forward the timer events to the timerPCS object
	 * for classes listening for timer events.
	 */
	private final ActionListener timerListener = new ActionListener() {
		
		private int timerTicks = 0;
		
		public void actionPerformed(ActionEvent e) {
			int oldTimer = timerTicks;
			timerTicks++;
			timerPCS.firePropertyChange("timerTicks", oldTimer, timerTicks);
		}
	};
	
	private PropertyChangeListener joinChangeListener = new PropertyChangeListener() {
		public void propertyChange(PropertyChangeEvent e) {
			if (e.getPropertyName().equals(SQLJoin.LEFT_JOIN_CHANGED)) {
				logger.debug("Got left join changed.");
				SQLJoin changedJoin = (SQLJoin) e.getSource();
				Container leftJoinContainer = changedJoin.getLeftColumn().getContainer();
				for (SQLJoin join : joinMapping.get(leftJoinContainer)) {
					if (join.getLeftColumn().getContainer() == leftJoinContainer) {
						join.setLeftColumnOuterJoin((Boolean)e.getNewValue());
					} else {
						join.setRightColumnOuterJoin((Boolean)e.getNewValue());
					}
				}
				if (!compoundEdit) {
					firePropertyChange(e.getPropertyName(), e.getOldValue(), e.getNewValue());
				}
			} else if (e.getPropertyName().equals(SQLJoin.RIGHT_JOIN_CHANGED)) {
				logger.debug("Got right join changed.");
				SQLJoin changedJoin = (SQLJoin) e.getSource();
				Container rightJoinContainer = changedJoin.getRightColumn().getContainer();
				logger.debug("There are " + joinMapping.get(rightJoinContainer) + " joins on the table with the changed join.");
				for (SQLJoin join : joinMapping.get(rightJoinContainer)) {
					if (join.getLeftColumn().getContainer() == rightJoinContainer) {
						logger.debug("Changing left side");
						join.setLeftColumnOuterJoin((Boolean)e.getNewValue());
					} else {
						logger.debug("Changing right side");
						join.setRightColumnOuterJoin((Boolean)e.getNewValue());
					}
				}
				if (!compoundEdit) {
					firePropertyChange(e.getPropertyName(), e.getOldValue(), e.getNewValue());
				}
			} else if (e.getPropertyName().equals(SQLJoin.COMPARATOR_CHANGED)) {
				if (!compoundEdit) {
					firePropertyChange(e.getPropertyName(), e.getOldValue(), e.getNewValue());
				}
			}
		}
	};
	
	private final WabitChildListener tableChildListener = new WabitChildListener() {
		public void wabitChildRemoved(WabitChildEvent e) {
			removeItem((Item)e.getChild());
		}
		public void wabitChildAdded(WabitChildEvent e) {
			addItem((Item)e.getChild());
		}
	}; 
	
	private final PropertyChangeListener whereListener = new PropertyChangeListener() {
		public void propertyChange(PropertyChangeEvent evt) {
			if (evt.getPropertyName().equals(Item.PROPERTY_WHERE) && !compoundEdit) {
				firePropertyChange(evt.getPropertyName(), evt.getOldValue(), evt.getNewValue());
			}
		}
	};
	
	/**
	 * This container holds the items that are considered constants in the SQL statement.
	 * This could include functions or other elements that don't belong in a table.
	 */
	private Container constantsContainer;
	
	/**
	 * If true the query cache will be in an editing state. When in this
	 * state events should not be fired. When the compound edit ends
	 * the query should fire a state changed if the query was changed.
	 */
	private boolean compoundEdit = false;

	/**
	 * Stores the current query at the start of a compound edit. For use
	 * when deciding how the query was changed.
	 */
	private String queryBeforeEdit;
	
	/**
	 * This database instance is obtained from the session when the 
	 * data source is called.
	 */
	private SQLDatabase database;
	
	/**
	 * This is the text of the query if the user edited the text manually. This means
	 * that the parts of the query cache will not represent the new query text the user
	 * created. If this is null then the user did not change the query manually.
	 */
	private String userModifiedQuery = null;
	
	/**
	 * The session this query cache is contained in.
	 */
	private WabitSession session;
	
	/**
	 * Tracks if the data source should be used as a streaming query or as a regular
	 * query. Streaming queries are populated on their own thread.
	 */
	private boolean streaming = false;
	
	/**
	 * This listener will be notified when additional rows are added to a streaming
	 * row set. This listener will then notify the listeners of this query that
	 * a result set has changed.
	 */
	private final RowSetChangeListener rsChangeListener = new RowSetChangeListener() {
		public void rowAdded(RowSetChangeEvent e) {
			for (int i = rowSetChangeListeners.size() - 1; i >= 0; i--) {
				rowSetChangeListeners.get(i).rowAdded(e);
			}
		}
	};
	
	/**
	 * The listeners to be updated from a change to the result set.
	 */
	private final List<RowSetChangeListener> rowSetChangeListeners = new ArrayList<RowSetChangeListener>();
	
	/**
	 * This is the statement currently entering result sets into this query cache.
	 * This lets the query cancel a running statement.
	 * <p>
	 * This is only used if {@link QueryCache#streaming} is true.
	 */
	private Statement currentStatement;
	
	/**
	 * This is the connection currently entering result sets into this query cache.
	 * This lets the query close a running connection
	 * <p>
	 * This is only used if {@link QueryCache#streaming} is true.
	 */
	private Connection currentConnection; 
	
	/**
	 * The threads in this list are used to stream queries from a connection into
	 * this query cache.
	 */
	private final List<Thread> streamingThreads = new ArrayList<Thread>();
	
	/**
	 * A change listener to flush the cached row sets on a row limit change.
	 */
	private final PropertyChangeListener rowLimitChangeListener = new PropertyChangeListener() {
		public void propertyChange(PropertyChangeEvent evt) {
			if (evt.getPropertyName().equals("rowLimit")) {
				resultSets.clear();
				updateCounts.clear();
				firePropertyChange(PROPERTY_ROW_LIMIT, evt.getOldValue(), evt.getNewValue());
			}
		}
	};

	/**
	 * This is the streaming row limit for the query. No more than this many
	 * rows will be shown in the streaming result set and if this limit is
	 * reached and new rows are added then the oldest rows in the result set
	 * will be removed.
	 */
	private int streamingRowLimit = 1000;
	
	public QueryCache(WabitSession session) {
		this((String)null, session);
	}
	
	/**
	 * The uuid defines the unique id of this query cache. If null
	 * is passed in a new UUID will be generated.
	 */
	public QueryCache(String uuid, WabitSession session) {
		super(uuid);
		this.session = session;
		session.addPropertyChangeListener(rowLimitChangeListener);
		orderByArgumentMap = new HashMap<Item, OrderByArgument>();
		orderByList = new ArrayList<Item>();
		selectedColumns = new ArrayList<Item>();
		fromTableList = new ArrayList<Container>();
		joinMapping = new HashMap<Container, List<SQLJoin>>();
		groupByAggregateMap = new HashMap<Item, SQLGroupFunction>();
		groupByList = new ArrayList<Item>();
		havingMap = new HashMap<Item, String>();
		
		constantsContainer = new ItemContainer("Constants");
		StringItem currentTime = new StringItem("current_time");
		constantsContainer.addItem(currentTime);
		addItem(currentTime);
		StringItem currentDate = new StringItem("current_date");
		constantsContainer.addItem(currentDate);
		addItem(currentDate);
		StringItem user = new StringItem("user");
		constantsContainer.addItem(user);
		addItem(user);
	}
	
	/**
	 * A copy constructor for the query cache. This will not
	 * hook up listeners.
	 */
	public QueryCache(Query copy) {
		selectedColumns = new ArrayList<Item>();
		fromTableList = new ArrayList<Container>();
		joinMapping = new HashMap<Container, List<SQLJoin>>();
		groupByList = new ArrayList<Item>();
		groupByAggregateMap = new HashMap<Item, SQLGroupFunction>();
		havingMap = new HashMap<Item, String>();
		orderByList = new ArrayList<Item>();
		orderByArgumentMap = new HashMap<Item, OrderByArgument>();
		
		session = copy.getSession();
		setName(copy.getName());
		setParent(copy.getParent());
		if (copy instanceof QueryCache) {
			QueryCache query = (QueryCache) copy;

			selectedColumns.addAll(query.getSelectedColumns());
			fromTableList.addAll(query.getFromTableList());
			joinMapping.putAll(query.getJoinMapping());
			groupByList.addAll(query.getGroupByList());
			groupByAggregateMap.putAll(query.getGroupByAggregateMap());
			havingMap.putAll(query.getHavingMap());
			orderByList.addAll(query.getOrderByList());
			orderByArgumentMap.putAll(query.getOrderByArgumentMap());
			globalWhereClause = query.getGlobalWhereClause();
			groupingEnabled = query.isGroupingEnabled();

			
			database = query.getDatabase();
			constantsContainer = query.getConstantsContainer();
			userModifiedQuery = query.getUserModifiedQuery();
			streaming = query.streaming;
			
			for (CachedRowSet rs : query.getResultSets()) {
				if (rs == null) {
					resultSets.add(null);
				} else {
					try {
						resultSets.add((CachedRowSet) rs.createShared());
					} catch (SQLException e) {
						throw new RuntimeException("This should not be able to happen", e);
					}
				}
			}
		} else {
			userModifiedQuery = copy.generateQuery();
			logger.warn("Unknown query type " + copy.getClass() + " to make a cached query of.");
		}
	}
	
	public void cleanup() {
		session.removePropertyChangeListener(rowLimitChangeListener);
		if (currentStatement != null) {
			try {
				currentStatement.cancel();
				currentStatement.close();
			} catch (SQLException e) {
				logger.error("Error while closing old streaming statement", e);
			}
		}
		if (currentConnection != null) {
			try {
				currentConnection.close();
			} catch (SQLException e) {
				logger.error("Error while closing old streaming connection", e);
			}
		}
	}
	
	public void setSession(WabitSession session) {
		if (this.session != null) {
			session.removePropertyChangeListener(rowLimitChangeListener);
		}
		this.session = session;
		session.addPropertyChangeListener(rowLimitChangeListener);
	}
	
	public SQLDatabase getDatabase() {
        return database;
    }
	
	public void setGroupingEnabled(boolean enabled) {
		logger.debug("Setting grouping enabled to " + enabled);
		if (!groupingEnabled && enabled) {
			startCompoundEdit();
			for (Item col : selectedColumns) {
				if (!groupByAggregateMap.containsKey(col)) {
					groupByList.add(col);
				}
			}
			for (Item item : getSelectedColumns()) {
				if (item instanceof StringItem) {
					setGrouping(item, SQLGroupFunction.COUNT.toString());
				}
			}
			endCompoundEdit();
		} else if (!enabled) {
			groupByList.clear();
			groupByAggregateMap.clear();
			havingMap.clear();
		}
		firePropertyChange(GROUPING_CHANGED, groupingEnabled, enabled);
		groupingEnabled = enabled;
	}
	
	/**
	 * Removes the column from the selected columns list and all other
	 * related lists.
	 */
	private void removeColumnSelection(Item column) {
		selectedColumns.remove(column);
		groupByList.remove(column);
		groupByAggregateMap.remove(column);
		havingMap.remove(column);
		orderByList.remove(column);
		orderByArgumentMap.remove(column);
	}
	
	/**
	 * Generates the query based on the cache.
	 */
	public String generateQuery() {
	    SPDataSource dataSource = null;
	    if (database != null) {
            dataSource = database.getDataSource();
	    }
        logger.debug("Data source is " + dataSource + " while generating the query.");
		ConstantConverter converter = ConstantConverter.getConverter(dataSource);
		if (userModifiedQuery != null) {
			return userModifiedQuery;
		}
		if (selectedColumns.size() ==  0) {
			return "";
		}
		StringBuffer query = new StringBuffer();
		query.append("SELECT");
		boolean isFirstSelect = true;
		for (Item col : selectedColumns) {
			if (isFirstSelect) {
				query.append(" ");
				isFirstSelect = false;
			} else {
				query.append(", ");
			}
			if (groupByAggregateMap.containsKey(col)) {
				if(col instanceof StringCountItem) {
					query.append(col.getName());
				} else {
					query.append(groupByAggregateMap.get(col).toString() + "(");
				}
			}
			String alias = col.getContainer().getAlias();
			if (alias != null && alias.length() > 0) {
				query.append(alias + ".");
			} else if (fromTableList.contains(col.getContainer())) {
				query.append(col.getContainer().getName() + ".");
			}
			if(!(col instanceof StringCountItem)) {
				query.append(converter.getName(col));			
			}
			if (groupByAggregateMap.containsKey(col) && !(col instanceof StringCountItem)) {
				query.append(")");
			}
			if (col.getAlias() != null && col.getAlias().trim().length() > 0) {
				query.append(" AS " + col.getAlias());
			}
		}
		if (!fromTableList.isEmpty()) {
			query.append(" \nFROM");
		}
		boolean isFirstFrom = true;
		
		DepthFirstSearch<Container, SQLJoin> dfs = new DepthFirstSearch<Container, SQLJoin>();
		dfs.performSearch(new TableJoinGraph());
		Container previousTable = null;
		for (Container table : dfs.getFinishOrder()) {
			String qualifiedName;
			if (table.getContainedObject() instanceof SQLTable) {
				qualifiedName = ((SQLTable)table.getContainedObject()).toQualifiedName();
			} else {
				qualifiedName = table.getName();
			}
			String alias = table.getAlias();
			if (alias == null || alias.length() <= 0) {
				alias = table.getName();
			}
			if (isFirstFrom) {
				query.append(" " + qualifiedName + " " + alias);
				isFirstFrom = false;
			} else {
				boolean joinFound = false;
				if (previousTable != null && joinMapping.get(table) != null) {
					for (SQLJoin join : joinMapping.get(table)) {
						if (join.getLeftColumn().getContainer() == previousTable) {
							joinFound = true;
							if (join.isLeftColumnOuterJoin() && join.isRightColumnOuterJoin()) {
								query.append(" \nFULL OUTER JOIN ");
							} else if (join.isLeftColumnOuterJoin() && !join.isRightColumnOuterJoin()) {
								query.append(" \nLEFT OUTER JOIN ");
							} else if (!join.isLeftColumnOuterJoin() && join.isRightColumnOuterJoin()) {
								query.append(" \nRIGHT OUTER JOIN ");
							} else {
								query.append(" \nINNER JOIN ");
							}
							break;
						}
					}
				}
				if (!joinFound) {
					query.append(" \nINNER JOIN ");
				}
				query.append(qualifiedName + " " + alias + " \n  ON ");
				if (joinMapping.get(table) == null || joinMapping.get(table).isEmpty()) {
					query.append("0 = 0");
				} else {
					boolean isFirstJoin = true;
					for (SQLJoin join : joinMapping.get(table)) {
						Item otherColumn;
						if (join.getLeftColumn().getContainer() == table) {
							otherColumn = join.getRightColumn();
						} else {
							otherColumn = join.getLeftColumn();
						}
						for (int i = 0; i < dfs.getFinishOrder().indexOf(table); i++) {
							if (otherColumn.getContainer() == dfs.getFinishOrder().get(i)) {
								if (isFirstJoin) {
									isFirstJoin = false;
								} else {
									query.append(" \n    AND ");
								}
								String leftAlias = join.getLeftColumn().getContainer().getAlias();
								if (leftAlias == null || leftAlias.length() <= 0) {
									leftAlias = join.getLeftColumn().getContainer().getName();
								}
								String rightAlias = join.getRightColumn().getContainer().getAlias();
								if (rightAlias == null || rightAlias.length() <= 0) {
									rightAlias = join.getRightColumn().getContainer().getName();
								}
								query.append(leftAlias + "." + join.getLeftColumn().getName() + 
										" " + join.getComparator() + " " + 
										rightAlias + "." + join.getRightColumn().getName());
							}
						}
					}
					if (isFirstJoin) {
						query.append("0 = 0");
					}
				}
			}
			previousTable = table;
		}
		query.append(" ");
		boolean isFirstWhere = true;
		Map<Item, String> whereMapping = new HashMap<Item, String>();
		for (Item item : constantsContainer.getItems()) {
			if (item.getWhere() != null && item.getWhere().trim().length() > 0) {
				whereMapping.put(item, item.getWhere());
			}
		}
		for (Container container : fromTableList) {
			for (Item item : container.getItems()) {
				if (item.getWhere() != null && item.getWhere().trim().length() > 0) {
					whereMapping.put(item, item.getWhere());
				}
			}
		}
		for (Map.Entry<Item, String> entry : whereMapping.entrySet()) {
			if (entry.getValue().length() > 0) {
				if (isFirstWhere) {
					query.append(" \nWHERE ");
					isFirstWhere = false;
				} else {
					query.append(" AND ");
				}
				String alias = entry.getKey().getContainer().getAlias();
				if (alias != null && alias.length() > 0) {
					query.append(alias + ".");
				} else if (fromTableList.contains(entry.getKey().getContainer())) {
					query.append(entry.getKey().getContainer().getName() + ".");
				}
				query.append(entry.getKey().getName() + " " + entry.getValue());
			}
		}
		if ((globalWhereClause != null && globalWhereClause.length() > 0)) {
			if (!isFirstWhere) {
				query.append(" AND"); 
			} else {
				query.append(" \nWHERE ");
			}
			query.append(" " + globalWhereClause);
		}
		if (!groupByList.isEmpty()) {
			query.append("\nGROUP BY");
			boolean isFirstGroupBy = true;
			for (Item col : groupByList) {
				if (isFirstGroupBy) {
					query.append(" ");
					isFirstGroupBy = false;
				} else {
					query.append(", ");
				}
				String alias = col.getContainer().getAlias();
				if (alias != null && alias.length() > 0) {
					query.append(alias + ".");
				} else if (fromTableList.contains(col.getContainer())) {
					query.append(col.getContainer().getName() + ".");
				}
				query.append(col.getName());
			}
			query.append(" ");
		}
		if (!havingMap.isEmpty()) {
			query.append("\nHAVING");
			boolean isFirstHaving = true;
			for (Map.Entry<Item, String> entry : havingMap.entrySet()) {
				if (isFirstHaving) {
					query.append(" ");
					isFirstHaving = false;
				} else {
					query.append(", ");
				}
				Item column = entry.getKey();
				if (groupByAggregateMap.get(column) != null) {
					query.append(groupByAggregateMap.get(column).toString() + "(");
				}
				String alias = column.getContainer().getAlias();
				if (alias != null && alias.length() > 0) {
					query.append(alias + ".");
				} else if (fromTableList.contains(column.getContainer())) {
					query.append(column.getContainer().getName() + ".");
				}
				query.append(column.getName());
				if (groupByAggregateMap.get(column) != null) {
					query.append(")");
				}
				query.append(" ");
				query.append(entry.getValue());
			}
			query.append(" ");
		}
		
		if (!orderByArgumentMap.isEmpty()) {
			boolean isFirstOrder = true;
			for (Item col : orderByList) {
				if (col instanceof StringItem) {
					continue;
				}
				if (isFirstOrder) {
					query.append("\nORDER BY ");
					isFirstOrder = false;
				} else {
					query.append(", ");
				}
				if (groupByAggregateMap.containsKey(col)) {
					query.append(groupByAggregateMap.get(col) + "(");
				}
				String alias = col.getContainer().getAlias();
				if (alias != null && alias.length() > 0) {
					query.append(alias + ".");
				} else if (fromTableList.contains(col.getContainer())) {
					query.append(col.getContainer().getName() + ".");
				}
				query.append(col.getName());
				if (groupByAggregateMap.containsKey(col)) {
					query.append(")");
				}
				query.append(" ");
				if (orderByArgumentMap.get(col) != null) {
					query.append(orderByArgumentMap.get(col).toString() + " ");
				}
			}
		}
		logger.debug(" Query is : " + query.toString());
		return query.toString();
	}

    /**
     * Executes the current SQL query, returning a cached copy of the result
     * set. The returned copy of the result set is guaranteed to be scrollable,
     * and does not hold any remote database resources.
     * 
     * @return an in-memory copy of the result set produced by this query
     *         cache's current query. You are not required to close the returned
     *         result set when you are finished with it, but you can if you
     *         like.
     * @throws SQLException
     *             If the query fails to execute for any reason.
     */
	public boolean executeStatement() throws SQLException {
		return executeStatement(false);
	}

	/**
	 * Executes the current SQL query, returning a cached copy of the result set
	 * that is either a subset of the full results, limited by the session's row
	 * limit, or the full result set. The returned copy of the result set is
	 * guaranteed to be scrollable, and does not hold any remote database
	 * resources.
	 * 
	 * @return an in-memory copy of the result set produced by this query
	 *         cache's current query. You are not required to close the returned
	 *         result set when you are finished with it, but you can if you
	 *         like.
	 * @throws SQLException
	 *             If the query fails to execute for any reason.
	 */
	public boolean executeStatement(boolean fetchFullResults) throws SQLException {
		stopRunning();
		resultPosition = 0;
		resultSets.clear();
		updateCounts.clear();
		if (database == null || database.getDataSource() == null) {
			throw new NullPointerException("Data source is null.");
		}
	    String sql = generateQuery();
	    ResultSet rs = null;
	    try {
	    	currentConnection = database.getConnection();
	    	firePropertyChange("running", false, true);
	    	currentStatement = currentConnection.createStatement();
	        if (!fetchFullResults) {
	        	currentStatement.setMaxRows(session.getRowLimit());
	        }
	        boolean initialResult = currentStatement.execute(sql);
            boolean sqlResult = initialResult;
            boolean hasNext = true;
            while (hasNext) {
            	if (sqlResult) {
            		final CachedRowSet crs = new CachedRowSet();
            		if (streaming) {
            			final ResultSet streamingRS = currentStatement.getResultSet();
            			Thread t = new Thread() {
            				@Override
            				public void run() {
            					try {
            						Thread.currentThread().setUncaughtExceptionHandler(new ExceptionHandler());
									crs.follow(streamingRS, rsChangeListener, getStreamingRowLimit());
								} catch (SQLException e) {
									logger.error("Exception while streaming result set", e);
								}
            				}
            			};
            			t.start();
            			streamingThreads.add(t);
            		} else {
            			crs.populate(currentStatement.getResultSet());
            		}
            		resultSets.add(crs);
            	} else {
            		resultSets.add(null);
            	}
                updateCounts.add(currentStatement.getUpdateCount());
                sqlResult = currentStatement.getMoreResults();
                hasNext = !((sqlResult == false) && (currentStatement.getUpdateCount() == -1));
            }
            return initialResult;
	    } catch (SQLObjectException e) {
	        throw new SQLObjectRuntimeException(e);
        } finally {
	    	if (!streaming) {
	    		if (rs != null) {
	    			try {
	    				rs.close();
	    			} catch (Exception ex) {
	    				logger.warn("Failed to close result set. Squishing this exception: ", ex);
	    			}
	    		}
	    		if (currentStatement != null) {
	    			try {
	    				currentStatement.close();
	    				currentStatement = null;
	    			} catch (Exception ex) {
	    				logger.warn("Failed to close statement. Squishing this exception: ", ex);
	    			}
	    		}
	    		if (currentConnection != null) {
	    			try {
	    				currentConnection.close();
	    				currentConnection = null;
	    			} catch (Exception ex) {
	    				logger.warn("Failed to close connection. Squishing this exception: ", ex);
	    			}
	    		}
	    		firePropertyChange("running", true, false);
	    	}
	    }
	}
    	
	private final List<CachedRowSet> resultSets = new ArrayList<CachedRowSet>();
	private final List<Integer> updateCounts = new ArrayList<Integer>();
	private int resultPosition = 0;


	public ResultSet getResultSet() {
		if (resultPosition >= resultSets.size()) {
			return null;
		}
		return resultSets.get(resultPosition);
	}

	//TODO:Rename this
	public String getStatement() {
		return generateQuery();
	}

	public int getUpdateCount() {
		if (resultPosition >= updateCounts.size()) {
			return -1;
		}
		return updateCounts.get(resultPosition);
	}

	public boolean getMoreResults() {
		resultPosition++;
		return resultPosition < resultSets.size() && resultSets.get(resultPosition) != null;
	}

	/**
	 * Returns the most up to date result set in the query cache. This may
	 * execute the query on the database.
	 * 
	 * @param fullResultSet
	 *            If true the full result set will be retrieved. If false then a
	 *            limited result set will be retrieved based on the session's
	 *            row limit.
	 */
	public ResultSet fetchResultSet() throws SQLException {
		if (!resultSets.isEmpty()) {
			for (CachedRowSet rs : resultSets) {
				if (rs != null) {
					return rs.createShared();
				}
			}
			return null;
		}
		executeStatement();
		for (CachedRowSet rs : resultSets) {
			if (rs != null) {
				return rs.createShared();
			}
		}
		return null;
	}
	
	public List<Item> getSelectedColumns() {
		return Collections.unmodifiableList(selectedColumns);
	}

	/**
	 * Returns the grouping function if the column is being aggregated on
	 * or null otherwise.
	 */
	public SQLGroupFunction getGroupByAggregate(Item column) {
		return groupByAggregateMap.get(column);
	}
	
	/**
	 * Returns the having clause of a specific column if it has text. Returns
	 * null otherwise.
	 * @param column
	 * @return
	 */
	public String getHavingClause(Item column) {
		return havingMap.get(column);
	}

	public OrderByArgument getOrderByArgument(Item column) {
		return orderByArgumentMap.get(column);
	}

	public List<Item> getOrderByList() {
		return Collections.unmodifiableList(orderByList);
	}
	
	/**
	 * This method will change the selection of a column.
	 */
	public void selectionChanged(Item column, Boolean isSelected) {
		if (isSelected.equals(true)) {
			selectedColumns.add(column);
			if (groupingEnabled) {
				if (column instanceof StringItem) {
					groupByAggregateMap.put(column, SQLGroupFunction.COUNT);
				} else {
					groupByList.add(column);
				}
			}
			logger.debug("Added " + column.getName() + " to the column list");
		} else if (isSelected.equals(false)) {
			removeColumnSelection(column);
		}
		if (!compoundEdit) {
			logger.debug("Firing change for selection.");
			firePropertyChange(PROPERTY_QUERY, Boolean.valueOf(!isSelected), Boolean.valueOf(isSelected));
		}
	}
	
	public void removeTable(Container table) {
		fromTableList.remove(table);
		table.removeChildListener(tableChildListener);
		for (Item col : table.getItems()) {
			removeItem(col);
		}
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_TABLE_REMOVED, table, null);
		}
	}

	public void addTable(Container container) {
		fromTableList.add(container);
		container.addChildListener(tableChildListener);
		for (Item col : container.getItems()) {
			addItem(col);
		}
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, null, container);
		}
	}
	
	/**
	 * This setter will fire a property change event.
	 */
	public void setGlobalWhereClause(String whereClause) {
		String oldWhere = globalWhereClause;
		globalWhereClause = whereClause;
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, oldWhere, whereClause);
		}
	}
	
	public void removeJoin(SQLJoin joinLine) {
		joinLine.removeJoinChangeListener(joinChangeListener);
		Item leftColumn = joinLine.getLeftColumn();
		Item rightColumn = joinLine.getRightColumn();

		List<SQLJoin> leftJoinList = joinMapping.get(leftColumn.getContainer());
		for (SQLJoin join : leftJoinList) {
			if (leftColumn == join.getLeftColumn() && rightColumn == join.getRightColumn()) {
				leftJoinList.remove(join);
				break;
			}
		}

		List<SQLJoin> rightJoinList = joinMapping.get(rightColumn.getContainer());
		for (SQLJoin join : rightJoinList) {
			if (leftColumn == join.getLeftColumn() && rightColumn == join.getRightColumn()) {
				rightJoinList.remove(join);
				break;
			}
		}
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, joinLine, null);
		}
	}

	public void addJoin(SQLJoin join) {
		join.addJoinChangeListener(joinChangeListener);
		Item leftColumn = join.getLeftColumn();
		Item rightColumn = join.getRightColumn();
		Container leftContainer = leftColumn.getContainer();
		Container rightContainer = rightColumn.getContainer();
		if (joinMapping.get(leftContainer) == null) {
			List<SQLJoin> joinList = new ArrayList<SQLJoin>();
			joinList.add(join);
			joinMapping.put(leftContainer, joinList);
		} else {
			if (joinMapping.get(leftContainer).size() > 0) {
				SQLJoin prevJoin = joinMapping.get(leftContainer).get(0);
				if (prevJoin.getLeftColumn().getContainer() == leftContainer) {
					join.setLeftColumnOuterJoin(prevJoin.isLeftColumnOuterJoin());
				} else if (prevJoin.getRightColumn().getContainer() == leftContainer) {
					join.setLeftColumnOuterJoin(prevJoin.isRightColumnOuterJoin());
				}
			}
				
			joinMapping.get(leftContainer).add(join);
		}

		if (joinMapping.get(rightContainer) == null) {
			List<SQLJoin> joinList = new ArrayList<SQLJoin>();
			joinList.add(join);
			joinMapping.put(rightContainer, joinList);
		} else {
			if (joinMapping.get(rightContainer).size() > 0) {
				SQLJoin prevJoin = joinMapping.get(rightContainer).get(0);
				if (prevJoin.getLeftColumn().getContainer() == rightContainer) {
					join.setRightColumnOuterJoin(prevJoin.isLeftColumnOuterJoin());
				} else if (prevJoin.getRightColumn().getContainer() == rightContainer) {
					join.setRightColumnOuterJoin(prevJoin.isRightColumnOuterJoin());
				} else {
					throw new IllegalStateException("A table contains a join that is not connected to any of its columns in the table.");
				}
			}
			joinMapping.get(rightContainer).add(join);
		}
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, null, join);
		}
	}
	
	/**
	 * This removes the item from all lists it could be
	 * contained in as well as disconnect its listeners.
	 */
	public void removeItem(Item col) {
		logger.debug("Item name is " + col.getName());
		col.removePropertyChangeListener(aliasListener);
		col.removePropertyChangeListener(selectedColumnListener);
		col.removePropertyChangeListener(whereListener);
		removeColumnSelection(col);
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, col, null);
		}
	}
	
	/**
	 * This adds the appropriate listeners to the new Item.
	 */
	public void addItem(Item col) {
		col.addPropertyChangeListener(aliasListener);
		col.addPropertyChangeListener(selectedColumnListener);
		col.addPropertyChangeListener(whereListener);
	}
	
	/**
	 * This aggregate is either the toString of a SQLGroupFunction or the
	 * string GROUP_BY defined in this class.
	 */
	public void setGrouping(Item column, String groupByAggregate) {
		SQLGroupFunction oldAggregate = groupByAggregateMap.get(column);
		if (groupByAggregate.equals(GROUP_BY)) {
			if (groupByList.contains(column)) {
				return;
			}
			groupByList.add(column);
			groupByAggregateMap.remove(column);
			logger.debug("Added " + column.getName() + " to group by list.");
		} else {
			if (SQLGroupFunction.valueOf(groupByAggregate).equals(groupByAggregateMap.get(column)) 
					&& !(column instanceof StringCountItem)) {
				return;
			}
			groupByAggregateMap.put(column, SQLGroupFunction.valueOf(groupByAggregate));
			groupByList.remove(column);
			logger.debug("Added " + column.getName() + " with aggregate " + groupByAggregate + " to aggregate group by map.");
		}
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, oldAggregate, groupByAggregate);
		}		

	}
	
	public void removeSort(Item item) {
		orderByList.remove(item);
		orderByArgumentMap.remove(item);
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, item, null);
		}
	}
	
	public void setSortOrder(Item item, OrderByArgument arg) {
		OrderByArgument oldSortArg = orderByArgumentMap.get(item);
		removeSort(item);
		orderByArgumentMap.put(item, arg);
		orderByList.add(item);
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, oldSortArg, arg);
		}
	}
	
	public void setHavingClause(Item item, String havingText) {
		String oldText = havingMap.get(item);
		if (havingText != null && havingText.length() > 0) {
			if (!havingText.equals(havingMap.get(item))) {
				havingMap.put(item, havingText);
			}
		} else {
			havingMap.remove(item);
		}
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, oldText, havingText);
		}
	}
	
	public void moveItem(Item movedColumn, int toIndex) {
		int oldIndex = selectedColumns.indexOf(movedColumn);
		selectedColumns.remove(movedColumn);
		selectedColumns.add(toIndex, movedColumn);
		if (!compoundEdit) {
			firePropertyChange(PROPERTY_QUERY, oldIndex, toIndex);
		}
	}
	
	public void startCompoundEdit() {
		compoundEdit = true;
		queryBeforeEdit = generateQuery();
	}
	
	public void endCompoundEdit() {
		compoundEdit = false;
		String currentQuery = generateQuery();
		if (!currentQuery.equals(queryBeforeEdit)) {
			firePropertyChange(PROPERTY_QUERY, queryBeforeEdit, currentQuery);
		}
		queryBeforeEdit = "";
	}

	public boolean isGroupingEnabled() {
		return groupingEnabled;
	}

	public Map<Item, SQLGroupFunction> getGroupByAggregateMap() {
		return Collections.unmodifiableMap(groupByAggregateMap);
	}

	protected List<Item> getGroupByList() {
		return Collections.unmodifiableList(groupByList);
	}

	public Map<Item, String> getHavingMap() {
		return Collections.unmodifiableMap(havingMap);
	}

	public Map<Item, OrderByArgument> getOrderByArgumentMap() {
		return Collections.unmodifiableMap(orderByArgumentMap);
	}

	public List<Container> getFromTableList() {
		return Collections.unmodifiableList(fromTableList);
	}

	protected Map<Container, List<SQLJoin>> getJoinMapping() {
		return Collections.unmodifiableMap(joinMapping);
	}
	
	/**
	 * This returns the joins between tables. Each join will be
	 * contained only once.
	 */
	public Collection<SQLJoin> getJoins() {
		Set<SQLJoin> joinSet = new HashSet<SQLJoin>();
		for (List<SQLJoin> joins : joinMapping.values()) {
			for (SQLJoin join : joins) {
				joinSet.add(join);
			}
		}
		return joinSet;
	}

	public String getGlobalWhereClause() {
		return globalWhereClause;
	}

	public boolean allowsChildren() {
		return false;
	}

	public int childPositionOffset(Class<? extends WabitObject> childType) {
		throw new IllegalStateException("There are no children of a QueryCache.");
	}

	public List<? extends WabitObject> getChildren() {
		return Collections.emptyList();
	}

	public Container getConstantsContainer() {
		return constantsContainer;
	}
	
	public WabitDataSource getWabitDataSource() {
		if (database == null || database.getDataSource() == null) {
			return null;
		}
		return new JDBCDataSource(database.getDataSource());
	}
	
	public void setDataSource(SPDataSource dataSource) {
	    this.database = session.getDatabase(dataSource);
	    if (dataSource != null) {
	    	setStreaming(dataSource.getParentType().getSupportsStreamQueries());
	    }
	}
	
	/**
	 * If this is set then only this query string will be returned by the generateQuery method
	 * and the query cache will not accurately represent the query.
	 */
	public void defineUserModifiedQuery(String query) {
		String generatedQuery = generateQuery();
		logger.debug("Generated query is " + generatedQuery + " and given query is " + query);
		if (generatedQuery.equals(query)) {
			return;
		}
		firePropertyChange(PROPERTY_QUERY_TEXT, userModifiedQuery, query);
		userModifiedQuery = query;
	}
	
	/**
	 * Returns true if the user manually edited the text of the query. Returns false otherwise.
	 */
	public boolean isScriptModified() {
		return userModifiedQuery != null;
	}
	
	/**
	 * Resets the manual modifications the user did to the text of the query so the textual
	 * query is the same as the query cache.
	 */
	public void removeUserModifications() {
		logger.debug("Removing user modified query.");
		userModifiedQuery = null;
	}

	/**
	 * Creates a new constants container for this QueryCache. This should
	 * only be used in loading.
	 */
	public Container newConstantsContainer(String uuid) {
		constantsContainer = new ItemContainer("Constants", uuid);
		return constantsContainer;
	}

	public void setZoomLevel(int zoomLevel) {
		this.zoomLevel = zoomLevel;
	}

	public int getZoomLevel() {
		return zoomLevel;
	}
	
	/**
	 * Used for constructing copies of the query cache.
	 */
	protected String getUserModifiedQuery() {
		return userModifiedQuery;
	}
	
	/**
	 * Used in the copy constructor to set the session.
	 */
	public WabitSession getSession() {
		return session;
	}
	
	protected List<CachedRowSet> getResultSets() {
		return Collections.unmodifiableList(resultSets);
	}
	
	public void setStreaming(boolean isStreaming) {
		this.streaming = isStreaming;
	}

	public boolean isStreaming() {
		return streaming;
	}

	@Override
	public String toString() {
		return getName();
	}

	public void addRowSetChangeListener(RowSetChangeListener l) {
		rowSetChangeListeners.add(l);
	}

	public void removeRowSetChangeListener(RowSetChangeListener l) {
		rowSetChangeListeners.remove(l);		
	}
	
	public List<Thread> getStreamingThreads() {
		return Collections.unmodifiableList(streamingThreads);
	}

	public void setStreamingRowLimit(int streamingRowLimit) {
		this.streamingRowLimit = streamingRowLimit;
	}

	public int getStreamingRowLimit() {
		return streamingRowLimit;
	}

	public void setDatabase(SQLDatabase db) {
		database = db;
	}

	public boolean isRunning() {
		return (currentStatement != null || currentConnection != null);
	}

	public void stopRunning() {
		if (currentStatement != null) {
			try {
				currentStatement.cancel();
				currentStatement.close();
				currentStatement = null;
			} catch (SQLException e) {
				logger.error("Exception while closing old streaming statement", e);
			}
		}
		if (currentConnection != null) {
			try {
				currentConnection.close();
				currentConnection = null;
			} catch (SQLException e) {
				logger.error("Exception while closing old streaming connection", e);
			}
		}
		streamingThreads.clear();
		firePropertyChange("running", true, false);
	}

	public void addTimerListener(PropertyChangeListener l) {
		timerPCS.addPropertyChangeListener(l);
	}

	public ActionListener getTimerListener() {
		return timerListener;
	}

	public void removeTimerListener(PropertyChangeListener l) {
		timerPCS.removePropertyChangeListener(l);
	}
}