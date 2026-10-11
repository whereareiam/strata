package me.whereareiam.strata.adapter.database.dialectica;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Creates and migrates a schema through Dialectica on the embedded databases.
 */
class SchemaMigrationTest {
	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void createsTheNewestSchemaOnANewInstallation(String dialect) throws Exception {
		SchemaScenarios.createsTheNewestSchemaOnANewInstallation(dataSource(dialect));
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void appliesSchemaChangesToAnExistingInstallation(String dialect) throws Exception {
		SchemaScenarios.appliesSchemaChangesToAnExistingInstallation(dataSource(dialect));
	}

	@Test
	void takesBackAFailedSchemaMigration() throws Exception {
		SchemaScenarios.takesBackAFailedSchemaMigration(dataSource("sqlite"));
	}

	@Test
	void refusesAMigrationWithoutChanges() {
		assertThrows(IllegalArgumentException.class, () -> SchemaMigration.of(1, "nothing"));
	}

	private DataSource dataSource(String dialect) {
		if (dialect.equals("h2")) {
			JdbcDataSource dataSource = new JdbcDataSource();
			dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

			return dataSource;
		}

		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + ".db"));

		return dataSource;
	}
}
