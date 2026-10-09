package me.whereareiam.strata.adapter.database.common.sql;

import me.whereareiam.strata.adapter.database.common.ConnectionContext;
import me.whereareiam.strata.adapter.database.common.TestDatabases;
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

class SqlScriptSetTest {
	@TempDir
	Path directory;

	@Test
	void groupsTheScriptsOfADirectoryByVersion() throws Exception {
		List<SqlScriptSet> sets = List.copyOf(SqlScriptSet.discover(getClass().getClassLoader(), "strata/test"));

		assertEquals(List.of(1, 2), sets.stream().map(SqlScriptSet::getVersion).toList());
		assertEquals(List.of("create_accounts", "add_last_login"), sets.stream().map(SqlScriptSet::getName).toList());
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void runsTheScriptOfTheDatabaseItIsAppliedTo(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);

		try (Connection connection = dataSource.getConnection()) {
			ConnectionContext context = new ConnectionContext(connection);
			for (SqlScriptSet set : SqlScriptSet.discover(getClass().getClassLoader(), "strata/test"))
				set.apply(context);

			assertTrue(context.columnExists("accounts", "last_login"));
		}

		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
	}

	@Test
	void failsOnADatabaseAVersionHasNoScriptFor() throws Exception {
		Files.createDirectories(scripts().resolve("postgresql"));
		Files.writeString(scripts().resolve("postgresql/V001__first.sql"), "SELECT 1;");

		try (URLClassLoader loader = loader(); Connection connection = TestDatabases.h2().getConnection()) {
			SqlScriptSet set = SqlScriptSet.discover(loader, "strata/test").iterator().next();

			assertThrows(IllegalStateException.class, () -> set.apply(new ConnectionContext(connection)));
		}
	}

	@Test
	void readsASingleScript() throws Exception {
		SqlScriptSet set = SqlScriptSet.resource(getClass().getClassLoader(), "strata/test/V001__create_accounts.sql");
		DataSource dataSource = TestDatabases.h2();

		try (Connection connection = dataSource.getConnection()) {
			set.apply(new ConnectionContext(connection));
		}

		assertEquals(1, set.getVersion());
		assertEquals("create_accounts", set.getName());
		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
	}

	@Test
	void rejectsAVersionWithAScriptForEveryDatabaseAndOneForASingleProduct() throws Exception {
		Files.createDirectories(scripts().resolve("mysql"));
		Files.writeString(scripts().resolve("V001__first.sql"), "SELECT 1;");
		Files.writeString(scripts().resolve("mysql/V001__first.sql"), "SELECT 2;");

		try (URLClassLoader loader = loader()) {
			assertThrows(IllegalArgumentException.class, () -> SqlScriptSet.discover(loader, "strata/test"));
		}
	}

	@Test
	void rejectsAVersionNamedDifferentlyPerProduct() throws Exception {
		Files.createDirectories(scripts().resolve("mysql"));
		Files.createDirectories(scripts().resolve("h2"));
		Files.writeString(scripts().resolve("mysql/V001__first.sql"), "SELECT 1;");
		Files.writeString(scripts().resolve("h2/V001__other.sql"), "SELECT 2;");

		try (URLClassLoader loader = loader()) {
			assertThrows(IllegalArgumentException.class, () -> SqlScriptSet.discover(loader, "strata/test"));
		}
	}

	@Test
	void rejectsFileNamesOutsideThePattern() throws Exception {
		Files.createDirectories(scripts());
		Files.writeString(scripts().resolve("create_accounts.sql"), "SELECT 1;");

		try (URLClassLoader loader = loader()) {
			assertThrows(IllegalArgumentException.class, () -> SqlScriptSet.discover(loader, "strata/test"));
			assertThrows(IllegalArgumentException.class, () -> SqlScriptSet.resource(loader, "strata/test/create_accounts.sql"));
		}
	}

	@Test
	void rejectsAMissingScript() {
		assertThrows(IllegalArgumentException.class, () -> SqlScriptSet.resource(getClass().getClassLoader(), "strata/missing/V001__first.sql"));
	}

	private Path scripts() {
		return directory.resolve("strata/test");
	}

	private URLClassLoader loader() throws Exception {
		return new URLClassLoader(new URL[]{directory.toUri().toURL()}, null);
	}
}
