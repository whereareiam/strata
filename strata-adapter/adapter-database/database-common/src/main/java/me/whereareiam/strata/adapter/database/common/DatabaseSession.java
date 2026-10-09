package me.whereareiam.strata.adapter.database.common;

import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.adapter.database.DatabaseConnection;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import me.whereareiam.strata.adapter.database.common.lock.DatabaseLock;
import me.whereareiam.strata.model.AppliedMigration;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * One connection holding the database's migration lock. Every migration runs in a transaction of
 * its own that also writes its history row.
 */
public final class DatabaseSession implements MigrationSession<DatabaseContext> {
	private final DatabaseConnection connection;
	private final DatabaseContext context;
	private final DatabaseHistory history;
	private final DatabaseLock lock;

	/**
	 * Takes the migration lock on a connection. The session owns the connection from here on, also
	 * when this constructor fails.
	 *
	 * @param connection  connection in auto-commit mode
	 * @param lockTimeout how long to wait for another instance that is migrating the database
	 * @throws Exception when the lock cannot be taken in time
	 */
	public DatabaseSession(@NotNull DatabaseConnection connection, @NotNull Duration lockTimeout) throws Exception {
		this.connection = connection;
		this.context = new ConnectionContext(connection.connection());
		this.history = new DatabaseHistory(context);
		this.lock = acquireLock(lockTimeout);
	}

	@Override
	public @NotNull DatabaseContext context() {
		return context;
	}

	@Override
	public int version(@NotNull String stream) throws SQLException {
		return history.version(stream);
	}

	@Override
	public void apply(
			@NotNull String stream,
			@NotNull MigrationAction<? super DatabaseContext> action,
			@NotNull AppliedMigration entry
	) throws Exception {
		history.createTable();

		Connection jdbc = context.getConnection();
		jdbc.setAutoCommit(false);
		try {
			action.apply(context);
			history.record(stream, entry);
			jdbc.commit();
		} catch (Exception | Error failure) {
			abort(jdbc, failure);
			throw failure;
		}

		jdbc.setAutoCommit(true);
	}

	@Override
	public void close() throws Exception {
		try (DatabaseConnection owned = connection; DatabaseLock held = lock) {
			// Releases the lock, then the connection, keeping the first failure.
		}
	}

	private DatabaseLock acquireLock(Duration timeout) throws Exception {
		try {
			return DatabaseLock.acquire(context.getConnection(), context.getDialect(), timeout);
		} catch (Exception failure) {
			try (DatabaseConnection owned = connection) {
				throw failure;
			}
		}
	}

	private void abort(Connection jdbc, Throwable failure) {
		try {
			jdbc.rollback();
			jdbc.setAutoCommit(true);
		} catch (SQLException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}
}
