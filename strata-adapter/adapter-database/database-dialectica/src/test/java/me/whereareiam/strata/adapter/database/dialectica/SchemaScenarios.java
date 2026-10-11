package me.whereareiam.strata.adapter.database.dialectica;

import me.whereareiam.dialectica.schema.IndexDefinition;
import me.whereareiam.dialectica.schema.change.AddColumn;
import me.whereareiam.dialectica.schema.change.CreateIndex;
import me.whereareiam.dialectica.schema.change.CreateTable;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.adapter.database.DatabaseConnection;
import me.whereareiam.strata.adapter.database.DatabaseConnector;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import me.whereareiam.strata.adapter.database.DatabaseTarget;
import me.whereareiam.strata.exception.MigrationFailedException;
import me.whereareiam.strata.model.MigrationReport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.accounts;
import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.applied;
import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.columnExists;
import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.first;
import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.history;
import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.insert;
import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.insertWithoutKey;
import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.migrate;
import static me.whereareiam.strata.adapter.database.dialectica.AccountStream.newest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every database is tested for, shared by the embedded and the container tests.
 */
final class SchemaScenarios {
	private SchemaScenarios() {
	}

	/** A new installation gets the newest schema from the creation, and a second start changes nothing. */
	static void createsTheNewestSchemaOnANewInstallation(DataSource dataSource) throws Exception {
		DatabaseTarget target = new DatabaseTarget(dataSource);

		assertEquals(List.of("3 creation"), applied(migrate(newest(target))));
		assertEquals(List.of("3 creation"), history(dataSource));
		assertTrue(columnExists(dataSource, "accounts", "username_key"));

		insert(dataSource, 1, "Alice", "alice");
		assertThrows(SQLException.class, () -> insert(dataSource, 2, "ALICE", "alice"), "the unique index is missing");

		assertTrue(migrate(newest(target)).isEmpty());
		assertEquals(List.of("3 creation"), history(dataSource));
		assertEquals(List.of("Alice alice"), accounts(dataSource));
	}

	/** An installation of the first build gets the schema changes and the data step, once. */
	static void appliesSchemaChangesToAnExistingInstallation(DataSource dataSource) throws Exception {
		DatabaseTarget target = new DatabaseTarget(dataSource);

		assertEquals(List.of("1 create-accounts"), applied(migrate(first(target))));
		insertWithoutKey(dataSource, 1, "Alice");
		insertWithoutKey(dataSource, 2, "Bob");

		assertEquals(List.of("2 username-key", "3 fill-username-keys"), applied(migrate(newest(target))));
		assertEquals(List.of("Alice alice", "Bob bob"), accounts(dataSource));
		assertThrows(SQLException.class, () -> insert(dataSource, 3, "ALICE", "alice"), "the unique index is missing");

		assertTrue(migrate(newest(target)).isEmpty());
		assertEquals(List.of("1 create-accounts", "2 username-key", "3 fill-username-keys"), history(dataSource));
	}

	/** On a database with transactional DDL, a failing schema migration leaves no change behind. */
	static void takesBackAFailedSchemaMigration(DataSource dataSource) throws Exception {
		DatabaseTarget target = new DatabaseTarget(dataSource);
		migrate(first(target));

		MigrationStream<DatabaseContext> broken = MigrationStream.<DatabaseContext>builder()
				.id(AccountStream.STREAM)
				.target(target)
				.migration(SchemaMigration.of(1, "create-accounts", new CreateTable(AccountStream.FIRST)))
				.migration(SchemaMigration.of(2, "username-key",
						new AddColumn("accounts", AccountStream.USERNAME_KEY),
						new CreateIndex("accounts", IndexDefinition.unique("accounts_missing", "missing"))))
				.build();

		MigrationFailedException failure = assertThrows(MigrationFailedException.class, () -> migrate(broken));

		assertEquals(2, failure.getVersion());
		assertFalse(columnExists(dataSource, "accounts", "username_key"));
		assertEquals(List.of("1 create-accounts"), history(dataSource));
	}

	/**
	 * Two instances install the same new database at once: the second one starts while the first is
	 * creating the schema under the lock, and finds it created.
	 */
	static void createsOnceWhenTwoInstancesInstallTogether(DataSource dataSource) throws Exception {
		AtomicInteger creations = new AtomicInteger();
		CountDownLatch secondConnected = new CountDownLatch(1);
		AtomicReference<CompletableFuture<MigrationReport>> second = new AtomicReference<>();
		DatabaseConnector signalling = () -> {
			Connection connection = dataSource.getConnection();
			secondConnected.countDown();

			return connection(connection);
		};

		MigrationReport first = migrate(newest(new DatabaseTarget(dataSource), context -> {
			creations.incrementAndGet();
			SchemaCreation.of(AccountStream.NEWEST).apply(context);

			second.set(CompletableFuture.supplyAsync(() -> migrate(newest(new DatabaseTarget(signalling), created -> {
				creations.incrementAndGet();
				SchemaCreation.of(AccountStream.NEWEST).apply(created);
			}))));
			assertTrue(secondConnected.await(30, TimeUnit.SECONDS), "the second instance did not start");
		}));

		assertEquals(List.of("3 creation"), applied(first));
		assertTrue(second.get().get(60, TimeUnit.SECONDS).isEmpty(), "the second instance changed something");
		assertEquals(1, creations.get());
		assertEquals(List.of("3 creation"), history(dataSource));
	}

	private static DatabaseConnection connection(Connection connection) {
		return new DatabaseConnection() {
			@Override
			public Connection connection() {
				return connection;
			}

			@Override
			public void close() throws Exception {
				connection.close();
			}
		};
	}
}
