package com.mapr.springframework.data.maprdb.core;

import com.fasterxml.jackson.databind.RuntimeJsonMappingException;
import com.mapr.db.MapRDB;
import com.mapr.springframework.data.maprdb.core.mapping.Document;
import com.mapr.springframework.data.maprdb.core.mapping.MapRId;
import com.mapr.springframework.data.maprdb.core.mapping.MapRJsonConverter;
import org.ojai.DocumentStream;
import org.ojai.store.Connection;
import org.ojai.store.DocumentStore;
import org.ojai.store.DriverManager;
import org.ojai.store.Query;
import org.ojai.store.QueryCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.data.annotation.Id;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class MapRTemplate implements MapROperations {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapRTemplate.class);

    private final String databaseName;
    private org.ojai.store.Connection ojaiConnection;
    private java.sql.Connection drillConnection;
    private MapRJsonConverter converter;
    private final PersistenceExceptionTranslator exceptionTranslator;

    public MapRTemplate(final String databaseName, final String host, final String username, final String password) {
        this(databaseName, getNewOjaiConnection(), getNewDrillConnection(host, username, password));
    }

    protected MapRTemplate(final String databaseName, org.ojai.store.Connection ojaiConnection,
                           java.sql.Connection drillConnection) {
        converter = new MapRJsonConverter();
        this.exceptionTranslator = new MapRExceptionTranslator();
        this.databaseName = databaseName;
        this.ojaiConnection = ojaiConnection;
        this.drillConnection = drillConnection;
    }

    private static org.ojai.store.Connection getNewOjaiConnection() {
        return DriverManager.getConnection("ojai:mapr:");
    }

    private static java.sql.Connection getNewDrillConnection(String host, String username, String password) {
        try {
            Class.forName("org.apache.drill.jdbc.Driver");
            return java.sql.DriverManager.getConnection(String.format("jdbc:drill:drillbit=%s", host), username, password);
        } catch (SQLException | ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    public Connection getConnection() {
        return ojaiConnection;
    }

    @Override
    public <T> void createTable(Class<T> entityClass) {
        createTable(getTablePath(entityClass));
    }

    @Override
    public void createTable(final String tableName) {
        MapRDB.createTable(getPath(tableName));
    }

    @Override
    public <T> void dropTable(Class<T> entityClass) {
        dropTable(getTablePath(entityClass));
    }

    @Override
    public void dropTable(final String tableName) {
        MapRDB.deleteTable(getPath(tableName));
    }

    @Override
    public <T> boolean tableExists(Class<T> entityClass) {
        return tableExists(getTablePath(entityClass));
    }

    @Override
    public boolean tableExists(final String tableName) {
        return MapRDB.tableExists(getPath(tableName));
    }

    @Override
    public <T> DocumentStore getStore(Class<T> entityClass) {
        return getStore(getTablePath(entityClass));
    }

    @Override
    public DocumentStore getStore(String storeName) {
        return ojaiConnection.getStore(getPath(storeName));
    }

    @Override
    public <T> Optional<T> findById(Object id, Class<T> entityClass) {
        return findById(id, entityClass, getTablePath(entityClass));
    }

    @Override
    public <T> Optional<T> findById(Object id, Class<T> entityClass, final String tableName) {
        org.ojai.Document document = getStore(entityClass).findById(id.toString());
        return Optional.ofNullable(document != null ? converter.toObject(document.asMap(), entityClass) : null);
    }

    @Override
    public <T> List<T> findAll(Class<T> entityClass) {
        return findAll(entityClass, getTablePath(entityClass));
    }

    @Override
    public <T> List<T> findAll(Class<T> entityClass, final String tableName) {
        return execute(ojaiConnection.newQuery().build(), entityClass, tableName);
    }

    @Override
    public <T> T insert(T objectToSave) {
        return insert(objectToSave, getTablePath(objectToSave.getClass()));
    }

    @Override
    public <T> T insert(T objectToSave, final String tableName) {
        Class idClass = getIdType(objectToSave.getClass());
        DocumentStore store = getStore(tableName);

        T object = insert(objectToSave, idClass, store);

        store.flush();
        store.close();

        return object;
    }

    private <T> T insert(T objectToSave, Class idClass, DocumentStore store) {
        org.ojai.Document document = getDocumentWithId(objectToSave, idClass);
        store.insert(document);
        return (T) converter.toObject(document.asMap(), objectToSave.getClass());
    }

    @Override
    public <T> List<T> insert(Iterable<T> objectsToSave) {
        Iterator<T> itr = objectsToSave.iterator();
        if (itr.hasNext()) {
            Class type = itr.next().getClass();
            DocumentStore store = getStore(getTablePath(type));
            Class idClass = getIdType(type);

            List<T> list = StreamSupport.stream(objectsToSave.spliterator(), false).map(o -> insert(o, idClass, store))
                    .collect(Collectors.toList());

            store.flush();
            store.close();

            return list;
        } else
            return Collections.emptyList();
    }

    @Override
    public <T> T save(T objectToSave) {
        return save(objectToSave, getTablePath(objectToSave.getClass()));
    }

    @Override
    public <T> T save(T objectToSave, final String tableName) {
        Class idClass = getIdType(objectToSave.getClass());
        DocumentStore store = getStore(tableName);

        T object = save(objectToSave, idClass, store);

        store.flush();
        store.close();

        return object;
    }

    private <T> T save(T objectToSave, Class idClass, DocumentStore store) {
        org.ojai.Document document = getDocumentWithId(objectToSave, idClass);

        store.insertOrReplace(document);

        return (T) converter.toObject(document.asMap(), objectToSave.getClass());
    }

    @Override
    public <T> List<T> save(Iterable<T> objectsToSave) {
        Iterator<T> itr = objectsToSave.iterator();
        if (itr.hasNext()) {
            Class type = itr.next().getClass();
            DocumentStore store = getStore(getTablePath(type));
            Class idClass = getIdType(type);

            List<T> list = StreamSupport.stream(objectsToSave.spliterator(), false).map(o -> save(o, idClass, store))
                    .collect(Collectors.toList());

            store.flush();
            store.close();

            return list;
        } else
            return Collections.emptyList();
    }

    @Override
    public void remove(Object object) {
        remove(object, getTablePath(object.getClass()));
    }

    @Override
    public void remove(Object object, final String tableName) {
        DocumentStore store = getStore(tableName);
        store.delete(ojaiConnection.newDocument(converter.toJson(object)));
        store.flush();
        store.close();
    }

    @Override
    public <T> void removeById(Object id, Class<T> entityClass) {
        removeById(id, entityClass, getTablePath(entityClass));
    }

    @Override
    public <T> void removeById(Object id, Class<T> entityClass, final String tableName) {
        DocumentStore store = getStore(tableName);
        store.delete(id.toString());
        store.flush();
        store.close();
    }

    @Override
    public <T> void remove(Iterable<T> objectsToDelete) {
        Iterator<T> itr = objectsToDelete.iterator();
        if (itr.hasNext()) {
            Class type = itr.next().getClass();
            DocumentStore store = getStore(getTablePath(type));
            StreamSupport.stream(objectsToDelete.spliterator(), false)
                    .forEach(o -> store.delete(ojaiConnection.newDocument(converter.toJson(o))));
            store.flush();
            store.close();
        }
    }

    @Override
    public <T> void removeAll(Class<T> entityClass) {
        DocumentStore store = getStore(entityClass);

        DocumentStream dc = store.find(ojaiConnection.newQuery().build());
        store.delete(dc);

        store.flush();
        store.close();
    }

    @Override
    public <T> long count(Class<T> entityClass) {
        String query = String.format("SELECT COUNT(*) FROM dfs.`%s`", getPath(getTablePath(entityClass)));
        try (Statement statement = drillConnection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            resultSet.next();
            return Long.parseLong(resultSet.getString(1));
        } catch (SQLException e) {
            throw potentiallyConvertRuntimeException(new RuntimeException(e), exceptionTranslator);
        }
    }

    @Override
    public <T> List<T> execute(QueryCondition queryCondition, Class<T> entityClass) {
        return execute(ojaiConnection.newQuery().where(queryCondition).build(), entityClass);
    }

    @Override
    public <T> List<T> execute(Query query, Class<T> entityClass) {
        return execute(query, entityClass, getTablePath(entityClass));
    }

    private <T> List<T> execute(Query query, Class<T> entityClass, String tableName) {
        DocumentStore store = getStore(tableName);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Executing query: {} in table: {}", query, tableName);
        }

        List<T> list = convertDocumentStreamToIterable(store.find(query), entityClass);

        store.flush();
        store.close();

        return list;
    }

    private <T> List<T> convertDocumentStreamToIterable(DocumentStream documentStream, Class<T> entityClass) {
        List<T> resultCollection = new LinkedList<>();

        documentStream.forEach(d -> resultCollection.add(converter.toObject(d.asMap(), entityClass)));

        documentStream.close();

        return resultCollection;
    }

    private String getPath(String className) {
        if (databaseName.startsWith("/"))
            return String.format("%s%s", databaseName, className);
        else
            return String.format("/%s%s", databaseName, className);
    }

    private <T> String getTablePath(Class<T> entityClass) {
        String className = entityClass.getAnnotation(Document.class).value();

        if (className.isEmpty())
            className = entityClass.getSimpleName().toLowerCase();

        if (className.startsWith("/"))
            return className;
        else
            return String.format("/%s", className);
    }

    private <T> org.ojai.Document getDocumentWithId(T object, Class idClass) {
        org.ojai.Document document = ojaiConnection.newDocument(converter.toJson(object));

        if (document.getId() == null) {
            if (idClass == String.class)
                document.setId(UUID.randomUUID().toString().replace("-", ""));
            else
                throw new RuntimeJsonMappingException("Id auto generation is provided only for String type, " +
                        idClass.toString() + "is not supported yet");
        }
        return document;
    }

    private Class getIdType(Class entityClass) {
        Optional<Field> optional = Arrays.stream(entityClass.getDeclaredFields())
                .filter(f -> f.getAnnotation(Id.class) != null || f.getAnnotation(MapRId.class) != null).findFirst();

        if (optional.isPresent())
            return optional.get().getType();
        else
            throw new RuntimeJsonMappingException("Id was not found in class " + entityClass.toString());
    }

    /**
     * Tries to convert the given {@link RuntimeException} into a {@link DataAccessException} but returns the original
     * exception if the conversation failed. Thus allows safe re-throwing of the return value.
     *
     * @param ex                  the exception to translate
     * @param exceptionTranslator the {@link PersistenceExceptionTranslator} to be used for translation
     * @return
     */
    private static RuntimeException potentiallyConvertRuntimeException(RuntimeException ex,
                                                                       PersistenceExceptionTranslator exceptionTranslator) {
        RuntimeException resolved = exceptionTranslator.translateExceptionIfPossible(ex);
        return resolved == null ? ex : resolved;
    }
}
