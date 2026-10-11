package me.whereareiam.strata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Reads what a database holds for the assertions of the database tests.
 */
final class HistoryTable {
	private HistoryTable() {
	}

	/** Lists the history rows of a stream as {@code "<version> <name>"}, in version order. */
	static List<String> entries(DataSource dataSource, String stream) throws SQLException {
		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement("SELECT version, name FROM strata_history WHERE stream = ? ORDER BY version")) {
			statement.setString(1, stream);

			List<String> entries = new ArrayList<>();
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) entries.add(rows.getInt(1) + " " + rows.getString(2));
			}

			return entries;
		}
	}

	static int count(DataSource dataSource, String table) throws SQLException {
		try (Connection connection = dataSource.getConnection();
		     Statement statement = connection.createStatement();
		     ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rows.next();

			return rows.getInt(1);
		}
	}

	static void execute(DataSource dataSource, String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
