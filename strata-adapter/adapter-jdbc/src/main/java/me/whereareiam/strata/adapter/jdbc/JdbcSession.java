package me.whereareiam.strata.adapter.jdbc;

import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.adapter.jdbc.lock.DatabaseLock;
import me.whereareiam.strata.model.AppliedMigration;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * One connection holding the database's migration lock. Every migration runs in a transaction of
 * its own that also writes its history row.
 * <p>
 * It is public so that an adapter for a library built on JDBC can hand its own context type to
 * migrations while reusing the locking, the transactions and the history.
 *
 * @param <C> context handed to migrations
 */
public final class JdbcSession<C extends JdbcContext> implements MigrationSession<C> {
	private final C context;
	private final AutoCloseable connectionOwner;
	private final JdbcHistory history;
	private final DatabaseLock lock;

	/**
	 * Takes the migration lock on the context's connection. The session owns the connection from
	 * here on, also when this constructor fails.
	 *
	 * @param context         context wrapping a connection in auto-commit mode
	 * @param lockTimeout     how long to wait for another instance that is migrating the database
	 * @param connectionOwner closes the connection, or whatever handed it out
	 * @throws Exception when the lock cannot be taken in time
	 */
	public JdbcSession(
			@NotNull C context,
			@NotNull Duration lockTimeout,
			@NotNull AutoCloseable connectionOwner
	) throws Exception {
		this.context = context;
		this.connectionOwner = connectionOwner;
		this.history = new JdbcHistory(context);
		this.lock = acquireLock(lockTimeout);
	}

	@Override
	public @NotNull C context() {
		return context;
	}

	@Override
	public int version(@NotNull String stream) throws SQLException {
		return history.version(stream);
	}

	@Override
	public void apply(
			@NotNull String stream,
			@NotNull MigrationAction<? super C> action,
			@NotNull AppliedMigration entry
	) throws Exception {
		history.createTable();

		Connection connection = context.getConnection();
		connection.setAutoCommit(false);
		try {
			action.apply(context);
			history.record(stream, entry);
			connection.commit();
		} catch (Exception | Error failure) {
			abort(connection, failure);
			throw failure;
		}

		connection.setAutoCommit(true);
	}

	@Override
	public void close() throws Exception {
		try (AutoCloseable connection = connectionOwner; DatabaseLock held = lock) {
			// Releases the lock, then the connection, keeping the first failure.
		}
	}

	private DatabaseLock acquireLock(Duration timeout) throws Exception {
		try {
			return DatabaseLock.acquire(context.getConnection(), context.getDialect(), timeout);
		} catch (Exception failure) {
			try (AutoCloseable connection = connectionOwner) {
				throw failure;
			}
		}
	}

	private void abort(Connection connection, Throwable failure) {
		try {
			connection.rollback();
			connection.setAutoCommit(true);
		} catch (SQLException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}
}
