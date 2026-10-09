package me.whereareiam.strata.adapter.jdbc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * What a database migration works with: the connection its transaction runs on, and the questions
 * migrations commonly ask about the database.
 */
@RequiredArgsConstructor
public class JdbcContext {
	/**
	 * The connection of the running migration. It is borrowed: do not commit, roll back, close it or
	 * change its auto-commit mode.
	 */
	@Getter
	private final @NotNull Connection connection;

	private @Nullable String dialect;

	/**
	 * Names the database product, to branch where the SQL differs.
	 *
	 * @return product name in lower case: {@code postgresql}, {@code mysql}, {@code mariadb},
	 * {@code h2}, {@code sqlite}, or whatever another driver reports
	 * @throws SQLException when the database cannot be asked
	 */
	public @NotNull String getDialect() throws SQLException {
		if (dialect == null) dialect = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);

		return dialect;
	}

	/**
	 * Runs one statement as written, without parameters.
	 *
	 * @param sql a single statement
	 * @throws SQLException when the database rejects it
	 */
	public void execute(@NotNull String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	/**
	 * Runs one data-changing statement with positional parameters.
	 *
	 * @param sql        a single statement with a {@code ?} per parameter
	 * @param parameters values in the order of their placeholders
	 * @return number of rows changed
	 * @throws SQLException when the database rejects it
	 */
	public int update(@NotNull String sql, Object... parameters) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++)
				statement.setObject(index + 1, parameters[index]);

			return statement.executeUpdate();
		}
	}

	/**
	 * Looks for a table or view in the current schema, ignoring case.
	 *
	 * @param table table name
	 * @return whether it exists
	 * @throws SQLException when the database cannot be inspected
	 */
	public boolean tableExists(@NotNull String table) throws SQLException {
		return storedTableName(table) != null;
	}

	/**
	 * Looks for a column of a table in the current schema, ignoring case.
	 *
	 * @param table  table name
	 * @param column column name
	 * @return whether the table exists and has the column
	 * @throws SQLException when the database cannot be inspected
	 */
	public boolean columnExists(@NotNull String table, @NotNull String column) throws SQLException {
		String stored = storedTableName(table);
		if (stored == null) return false;

		DatabaseMetaData metadata = connection.getMetaData();
		String pattern = escape(stored, metadata.getSearchStringEscape());
		try (ResultSet columns = metadata.getColumns(connection.getCatalog(), connection.getSchema(), pattern, null)) {
			while (columns.next())
				if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
		}

		return false;
	}

	private @Nullable String storedTableName(String table) throws SQLException {
		DatabaseMetaData metadata = connection.getMetaData();
		try (ResultSet tables = metadata.getTables(connection.getCatalog(), connection.getSchema(), null, null)) {
			while (tables.next()) {
				String stored = tables.getString("TABLE_NAME");
				if (table.equalsIgnoreCase(stored)) return stored;
			}
		}

		return null;
	}

	private static String escape(String name, @Nullable String escape) {
		if (escape == null || escape.isEmpty()) return name;

		return name.replace(escape, escape + escape)
				.replace("_", escape + "_")
				.replace("%", escape + "%");
	}
}
