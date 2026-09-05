package me.whereareiam.strata.integration.jdbc;

import me.whereareiam.strata.*;
import me.whereareiam.strata.common.Strata;
import org.h2.jdbcx.JdbcDataSource;
import org.sqlite.SQLiteDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class JdbcIntegrationTest {
	@TempDir Path temporary;
	private JdbcDataSource h2() {
		var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"); return source;
	}
	private void sql(DataSource source, String... statements) throws Exception {
		try (var connection = source.getConnection(); var statement = connection.createStatement()) {
			for (String sql : statements) statement.execute(sql);
		}
	}
	private int count(DataSource source, String table) throws Exception {
		try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rows.next(); return rows.getInt(1);
		}
	}
	private Strata runner(DataSource source, Migration<JdbcContext> migration) {
		return new Strata(List.of(new MigrationStream<>("plugin/database", new JdbcIntegration("db", source), 1,
				List.of(migration), Map.of(), context -> 0, null)), Map.of());
	}
	@Test void transfersAcrossTablesAndRecordsOnce() throws Exception {
		var source = h2();
		sql(source, "CREATE TABLE old_profiles (id INT PRIMARY KEY)", "CREATE TABLE profiles (id INT PRIMARY KEY)", "INSERT INTO old_profiles VALUES (7)");
		var migration = new Migration<JdbcContext>("transfer", 0, 1, "v1", context -> {
			context.execute("INSERT INTO profiles SELECT id FROM old_profiles");
			context.execute("DELETE FROM old_profiles");
		});
		var runner = runner(source, migration);
		assertEquals(2, runner.inspect().size());
		assertEquals(1, count(source, "old_profiles"));
		runner.execute(); runner.execute();
		assertEquals(0, count(source, "old_profiles")); assertEquals(1, count(source, "profiles"));
		assertEquals(1, count(source, "strata_history"));
	}
	@Test void rollsBackDataAndHistoryTogether() throws Exception {
		var source = h2(); sql(source, "CREATE TABLE profiles (id INT PRIMARY KEY)");
		AtomicBoolean fail = new AtomicBoolean(true);
		var runner = runner(source, new Migration<>("insert", 0, 1, "same", context -> {
			context.execute("INSERT INTO profiles VALUES (1)");
			if (fail.get()) throw new IllegalStateException("injected");
		}));
		assertThrows(IllegalStateException.class, runner::execute);
		assertEquals(0, count(source, "profiles")); assertEquals(0, count(source, "strata_history"));
		fail.set(false); runner.execute(); assertEquals(1, count(source, "profiles"));
	}
	@Test void preventsImplicitDdlWithoutRecoveryAndTransactionControl() throws Exception {
		var source = h2();
		assertThrows(SQLException.class, () -> runner(source, new Migration<>("ddl", 0, 1, "1", context -> context.execute("CREATE TABLE broken (id INT)"))).execute());
		assertThrows(SQLException.class, () -> runner(source, new Migration<>("commit", 0, 1, "1", context -> context.connection().commit())).execute());
	}
	@Test void recoversImplicitDdlUsingDurableIntent() throws Exception {
		var source = h2();
		var migration = new Migration<JdbcContext>("ddl", 0, 1, "1", new RecoverableJdbcAction() {
			@Override public void apply(JdbcContext context) throws Exception {
				context.execute("CREATE TABLE profiles (id INT PRIMARY KEY)");
				throw new IllegalStateException("after implicit commit");
			}
			@Override public void recover(JdbcContext context) throws Exception {
				assertTrue(context.tableExists("profiles"));
				context.execute("INSERT INTO profiles VALUES (1)");
			}
		});
		var runner = runner(source, migration);
		assertThrows(IllegalStateException.class, runner::execute);
		assertEquals(1, count(source, "strata_pending"));
		runner.execute(); runner.execute();
		assertEquals(1, count(source, "profiles")); assertEquals(0, count(source, "strata_pending"));
	}
	@Test void rejectsChangedRecoveryImplementation() throws Exception {
		var source = h2();
		var action = new RecoverableJdbcAction() {
			@Override public void apply(JdbcContext context) { throw new IllegalStateException("injected"); }
			@Override public void recover(JdbcContext context) { fail("must validate intent first"); }
		};
		assertThrows(IllegalStateException.class, () -> runner(source, new Migration<>("ddl", 0, 1, "1", action)).execute());
		assertThrows(IllegalStateException.class, () -> runner(source, new Migration<>("ddl", 0, 1, "changed", action)).execute());
	}
	@Test void serializesConcurrentInstances() throws Exception {
		var source = h2(); sql(source, "CREATE TABLE profiles (id INT PRIMARY KEY)");
		var migration = new Migration<JdbcContext>("insert", 0, 1, "1", context -> {
			context.execute("INSERT INTO profiles VALUES (1)"); Thread.sleep(100);
		});
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> { runner(source, migration).execute(); return true; });
			var second = executor.submit(() -> { runner(source, migration).execute(); return true; });
			assertTrue(first.get(15, TimeUnit.SECONDS)); assertTrue(second.get(15, TimeUnit.SECONDS));
		} finally { executor.shutdownNow(); }
		assertEquals(1, count(source, "profiles"));
	}
	@Test void sqliteSchemaAndDataRollbackTogether() throws Exception {
		var source = new SQLiteDataSource(); source.setUrl("jdbc:sqlite:" + temporary.resolve("test.db"));
		AtomicBoolean fail = new AtomicBoolean(true);
		var runner = runner(source, new Migration<>("schema", 0, 1, "1", context -> {
			context.execute("CREATE TABLE profiles (id INTEGER PRIMARY KEY)");
			context.execute("INSERT INTO profiles VALUES (1)");
			if (fail.get()) throw new IllegalStateException("injected");
		}));
		assertThrows(IllegalStateException.class, runner::execute);
		try (var session = new JdbcIntegration("db", source).open(false)) { assertFalse(session.context().tableExists("profiles")); }
		fail.set(false); runner.execute(); runner.execute();
		assertEquals(1, count(source, "profiles"));
	}
	@Test void detectionCannotWriteAndInspectionDoesNotCreateHistory() throws Exception {
		var source = h2();
		var integration = new JdbcIntegration("db", source);
		try (var session = integration.open(false)) {
			assertNull(session.history("test"));
			assertFalse(session.context().tableExists("strata_history"));
			assertThrows(SQLException.class, () -> session.context().execute("CREATE TABLE bad(id INT)"));
		}
	}
}
