package me.whereareiam.strata.adapter.database.common;

import lombok.RequiredArgsConstructor;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import me.whereareiam.strata.model.AppliedMigration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.OptionalInt;

/**
 * The table in which a database remembers the migrations applied to it, one row per stream and
 * version. The highest row is the version a stream has reached; names and times are kept for people.
 */
@RequiredArgsConstructor
final class DatabaseHistory {
	private static final String TABLE = "strata_history";
	private static final String CREATE = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
			+ "stream VARCHAR(100) NOT NULL, "
			+ "version INT NOT NULL, "
			+ "name VARCHAR(100) NOT NULL, "
			+ "applied_at BIGINT NOT NULL, "
			+ "PRIMARY KEY (stream, version))";
	private static final String SELECT = "SELECT MAX(version) FROM " + TABLE + " WHERE stream = ?";
	private static final String INSERT = "INSERT INTO " + TABLE
			+ " (stream, version, name, applied_at) VALUES (?, ?, ?, ?)";

	private final DatabaseContext context;
	private boolean tableKnown;

	OptionalInt version(String stream) throws SQLException {
		if (!tableKnown && !context.tableExists(TABLE)) return OptionalInt.empty();

		try (PreparedStatement statement = context.getConnection().prepareStatement(SELECT)) {
			statement.setString(1, stream);
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) return OptionalInt.empty();

				int version = rows.getInt(1);
				return rows.wasNull() ? OptionalInt.empty() : OptionalInt.of(version);
			}
		}
	}

	/**
	 * Creates the table outside any migration's transaction: on databases that commit on every DDL
	 * statement, creating it later would commit a migration's data changes ahead of its entry.
	 */
	void createTable() throws SQLException {
		if (tableKnown) return;

		context.execute(CREATE);
		tableKnown = true;
	}

	void record(String stream, AppliedMigration entry) throws SQLException {
		context.update(INSERT, stream, entry.getVersion(), entry.getName(), entry.getAppliedAt().toEpochMilli());
	}
}
