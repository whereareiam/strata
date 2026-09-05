package me.whereareiam.strata.integration.dialectica;

import me.whereareiam.dialectica.DialectPlugin;
import me.whereareiam.strata.*;
import me.whereareiam.strata.integration.jdbc.*;
import org.jdbi.v3.core.*;
import org.jetbrains.annotations.NotNull;
import javax.sql.DataSource;

/** Adds Dialectica/Jdbi actions to the JDBC integration without a second history store or runner. */
public final class DialecticaIntegration implements MigrationIntegration<JdbcContext> {
	private final JdbcIntegration jdbc;

	/** Registers the shared database.
	 * @param id resource identity
	 * @param dataSource application connection pool
	 */
	public DialecticaIntegration(@NotNull String id, @NotNull DataSource dataSource) {
		jdbc = new JdbcIntegration(id, dataSource);
	}
	@Override public @NotNull String id() { return jdbc.id(); }
	@Override public @NotNull MigrationSession<JdbcContext> open(boolean writable) throws Exception { return jdbc.open(writable); }

	/** Adapts a Jdbi action while retaining Strata's connection and transaction ownership.
	 * @param databaseType Dialectica database identifier
	 * @param action action using the borrowed handle; do not begin or end transactions
	 * @return JDBC-compatible migration action
	 */
	public static @NotNull MigrationAction<JdbcContext> action(@NotNull String databaseType,
			@NotNull MigrationAction<Handle> action) {
		return context -> {
			Jdbi jdbi = Jdbi.create(context.connection());
			jdbi.installPlugin(new DialectPlugin(databaseType));
			jdbi.getConfig(Handles.class).setForceEndTransactions(false);
			try (Handle handle = jdbi.open()) { action.apply(handle); }
		};
	}
}
