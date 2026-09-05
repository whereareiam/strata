package me.whereareiam.strata.integration.dialectica;

import me.whereareiam.strata.*;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.integration.jdbc.JdbcContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DialecticaIntegrationTest {
	private JdbcDataSource database() throws Exception {
		var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
		try (var connection = source.getConnection(); var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE profiles (id INT PRIMARY KEY)");
		}
		return source;
	}
	@Test void jdbiUsesStrataTransactionAndDoesNotCloseConnection() throws Exception {
		var source = database();
		var migration = new Migration<JdbcContext>("insert", 0, 1, "1", DialecticaIntegration.action("h2", handle -> {
			handle.createUpdate("INSERT INTO profiles VALUES (:id)").bind("id", 7).execute();
		}));
		var integration = new DialecticaIntegration("db", source);
		var stream = new MigrationStream<>("test", integration, 1, List.of(migration), Map.of(), context -> 0, null);
		new Strata(List.of(stream), Map.of()).execute();
		try (var session = integration.open(false)) { assertEquals(1, session.history("test").version()); }
	}
	@Test void adoptsExactOldHistoryOnlyAfterLayoutValidation() throws Exception {
		var source = database();
		try (var connection = source.getConnection(); var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE dialectica_schema_migrations (scope VARCHAR(128), version INT, name VARCHAR(255))");
			statement.execute("INSERT INTO dialectica_schema_migrations VALUES ('accounts', 1, 'rename_accounts')");
		}
		var detector = LegacyDialectica.detector("dialectica_schema_migrations", "accounts", Map.of(1, "rename_accounts"), 3,
				context -> { if (!context.tableExists("profiles")) throw new IllegalStateException("missing table"); });
		var integration = new DialecticaIntegration("db", source);
		var stream = new MigrationStream<>("test", integration, 3, List.<Migration<JdbcContext>>of(), Map.of(), detector, null);
		new Strata(List.of(stream), Map.of()).execute();
		try (var session = integration.open(false)) {
			assertEquals(3, session.history("test").getBaseline());
			assertTrue(session.context().tableExists("dialectica_schema_migrations"));
			assertThrows(IllegalStateException.class, () -> LegacyDialectica.detector("dialectica_schema_migrations", "accounts",
					Map.of(2, "wrong"), 3, context -> { }).detect(session.context()));
		}
	}
}
