package me.whereareiam.strata.integration.dialectica;

import me.whereareiam.strata.*;
import me.whereareiam.strata.integration.jdbc.JdbcContext;
import org.jetbrains.annotations.NotNull;
import java.sql.*;
import java.util.*;

/** Explicit adoption of old Dialectica history. Existing rows are retained as evidence, never deleted. */
public final class LegacyDialectica {
	/** Builds a detector that checks exact old scope history and then validates actual schema/data.
	 * Old history has no checksums; names and versions alone are insufficient evidence.
	 * @param table old history table, normally dialectica_schema_migrations
	 * @param scope old scope name
	 * @param expected exact expected version/name mapping
	 * @param baseline resulting verified Strata layout version
	 * @param validateLayout read-only verification of actual tables, columns and necessary data invariants
	 * @return detector suitable for MigrationStream
	 */
	public static @NotNull LegacyDetector<JdbcContext> detector(@NotNull String table, @NotNull String scope,
			@NotNull Map<Integer, String> expected, int baseline, @NotNull MigrationAction<JdbcContext> validateLayout) {
		if (!table.matches("[A-Za-z_][A-Za-z0-9_]*") || baseline < 0) throw new IllegalArgumentException("Invalid legacy declaration");
		Map<Integer, String> required = Map.copyOf(expected);
		return context -> {
			if (!context.tableExists(table)) throw new IllegalStateException("Missing legacy history table " + table);
			Map<Integer, String> actual = new HashMap<>();
			try (PreparedStatement statement = context.connection().prepareStatement("SELECT version, name FROM " + table + " WHERE scope = ?")) {
				statement.setString(1, scope);
				try (ResultSet rows = statement.executeQuery()) {
					while (rows.next())
						if (actual.putIfAbsent(rows.getInt(1), rows.getString(2)) != null)
							throw new IllegalStateException("Duplicate legacy version in " + scope);
				}
			}
			if (!actual.equals(required)) throw new IllegalStateException("Unrecognized legacy scope " + scope);
			validateLayout.apply(context);
			return baseline;
		};
	}
}
