package com.mapr.springframework.data.maprdb.core;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Simple {@link PersistenceExceptionTranslator} for MapR. Convert the given runtime exception to an appropriate
 * exception from the {@code org.springframework.dao} hierarchy. Return {@literal null} if no translation is
 * appropriate: any other exception may have resulted from user code, and should not be translated.
 *
 * @author Grzegorz Solecki
 */
public class MapRExceptionTranslator implements PersistenceExceptionTranslator {

    private static final Set<String> RESOURCE_USAGE_EXCEPTIONS = new HashSet<>(
            Collections.singletonList("SQLException"));

    /*
     * (non-Javadoc)
     * @see org.springframework.dao.support.PersistenceExceptionTranslator#translateExceptionIfPossible(java.lang.RuntimeException)
     */
    @Nullable
    public DataAccessException translateExceptionIfPossible(RuntimeException ex) {

        String exception = ClassUtils.getShortName(ClassUtils.getUserClass(ex.getClass()));

        if (RESOURCE_USAGE_EXCEPTIONS.contains(exception)) {
            return new InvalidDataAccessResourceUsageException(ex.getMessage(), ex);
        }

        // If we get here, we have an exception that resulted from user code,
        // rather than the persistence provider, so we return null to indicate
        // that translation should not occur.
        return null;
    }
}
