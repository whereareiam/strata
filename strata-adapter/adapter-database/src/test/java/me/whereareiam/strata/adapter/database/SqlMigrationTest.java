package me.whereareiam.strata.adapter.database;

import me.whereareiam.strata.Migration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlMigrationTest {
	@TempDir
	Path directory;

	@Test
	void turnsTheScriptsOfADirectoryIntoOneMigrationPerVersion() throws Exception {
		Path scripts = Files.createDirectories(directory.resolve("strata/test/postgresql"));
		Files.writeString(scripts.resolveSibling("V001__create_accounts.sql"), "SELECT 1;");
		Files.writeString(scripts.resolve("V002__add_last_login.sql"), "SELECT 2;");

		try (URLClassLoader loader = loader()) {
			List<Migration<DatabaseContext>> migrations = SqlMigration.discover(loader, "strata/test");

			assertEquals(List.of(1, 2), migrations.stream().map(Migration::getVersion).toList());
			assertEquals(List.of("create_accounts", "add_last_login"), migrations.stream().map(Migration::getName).toList());
		}
	}

	@Test
	void turnsASingleScriptIntoAMigration() throws Exception {
		Files.createDirectories(directory.resolve("strata/test"));
		Files.writeString(directory.resolve("strata/test/V003__lowercase_names.sql"), "SELECT 1;");

		try (URLClassLoader loader = loader()) {
			Migration<DatabaseContext> migration = SqlMigration.resource(loader, "strata/test/V003__lowercase_names.sql");

			assertEquals(3, migration.getVersion());
			assertEquals("lowercase_names", migration.getName());
		}
	}

	@Test
	void rejectsADirectoryWithoutScripts() throws Exception {
		try (URLClassLoader loader = loader()) {
			assertThrows(IllegalArgumentException.class, () -> SqlMigration.discover(loader, "strata/missing"));
		}
	}

	private URLClassLoader loader() throws Exception {
		return new URLClassLoader(new URL[]{directory.toUri().toURL()}, null);
	}
}
