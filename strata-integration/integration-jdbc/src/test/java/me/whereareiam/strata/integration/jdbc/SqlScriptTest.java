package me.whereareiam.strata.integration.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptTest {
	@TempDir Path temporary;
	@Test void handlesQuotesCommentsAndDollarBodies() {
		var statements = SqlScript.statements("-- leading\nINSERT INTO x VALUES ('a;''b'); /* ; /* nested */ */ SELECT $$a;b$$; SELECT $body$a;b$body$;");
		assertEquals(3, statements.size());
		assertTrue(statements.get(0).contains("a;''b"));
	}
	@Test void handlesSqliteTriggerAndMysqlDelimiter() {
		assertEquals(2, SqlScript.statements("CREATE TRIGGER audit AFTER INSERT ON x BEGIN INSERT INTO y VALUES (';'); UPDATE z SET v = CASE WHEN v=1 THEN 2 ELSE 3 END; END; SELECT 1;").size());
		assertEquals(2, SqlScript.statements("DELIMITER //\nCREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END//\nDELIMITER ;\nSELECT 3;").size());
	}
	@Test void rejectsIncompleteScripts() {
		for (String script : List.of("SELECT 'oops", "SELECT $$oops", "SELECT 1; /*oops"))
			assertThrows(IllegalArgumentException.class, () -> SqlScript.statements(script));
	}
	@Test void discoversJarWithoutDirectoryEntriesAndSelectsDialect() throws Exception {
		Path jar = temporary.resolve("plugin.jar");
		try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
			for (String entry : List.of("strata/test/common/V001__first.sql", "strata/test/postgresql/V002__second.sql", "strata/test/mysql/V002__second.sql")) {
				output.putNextEntry(new JarEntry(entry)); output.write("SELECT 1;".getBytes()); output.closeEntry();
			}
		}
		try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
			var migrations = SqlMigrationSource.discover(loader, "strata/test", "postgresql");
			assertEquals(List.of(1, 2), migrations.stream().map(migration -> migration.getToVersion()).toList());
			assertEquals(64, migrations.get(0).getFingerprint().length());
		}
	}
	@Test void rejectsOverlappingCommonAndDialectVersions() throws Exception {
		Path root = temporary.resolve("strata/test"); Files.createDirectories(root.resolve("common")); Files.createDirectories(root.resolve("mysql"));
		Files.writeString(root.resolve("common/V001__first.sql"), "SELECT 1;");
		Files.writeString(root.resolve("mysql/V001__first.sql"), "SELECT 2;");
		try (var loader = new URLClassLoader(new URL[]{temporary.toUri().toURL()}, null)) {
			assertThrows(IllegalArgumentException.class, () -> SqlMigrationSource.discover(loader, "strata/test", "mysql"));
		}
	}
	@Test void standardBackslashesDoNotEscapeSqlQuotes() {
		assertEquals(2, SqlScript.statements("SELECT '\\'; SELECT 2;", "sqlite").size());
		assertEquals(2, SqlScript.statements("SELECT E'a\\';b'; SELECT 2;", "postgresql").size());
	}

}
