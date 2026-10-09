package me.whereareiam.strata.adapter.database.common;

import me.whereareiam.strata.model.AppliedMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSessionTest {
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void runsAnActionAndRecordsItsVersion(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);

		try (DatabaseSession session = open(dataSource)) {
			assertEquals(0, session.version("plugin/database"));

			session.apply("plugin/database", context -> context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)"), entry(1));
			session.apply("plugin/database", context -> context.update("INSERT INTO accounts (id) VALUES (?)", 1), entry(2));

			assertEquals(2, session.version("plugin/database"));
		}

		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
		assertEquals(2, TestDatabases.count(dataSource, "strata_history"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void takesBackTheDataOfAFailedAction(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);
		TestDatabases.execute(dataSource, "CREATE TABLE accounts (id INT PRIMARY KEY)");

		try (DatabaseSession session = open(dataSource)) {
			assertThrows(IllegalStateException.class, () -> session.apply("plugin/database", context -> {
				context.update("INSERT INTO accounts (id) VALUES (?)", 1);
				throw new IllegalStateException("broken");
			}, entry(1)));

			assertEquals(0, session.version("plugin/database"));
			assertTrue(session.context().getConnection().getAutoCommit());
		}

		assertEquals(0, TestDatabases.count(dataSource, "accounts"));
	}

	@Test
	void takesBackSchemaChangesWhereTheDatabaseCan() throws Exception {
		DataSource dataSource = TestDatabases.sqlite(directory);

		try (DatabaseSession session = open(dataSource)) {
			assertThrows(IllegalStateException.class, () -> session.apply("plugin/database", context -> {
				context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)");
				throw new IllegalStateException("broken");
			}, entry(1)));

			assertFalse(session.context().tableExists("accounts"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void keepsTheVersionsOfStreamsApart(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);

		try (DatabaseSession session = open(dataSource)) {
			session.apply("plugin/database", context -> {}, entry(3));
			session.apply("plugin/provider/database", context -> {}, entry(1));

			assertEquals(3, session.version("plugin/database"));
			assertEquals(1, session.version("plugin/provider/database"));
			assertEquals(0, session.version("plugin/unknown"));
		}
	}

	@Test
	void readsAVersionWithoutCreatingTheHistoryTable() throws Exception {
		DataSource dataSource = TestDatabases.h2();

		try (DatabaseSession session = open(dataSource)) {
			assertEquals(0, session.version("plugin/database"));
			assertFalse(session.context().tableExists("strata_history"));
		}
	}

	@Test
	void givesItsConnectionBackWhenClosed() throws Exception {
		DataSource dataSource = TestDatabases.h2();
		Connection connection;

		try (DatabaseSession session = open(dataSource)) {
			connection = session.context().getConnection();
			assertFalse(connection.isClosed());
		}

		assertTrue(connection.isClosed());
	}

	private static DatabaseSession open(DataSource dataSource) throws Exception {
		return new DatabaseSession(new DataSourceConnector(dataSource).open(), TIMEOUT);
	}

	private static AppliedMigration entry(int version) {
		return new AppliedMigration(version, "migration-" + version, Instant.EPOCH);
	}
}
