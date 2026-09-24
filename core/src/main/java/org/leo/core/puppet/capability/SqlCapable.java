package org.leo.core.puppet.capability;

import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.puppet.database.SqlCommand;

import java.util.Map;

/**
 * Capability marker for nodes that can execute SQL through their native
 * database provider (JDBC for Java, PDO for PHP).
 */
public interface SqlCapable {

    default Map<String, Object> executeSql(DatabaseConnectionSpec connection,
                                           String sqlScript) throws Exception {
        return executeSql(connection, SqlCommand.raw(sqlScript));
    }

    /** Executes SQL with parameter values kept separate from the statement. */
    Map<String, Object> executeSql(DatabaseConnectionSpec connection,
                                   SqlCommand command) throws Exception;

    /** Inspects the provider before a complete connection is available. */
    Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) throws Exception;
}
