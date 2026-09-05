package me.whereareiam.strata.integration.jdbc;

import me.whereareiam.strata.*;
import me.whereareiam.strata.common.Strata;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.mariadb.jdbc.MariaDbDataSource;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("database-container")
@Testcontainers
class DatabaseFamiliesTest {
	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");
	@Container static final MariaDBContainer<?> MARIA = new MariaDBContainer<>("mariadb:12");

	@Test void postgresTransactionalDdlAndConcurrentStartup() throws Exception {
		var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
		var migration = new Migration<JdbcContext>("create", 0, 1, "1", context -> {
			context.execute("CREATE TABLE strata_test_profiles (id INT PRIMARY KEY)");
			context.execute("INSERT INTO strata_test_profiles VALUES (1)");
		});
		concurrent(source, "pg-test", migration);
		try (var session = new JdbcIntegration("db", source).open(false)) { assertEquals(1, session.history("pg-test").version()); }
	}
	@Test void mariaImplicitDdlAndCrossTableTransfer() throws Exception {
		var source = new MariaDbDataSource(MARIA.getJdbcUrl()); source.setUser(MARIA.getUsername()); source.setPassword(MARIA.getPassword());
		try (var connection = source.getConnection(); var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE legacy_profiles (id INT PRIMARY KEY)");
			statement.execute("INSERT INTO legacy_profiles VALUES (7)");
		}
		var action = new RecoverableJdbcAction() {
			@Override public void apply(JdbcContext context) throws Exception {
				context.execute("CREATE TABLE profiles (id INT PRIMARY KEY)");
				throw new IllegalStateException("after implicit DDL commit");
			}
			@Override public void recover(JdbcContext context) throws Exception {
				assertTrue(context.tableExists("profiles"));
			}
		};
		var create = new Migration<JdbcContext>("create", 0, 1, "1", action);
		assertThrows(IllegalStateException.class, () -> runner(source, "maria-schema", create).execute());
		runner(source, "maria-schema", create).execute();
		var transfer = new Migration<JdbcContext>("transfer", 0, 1, "1", context -> {
			context.execute("INSERT INTO profiles SELECT id FROM legacy_profiles");
			context.execute("DELETE FROM legacy_profiles");
		});
		concurrent(source, "maria-data", transfer);
		try (var connection = source.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM profiles")) {
			result.next(); assertEquals(1, result.getInt(1));
		}
	}
	private Strata runner(DataSource source, String stream, Migration<JdbcContext> migration) {
		return new Strata(List.of(new MigrationStream<>(stream, new JdbcIntegration("db", source), 1, List.of(migration), Map.of(), context -> 0, null)), Map.of());
	}
	private void concurrent(DataSource source, String stream, Migration<JdbcContext> migration) throws Exception {
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> { runner(source, stream, migration).execute(); return true; });
			var second = executor.submit(() -> { runner(source, stream, migration).execute(); return true; });
			assertTrue(first.get(30, TimeUnit.SECONDS)); assertTrue(second.get(30, TimeUnit.SECONDS));
		} finally { executor.shutdownNow(); }
	}
}
