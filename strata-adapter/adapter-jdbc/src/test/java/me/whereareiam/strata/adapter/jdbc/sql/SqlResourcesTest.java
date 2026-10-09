package me.whereareiam.strata.adapter.jdbc.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlResourcesTest {
	@TempDir
	Path directory;

	@Test
	void readsScriptsOfADirectoryAndItsSubdirectories() throws Exception {
		Map<String, String> scripts = SqlResources.read(getClass().getClassLoader(), "/strata/test/");

		assertEquals(
				List.of("V001__create_accounts.sql", "h2/V002__add_last_login.sql", "postgresql/V002__add_last_login.sql", "sqlite/V002__add_last_login.sql"),
				List.copyOf(scripts.keySet())
		);
		assertTrue(scripts.get("V001__create_accounts.sql").contains("CREATE TABLE accounts"));
	}

	@Test
	void readsScriptsOfAJar() throws Exception {
		Path jar = jar(true, "strata/test/V001__first.sql", "strata/test/mysql/V002__second.sql", "strata/test/notes.txt");

		try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
			assertEquals(List.of("V001__first.sql", "mysql/V002__second.sql"), List.copyOf(SqlResources.read(loader, "strata/test").keySet()));
		}
	}

	@Test
	void readsScriptsOfAJarWithoutDirectoryEntries() throws Exception {
		Path jar = jar(false, "strata/test/V001__first.sql", "strata/test/mysql/V002__second.sql");

		try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
			assertEquals(List.of("V001__first.sql", "mysql/V002__second.sql"), List.copyOf(SqlResources.read(loader, "strata/test").keySet()));
		}
	}

	@Test
	void findsNothingWhereNothingIs() throws Exception {
		assertTrue(SqlResources.read(getClass().getClassLoader(), "strata/missing").isEmpty());
	}

	@Test
	void rejectsAScriptProvidedTwice() throws Exception {
		Path first = Files.createDirectories(directory.resolve("first/strata/test"));
		Path second = Files.createDirectories(directory.resolve("second/strata/test"));
		Files.writeString(first.resolve("V001__first.sql"), "SELECT 1;");
		Files.writeString(second.resolve("V001__first.sql"), "SELECT 2;");
		URL[] roots = {directory.resolve("first").toUri().toURL(), directory.resolve("second").toUri().toURL()};

		try (URLClassLoader loader = new URLClassLoader(roots, null)) {
			assertThrows(IllegalArgumentException.class, () -> SqlResources.read(loader, "strata/test"));
		}
	}

	private Path jar(boolean directoryEntries, String... files) throws Exception {
		Path jar = directory.resolve("plugin.jar");
		try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file)) {
			if (directoryEntries)
				for (String entry : List.of("strata/", "strata/test/", "strata/test/mysql/")) {
					output.putNextEntry(new JarEntry(entry));
					output.closeEntry();
				}

			for (String entry : files) {
				output.putNextEntry(new JarEntry(entry));
				output.write("SELECT 1;".getBytes(StandardCharsets.UTF_8));
				output.closeEntry();
			}
		}

		return jar;
	}
}
