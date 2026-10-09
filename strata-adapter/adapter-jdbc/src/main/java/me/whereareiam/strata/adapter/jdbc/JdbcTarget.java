package me.whereareiam.strata.adapter.jdbc;

import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationTarget;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.time.Duration;
import javax.sql.DataSource;

/**
 * A database reached through plain JDBC. The history lives in its {@code strata_history} table, so
 * every application instance sharing the database sees the same versions.
 * <p>
 * A migration is one transaction. PostgreSQL and SQLite take schema changes back with it; MySQL,
 * MariaDB and H2 commit on every DDL statement, so there a migration that changes the schema should
 * be written to survive being run again after a failure.
 */
public final class JdbcTarget implements MigrationTarget<JdbcContext> {
	private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(60);

	private final DataSource dataSource;
	private final Duration lockTimeout;

	/**
	 * Uses a database, waiting up to a minute for another instance that is migrating it.
	 *
	 * @param dataSource where connections come from; one is used per upgraded stream
	 */
	public JdbcTarget(@NotNull DataSource dataSource) {
		this(dataSource, DEFAULT_LOCK_TIMEOUT);
	}

	/**
	 * Uses a database.
	 *
	 * @param dataSource  where connections come from; one is used per upgraded stream
	 * @param lockTimeout how long to wait for another instance that is migrating the database
	 */
	public JdbcTarget(@NotNull DataSource dataSource, @NotNull Duration lockTimeout) {
		this.dataSource = dataSource;
		this.lockTimeout = lockTimeout;
	}

	@Override
	public @NotNull MigrationSession<JdbcContext> open() throws Exception {
		Connection connection = dataSource.getConnection();

		return new JdbcSession<>(new JdbcContext(connection), lockTimeout, connection::close);
	}
}
