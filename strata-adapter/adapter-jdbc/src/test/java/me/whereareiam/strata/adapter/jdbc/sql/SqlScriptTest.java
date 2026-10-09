package me.whereareiam.strata.adapter.jdbc.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptTest {
	@Test
	void splitsAtSemicolonsAndDropsEmptyStatements() {
		assertEquals(List.of("SELECT 1", "SELECT 2"), split("SELECT 1;\n;\nSELECT 2;\n", "h2"));
		assertEquals(List.of("SELECT 1"), split("SELECT 1", "h2"));
		assertTrue(split("  \n", "h2").isEmpty());
	}

	@Test
	void keepsSemicolonsInsideQuotes() {
		assertEquals(
				List.of("INSERT INTO x VALUES ('a;''b')", "SELECT \"odd;name\", `other;name` FROM x"),
				split("INSERT INTO x VALUES ('a;''b'); SELECT \"odd;name\", `other;name` FROM x;", "h2")
		);
	}

	@Test
	void dropsComments() {
		assertEquals(
				List.of("SELECT 1", "SELECT   2"),
				split("-- leading; comment\nSELECT 1; -- trailing\nSELECT /* ; /* nested */ ; */ 2;\n-- closing", "postgresql")
		);
	}

	@Test
	void keepsExecutableCommentsOfMysql() {
		assertEquals(List.of("CREATE TABLE x (id INT) /*!50100 ENGINE=InnoDB; */"), split("CREATE TABLE x (id INT) /*!50100 ENGINE=InnoDB; */;", "mysql"));
	}

	@Test
	void keepsDollarQuotedBodiesWhole() {
		assertEquals(
				List.of("SELECT $$a;b$$", "CREATE FUNCTION f() RETURNS int AS $body$ BEGIN RETURN 1; END; $body$ LANGUAGE plpgsql"),
				split("SELECT $$a;b$$; CREATE FUNCTION f() RETURNS int AS $body$ BEGIN RETURN 1; END; $body$ LANGUAGE plpgsql;", "postgresql")
		);
	}

	@Test
	void keepsTriggerBodiesWhole() {
		List<String> statements = split(
				"CREATE TRIGGER audit AFTER INSERT ON x BEGIN INSERT INTO y VALUES (';');"
						+ " UPDATE z SET v = CASE WHEN v = 1 THEN 2 ELSE 3 END; END; SELECT 1;",
				"sqlite"
		);

		assertEquals(2, statements.size());
		assertTrue(statements.get(0).endsWith("END; END"));
		assertEquals("SELECT 1", statements.get(1));
	}

	@Test
	void followsDelimiterLines() {
		assertEquals(
				List.of("CREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END", "SELECT 3"),
				split("DELIMITER //\nCREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END//\nDELIMITER ;\nSELECT 3;", "mysql")
		);
	}

	@Test
	void treatsBackslashesAsTheDatabaseDoes() {
		assertEquals(2, split("SELECT '\\'; SELECT 2;", "sqlite").size());
		assertEquals(1, split("SELECT '\\'; SELECT 2;'", "mysql").size());
		assertEquals(2, split("SELECT E'a\\';b'; SELECT 2;", "postgresql").size());
	}

	@Test
	void rejectsScriptsThatLeaveSomethingOpen() {
		for (String script : List.of("SELECT 'open", "SELECT $$open", "SELECT 1; /* open", "CREATE TRIGGER t AFTER INSERT ON x BEGIN SELECT 1;"))
			assertThrows(IllegalArgumentException.class, () -> split(script, "sqlite"), script);
	}

	private static List<String> split(String script, String dialect) {
		return SqlScript.split(script, dialect);
	}
}
