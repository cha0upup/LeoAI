package org.leo.web.testing;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

/** Current schema and baseline settings; each test owns its database and sessions. */
public final class SqliteTestDatabase {
    private SqliteTestDatabase() {}

    public static SQLiteDataSource create(Path file) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + file);
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(source);
        return source;
    }

    public static Configuration myBatisConfiguration(DataSource source) {
        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        return configuration;
    }
}
