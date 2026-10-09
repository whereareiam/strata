package me.whereareiam.strata.adapter.jdbc.sql;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.adapter.jdbc.JdbcContext;
import me.whereareiam.strata.adapter.jdbc.JdbcTarget;
import me.whereareiam.strata.adapter.jdbc.TestDatabases;
import me.whereareiam.strata.common.Strata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMigrationTest {
	@TempDir
	Path directory;

	@Test
	void turnsTheScriptsOfADirectoryIntoOneMigrationPerVersion() throws Exception {
		List<Migration<JdbcContext>> migrations = SqlMigration.discover(getClass().getClassLoader(), "strata/test");

		assertEquals(List.of(1, 2), migrations.stream().map(Migration::getVersion).toList());
		assertEquals(List.of("create_accounts", "add_last_login"), migrations.stream().map(Migration::getName).toList());
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void runsTheScriptOfTheDatabaseBeingMigrated(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);
		MigrationStream<JdbcContext> stream = MigrationStream.<JdbcContext>builder()
				.id("plugin/database")
				.target(new JdbcTarget(dataSource))
				.migrations(SqlMigration.discover(getClass().getClassLoader(), "strata/test"))
				.build();

		new Strata(List.of(stream)).migrate();

		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
		try (Connection connection = dataSource.getConnection()) {
			assertTrue(new JdbcContext(connection).columnExists("accounts", "last_login"));
		}
	}

	@Test
	void failsOnADatabaseAVersionHasNoScriptFor() throws Exception {
		Files.createDirectories(scripts().resolve("postgresql"));
		Files.writeString(scripts().resolve("postgresql/V001__first.sql"), "SELECT 1;");

		try (URLClassLoader loader = loader(); Connection connection = TestDatabases.h2().getConnection()) {
			Migration<JdbcContext> migration = SqlMigration.discover(loader, "strata/test").get(0);

			assertThrows(IllegalStateException.class, () -> migration.getAction().apply(new JdbcContext(connection)));
		}
	}

	@Test
	void turnsASingleScriptIntoAMigration() throws Exception {
		Migration<JdbcContext> migration = SqlMigration.resource(getClass().getClassLoader(), "strata/test/V001__create_accounts.sql");
		DataSource dataSource = TestDatabases.h2();

		try (Connection connection = dataSource.getConnection()) {
			migration.getAction().apply(new JdbcContext(connection));
		}

		assertEquals(1, migration.getVersion());
		assertEquals("create_accounts", migration.getName());
		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
	}

	@Test
	void rejectsAVersionWithAScriptForEveryDatabaseAndOneForASingleProduct() throws Exception {
		Files.createDirectories(scripts().resolve("mysql"));
		Files.writeString(scripts().resolve("V001__first.sql"), "SELECT 1;");
		Files.writeString(scripts().resolve("mysql/V001__first.sql"), "SELECT 2;");

		try (URLClassLoader loader = loader()) {
			assertThrows(IllegalArgumentException.class, () -> SqlMigration.discover(loader, "strata/test"));
		}
	}

	@Test
	void rejectsAVersionNamedDifferentlyPerProduct() throws Exception {
		Files.createDirectories(scripts().resolve("mysql"));
		Files.createDirectories(scripts().resolve("h2"));
		Files.writeString(scripts().resolve("mysql/V001__first.sql"), "SELECT 1;");
		Files.writeString(scripts().resolve("h2/V001__other.sql"), "SELECT 2;");

		try (URLClassLoader loader = loader()) {
			assertThrows(IllegalArgumentException.class, () -> SqlMigration.discover(loader, "strata/test"));
		}
	}

	@Test
	void rejectsFileNamesOutsideThePattern() throws Exception {
		Files.createDirectories(scripts());
		Files.writeString(scripts().resolve("create_accounts.sql"), "SELECT 1;");

		try (URLClassLoader loader = loader()) {
			assertThrows(IllegalArgumentException.class, () -> SqlMigration.discover(loader, "strata/test"));
			assertThrows(IllegalArgumentException.class, () -> SqlMigration.resource(loader, "strata/test/create_accounts.sql"));
		}
	}

	@Test
	void rejectsMissingScripts() {
		ClassLoader loader = getClass().getClassLoader();

		assertThrows(IllegalArgumentException.class, () -> SqlMigration.discover(loader, "strata/missing"));
		assertThrows(IllegalArgumentException.class, () -> SqlMigration.resource(loader, "strata/missing/V001__first.sql"));
	}

	private Path scripts() {
		return directory.resolve("strata/test");
	}

	private URLClassLoader loader() throws Exception {
		return new URLClassLoader(new URL[]{directory.toUri().toURL()}, null);
	}
}
