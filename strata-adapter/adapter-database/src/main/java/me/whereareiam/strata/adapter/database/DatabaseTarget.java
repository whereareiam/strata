package me.whereareiam.strata.adapter.database;

import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationTarget;
import me.whereareiam.strata.adapter.database.common.DataSourceConnector;
import me.whereareiam.strata.adapter.database.common.DatabaseSession;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import javax.sql.DataSource;

/**
 * A database. The history lives in its {@code strata_history} table, so every application instance
 * sharing the database sees the same versions.
 * <p>
 * A migration is one transaction. PostgreSQL and SQLite take schema changes back with it; MySQL,
 * MariaDB and H2 commit on every DDL statement, so there a migration that changes the schema should
 * be written to survive being run again after a failure.
 */
public final class DatabaseTarget implements MigrationTarget<DatabaseContext> {
	private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(60);

	private final DatabaseConnector connector;
	private final Duration lockTimeout;

	/**
	 * Uses a database through plain JDBC, waiting up to a minute for another instance that is
	 * migrating it.
	 *
	 * @param dataSource where connections come from; one is used per upgraded stream
	 */
	public DatabaseTarget(@NotNull DataSource dataSource) {
		this(new DataSourceConnector(dataSource), DEFAULT_LOCK_TIMEOUT);
	}

	/**
	 * Uses a database through the library the application reaches it with, waiting up to a minute
	 * for another instance that is migrating it.
	 *
	 * @param connector opens the connection of each upgraded stream
	 */
	public DatabaseTarget(@NotNull DatabaseConnector connector) {
		this(connector, DEFAULT_LOCK_TIMEOUT);
	}

	/**
	 * Uses a database.
	 *
	 * @param connector   opens the connection of each upgraded stream
	 * @param lockTimeout how long to wait for another instance that is migrating the database
	 */
	public DatabaseTarget(@NotNull DatabaseConnector connector, @NotNull Duration lockTimeout) {
		this.connector = connector;
		this.lockTimeout = lockTimeout;
	}

	@Override
	public @NotNull MigrationSession<DatabaseContext> open() throws Exception {
		return new DatabaseSession(connector.open(), lockTimeout);
	}
}
