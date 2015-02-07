/*
 * Copyright (C) 2010-2014 Serge Rieder
 * serge@jkiss.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.jkiss.dbeaver.model.impl.jdbc.cache;

import org.jkiss.dbeaver.core.Log;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.DBSObjectCache;
import org.jkiss.dbeaver.model.impl.DBSStructCache;
import org.jkiss.dbeaver.model.impl.SimpleObjectCache;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * JDBC structured objects cache. Stores objects themselves and their child objects.
 */
public abstract class JDBCStructCache<OWNER extends DBSObject, OBJECT extends DBSObject, CHILD extends DBSObject> extends
    JDBCObjectCache<OWNER, OBJECT> implements DBSStructCache<OWNER, OBJECT, CHILD> {
    static final Log log = Log.getLog(JDBCStructCache.class);

    private final Object objectNameColumn;
    private volatile boolean childrenCached = false;
    private final Map<OBJECT, SimpleObjectCache<OBJECT, CHILD>> childrenCache = new IdentityHashMap<OBJECT, SimpleObjectCache<OBJECT, CHILD>>();

    abstract protected JDBCStatement prepareChildrenStatement(JDBCSession session, OWNER owner, OBJECT forObject)
        throws SQLException;

    abstract protected CHILD fetchChild(JDBCSession session, OWNER owner, OBJECT parent, ResultSet dbResult)
        throws SQLException, DBException;

    protected JDBCStructCache(Object objectNameColumn)
    {
        this.objectNameColumn = objectNameColumn;
    }

    /**
     * Reads children objects from database
     * 
     * @param monitor
     *            monitor
     * @param forObject
     *            object for which to read children. If null then reads children for all objects in this container.
     * @throws org.jkiss.dbeaver.DBException
     *             on error
     */
    public void loadChildren(DBRProgressMonitor monitor, OWNER owner, @Nullable final OBJECT forObject) throws DBException
    {
        if ((forObject == null && this.childrenCached)
            || (forObject != null && (!forObject.isPersisted() || isChildrenCached(forObject))) || monitor.isCanceled()) {
            return;
        }
        if (forObject == null) {
            super.loadObjects(monitor, owner);
        }

        JDBCSession session = (JDBCSession) owner.getDataSource().openSession(monitor, DBCExecutionPurpose.META,
            "Load child objects");
        try {
            Map<OBJECT, List<CHILD>> objectMap = new HashMap<OBJECT, List<CHILD>>();

            // Load columns
            JDBCStatement dbStat = prepareChildrenStatement(session, owner, forObject);
            try {
                dbStat.setFetchSize(DBConstants.METADATA_FETCH_SIZE);
                dbStat.executeStatement();
                JDBCResultSet dbResult = dbStat.getResultSet();
                try {
                    while (dbResult.next()) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        String objectName;
                        if (objectNameColumn instanceof Number) {
                            objectName = JDBCUtils.safeGetString(dbResult, ((Number) objectNameColumn).intValue());
                        } else {
                            objectName = JDBCUtils.safeGetStringTrimmed(dbResult, objectNameColumn.toString());
                        }

                        OBJECT object = forObject;
                        if (object == null) {
                            object = super.getCachedObject(objectName);
                            if (object == null) {
                                log.debug("Object '" + objectName + "' not found");
                                continue;
                            }
                        }
                        if (isChildrenCached(object)) {
                            // Already read
                            continue;
                        }
                        CHILD child = fetchChild(session, owner, object, dbResult);
                        if (child == null) {
                            continue;
                        }

                        // Add to map
                        List<CHILD> children = objectMap.get(object);
                        if (children == null) {
                            children = new ArrayList<CHILD>();
                            objectMap.put(object, children);
                        }
                        children.add(child);
                    }

                    if (monitor.isCanceled()) {
                        return;
                    }

                    // All children are read. Now assign them to parents
                    for (Map.Entry<OBJECT, List<CHILD>> colEntry : objectMap.entrySet()) {
                        cacheChildren(colEntry.getKey(), colEntry.getValue());
                    }
                    if (forObject == null) {
                        if (objectMap.isEmpty()) {
                            // Nothing was read. May be it means empty list of children
                            // but possibly this feature is not supported [JDBC: SQLite]
                        } else {
                            // Now set empty column list for other tables
                            for (OBJECT tmpObject : getObjects(monitor, owner)) {
                                if (!isChildrenCached(tmpObject) && !objectMap.containsKey(tmpObject)) {
                                    cacheChildren(tmpObject, new ArrayList<CHILD>());
                                }
                            }
                            this.childrenCached = true;
                        }
                    } else if (!objectMap.containsKey(forObject)) {
                        cacheChildren(forObject, new ArrayList<CHILD>());
                    }
                } finally {
                    dbResult.close();
                }
            } finally {
                dbStat.close();
            }
        } catch (SQLException ex) {
            throw new DBException(ex, session.getDataSource());
        } finally {
            session.close();
        }
    }

    @Override
    public void removeObject(OBJECT object)
    {
        super.removeObject(object);
        clearChildrenCache(object);
    }

    @Override
    public void clearCache()
    {
        this.clearChildrenCache(null);
        super.clearCache();
    }

    /**
     * Returns cache for child objects. Creates cache i it doesn't exists
     * 
     * @param forObject
     *            parent object
     * @return cache
     */
    public DBSObjectCache<OBJECT, CHILD> getChildrenCache(final OBJECT forObject)
    {
        synchronized (childrenCache) {
            SimpleObjectCache<OBJECT, CHILD> nestedCache = childrenCache.get(forObject);
            if (nestedCache == null) {
                // Create new empty children cache
                // This may happen only when invoked for newly created object (e.g. when we create new column
                // in a new created table)
                nestedCache = new SimpleObjectCache<OBJECT, CHILD>();
                nestedCache.setCache(new ArrayList<CHILD>());
                childrenCache.put(forObject, nestedCache);
            }
            return nestedCache;
        }
    }

    @Nullable
    public List<CHILD> getChildren(DBRProgressMonitor monitor, OWNER owner, final OBJECT forObject) throws DBException
    {
        loadChildren(monitor, owner, forObject);
        synchronized (childrenCache) {
            SimpleObjectCache<OBJECT, CHILD> nestedCache = childrenCache.get(forObject);
            return nestedCache == null ? null : nestedCache.getObjects(monitor, null);
        }
    }

    @Nullable
    public CHILD getChild(DBRProgressMonitor monitor, OWNER owner, final OBJECT forObject, String objectName) throws DBException
    {
        loadChildren(monitor, owner, forObject);
        synchronized (childrenCache) {
            SimpleObjectCache<OBJECT, CHILD> nestedCache = childrenCache.get(forObject);
            return nestedCache == null ? null : nestedCache.getObject(monitor, null, objectName);
        }
    }

    public void clearChildrenCache(OBJECT forParent)
    {
        synchronized (childrenCache) {
            if (forParent != null) {
                this.childrenCache.remove(forParent);
            } else {
                this.childrenCache.clear();
            }
        }
    }

    private boolean isChildrenCached(OBJECT parent)
    {
        synchronized (childrenCache) {
            return childrenCache.containsKey(parent);
        }
    }

    private void cacheChildren(OBJECT parent, List<CHILD> children)
    {
        synchronized (childrenCache) {
            SimpleObjectCache<OBJECT, CHILD> nestedCache = childrenCache.get(parent);
            if (nestedCache == null) {
                nestedCache = new SimpleObjectCache<OBJECT, CHILD>();
                nestedCache.setCaseSensitive(caseSensitive);
                childrenCache.put(parent, nestedCache);
            }
            nestedCache.setCache(children);
        }
    }

}