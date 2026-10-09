package me.whereareiam.strata.adapter.database.common.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;

/**
 * A session-level advisory lock, which is scoped to the current database and survives the
 * transactions of the migrations.
 */
final class PostgresLock implements DatabaseLock {
	/** "STRATA" in ASCII. */
	private static final long KEY = 0x5354_5241_5441L;
	private static final long RETRY_MILLIS = 250;

	private final Connection connection;

	PostgresLock(Connection connection, Duration timeout) throws SQLException, InterruptedException {
		this.connection = connection;

		long deadline = System.nanoTime() + timeout.toNanos();
		while (!tryAcquire()) {
			if (System.nanoTime() >= deadline)
				throw new SQLTimeoutException("Another instance is still migrating this database after " + timeout.toSeconds() + "s");

			Thread.sleep(RETRY_MILLIS);
		}
	}

	@Override
	public void close() throws SQLException {
		call("SELECT pg_advisory_unlock(?)");
	}

	private boolean tryAcquire() throws SQLException {
		return call("SELECT pg_try_advisory_lock(?)");
	}

	private boolean call(String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, KEY);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}
}
