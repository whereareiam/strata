package me.whereareiam.strata.adapter.database.common;

import lombok.RequiredArgsConstructor;
import me.whereareiam.strata.adapter.database.DatabaseConnection;
import me.whereareiam.strata.adapter.database.DatabaseConnector;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Takes connections straight from a {@link DataSource}, for applications that use plain JDBC.
 */
@RequiredArgsConstructor
public final class DataSourceConnector implements DatabaseConnector {
	private final @NotNull DataSource dataSource;

	@Override
	public @NotNull DatabaseConnection open() throws SQLException {
		Connection connection = dataSource.getConnection();

		return new DatabaseConnection() {
			@Override
			public @NotNull Connection connection() {
				return connection;
			}

			@Override
			public void close() throws SQLException {
				connection.close();
			}
		};
	}
}
