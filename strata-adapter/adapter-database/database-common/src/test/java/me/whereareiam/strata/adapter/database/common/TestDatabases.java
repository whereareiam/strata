package me.whereareiam.strata.adapter.database.common;

import org.h2.jdbcx.JdbcDataSource;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Embedded databases for tests, and the queries the assertions share.
 */
public final class TestDatabases {
	private TestDatabases() {
	}

	public static DataSource create(String dialect, Path directory) {
		return switch (dialect) {
			case "h2" -> h2();
			case "sqlite" -> sqlite(directory);
			default -> throw new IllegalArgumentException(dialect);
		};
	}

	public static DataSource h2() {
		JdbcDataSource dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

		return dataSource;
	}

	public static DataSource sqlite(Path directory) {
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + ".db"));

		return dataSource;
	}

	public static int count(DataSource dataSource, String table) throws SQLException {
		try (Connection connection = dataSource.getConnection();
		     Statement statement = connection.createStatement();
		     ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rows.next();

			return rows.getInt(1);
		}
	}

	public static void execute(DataSource dataSource, String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
