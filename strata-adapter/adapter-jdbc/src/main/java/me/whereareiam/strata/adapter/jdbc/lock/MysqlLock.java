package me.whereareiam.strata.adapter.jdbc.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;

/**
 * A named lock of MySQL and MariaDB. Such names are shared by the whole server, so the name carries
 * the database.
 */
final class MysqlLock implements DatabaseLock {
	private static final String PREFIX = "strata:";
	private static final int MAX_NAME_LENGTH = 64;

	private final Connection connection;
	private final String name;

	MysqlLock(Connection connection, Duration timeout) throws SQLException {
		this.connection = connection;
		this.name = name(connection.getCatalog());

		try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
			statement.setString(1, name);
			statement.setLong(2, timeout.toSeconds());
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || result.getInt(1) != 1)
					throw new SQLTimeoutException("Another instance is still migrating this database after " + timeout.toSeconds() + "s");
			}
		}
	}

	@Override
	public void close() throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			statement.setString(1, name);
			statement.executeQuery().close();
		}
	}

	private static String name(String database) {
		String name = PREFIX + database;

		return name.length() <= MAX_NAME_LENGTH ? name : PREFIX + Integer.toHexString(database.hashCode());
	}
}
