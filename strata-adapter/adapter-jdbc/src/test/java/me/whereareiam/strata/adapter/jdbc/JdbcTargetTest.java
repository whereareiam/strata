package me.whereareiam.strata.adapter.jdbc;

import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.exception.MigrationFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTargetTest {
	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void runsMigrationsAndKeepsTheirHistoryInTheDatabase(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);
		MigrationStream<JdbcContext> stream = accounts(dataSource).build();

		new Strata(List.of(stream)).migrate();

		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
		assertEquals(2, version(dataSource, "plugin/database"));
		assertEquals(2, TestDatabases.count(dataSource, "strata_history"));
		assertTrue(new Strata(List.of(stream)).migrate().isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void takesBackTheDataOfAFailedMigration(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);
		MigrationStream<JdbcContext> broken = accounts(dataSource)
				.migration(3, "broken", context -> {
					context.update("INSERT INTO accounts (id) VALUES (?)", 2);
					throw new IllegalStateException("broken");
				})
				.build();

		assertThrows(MigrationFailedException.class, () -> new Strata(List.of(broken)).migrate());

		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
		assertEquals(2, version(dataSource, "plugin/database"));
		try (Connection connection = dataSource.getConnection()) {
			assertTrue(connection.getAutoCommit());
		}
	}

	@Test
	void takesBackSchemaChangesWhereTheDatabaseCan() throws Exception {
		DataSource dataSource = TestDatabases.sqlite(directory);
		MigrationStream<JdbcContext> broken = MigrationStream.<JdbcContext>builder()
				.id("plugin/database")
				.target(new JdbcTarget(dataSource))
				.migration(1, "broken", context -> {
					context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)");
					throw new IllegalStateException("broken");
				})
				.build();

		assertThrows(MigrationFailedException.class, () -> new Strata(List.of(broken)).migrate());

		try (Connection connection = dataSource.getConnection()) {
			assertFalse(new JdbcContext(connection).tableExists("accounts"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void stampsADatabaseWhoseBaselineSaysItIsCurrent(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);
		MigrationStream<JdbcContext> stream = accounts(dataSource)
				.baseline((context, latest) -> context.tableExists("accounts") ? 0 : latest)
				.build();

		assertTrue(new Strata(List.of(stream)).migrate().isEmpty());

		assertEquals(2, version(dataSource, "plugin/database"));
		try (Connection connection = dataSource.getConnection()) {
			assertFalse(new JdbcContext(connection).tableExists("accounts"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void keepsTheVersionsOfStreamsApart(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);
		MigrationStream<JdbcContext> other = MigrationStream.<JdbcContext>builder()
				.id("plugin/provider/database")
				.target(new JdbcTarget(dataSource))
				.migration(1, "create-sessions", context -> context.execute("CREATE TABLE sessions (id INT PRIMARY KEY)"))
				.build();

		new Strata(List.of(accounts(dataSource).build(), other)).migrate();

		assertEquals(2, version(dataSource, "plugin/database"));
		assertEquals(1, version(dataSource, "plugin/provider/database"));
		assertEquals(0, version(dataSource, "plugin/unknown"));
	}

	@Test
	void readsTheVersionWithoutCreatingTheHistoryTable() throws Exception {
		DataSource dataSource = TestDatabases.h2();

		assertEquals(0, version(dataSource, "plugin/database"));
		try (Connection connection = dataSource.getConnection()) {
			assertFalse(new JdbcContext(connection).tableExists("strata_history"));
		}
	}

	private static MigrationStream.Builder<JdbcContext> accounts(DataSource dataSource) {
		return MigrationStream.<JdbcContext>builder()
				.id("plugin/database")
				.target(new JdbcTarget(dataSource))
				.migration(1, "create-accounts", context -> context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)"))
				.migration(2, "add-first-account", context -> context.update("INSERT INTO accounts (id) VALUES (?)", 1));
	}

	private static int version(DataSource dataSource, String stream) throws Exception {
		try (MigrationSession<JdbcContext> session = new JdbcTarget(dataSource).open()) {
			return session.version(stream);
		}
	}
}
