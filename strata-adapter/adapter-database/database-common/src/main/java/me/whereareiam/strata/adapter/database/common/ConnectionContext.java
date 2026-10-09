package me.whereareiam.strata.adapter.database.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.whereareiam.strata.adapter.database.DatabaseContext;
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
 * A {@link DatabaseContext} over one JDBC connection.
 */
@RequiredArgsConstructor
public final class ConnectionContext implements DatabaseContext {
	@Getter
	private final @NotNull Connection connection;

	private @Nullable String dialect;

	@Override
	public @NotNull String getDialect() throws SQLException {
		if (dialect == null) dialect = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);

		return dialect;
	}

	@Override
	public void execute(@NotNull String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	@Override
	public int update(@NotNull String sql, Object... parameters) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++)
				statement.setObject(index + 1, parameters[index]);

			return statement.executeUpdate();
		}
	}

	@Override
	public boolean tableExists(@NotNull String table) throws SQLException {
		return storedTableName(table) != null;
	}

	@Override
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
