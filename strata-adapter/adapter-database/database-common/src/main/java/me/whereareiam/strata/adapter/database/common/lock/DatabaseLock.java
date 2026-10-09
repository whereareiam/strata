package me.whereareiam.strata.adapter.database.common.lock;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Keeps other application instances from migrating the same database at the same time. The lock
 * belongs to the connection it was taken on and ends with it at the latest.
 */
public interface DatabaseLock extends AutoCloseable {
	/**
	 * Takes the lock the database offers. Server databases shared by several instances are locked;
	 * embedded ones, which a single process owns, need none.
	 *
	 * @param connection connection that will run the migrations
	 * @param dialect    database product name in lower case
	 * @param timeout    how long to wait for another holder
	 * @return held lock
	 * @throws SQLException         when the lock stays taken or cannot be requested
	 * @throws InterruptedException when interrupted while waiting
	 */
	static @NotNull DatabaseLock acquire(
			@NotNull Connection connection,
			@NotNull String dialect,
			@NotNull Duration timeout
	) throws SQLException, InterruptedException {
		return switch (dialect) {
			case "postgresql" -> new PostgresLock(connection, timeout);
			case "mysql", "mariadb" -> new MysqlLock(connection, timeout);
			default -> () -> {};
		};
	}

	@Override
	void close() throws SQLException;
}
