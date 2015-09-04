/*
 * Copyright 2014 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;


import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import io.realm.exceptions.RealmException;
import io.realm.internal.ColumnType;
import io.realm.internal.LinkView;
import io.realm.internal.Mixed;
import io.realm.internal.Row;
import io.realm.internal.SharedGroup;
import io.realm.internal.Table;
import io.realm.internal.TableQuery;
import io.realm.internal.TableView;
import io.realm.internal.async.BadVersionException;
import io.realm.internal.async.QueryUpdateTask;
import io.realm.internal.async.RealmThreadPoolExecutor;
import io.realm.internal.async.RetryPolicy;
import io.realm.internal.async.RetryPolicyFactory;

import static io.realm.Realm.asyncQueryExecutor;


/**
 * A RealmQuery encapsulates a query on a {@link io.realm.Realm} or a {@link io.realm.RealmResults}
 * using the Builder pattern. The query is executed using either {@link #findAll()} or
 * {@link #findFirst()}
 * <p/>
 * The input to many of the query functions take a field name as String. Note that this is not
 * type safe. If a model class is refactored care has to be taken to not break any queries.
 * <p/>
 * A {@link io.realm.Realm} is unordered, which means that there is no guarantee that querying a
 * Realm will return the objects in the order they where inserted. Use
 * {@link #findAllSorted(String)} and similar methods if a specific order is required.
 * <p/>
 * A RealmQuery cannot be passed between different threads.
 *
 * @param <E> The class of the objects to be queried.
 * @see <a href="http://en.wikipedia.org/wiki/Builder_pattern">Builder pattern</a>
 * @see Realm#where(Class)
 * @see RealmResults#where()
 */
public class RealmQuery<E extends RealmObject> {

    private Realm realm;
    private Table table;
    private LinkView view;
    private TableQuery query;
    private Map<String, Long> columns = new HashMap<String, Long>();
    private Class<E> clazz;

    public static final boolean CASE_SENSITIVE = true;
    public static final boolean CASE_INSENSITIVE = false;

    private Request asyncRequest;
    private final RetryPolicy retryPolicy;
    private static int RETRY_POLICY_MODE = RetryPolicy.MODE_INDEFINITELY;
    private static int MAX_NUMBER_RETRIES_POLICY = 0;

    // sorting properties
    private String fieldName;
    private boolean sortAscending;
    private String[] fieldNames;
    private boolean[] sortAscendings;

    //TODO do we still need those as attributes?
    // native pointers to be released
    private long handoverQueryPtr = -1;
    private long handoverRowPtr = -1;
    private long handoverTableViewPtr = -1;


    /**
     * Creating a RealmQuery instance.
     *
     * @param realm The realm to query within.
     * @param clazz The class to query.
     * @throws java.lang.RuntimeException Any other error.
     */
    public RealmQuery(Realm realm, Class<E> clazz) {
        this.realm = realm;
        this.clazz = clazz;
        this.table = realm.getTable(clazz);
        this.query = table.where();
        this.columns = realm.columnIndices.getClassFields(clazz);
        this.retryPolicy = RetryPolicyFactory.get(RETRY_POLICY_MODE, MAX_NUMBER_RETRIES_POLICY);
    }

    /**
     * Create a RealmQuery instance from a @{link io.realm.RealmResults}.
     *
     * @param realmList The @{link io.realm.RealmResults} to query
     * @param clazz     The class to query
     * @throws java.lang.RuntimeException Any other error
     */
    public RealmQuery(RealmResults realmList, Class<E> clazz) {
        this.realm = realmList.getRealm();
        this.clazz = clazz;
        this.table = realm.getTable(clazz);
        this.query = realmList.getTable().where();
        this.columns = realm.columnIndices.getClassFields(clazz);
        this.retryPolicy = RetryPolicyFactory.get(RETRY_POLICY_MODE, MAX_NUMBER_RETRIES_POLICY);
    }

    RealmQuery(Realm realm, LinkView view, Class<E> clazz) {
        this.realm = realm;
        this.clazz = clazz;
        this.query = view.where();
        this.view = view;
        this.table = realm.getTable(clazz);
        this.columns = realm.columnIndices.getClassFields(clazz);
        this.retryPolicy = RetryPolicyFactory.get(RETRY_POLICY_MODE, MAX_NUMBER_RETRIES_POLICY);
    }

    private boolean containsDot(String s) {
        return s.indexOf('.') != -1;
    }

    private String[] splitString(String s) {
        int i, j, n;

        // count the number of .
        n = 0;
        for (i = 0; i < s.length(); i++)
            if (s.charAt(i) == '.')
                n++;

        // split at .
        String[] arr = new String[n + 1];
        i = 0;
        n = 0;
        j = s.indexOf('.');
        while (j != -1) {
            arr[n] = s.substring(i, j);
            i = j + 1;
            j = s.indexOf('.', i);
            n++;
        }
        arr[n] = s.substring(s.lastIndexOf('.') + 1);

        return arr;
    }

    // TODO: consider another caching strategy so linked classes are included in the cache.
    private long[] getColumnIndices(String fieldName, ColumnType fieldType) {
        Table table = this.table;
        if (containsDot(fieldName)) {
            String[] names = splitString(fieldName); //fieldName.split("\\.");
            long[] columnIndices = new long[names.length];
            for (int i = 0; i < names.length - 1; i++) {
                long index = table.getColumnIndex(names[i]);
                if (index < 0) {
                    throw new IllegalArgumentException("Invalid query: " + names[i] + " does not refer to a class.");
                }
                ColumnType type = table.getColumnType(index);
                if (type == ColumnType.LINK || type == ColumnType.LINK_LIST) {
                    table = table.getLinkTarget(index);
                    columnIndices[i] = index;
                } else {
                    throw new IllegalArgumentException("Invalid query: " + names[i] + " does not refer to a class.");
                }
            }
            columnIndices[names.length - 1] = table.getColumnIndex(names[names.length - 1]);
            if (fieldType != table.getColumnType(columnIndices[names.length - 1])) {
                throw new IllegalArgumentException(String.format("Field '%s': type mismatch.", names[names.length - 1]));
            }
            return columnIndices;
        } else {
            if (columns.get(fieldName) == null) {
                throw new IllegalArgumentException(String.format("Field '%s' does not exist.", fieldName));
            }

            ColumnType tableColumnType = table.getColumnType(columns.get(fieldName));
            if (fieldType != tableColumnType) {
                throw new IllegalArgumentException(String.format("Field '%s': type mismatch. Was %s, expected %s.",
                        fieldName, fieldType, tableColumnType
                ));
            }
            return new long[]{columns.get(fieldName)};
        }
    }

    /**
     * Test if a field is null. Only works for relationships and RealmLists.
     *
     * @param fieldName - the field name
     * @return The query object
     * @throws java.lang.IllegalArgumentException if field is not a RealmObject or RealmList
     */
    public RealmQuery<E> isNull(String fieldName) {
        // Currently we only support querying top-level
        if (containsDot(fieldName)) {
            throw new IllegalArgumentException("Checking for null in nested objects is not supported.");
        }

        // checking that fieldName has the correct type is done in C++
        this.query.isNull(columns.get(fieldName));
        return this;
    }

    /**
     * Test if a field is not null. Only works for relationships and RealmLists.
     *
     * @param fieldName - the field name
     * @return The query object
     * @throws java.lang.IllegalArgumentException if field is not a RealmObject or RealmList
     */
    public RealmQuery<E> isNotNull(String fieldName) {
        return this.beginGroup().not().isNull(fieldName).endGroup();
    }

    // Equal

    /**
     * Equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> equalTo(String fieldName, String value) {
        return this.equalTo(fieldName, value, CASE_SENSITIVE);
    }

    /**
     * Equal-to comparison
     *
     * @param fieldName     The field to compare
     * @param value         The value to compare with
     * @param caseSensitive if true, substring matching is case sensitive. Setting this to false only works for English
     *                      locale characters.
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> equalTo(String fieldName, String value, boolean caseSensitive) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.STRING);
        this.query.equalTo(columnIndices, value, caseSensitive);
        return this;
    }

    /**
     * Equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> equalTo(String fieldName, int value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.equalTo(columnIndices, value);
        return this;
    }

    /**
     * Equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> equalTo(String fieldName, long value) {
        long[] columnIndices = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.equalTo(columnIndices, value);
        return this;
    }

    /**
     * Equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> equalTo(String fieldName, double value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DOUBLE);
        this.query.equalTo(columnIndices, value);
        return this;
    }

    /**
     * Equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> equalTo(String fieldName, float value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.FLOAT);
        this.query.equalTo(columnIndices, value);
        return this;
    }

    /**
     * Equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> equalTo(String fieldName, boolean value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.BOOLEAN);
        this.query.equalTo(columnIndices, value);
        return this;
    }

    /**
     * Equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> equalTo(String fieldName, Date value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DATE);
        this.query.equalTo(columnIndices, value);
        return this;
    }

    // Not Equal

    /**
     * Not-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> notEqualTo(String fieldName, String value) {
        return this.notEqualTo(fieldName, value, RealmQuery.CASE_SENSITIVE);
    }

    /**
     * Not-equal-to comparison
     *
     * @param fieldName     The field to compare
     * @param value         The value to compare with
     * @param caseSensitive if true, substring matching is case sensitive. Setting this to false only works for English
     *                      locale characters.
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> notEqualTo(String fieldName, String value, boolean caseSensitive) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.STRING);
        if (columnIndices.length > 1 && !caseSensitive) {
            throw new IllegalArgumentException("Link queries cannot be case insensitive - coming soon.");
        }
        this.query.notEqualTo(columnIndices, value, caseSensitive);
        return this;
    }

    /**
     * Not-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> notEqualTo(String fieldName, int value) {
        long[] columnIndices = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.notEqualTo(columnIndices, value);
        return this;
    }

    /**
     * Not-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> notEqualTo(String fieldName, long value) {
        long[] columnIndices = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.notEqualTo(columnIndices, value);
        return this;
    }

    /**
     * Not-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> notEqualTo(String fieldName, double value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DOUBLE);
        this.query.notEqualTo(columnIndices, value);
        return this;
    }

    /**
     * Not-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> notEqualTo(String fieldName, float value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.FLOAT);
        this.query.notEqualTo(columnIndices, value);
        return this;
    }

    /**
     * Not-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> notEqualTo(String fieldName, boolean value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.BOOLEAN);
        this.query.equalTo(columnIndices, !value);
        return this;
    }

    /**
     * Not-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> notEqualTo(String fieldName, Date value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DATE);
        this.query.notEqualTo(columnIndices, value);
        return this;
    }

    // Greater Than

    /**
     * Greater-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThan(String fieldName, int value) {
        long[] columnIndices = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.greaterThan(columnIndices, value);
        return this;
    }

    /**
     * Greater-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThan(String fieldName, long value) {
        long[] columnIndices = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.greaterThan(columnIndices, value);
        return this;
    }

    /**
     * Greater-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThan(String fieldName, double value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DOUBLE);
        this.query.greaterThan(columnIndices, value);
        return this;
    }

    /**
     * Greater-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThan(String fieldName, float value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.FLOAT);
        this.query.greaterThan(columnIndices, value);
        return this;
    }

    /**
     * Greater-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThan(String fieldName, Date value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DATE);
        this.query.greaterThan(columnIndices, value);
        return this;
    }

    /**
     * Greater-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThanOrEqualTo(String fieldName, int value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.greaterThanOrEqual(columnIndices, value);
        return this;
    }

    /**
     * Greater-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThanOrEqualTo(String fieldName, long value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.greaterThanOrEqual(columnIndices, value);
        return this;
    }

    /**
     * Greater-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThanOrEqualTo(String fieldName, double value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DOUBLE);
        this.query.greaterThanOrEqual(columnIndices, value);
        return this;
    }

    /**
     * Greater-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThanOrEqualTo(String fieldName, float value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.FLOAT);
        this.query.greaterThanOrEqual(columnIndices, value);
        return this;
    }

    /**
     * Greater-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> greaterThanOrEqualTo(String fieldName, Date value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DATE);
        this.query.greaterThanOrEqual(columnIndices, value);
        return this;
    }

    // Less Than

    /**
     * Less-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThan(String fieldName, int value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.lessThan(columnIndices, value);
        return this;
    }

    /**
     * Less-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThan(String fieldName, long value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.lessThan(columnIndices, value);
        return this;
    }

    /**
     * Less-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThan(String fieldName, double value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DOUBLE);
        this.query.lessThan(columnIndices, value);
        return this;
    }

    /**
     * Less-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThan(String fieldName, float value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.FLOAT);
        this.query.lessThan(columnIndices, value);
        return this;
    }

    /**
     * Less-than comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThan(String fieldName, Date value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DATE);
        this.query.lessThan(columnIndices, value);
        return this;
    }

    /**
     * Less-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThanOrEqualTo(String fieldName, int value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.lessThanOrEqual(columnIndices, value);
        return this;
    }

    /**
     * Less-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThanOrEqualTo(String fieldName, long value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.lessThanOrEqual(columnIndices, value);
        return this;
    }

    /**
     * Less-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThanOrEqualTo(String fieldName, double value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DOUBLE);
        this.query.lessThanOrEqual(columnIndices, value);
        return this;
    }

    /**
     * Less-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThanOrEqualTo(String fieldName, float value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.FLOAT);
        this.query.lessThanOrEqual(columnIndices, value);
        return this;
    }

    /**
     * Less-than-or-equal-to comparison
     *
     * @param fieldName The field to compare
     * @param value     The value to compare with
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> lessThanOrEqualTo(String fieldName, Date value) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DATE);
        this.query.lessThanOrEqual(columnIndices, value);
        return this;
    }

    // Between

    /**
     * Between condition
     *
     * @param fieldName The field to compare
     * @param from      Lowest value (inclusive)
     * @param to        Highest value (inclusive)
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> between(String fieldName, int from, int to) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.between(columnIndices, from, to);
        return this;
    }

    /**
     * Between condition
     *
     * @param fieldName The field to compare
     * @param from      Lowest value (inclusive)
     * @param to        Highest value (inclusive)
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> between(String fieldName, long from, long to) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.INTEGER);
        this.query.between(columnIndices, from, to);
        return this;
    }

    /**
     * Between condition
     *
     * @param fieldName The field to compare
     * @param from      Lowest value (inclusive)
     * @param to        Highest value (inclusive)
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> between(String fieldName, double from, double to) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DOUBLE);
        this.query.between(columnIndices, from, to);
        return this;
    }

    /**
     * Between condition
     *
     * @param fieldName The field to compare
     * @param from      Lowest value (inclusive)
     * @param to        Highest value (inclusive)
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> between(String fieldName, float from, float to) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.FLOAT);
        this.query.between(columnIndices, from, to);
        return this;
    }

    /**
     * Between condition
     *
     * @param fieldName The field to compare
     * @param from      Lowest value (inclusive)
     * @param to        Highest value (inclusive)
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> between(String fieldName, Date from, Date to) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.DATE);
        this.query.between(columnIndices, from, to);
        return this;
    }


    // Contains

    /**
     * Condition that value of field contains the specified substring
     *
     * @param fieldName The field to compare
     * @param value     The substring
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> contains(String fieldName, String value) {
        return contains(fieldName, value, CASE_SENSITIVE);
    }

    /**
     * Condition that value of field contains the specified substring
     *
     * @param fieldName     The field to compare
     * @param value         The substring
     * @param caseSensitive if true, substring matching is case sensitive. Setting this to false only works for English
     *                      locale characters.
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> contains(String fieldName, String value, boolean caseSensitive) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.STRING);
        this.query.contains(columnIndices, value, caseSensitive);
        return this;
    }

    /**
     * Condition that the value of field begins with the specified string
     *
     * @param fieldName The field to compare
     * @param value     The string
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> beginsWith(String fieldName, String value) {
        return beginsWith(fieldName, value, CASE_SENSITIVE);
    }

    /**
     * Condition that the value of field begins with the specified substring
     *
     * @param fieldName     The field to compare
     * @param value         The substring
     * @param caseSensitive if true, substring matching is case sensitive. Setting this to false only works for English
     *                      locale characters.
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> beginsWith(String fieldName, String value, boolean caseSensitive) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.STRING);
        this.query.beginsWith(columnIndices, value, caseSensitive);
        return this;
    }

    /**
     * Condition that the value of field ends with the specified string
     *
     * @param fieldName The field to compare
     * @param value     The string
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> endsWith(String fieldName, String value) {
        return endsWith(fieldName, value, CASE_SENSITIVE);
    }

    /**
     * Condition that the value of field ends with the specified substring
     *
     * @param fieldName     The field to compare
     * @param value         The substring
     * @param caseSensitive if true, substring matching is case sensitive. Setting this to false only works for English
     *                      locale characters.
     * @return The query object
     * @throws java.lang.IllegalArgumentException One or more arguments do not match class or
     *                                            field type
     * @throws java.lang.RuntimeException         Any other error
     */
    public RealmQuery<E> endsWith(String fieldName, String value, boolean caseSensitive) {
        long columnIndices[] = getColumnIndices(fieldName, ColumnType.STRING);
        this.query.endsWith(columnIndices, value, caseSensitive);
        return this;
    }

    // Grouping

    /**
     * Begin grouping of conditions ("left parenthesis"). A group must be closed with a
     * call to <code>endGroup()</code>.
     *
     * @return The query object
     * @see #endGroup()
     */
    public RealmQuery<E> beginGroup() {
        this.query.group();
        return this;
    }

    /**
     * End grouping of conditions ("right parenthesis") which was opened by a call to
     * <code>beginGroup()</code>.
     *
     * @return The query object
     * @see #beginGroup()
     */
    public RealmQuery<E> endGroup() {
        this.query.endGroup();
        return this;
    }

    /**
     * Logical-or two conditions
     *
     * @return The query object
     */
    public RealmQuery<E> or() {
        this.query.or();
        return this;
    }

    /**
     * Negate condition.
     *
     * @return The query object
     */
    public RealmQuery<E> not() {
        this.query.not();
        return this;
    }

    // Aggregates

    // Sum

    /**
     * Calculate the sum of a field
     *
     * @param fieldName The field name
     * @return The sum
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public long sumInt(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.sumInt(columnIndex);
    }

    /**
     * Calculate the sum of a field
     *
     * @param fieldName The field name
     * @return The sum
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public double sumDouble(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.sumDouble(columnIndex);
    }

    /**
     * Calculate the sum of a field
     *
     * @param fieldName The field name
     * @return The sum
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public double sumFloat(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.sumFloat(columnIndex);
    }

    // Average

    /**
     * Calculate the average of a field
     *
     * @param fieldName The field name
     * @return The average
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public double averageInt(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.averageInt(columnIndex);
    }

    /**
     * Calculate the average of a field
     *
     * @param fieldName The field name
     * @return The average
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public double averageDouble(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.averageDouble(columnIndex);
    }

    /**
     * Calculate the average of a field
     *
     * @param fieldName The field name
     * @return The average
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public double averageFloat(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.averageFloat(columnIndex);
    }

    // Min

    /**
     * Find the minimum value of a field
     *
     * @param fieldName The field name
     * @return The minimum value
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public long minimumInt(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.minimumInt(columnIndex);
    }

    /**
     * Find the minimum value of a field
     *
     * @param fieldName The field name
     * @return The minimum value
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public double minimumDouble(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.minimumDouble(columnIndex);
    }

    /**
     * Find the minimum value of a field
     *
     * @param fieldName The field name
     * @return The minimum value
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public float minimumFloat(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.minimumFloat(columnIndex);
    }

    /**
     * Find the minimum value of a field
     *
     * @param fieldName The field name
     * @return The minimum value
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public Date minimumDate(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.minimumDate(columnIndex);
    }

    // Max

    /**
     * Find the maximum value of a field
     *
     * @param fieldName The field name
     * @return The maximum value
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public long maximumInt(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.maximumInt(columnIndex);
    }

    /**
     * Find the maximum value of a field
     *
     * @param fieldName The field name
     * @return The maximum value
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public double maximumDouble(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.maximumDouble(columnIndex);
    }

    /**
     * Find the maximum value of a field
     *
     * @param fieldName The field name
     * @return The maximum value
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public float maximumFloat(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.maximumFloat(columnIndex);
    }

    /**
     * Find the maximum value of a field
     *
     * @param fieldName The field name
     * @return The maximum value
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public Date maximumDate(String fieldName) {
        long columnIndex = columns.get(fieldName);
        return this.query.maximumDate(columnIndex);
    }

    /**
     * Count the number of objects that fulfill the query conditions.
     *
     * @return The number of matching objects.
     * @throws java.lang.UnsupportedOperationException The query is not valid ("syntax error")
     */
    public long count() {
        return this.query.count();
    }

    // Execute

    /**
     * Find all objects that fulfill the query conditions.
     *
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.RuntimeException Any other error
     * @see io.realm.RealmResults
     */
    public RealmResults<E> findAll() {
        return new RealmResults<E>(realm, query.findAll(), clazz);
    }

    /**
     * Find all objects that fulfill the query conditions.
     * Results will be posted to the callback instance {@link RealmResults.QueryCallback} asynchronously
     *
     * @param callback to receive the result of this query
     * @return A {@link Request} representing a cancellable, pending asynchronous query
     * @throws java.lang.RuntimeException Any other error
     * @see io.realm.RealmResults
     */

    public Request findAll(final RealmResults.QueryCallback<E> callback) {
        // capture the sorting properties in case we want to retry the query
        this.callbackRealmResults = callback;

        // will use the Looper of the caller thread to post the result
        final Handler handler = new EventHandler(callback);

        // We need a pointer to the caller Realm, to be able to handover the result to it
        final long callerSharedGroupNativePtr = realm.getSharedGroupPointer();

        // Handover the query (to be used by a worker thread)
        handoverQueryPtr = query.handoverQuery(callerSharedGroupNativePtr);
        // save query arguments (for future update)
        argumentsHolder =  new ArgumentsHolder(ArgumentsHolder.TYPE_FIND_ALL_SORTED);

        // We need to use the same configuration to open a background SharedGroup (i.e Realm)
        // to perform the query
        final RealmConfiguration realmConfiguration = realm.getConfiguration();

        Future<?> pendingQuery = asyncQueryExecutor.submit(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                if (!Thread.currentThread().isInterrupted() && (asyncRequest == null || !asyncRequest.isCancelled())) {
                    Realm bgRealm = null;

                    try {
                        //TODO Once SharedGroup is thread safe, start reusing a cached instance
                        //     of a worker Realm to avoid the cost of opening/closing a SharedGroup
                        //     for each query
                        bgRealm = Realm.getInstance(realmConfiguration);

                        // Run the query & handover the table view for the caller thread
//                        handoverTableViewPtr = query.findAllWithHandover(bgRealm.getSharedGroupPointer(), handoverQueryPtr);
                        handoverQueryPtr = -1;

                        if (IS_DEBUG && NB_ADVANCE_READ_SIMULATION-- > 0) {
                            // notify caller thread that we're about to post result to Handler
                            // (this is relevant in Unit Testing, as we can advance read to simulate
                            // a mismatch between the query result, and the current version of the Realm
                            handler.sendMessage(handler.obtainMessage(
                                    EventHandler.MSG_ADVANCE_READ,
                                    EventHandler.FIND_ALL_QUERY, 0));
                        }

                        // send results to the caller thread's callback
                        Bundle bundle = new Bundle(2);
                        bundle.putLong(EventHandler.QUERY_RESULT_POINTER_ARG, handoverTableViewPtr);
                        bundle.putLong(EventHandler.CALLER_SHARED_GROUP_POINTER_ARG, callerSharedGroupNativePtr);

                        Message message = handler.obtainMessage(EventHandler.MSG_SUCCESS);
                        message.arg1 = EventHandler.FIND_ALL_QUERY;
                        message.setData(bundle);
                        handler.sendMessage(message);

                    } catch (BadVersionException e) {
                        handler.sendMessage(handler.obtainMessage(
                                EventHandler.MSG_UNREACHABLE_VERSION,
                                EventHandler.FIND_ALL_QUERY));

                    } catch (Exception e) {
                        handler.sendMessage(handler.obtainMessage(
                                EventHandler.MSG_ERROR,
                                EventHandler.FIND_ALL_QUERY, 0, e));

                    } finally {
                        if (null != bgRealm) {
                            bgRealm.close();
                        }
                    }
                } else {
                    releaseHandoverResources();
                }
            }
        });
        if (null != asyncRequest) {
            // update current reference, since retrying the query will
            // submit a new Runnable, hence the need to update the user
            // with the new Future<?> reference.
            asyncRequest.setPendingQuery(pendingQuery);

        } else { // first run
            asyncRequest = new Request(pendingQuery);
        }
        return asyncRequest;
    }

    // TODO we need a call from call to tell us whether
    //      a TV will be updated by a specific version, so we can
    //      ignore re-running the query & notify the user (if he' going to get the same content)
    private final static Long INVALID_TABLE_VIEW_POINTER = 0L;
    public RealmResults<E> findAllAsync() {
        // TODO: Need to clear all params of this query, as user may use the same instance to
        // call multiple findAllAsync

        final Handler handler = realm.getHandler();//use caller Realm Looper

        // just need to build a query configuration that we need to add to the list
        // of current async queries

        // We need a pointer to the caller Realm, to be able to handover the result to it
        final long callerSharedGroupNativePtr = realm.getSharedGroupPointer();

        // Handover the query (to be used by a worker thread)
        final long handoverQueryPointer = query.handoverQuery(callerSharedGroupNativePtr);
        Log.e("REALM", "naruto java_caller thread >> " + Thread.currentThread().getName() + " handoverQueryPtr " + handoverQueryPtr);

        // save query arguments (for future update)
        argumentsHolder =  new ArgumentsHolder(ArgumentsHolder.TYPE_FIND_ALL);

        // We need to use the same configuration to open a background SharedGroup (i.e Realm)
        // to perform the query
        final RealmConfiguration realmConfiguration = realm.getConfiguration();
//        RealmResults<E> realmResults = RealmResults.newEmpty(realm, query, clazz);
        RealmResults<E> realmResults = new RealmResults<E>(realm, query, clazz);
        final WeakReference<RealmResults<?>> weakRealmResults = new WeakReference<RealmResults<?>>(realmResults);
//        final int hashRealmResults = System.identityHashCode(realmResults);
        Realm.asyncQueries.get().put(weakRealmResults, this);
//        realm.hashToInstance.put(hashRealmResults, new WeakReference<RealmResults<?>>(realmResults));
        final Future<Long> pendingQuery = asyncQueryExecutor.submit(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                //TODO emable thread priority & check for interruption
               // android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
//                if (!Thread.currentThread().isInterrupted() && (asyncRequest == null || !asyncRequest.isCancelled())) {
//                    Realm bgRealm = null;
                    SharedGroup sharedGroup = null;

                    try {
                        sharedGroup = new SharedGroup(realmConfiguration.getPath(),
                                true, realmConfiguration.getDurability(),
                                realmConfiguration.getEncryptionKey());

//                        bgRealm = Realm.getInstance(realmConfiguration);

                        // Run the query & handover the table view for the caller thread
                        // Note: the handoverQueryPtr contain the versionID needed by the SG in order
                        // to import it.
                        // TODO: need to position back the SG to the latest version in JNI so the query
                        // will run against the latest version
                        Log.e("REALM", "naruto java_bg version >> " + Thread.currentThread().getName() + " version " + sharedGroup.getVersion());
                        handoverTableViewPtr = query.findAllWithHandover(sharedGroup.getNativePointer(), sharedGroup.getNativeReplicationPointer() ,handoverQueryPointer);

//                        UpdateAsyncQueriesTask.Result
                        // we need to post 'like the update job' to the handler
                        // using the signal REALM_COMPLETED_ASYNC_QUERY
                        QueryUpdateTask.Result result = QueryUpdateTask.Result.newRealmResults();
                        result.updatedTableViews.put(weakRealmResults, handoverTableViewPtr);
                        result.versionID = sharedGroup.getVersion();
//                        IdentityHashMap<WeakReference<RealmResults<?>>, Long> updatedTV = new IdentityHashMap<WeakReference<RealmResults<?>>, Long>(1);
//                        updatedTV.put(weakRealmResults, handoverTableViewPtr);
//                        UpdateAsyncQueriesTask.Result result =  UpdateAsyncQueriesTask.Result.newRealmResults(updatedTV, );

                        // notify caller thread
                        if (handler.getLooper().getThread().isAlive()) {
                            Message message = handler.obtainMessage(Realm.REALM_COMPLETED_ASYNC_QUERY, result);
                            handler.sendMessage(message);
                        }


                        // need to use CompleteableFuture in order to notify when the RealmResults.notifyChangeListeners
                        // once this finished
                        return handoverTableViewPtr;

                    } catch (BadVersionException e) {// TODO this exception shouldn't be thrown
                        e.printStackTrace();
//                        throw e;
                        // TODO consider calling releaseHandoverResources();
                        return INVALID_TABLE_VIEW_POINTER;

                    } catch (Exception e) {
                        e.printStackTrace();
                        // TODO consider calling releaseHandoverResources();
                        return INVALID_TABLE_VIEW_POINTER;

                    } finally {
//                        if (null != bgRealm) {
//                            bgRealm.close();
//                        }
                        if (null != sharedGroup) {
                            sharedGroup.close();
                        }
                    }
//                } else {
//                    // TODO consider calling releaseHandoverResources();
//                    releaseHandoverResources();
//                    return INVALID_TABLE_VIEW_POINTER;
//                }
            }
        });
//        if (null != asyncRequest) {
//            // update current reference, since retrying the query will
//            // submit a new Runnable, hence the need to update the user
//            // with the new Future<?> reference.
//            asyncRequest.setPendingQuery(pendingQuery);
//
//        } else { // first run
//            asyncRequest = new Request(pendingQuery);
//        }
        //TODO: need to add this instance to the global list of async queries
        realmResults.setPendingQuery(pendingQuery);


        return realmResults;
    }

    public E findFirstAsync () {
        // TODO: Need to clear all params of this query, as user may use the same instance to
        // call multiple findAllAsync

        //TODO check that caller has a looper
        final Handler handler = realm.getHandler();//use caller Looper

        // just need to build a query configuration that we need to add to the list
        // of current async queries

        // We need a pointer to the caller Realm, to be able to handover the result to it
        final long callerSharedGroupNativePtr = realm.getSharedGroupPointer();

        // Handover the query (to be used by a worker thread)
        final long handoverQueryPointer = query.handoverQuery(callerSharedGroupNativePtr);
        Log.e("REALM", "naruto java_caller thread >> " + Thread.currentThread().getName() + " handoverQueryPtr " + handoverQueryPtr);

        // save query arguments (for future update)
        argumentsHolder =  new ArgumentsHolder(ArgumentsHolder.TYPE_FIND_FIRST);

        // We need to use the same configuration to open a background SharedGroup (i.e Realm)
        // to perform the query
        final RealmConfiguration realmConfiguration = realm.getConfiguration();
        final E result = realm.getConfiguration().getSchemaMediator().newInstance(clazz);
        final WeakReference<RealmObject> realmObjectWeakReference = new WeakReference<RealmObject>(result);
        Realm.asyncRealmObjects.get().put(realmObjectWeakReference, this);
        result.realm = realm;
//        final WeakReference<RealmResults<?>> weakRealmResults = new WeakReference<RealmResults<?>>(realmResults);
//        final int hashRealmResults = System.identityHashCode(realmResults);
//        Realm.asyncQueries.get().put(weakRealmResults, this);
//        realm.hashToInstance.put(hashRealmResults, new WeakReference<RealmResults<?>>(realmResults));
        final Future<Long> pendingQuery = asyncQueryExecutor.submit(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                // android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
//                if (!Thread.currentThread().isInterrupted() && (asyncRequest == null || !asyncRequest.isCancelled())) {
//                    Realm bgRealm = null;
                SharedGroup sharedGroup = null;

                try {
                    sharedGroup = new SharedGroup(realmConfiguration.getPath(),
                            true, realmConfiguration.getDurability(),
                            realmConfiguration.getEncryptionKey());

                    handoverRowPtr = query.findWithHandover(sharedGroup.getNativePointer(), handoverQueryPointer);

                    QueryUpdateTask.Result result = QueryUpdateTask.Result.newRealmObject();
                    result.updatedRow.put(realmObjectWeakReference, handoverRowPtr);
                    result.versionID = sharedGroup.getVersion();
//                    IdentityHashMap<WeakReference<RealmObject>, Long> updatedRow = new IdentityHashMap<WeakReference<RealmObject>, Long>(1);
//                    updatedRow.put(realmObjectWeakReference, handoverRowPtr);
//                    UpdateAsyncQueriesTask.Result result =  UpdateAsyncQueriesTask.Result.newRealmObject(updatedRow, );

                    // notify caller thread
                    if (handler.getLooper().getThread().isAlive()) {
                        Message message = handler.obtainMessage(Realm.REALM_COMPLETED_ASYNC_FIND_FIRST, result);
                        handler.sendMessage(message);
                    }


                    // need to use CompleteableFuture in order to notify when the RealmResults.notifyChangeListeners
                    // once this finished
                    return handoverRowPtr;

                } catch (BadVersionException e) {// TODO this exception shouldn't be thrown
                    e.printStackTrace();
//                        throw e;
                    // TODO consider calling releaseHandoverResources();
                    return INVALID_TABLE_VIEW_POINTER;

                } catch (Exception e) {
                    e.printStackTrace();
                    // TODO consider calling releaseHandoverResources();
                    return INVALID_TABLE_VIEW_POINTER;

                } finally {
//                        if (null != bgRealm) {
//                            bgRealm.close();
//                        }
                    if (null != sharedGroup) {
                        sharedGroup.close();
                    }
                }
//                } else {
//                    // TODO consider calling releaseHandoverResources();
//                    releaseHandoverResources();
//                    return INVALID_TABLE_VIEW_POINTER;
//                }
            }
        });
//        if (null != asyncRequest) {
//            // update current reference, since retrying the query will
//            // submit a new Runnable, hence the need to update the user
//            // with the new Future<?> reference.
//            asyncRequest.setPendingQuery(pendingQuery);
//
//        } else { // first run
//            asyncRequest = new Request(pendingQuery);
//        }
        //TODO: need to add this instance to the global list of async queries
        result.setPendingQuery(pendingQuery);
        result.setType(clazz);
        // TODO implemntation is to return an empty realm.createObject, then
        //      swap the pointer (Row when the query finished)
        // E result = configuration.getSchemaMediator().newInstance(clazz);
        // result.row = null; or empty row (Empty implementaion of Row interface where getDouble
        // return the default value for each primitive ) basicallly mock all method returning nativePointer
        // result.realm = this;

        result.row = new Row() {
            @Override
            public long getColumnCount() {
                return 0;
            }

            @Override
            public String getColumnName(long columnIndex) {
                return null;
            }

            @Override
            public long getColumnIndex(String columnName) {
                return 0;
            }

            @Override
            public ColumnType getColumnType(long columnIndex) {
                return null;
            }

            @Override
            public Table getTable() {
                return null;
            }

            @Override
            public long getIndex() {
                return 0;
            }

            @Override
            public long getLong(long columnIndex) {
                return 0;
            }

            @Override
            public boolean getBoolean(long columnIndex) {
                return false;
            }

            @Override
            public float getFloat(long columnIndex) {
                return 0;
            }

            @Override
            public double getDouble(long columnIndex) {
                return 0;
            }

            @Override
            public Date getDate(long columnIndex) {
                return null;
            }

            @Override
            public String getString(long columnIndex) {
                return "";
            }

            @Override
            public byte[] getBinaryByteArray(long columnIndex) {
                return new byte[0];
            }

            @Override
            public Mixed getMixed(long columnIndex) {
                return null;
            }

            @Override
            public ColumnType getMixedType(long columnIndex) {
                return null;
            }

            @Override
            public long getLink(long columnIndex) {
                return 0;
            }

            @Override
            public boolean isNullLink(long columnIndex) {
                return true;
            }

            @Override
            public LinkView getLinkList(long columnIndex) {
                return null;
            }

            @Override
            public void setLong(long columnIndex, long value) {

            }

            @Override
            public void setBoolean(long columnIndex, boolean value) {

            }

            @Override
            public void setFloat(long columnIndex, float value) {

            }

            @Override
            public void setDouble(long columnIndex, double value) {

            }

            @Override
            public void setDate(long columnIndex, Date date) {

            }

            @Override
            public void setString(long columnIndex, String value) {

            }

            @Override
            public void setBinaryByteArray(long columnIndex, byte[] data) {

            }

            @Override
            public void setMixed(long columnIndex, Mixed data) {

            }

            @Override
            public void setLink(long columnIndex, long value) {

            }

            @Override
            public void nullifyLink(long columnIndex) {

            }

            @Override
            public boolean isAttached() {
                return false;
            }

            @Override
            public boolean hasColumn(String fieldName) {
                return false;
            }
        };
        return result;

    }
    /**
     * Find all objects that fulfill the query conditions and sorted by specific field name.
     * <p/>
     * Sorting is currently limited to character sets in 'Latin Basic', 'Latin Supplement', 'Latin Extended A',
     * 'Latin Extended B' (UTF-8 range 0-591). For other character sets, sorting will have no effect.
     *
     * @param fieldName     the field name to sort by.
     * @param sortAscending sort ascending if <code>SORT_ORDER_ASCENDING</code>, sort descending
     *                      if <code>SORT_ORDER_DESCENDING</code>
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if field name does not exist.
     */
    public RealmResults<E> findAllSorted(String fieldName, boolean sortAscending) {
        TableView tableView = query.findAll();
        TableView.Order order = sortAscending ? TableView.Order.ascending : TableView.Order.descending;
        Long columnIndex = columns.get(fieldName);
        if (columnIndex == null || columnIndex < 0) {
            throw new IllegalArgumentException(String.format("Field name '%s' does not exist.", fieldName));
        }
        tableView.sort(columnIndex, order);
        return new RealmResults<E>(realm, tableView, clazz);
    }

    /**
     * Similar to {@link #findAllSorted(String, boolean)} but runs asynchronously from a worker thread
     *
     * @param fieldName     the field name to sort by.
     * @param sortAscending sort ascending if <code>SORT_ORDER_ASCENDING</code>, sort descending
     *                      if <code>SORT_ORDER_DESCENDING</code>
     * @param callback      to receive the result of this query
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if field name does not exist.
     */
    public Request findAllSorted(String fieldName, boolean sortAscending, RealmResults.QueryCallback<E> callback) {
        // capture the sorting properties in case we want to retry the query
        this.callbackRealmResults = callback;
        this.fieldName = fieldName;
        this.sortAscending = sortAscending;

        // will use the Looper of the caller thread to post the result
        final Handler handler = new EventHandler(callback);

        // We need a pointer to the caller Realm, to be able to handover the result to it
        final long callerSharedGroupNativePtr = realm.getSharedGroupPointer();

        // Handover the query (to be used by a worker thread)
        handoverQueryPtr = query.handoverQuery(callerSharedGroupNativePtr);

        // We need to use the same configuration to open a background SharedGroup (i.e Realm)
        // to perform the query
        final RealmConfiguration realmConfiguration = realm.getConfiguration();

        final TableView.Order order = sortAscending ? TableView.Order.ascending : TableView.Order.descending;
        final Long columnIndex = columns.get(fieldName);
        if (columnIndex == null || columnIndex < 0) {
            throw new IllegalArgumentException(String.format("Field name '%s' does not exist.", fieldName));
        }

        Future<?> pendingQuery = asyncQueryExecutor.submit(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                if (!Thread.currentThread().isInterrupted() && (asyncRequest == null || !asyncRequest.isCancelled())) {
                    Realm bgRealm = null;

                    try {
                        //TODO Once SharedGroup is thread safe, start reusing a cached instance
                        //     of a worker Realm to avoid the cost of opening/closing a SharedGroup
                        //     for each query
                        bgRealm = Realm.getInstance(realmConfiguration);

                        // Run the query & handover the table view for the caller thread
                        handoverTableViewPtr = query.findAllSortedWithHandover(bgRealm.getSharedGroupPointer(), bgRealm.sharedGroup.getNativeReplicationPointer(), handoverQueryPtr, columnIndex, (order == TableView.Order.ascending));
                        // save query arguments (for future update)
                        argumentsHolder =  new ArgumentsHolder(ArgumentsHolder.TYPE_FIND_ALL_SORTED);
                        argumentsHolder.columnIndex = columnIndex;
                        argumentsHolder.ascending = (order == TableView.Order.ascending);


                        handoverQueryPtr = -1;
                        if (IS_DEBUG && NB_ADVANCE_READ_SIMULATION-- > 0) {
                            // notify caller thread that we're about to post result to Handler
                            // (this is relevant in Unit Testing, as we can advance read to simulate
                            // a mismatch between the query result, and the current version of the Realm
                            handler.sendMessage(handler.obtainMessage(
                                    EventHandler.MSG_ADVANCE_READ,
                                    EventHandler.FIND_ALL_SORTED_QUERY, 0));

                        }

                        // send results to the caller thread's callback
                        Bundle bundle = new Bundle(2);
                        bundle.putLong(EventHandler.QUERY_RESULT_POINTER_ARG, handoverTableViewPtr);
                        bundle.putLong(EventHandler.CALLER_SHARED_GROUP_POINTER_ARG, callerSharedGroupNativePtr);

                        Message message = handler.obtainMessage(EventHandler.MSG_SUCCESS);
                        message.arg1 = EventHandler.FIND_ALL_SORTED_QUERY;
                        message.setData(bundle);
                        handler.sendMessage(message);

                    } catch (BadVersionException e) {
                        handler.sendMessage(handler.obtainMessage(
                                EventHandler.MSG_UNREACHABLE_VERSION,
                                EventHandler.FIND_ALL_SORTED_QUERY, 0));

                    } catch (Exception e) {
                        handler.sendMessage(handler.obtainMessage(
                                EventHandler.MSG_ERROR,
                                EventHandler.FIND_ALL_SORTED_QUERY, 0, e));

                    } finally {
                        if (null != bgRealm) {
                            bgRealm.close();
                        }
                    }
                } else {
                    releaseHandoverResources();
                }
            }
        });
        if (null != asyncRequest) {
            // update current reference, since retrying the query will
            // submit a new Runnable, hence the need to update the user
            // with the new Future<?> reference.
            asyncRequest.setPendingQuery(pendingQuery);

        } else { //First run
            asyncRequest = new Request(pendingQuery);
        }
        return asyncRequest;
    }

    public RealmResults<E> findAllSortedAsync(String fieldName, boolean sortAscending) {
        final TableView.Order order = sortAscending ? TableView.Order.ascending : TableView.Order.descending;
        final Long columnIndex = columns.get(fieldName);
        if (columnIndex == null || columnIndex < 0) {
            throw new IllegalArgumentException(String.format("Field name '%s' does not exist.", fieldName));
        }

        // capture the query arguments for future retries & update
        argumentsHolder =  new ArgumentsHolder(ArgumentsHolder.TYPE_FIND_ALL_SORTED);
        argumentsHolder.ascending = sortAscending;
        argumentsHolder.columnIndex = columnIndex;

        // will use the Looper of the caller Realm to post the result
        final Handler handler = realm.getHandler();

        // Handover the query (to be used by a worker thread)
        handoverQueryPtr = query.handoverQuery(realm.getSharedGroupPointer());

        // We need to use the same configuration to open a background SharedGroup to perform the query
        final RealmConfiguration realmConfiguration = realm.getConfiguration();

        RealmResults<E> realmResults = new RealmResults<E>(realm, query, clazz);
        final WeakReference<RealmResults<?>> weakRealmResults = new WeakReference<RealmResults<?>>(realmResults);
        Realm.asyncQueries.get().put(weakRealmResults, this);

        final Future<Long> pendingQuery = asyncQueryExecutor.submit(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                if (!Thread.currentThread().isInterrupted() && (asyncRequest == null || !asyncRequest.isCancelled())) {
                    SharedGroup sharedGroup = null;

                    try {
                        sharedGroup = new SharedGroup(realmConfiguration.getPath(),
                                true, realmConfiguration.getDurability(),
                                realmConfiguration.getEncryptionKey());

                        // Run the query & handover the table view for the caller thread
                        handoverTableViewPtr = query.findAllSortedWithHandover(sharedGroup.getNativePointer(), sharedGroup.getNativeReplicationPointer(), handoverQueryPtr, columnIndex, (order == TableView.Order.ascending));

                        QueryUpdateTask.Result result = QueryUpdateTask.Result.newRealmResults();
                        result.updatedTableViews.put(weakRealmResults, handoverTableViewPtr);
                        result.versionID = sharedGroup.getVersion();

                        if (handler.getLooper().getThread().isAlive()) {
                            Message message = handler.obtainMessage(Realm.REALM_COMPLETED_ASYNC_QUERY, result);
                            handler.sendMessage(message);
                        }

                        return handoverTableViewPtr;

                    } catch (BadVersionException e) {
                        e.printStackTrace();
                        return INVALID_TABLE_VIEW_POINTER;

                    } catch (Exception e) {
                        e.printStackTrace();
                        return INVALID_TABLE_VIEW_POINTER;

                    } finally {
                        if (sharedGroup != null) {
                            sharedGroup.close();
                        }
                    }
                } else {
                    //TODO is there any releaseHandoverResources() to release?

                }
                return INVALID_TABLE_VIEW_POINTER;
            }
        });
        realmResults.setPendingQuery(pendingQuery);
        return realmResults;
    }


    /**
     * Find all objects that fulfill the query conditions and sorted by specific field name in
     * ascending order.
     * <p/>
     * Sorting is currently limited to character sets in 'Latin Basic', 'Latin Supplement', 'Latin Extended A',
     * 'Latin Extended B' (UTF-8 range 0-591). For other character sets, sorting will have no effect.
     *
     * @param fieldName the field name to sort by.
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if field name does not exist.
     */
    public RealmResults<E> findAllSorted(String fieldName) {
        return findAllSorted(fieldName, true);
    }

    /**
     * Similar to {@link #findAllSorted(String)} but runs asynchronously from a worker thread
     * ascending order.
     * <p/>
     * Sorting is currently limited to character sets in 'Latin Basic', 'Latin Supplement', 'Latin Extended A',
     * 'Latin Extended B' (UTF-8 range 0-591). For other character sets, sorting will have no effect.
     *
     * @param fieldName the field name to sort by.
     * @param callback  to receive the result of this query
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if field name does not exist.
     */
    public Request findAllSorted(String fieldName, RealmResults.QueryCallback<E> callback) {
        return findAllSorted(fieldName, true, callback);
    }

    /**
     * Find all objects that fulfill the query conditions and sorted by specific field names.
     * <p/>
     * Sorting is currently limited to character sets in 'Latin Basic', 'Latin Supplement', 'Latin Extended A',
     * 'Latin Extended B' (UTF-8 range 0-591). For other character sets, sorting will have no effect.
     *
     * @param fieldNames    an array of field names to sort by.
     * @param sortAscending sort ascending if <code>SORT_ORDER_ASCENDING</code>, sort descending
     *                      if <code>SORT_ORDER_DESCENDING</code>.
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if a field name does not exist.
     */
    public RealmResults<E> findAllSorted(String fieldNames[], boolean sortAscending[]) {
        if (fieldNames == null) {
            throw new IllegalArgumentException("fieldNames cannot be 'null'.");
        } else if (sortAscending == null) {
            throw new IllegalArgumentException("sortAscending cannot be 'null'.");
        } else if (fieldNames.length == 0) {
            throw new IllegalArgumentException("At least one field name must be specified.");
        } else if (fieldNames.length != sortAscending.length) {
            throw new IllegalArgumentException(String.format("Number of field names (%d) and sort orders (%d) does not match.", fieldNames.length, sortAscending.length));
        }

        if (fieldNames.length == 1 && sortAscending.length == 1) {
            return findAllSorted(fieldNames[0], sortAscending[0]);
        } else {
            TableView tableView = query.findAll();
            List<Long> columnIndices = new ArrayList<Long>();
            List<TableView.Order> orders = new ArrayList<TableView.Order>();
            for (int i = 0; i < fieldNames.length; i++) {
                String fieldName = fieldNames[i];
                Long columnIndex = columns.get(fieldName);
                if (columnIndex == null || columnIndex < 0) {
                    throw new IllegalArgumentException(String.format("Field name '%s' does not exist.", fieldName));
                }
                columnIndices.add(columnIndex);
            }
            for (int i = 0; i < sortAscending.length; i++) {
                orders.add(sortAscending[i] ? TableView.Order.ascending : TableView.Order.descending);
            }
            tableView.sort(columnIndices, orders);
            return new RealmResults<E>(realm, tableView, clazz);
        }
    }


    public Request findAllSorted(String fieldNames[], final boolean sortAscendings[], RealmResults.QueryCallback<E> callback) {
        // capture the sorting properties in case we want to retry the query
        this.fieldNames = fieldNames;
        this.sortAscendings = sortAscendings;

        if (fieldNames == null) {
            throw new IllegalArgumentException("fieldNames cannot be 'null'.");
        } else if (sortAscendings == null) {
            throw new IllegalArgumentException("sortAscending cannot be 'null'.");
        } else if (fieldNames.length == 0) {
            throw new IllegalArgumentException("At least one field name must be specified.");
        } else if (fieldNames.length != sortAscendings.length) {
            throw new IllegalArgumentException(String.format("Number of field names (%d) and sort orders (%d) does not match.", fieldNames.length, sortAscendings.length));
        }

        if (fieldNames.length == 1 && sortAscendings.length == 1) {
            return findAllSorted(fieldNames[0], sortAscendings[0], callback);
        } else {

            // will use the Looper of the caller thread to post the result
            final Handler handler = new EventHandler(callback);

            // We need a pointer to the caller Realm, to be able to handover the result to it
            final long callerSharedGroupNativePtr = realm.getSharedGroupPointer();

            // Handover the query (to be used by a worker thread)
            handoverQueryPtr = query.handoverQuery(callerSharedGroupNativePtr);

            // We need to use the same configuration to open a background SharedGroup (i.e Realm)
            // to perform the query
            final RealmConfiguration realmConfiguration = realm.getConfiguration();

            final long indices[] = new long[fieldNames.length];

            for (int i = 0; i < fieldNames.length; i++) {
                String fieldName = fieldNames[i];
                Long columnIndex = columns.get(fieldName);
                if (columnIndex == null || columnIndex < 0) {
                    throw new IllegalArgumentException(String.format("Field name '%s' does not exist.", fieldName));
                }
                indices[i] = columnIndex;
            }

            Future<?> pendingQuery = asyncQueryExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    if (!Thread.currentThread().isInterrupted() && (asyncRequest == null || !asyncRequest.isCancelled())) {
                        Realm bgRealm = null;

                        try {
                            //TODO Once SharedGroup is thread safe, start reusing a cached instance
                            //     of a worker Realm to avoid the cost of opening/closing a SharedGroup
                            //     for each query
                            bgRealm = Realm.getInstance(realmConfiguration);

                            // Run the query & handover the table view for the caller thread
                            handoverTableViewPtr = query.findAllMultiSortedWithHandover(bgRealm.getSharedGroupPointer(), bgRealm.sharedGroup.getNativeReplicationPointer(), handoverQueryPtr, indices, sortAscendings);
                            handoverQueryPtr = -1;
                            if (IS_DEBUG && NB_ADVANCE_READ_SIMULATION-- > 0) {
                                // notify caller thread that we're about to post result to Handler
                                // (this is relevant in Unit Testing, as we can advance read to simulate
                                // a mismatch between the query result, and the current version of the Realm
                                handler.sendMessage(handler.obtainMessage(
                                        EventHandler.MSG_ADVANCE_READ,
                                        EventHandler.FIND_ALL_SORTED_MULTI_QUERY, 0));
                            }

                            // send results to the caller thread's callback
                            Bundle bundle = new Bundle(2);
                            bundle.putLong(EventHandler.QUERY_RESULT_POINTER_ARG, handoverTableViewPtr);
                            bundle.putLong(EventHandler.CALLER_SHARED_GROUP_POINTER_ARG, callerSharedGroupNativePtr);

                            Message message = handler.obtainMessage(EventHandler.MSG_SUCCESS);
                            message.arg1 = EventHandler.FIND_ALL_SORTED_MULTI_QUERY;
                            message.setData(bundle);
                            handler.sendMessage(message);

                        } catch (BadVersionException e) {
                            handler.sendMessage(handler.obtainMessage(
                                    EventHandler.MSG_UNREACHABLE_VERSION,
                                    EventHandler.FIND_ALL_SORTED_MULTI_QUERY, 0));

                        } catch (Exception e) {
                            handler.sendMessage(handler.obtainMessage(
                                    EventHandler.MSG_ERROR,
                                    EventHandler.FIND_ALL_SORTED_MULTI_QUERY, 0, e));

                        } finally {
                            if (null != bgRealm) {
                                bgRealm.close();
                            }
                        }
                    } else {
                        releaseHandoverResources();
                    }
                }
            });
            if (null != asyncRequest) {
                // update current reference, since retrying the query will
                // submit a new Runnable, hence the need to update the user
                // with the new Future<?> reference.
                asyncRequest.setPendingQuery(pendingQuery);

            } else { //First run
                asyncRequest = new Request(pendingQuery);
            }
            return asyncRequest;
        }
    }


    public RealmResults<E> findAllSortedAsync(String fieldNames[], final boolean [] sortAscendings) {
        if (fieldNames == null) {
            throw new IllegalArgumentException("fieldNames cannot be 'null'.");
        } else if (sortAscendings == null) {
            throw new IllegalArgumentException("sortAscending cannot be 'null'.");
        } else if (fieldNames.length == 0) {
            throw new IllegalArgumentException("At least one field name must be specified.");
        } else if (fieldNames.length != sortAscendings.length) {
            throw new IllegalArgumentException(String.format("Number of field names (%d) and sort orders (%d) does not match.", fieldNames.length, sortAscendings.length));
        }

        if (fieldNames.length == 1 && sortAscendings.length == 1) {
            return findAllSortedAsync(fieldNames[0], sortAscendings[0]);

        } else {

            // will use the Looper of the caller Realm to post the result
            final Handler handler = realm.getHandler();

            // Handover the query (to be used by a worker thread)
            handoverQueryPtr = query.handoverQuery(realm.getSharedGroupPointer());

            // We need to use the same configuration to open a background SharedGroup to perform the query
            final RealmConfiguration realmConfiguration = realm.getConfiguration();

            final long indices[] = new long[fieldNames.length];
            for (int i = 0; i < fieldNames.length; i++) {
                String fieldName = fieldNames[i];
                Long columnIndex = columns.get(fieldName);
                if (columnIndex == null || columnIndex < 0) {
                    throw new IllegalArgumentException(String.format("Field name '%s' does not exist.", fieldName));
                }
                indices[i] = columnIndex;
            }

            // capture the query arguments for future retries & update
            argumentsHolder =  new ArgumentsHolder(ArgumentsHolder.TYPE_FIND_ALL_MULTI_SORTED);
            argumentsHolder.ascendings = sortAscendings;
            argumentsHolder.columnIndices = indices;

            // prepare the promise result
            RealmResults<E> realmResults = new RealmResults<E>(realm, query, clazz);
            final WeakReference<RealmResults<?>> weakRealmResults = new WeakReference<RealmResults<?>>(realmResults);
            Realm.asyncQueries.get().put(weakRealmResults, this);

            final Future<Long> pendingQuery = asyncQueryExecutor.submit(new Callable<Long>() {
                @Override
                public Long call() throws Exception {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    if (!Thread.currentThread().isInterrupted() && (asyncRequest == null || !asyncRequest.isCancelled())) {
                        SharedGroup sharedGroup = null;

                        try {
                            sharedGroup = new SharedGroup(realmConfiguration.getPath(),
                                    true, realmConfiguration.getDurability(),
                                    realmConfiguration.getEncryptionKey());

                            // Run the query & handover the table view for the caller thread
                            handoverTableViewPtr = query.findAllMultiSortedWithHandover(sharedGroup.getNativePointer(), sharedGroup.getNativeReplicationPointer(), handoverQueryPtr, indices, sortAscendings);

                            QueryUpdateTask.Result result = QueryUpdateTask.Result.newRealmResults();
                            result.updatedTableViews.put(weakRealmResults, handoverTableViewPtr);
                            result.versionID = sharedGroup.getVersion();

                            if (handler.getLooper().getThread().isAlive()) {
                                Message message = handler.obtainMessage(Realm.REALM_COMPLETED_ASYNC_QUERY, result);
                                handler.sendMessage(message);
                            }

                            return handoverTableViewPtr;

                        } catch (BadVersionException e) {
                            e.printStackTrace();
                            return INVALID_TABLE_VIEW_POINTER;

                        } catch (Exception e) {
                            e.printStackTrace();
                            return INVALID_TABLE_VIEW_POINTER;

                        } finally {
                            if (sharedGroup != null) {
                                sharedGroup.close();
                            }
                        }
                    } else {
                        //TODO is there any releaseHandoverResources() to release?

                    }
                    return INVALID_TABLE_VIEW_POINTER;
                }
            });

            realmResults.setPendingQuery(pendingQuery);
            return realmResults;
        }
    }

    /**
     * Find all objects that fulfill the query conditions and sorted by specific field names in
     * ascending order.
     * <p/>
     * Sorting is currently limited to character sets in 'Latin Basic', 'Latin Supplement', 'Latin Extended A',
     * 'Latin Extended B' (UTF-8 range 0-591). For other character sets, sorting will have no effect.
     *
     * @param fieldName1     first field name
     * @param sortAscending1 sort order for first field
     * @param fieldName2     second field name
     * @param sortAscending2 sort order for second field
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if a field name does not exist.
     */
    public RealmResults<E> findAllSorted(String fieldName1, boolean sortAscending1,
                                         String fieldName2, boolean sortAscending2) {
        return findAllSorted(new String[]{fieldName1, fieldName2}, new boolean[]{sortAscending1, sortAscending2});
    }

    /**
     * Similar to {@link #findAllSorted(String, boolean, String, boolean)} but runs asynchronously from a worker thread
     *
     * @param fieldName1     first field name
     * @param sortAscending1 sort order for first field
     * @param fieldName2     second field name
     * @param sortAscending2 sort order for second field
     * @param callback       to receive the result of this query
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if a field name does not exist.
     */
    public Request findAllSorted(String fieldName1, boolean sortAscending1,
                                 String fieldName2, boolean sortAscending2,
                                 RealmResults.QueryCallback<E> callback) {
        return findAllSorted(new String[]{fieldName1, fieldName2},
                new boolean[]{sortAscending1, sortAscending2},
                callback);
    }

    /**
     * Find all objects that fulfill the query conditions and sorted by specific field names in
     * ascending order.
     * <p/>
     * Sorting is currently limited to character sets in 'Latin Basic', 'Latin Supplement', 'Latin Extended A',
     * 'Latin Extended B' (UTF-8 range 0-591). For other character sets, sorting will have no effect.
     *
     * @param fieldName1     first field name
     * @param sortAscending1 sort order for first field
     * @param fieldName2     second field name
     * @param sortAscending2 sort order for second field
     * @param fieldName3     third field name
     * @param sortAscending3 sort order for third field
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if a field name does not exist.
     */
    public RealmResults<E> findAllSorted(String fieldName1, boolean sortAscending1,
                                         String fieldName2, boolean sortAscending2,
                                         String fieldName3, boolean sortAscending3) {
        return findAllSorted(new String[]{fieldName1, fieldName2, fieldName3},
                new boolean[]{sortAscending1, sortAscending2, sortAscending3});
    }

    /**
     * Similar to {@link #findAllSorted(String, boolean, String, boolean, String, boolean)} but runs asynchronously from a worker thread
     *
     * @param fieldName1     first field name
     * @param sortAscending1 sort order for first field
     * @param fieldName2     second field name
     * @param sortAscending2 sort order for second field
     * @param fieldName3     third field name
     * @param sortAscending3 sort order for third field
     * @param callback       to receive the result of this query
     * @return A {@link io.realm.RealmResults} containing objects. If no objects match the condition,
     * a list with zero objects is returned.
     * @throws java.lang.IllegalArgumentException if a field name does not exist.
     */
    public Request findAllSorted(String fieldName1, boolean sortAscending1,
                                 String fieldName2, boolean sortAscending2,
                                 String fieldName3, boolean sortAscending3,
                                 RealmResults.QueryCallback<E> callback) {
        return findAllSorted(new String[]{fieldName1, fieldName2, fieldName3},
                new boolean[]{sortAscending1, sortAscending2, sortAscending3}, callback);
    }

    /**
     * Find the first object that fulfills the query conditions.
     *
     * @return The object found or null if no object matches the query conditions.
     * @throws java.lang.RuntimeException Any other error.
     * @see io.realm.RealmObject
     */
    public E findFirst() {
        long rowIndex = this.query.find();
        if (rowIndex >= 0) {
            return realm.getByIndex(clazz, (view != null) ? view.getTargetRowIndex(rowIndex) : rowIndex);
        } else {
            return null;
        }
    }

    /**
     * Similar to {@link #findFirst()} but runs asynchronously from a worker thread
     *
     * @param callback to receive the result of this query
     * @return A {@link Request} representing a cancellable, pending asynchronous query
     */
    public Request findFirst(RealmObject.QueryCallback<E> callback) {
        // will use the Looper of the caller thread to post the result
        final Handler handler = new EventHandler(callback);

        // We need a pointer to the caller Realm, to be able to handover the result to it
        final long callerSharedGroupNativePtr = realm.getSharedGroupPointer();

        // Handover the query (to be used by a worker thread)
        handoverQueryPtr = query.handoverQuery(callerSharedGroupNativePtr);

        // We need to use the same configuration to open a background SharedGroup (i.e Realm)
        // to perform the query
        final RealmConfiguration realmConfiguration = realm.getConfiguration();

        Future<?> pendingQuery = asyncQueryExecutor.submit(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                if (!Thread.currentThread().isInterrupted() && (asyncRequest == null || !asyncRequest.isCancelled())) {
                    Realm bgRealm = null;

                    try {
                        //TODO Once SharedGroup is thread safe, start reusing a cached instance
                        //     of a worker Realm to avoid the cost of opening/closing a SharedGroup
                        //     for each query
                        bgRealm = Realm.getInstance(realmConfiguration);

                        // Run the query & handover the table view for the caller thread
                        handoverRowPtr = query.findWithHandover(bgRealm.getSharedGroupPointer(), handoverQueryPtr);
                        handoverQueryPtr = -1;
                        if (IS_DEBUG && NB_ADVANCE_READ_SIMULATION-- > 0) {
                            // notify caller thread that we're about to post result to Handler
                            // (this is relevant in Unit Testing, as we can advance read to simulate
                            // a mismatch between the query result, and the current version of the Realm
                            handler.sendMessage(handler.obtainMessage(
                                    EventHandler.MSG_ADVANCE_READ,
                                    EventHandler.FIND_FIRST_QUERY, 0));
                        }

                        // send results to the caller thread's callback
                        Bundle bundle = new Bundle(2);
                        bundle.putLong(EventHandler.QUERY_RESULT_POINTER_ARG, handoverRowPtr);
                        bundle.putLong(EventHandler.CALLER_SHARED_GROUP_POINTER_ARG, callerSharedGroupNativePtr);

                        Message message = handler.obtainMessage(EventHandler.MSG_SUCCESS);
                        message.arg1 = EventHandler.FIND_FIRST_QUERY;
                        message.setData(bundle);
                        handler.sendMessage(message);

                    } catch (BadVersionException e) {
                        handler.sendMessage(handler.obtainMessage(
                                EventHandler.MSG_UNREACHABLE_VERSION,
                                EventHandler.FIND_FIRST_QUERY, 0));

                    } catch (Exception e) {
                        handler.sendMessage(handler.obtainMessage(
                                EventHandler.MSG_ERROR,
                                EventHandler.FIND_FIRST_QUERY, 0, e));

                    } finally {
                        if (null != bgRealm) {
                            bgRealm.close();
                        }
                    }
                } else {
                    releaseHandoverResources();
                }
            }
        });
        if (null != asyncRequest) {
            // update current reference, since retrying the query will
            // submit a new Runnable, hence the need to update the user
            // with the new Future<?> reference.
            asyncRequest.setPendingQuery(pendingQuery);

        } else { //First run
            asyncRequest = new Request(pendingQuery);
        }
        return asyncRequest;
    }

    /**
     * Represents a pending asynchronous Realm query.
     * <p/>
     * Users are responsible of maintaining a reference to {@code Request} in order
     * to call #cancel in case of a configuration change for example (to avoid memory leak, as the
     * query will post the result to the caller's thread callback)
     */
    public static class Request {
        private Future<?> pendingQuery;
        private volatile boolean isCancelled = false;

        public Request(Future<?> pendingQuery) {
            this.pendingQuery = pendingQuery;
        }

        /**
         * Attempts to cancel execution of this query (if it hasn't already completed or previously cancelled)
         */
        public void cancel() {
            pendingQuery.cancel(true);
            isCancelled = true;
            Realm.asyncQueryExecutor.getQueue().remove(pendingQuery);
            // Cancelled tasks are never executed, but may
            // accumulate in work queues, which may causes a memory leak
            // if the task hold references (to an enclosing class for example)
            //TODO call explicitly purge from the ThreadPoolExecutor
            /*
            One caveat applies: if a second thread attempts to add something to the pool (suing the execute() method) at the same time the first thread is attempting to purge the queue the attempt to purge the queue fails and the canceled objects remain in the queue.
A better way to cancel objects with thread pools os to use the remove() method of the thread pool, which immediatelt removes the task
from the thread pool queue. The remove() method can be used with runnable objects

             */
//            Realm.asyncQueryExecutor.purge(); remove

        }

        private void setPendingQuery(Future<?> pendingQuery) {
            this.pendingQuery = pendingQuery;
        }

        /**
         * Whether an attempt to cancel the query was performed
         *
         * @return {@code true} if {@link #cancel()} has already been called, {@code false} otherwise
         */
        public boolean isCancelled() {
            return isCancelled;
        }
    }

    /**
     * Custom {@link android.os.Handler} using the caller {@link android.os.Looper}
     * to deliver result or error to a {@link io.realm.RealmResults.QueryCallback} or
     * a {@link io.realm.RealmObject.QueryCallback}
     */
    // this handler is private & lives within the scope of the retained RealmQuery instance only
    @SuppressLint("HandlerLeak")
    private class EventHandler extends Handler {
        private final static int FIND_FIRST_QUERY = 1;
        private final static int FIND_ALL_QUERY = 2;
        private final static int FIND_ALL_SORTED_QUERY = 3;
        private final static int FIND_ALL_SORTED_MULTI_QUERY = 4;

        private final static String QUERY_RESULT_POINTER_ARG = "queryResultPtr";
        private final static String CALLER_SHARED_GROUP_POINTER_ARG = "callerSgPtr";

        private static final int MSG_SUCCESS = 1;
        private static final int MSG_ERROR = 2;
        // Used when begin_read fails to position the background Realm at a specific version
        // most likely the caller thread has advanced_read or the provided version of Realm
        // is no longer available
        private static final int MSG_UNREACHABLE_VERSION = 3;
        // This is only used for testing scenarios, when we want to simulate a change in the
        // caller Realm before delivering the result. Thus, this will trigger 'Handover failed due to version mismatch'
        // that helps testing the retry process
        private static final int MSG_ADVANCE_READ = 4;

        private RealmResults.QueryCallback<E> callbackRealmResults;
        private RealmObject.QueryCallback<E> callbackRealmObject;

        EventHandler(RealmResults.QueryCallback<E> callbackRealmResults) {
            super();
            this.callbackRealmResults = callbackRealmResults;
            this.callbackRealmObject = null;
        }

        EventHandler(RealmObject.QueryCallback<E> callbackRealmObject) {
            super();
            this.callbackRealmObject = callbackRealmObject;
            this.callbackRealmResults = null;
        }


        @Override
        public void handleMessage(Message msg) {
            if (!asyncRequest.isCancelled()) {
                switch (msg.what) {
                    case MSG_SUCCESS:
                        handleSuccess(msg);
                        break;
                    case MSG_ERROR:
                        handleError(msg);
                        break;
                    case MSG_UNREACHABLE_VERSION: {
                        handleUnreachableVersion(msg);
                        break;
                    }
                    case MSG_ADVANCE_READ: {
                        handleAdvanceRead(msg);
                        break;
                    }
                }

            } else {
                // in case we didn't have the chance to import the different handovers(because the query was cancelled/crash)
                // we need to manually free those resources to avoid memory leak
                releaseHandoverResources();
            }
        }

        private void handleSuccess(final Message message) {
            Bundle bundle = message.getData();
            try {
                switch (message.arg1) {
                    case FIND_FIRST_QUERY: {
                        E realmObject = realm.getByPointer(clazz,
                                query.importHandoverRow(bundle.getLong(QUERY_RESULT_POINTER_ARG),
                                        bundle.getLong(CALLER_SHARED_GROUP_POINTER_ARG)));

                        callbackRealmObject.onSuccess(realmObject);
                        callbackRealmObject = null;
                        break;
                    }
                    case FIND_ALL_QUERY: {
                        RealmResults<E> resultList = new RealmResults<E>(realm,
                                query.importHandoverTableView(bundle.getLong(QUERY_RESULT_POINTER_ARG),
                                        bundle.getLong(CALLER_SHARED_GROUP_POINTER_ARG)),
                                clazz);
                        // instead of returning a new instance of RealmResults we swap pointers
                        // we the empty one returned before
//                        resultList.swapTableViewPointer();
                        callbackRealmResults.onSuccess(resultList);
//                        Realm.asyncQueries.get().put(resultList, RealmQuery.this);
                        // need also to add the handoverQuery or the original one (depends on the GC)
                        // use the associated Handler passed thru the constructor in order to add
                        // this couple to the correct thread
//                        if (!Realm.asyncQueries.containsKey(/*handler*/ null)) {
//                            Realm.asyncQueries.put(/*handler*/ null, new HashMap<WeakReference<RealmResults<?>>, Long>());
//                        }
//                        Realm.asyncQueries.get(/*handler*/null).put(new WeakReference<RealmResults<?>>(resultList), /*query ptr or query object as WeakRef*/ 0L);
//                        Realm.asyncQueriesCallback.put(new WeakReference<RealmResults<?>>(resultList), new WeakReference<RealmResults.QueryCallback<?>>(callbackRealmResults));
//
//                        Realm.asyncRealmResults.get().add(new WeakReference<RealmResults<?>>(resultList));
                        callbackRealmResults = null;
                        break;
                    }
                    case FIND_ALL_SORTED_QUERY: {
                        RealmResults<E> resultList = new RealmResults<E>(realm,
                                query.importHandoverTableView(bundle.getLong(QUERY_RESULT_POINTER_ARG),
                                        bundle.getLong(CALLER_SHARED_GROUP_POINTER_ARG)),
                                clazz);

                        callbackRealmResults.onSuccess(resultList);
//                        Realm.asyncRealmResults.get().add(new WeakReference<RealmResults<?>>(resultList));
                        callbackRealmResults = null;
                        break;
                    }
                    case FIND_ALL_SORTED_MULTI_QUERY: {
                        RealmResults<E> resultList = new RealmResults<E>(realm,
                                query.importHandoverTableView(bundle.getLong(QUERY_RESULT_POINTER_ARG),
                                        bundle.getLong(CALLER_SHARED_GROUP_POINTER_ARG)),
                                clazz);

                        callbackRealmResults.onSuccess(resultList);
//                        Realm.asyncRealmResults.get().add(new WeakReference<RealmResults<?>>(resultList));
                        callbackRealmResults = null;
                        break;
                    }
                }

            } catch (BadVersionException e) {
                handleUnreachableVersion(message);

            } catch (Exception e) {
                handoverQueryPtr = -1;
                handoverTableViewPtr = -1;
                handoverRowPtr = -1;
                message.obj = e;
                handleError(message);
            }
        }

        private void handleError(Message message) {
            releaseHandoverResources();

            switch (message.arg1) {
                case FIND_FIRST_QUERY:
                    callbackRealmObject.onError((Exception) message.obj);
                    callbackRealmObject = null;
                    break;
                case FIND_ALL_QUERY:
                case FIND_ALL_SORTED_QUERY:
                case FIND_ALL_SORTED_MULTI_QUERY:
                    callbackRealmResults.onError((Exception) message.obj);
                    callbackRealmResults = null;
                    break;
            }
        }

        private void handleUnreachableVersion(Message message) {
            if (retryPolicy.shouldRetry()) {
                switch (message.arg1) {
                    case FIND_FIRST_QUERY:
                        findFirst(callbackRealmObject);
                        break;
                    case FIND_ALL_QUERY:
                        findAll(callbackRealmResults);
                        break;
                    case FIND_ALL_SORTED_QUERY:
                        findAllSorted(fieldName, sortAscending, callbackRealmResults);
                        break;
                    case FIND_ALL_SORTED_MULTI_QUERY:
                        findAllSorted(fieldNames, sortAscendings, callbackRealmResults);
                        break;
                }
            } else {
                // pointers were already freed/consumed by 'import_from_handover'
                // calling delete again will crash core
                handoverQueryPtr = -1;
                handoverTableViewPtr = -1;
                handoverRowPtr = -1;
                message.obj = new RealmException("Query failed due to concurrent modification of the Realm");
                handleError(message);
            }
        }

        private void handleAdvanceRead(Message message) {
            switch (message.arg1) {
                case FIND_FIRST_QUERY:
                    ((RealmObject.DebugRealmObjectQueryCallback<E>) callbackRealmObject).onBackgroundQueryCompleted(realm);
                    break;
                case FIND_ALL_QUERY:
                case FIND_ALL_SORTED_QUERY:
                case FIND_ALL_SORTED_MULTI_QUERY:
                    ((RealmResults.DebugRealmResultsQueryCallback<E>) callbackRealmResults).onBackgroundQueryCompleted(realm);
                    break;
            }
        }
    }

    protected ArgumentsHolder argumentsHolder;
    private RealmResults.QueryCallback<E> callbackRealmResults;
    private RealmObject.QueryCallback<E> callbackRealmObject;

    // encapsulate argument (in case we want to re-query)
    // act like a bundle (in Android) that help recreate a state
    // in our UC all the information needed to rerun the query
    public static class ArgumentsHolder {
        public final static int TYPE_FIND_ALL = 0;
        public final static int TYPE_FIND_ALL_SORTED = 1;
        public final static int TYPE_FIND_ALL_MULTI_SORTED = 2;
        public final static int TYPE_FIND_FIRST = 3;

        public final int type;
//        long start;
//        long end;
//        long limit;
        public long columnIndex;
        public boolean ascending;
        public long[] columnIndices;
        public boolean[] ascendings;

        ArgumentsHolder(int type) {
            this.type = type;
        }
    }

    public ArgumentsHolder getArgument () {
        return argumentsHolder;
    }

    private void releaseHandoverResources() {
        //TODO enable again
//        query.closeRowHandover(handoverRowPtr);
//        handoverRowPtr = -1;
//        query.closeQueryHandover(handoverQueryPtr);
//        handoverQueryPtr = -1;
//        query.closeTableViewHandover(handoverTableViewPtr);
//        handoverTableViewPtr = -1;
    }


//    protected long updateQuery (long handoverQueryPtr) {
//        // call the appropriate native code operation
//        return -1;
//    }


    //TODO replace previous call with this method
    protected long handoverQueryPointer (long sharedGroupNativePointer) {
        return query.handoverQuery(sharedGroupNativePointer);
    }

    protected void notifyUpdateCallback () {
        if (callbackRealmObject != null) {
//            callbackRealmObject.onSuccess();//TODO onUpdate
        }

        if (callbackRealmResults != null) {
//            callbackRealmObject.onSuccess();//TODO onUpdate
        }
//        callback.onUpdate();
    }

    protected long exportTableQuery () { // for handover
        return -1;
    }

    // Unit Test Helper
    private static boolean IS_DEBUG = false;
    private static int NB_ADVANCE_READ_SIMULATION = 0;
}
