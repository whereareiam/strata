package me.whereareiam.strata;

import me.whereareiam.strata.adapter.database.DatabaseContext;
import me.whereareiam.strata.adapter.database.DatabaseTarget;
import me.whereareiam.strata.exception.MigrationFailedException;
import me.whereareiam.strata.model.MigrationReport;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creates new installations of a database stream on the embedded databases.
 */
class DatabaseCreationTest {
	private static final String STREAM = "plugin/database";

	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void createsANewInstallationOnceAndRecordsTheLatestVersion(String dialect) throws Exception {
		DataSource dataSource = dataSource(dialect);
		MigrationStream<DatabaseContext> stream = stream(dataSource)
				.creation(context -> {
					context.execute("CREATE TABLE accounts (id INT PRIMARY KEY, last_login BIGINT)");
					context.update("INSERT INTO accounts (id, last_login) VALUES (?, ?)", 1, 0);
				})
				.build();

		MigrationReport report = new Strata(List.of(stream)).migrate();

		assertEquals("creation", report.getApplied().get(STREAM).get(0).getName());
		assertEquals(List.of("2 creation"), HistoryTable.entries(dataSource, STREAM));
		assertEquals(1, HistoryTable.count(dataSource, "accounts"));
		assertTrue(new Strata(List.of(stream)).migrate().isEmpty());
		assertEquals(1, HistoryTable.count(dataSource, "accounts"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void migratesAnExistingInstallationWithoutCreatingIt(String dialect) throws Exception {
		DataSource dataSource = dataSource(dialect);
		HistoryTable.execute(dataSource, "CREATE TABLE accounts (id INT PRIMARY KEY)");
		MigrationStream<DatabaseContext> stream = stream(dataSource)
				.creation(context -> context.execute("CREATE TABLE created (id INT PRIMARY KEY)"))
				.build();

		new Strata(List.of(stream)).migrate();

		assertEquals(List.of("1 baseline", "2 add-last-login"), HistoryTable.entries(dataSource, STREAM));
		assertEquals(0, HistoryTable.count(dataSource, "accounts"));
		assertFalse(tableExists(dataSource, "created"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void takesBackTheDataOfAFailedCreationAndRecordsNothing(String dialect) throws Exception {
		DataSource dataSource = dataSource(dialect);
		HistoryTable.execute(dataSource, "CREATE TABLE settings (name VARCHAR(32) PRIMARY KEY)");
		MigrationStream<DatabaseContext> stream = stream(dataSource)
				.baseline((context, latest) -> OptionalInt.empty())
				.creation(context -> {
					context.update("INSERT INTO settings (name) VALUES (?)", "created");
					throw new IllegalStateException("broken");
				})
				.build();

		MigrationFailedException failure = assertThrows(MigrationFailedException.class, () -> new Strata(List.of(stream)).migrate());

		assertEquals("creation", failure.getName());
		assertEquals(0, HistoryTable.count(dataSource, "settings"));
		assertTrue(HistoryTable.entries(dataSource, STREAM).isEmpty());
	}

	@Test
	void takesBackTheSchemaOfAFailedCreationWhereTheDatabaseCan() throws Exception {
		DataSource dataSource = dataSource("sqlite");
		MigrationStream<DatabaseContext> stream = stream(dataSource)
				.creation(context -> {
					context.execute("CREATE TABLE accounts (id INT PRIMARY KEY, last_login BIGINT)");
					throw new IllegalStateException("broken");
				})
				.build();

		assertThrows(MigrationFailedException.class, () -> new Strata(List.of(stream)).migrate());

		assertFalse(tableExists(dataSource, "accounts"));
		assertTrue(HistoryTable.entries(dataSource, STREAM).isEmpty());
	}

	private static boolean tableExists(DataSource dataSource, String table) throws Exception {
		try (MigrationSession<DatabaseContext> session = new DatabaseTarget(dataSource).open()) {
			return session.context().tableExists(table);
		}
	}

	private static MigrationStream.Builder<DatabaseContext> stream(DataSource dataSource) {
		return MigrationStream.<DatabaseContext>builder()
				.id(STREAM)
				.target(new DatabaseTarget(dataSource))
				.baseline((context, latest) -> context.tableExists("accounts") ? OptionalInt.of(1) : OptionalInt.empty())
				.migration(1, "create-accounts", context -> context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)"))
				.migration(2, "add-last-login", context -> context.execute("ALTER TABLE accounts ADD COLUMN last_login BIGINT"));
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
