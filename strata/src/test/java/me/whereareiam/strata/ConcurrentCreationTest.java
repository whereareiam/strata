package me.whereareiam.strata;

import me.whereareiam.strata.adapter.database.DatabaseConnection;
import me.whereareiam.strata.adapter.database.DatabaseConnector;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import me.whereareiam.strata.adapter.database.DatabaseTarget;
import me.whereareiam.strata.model.MigrationReport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.MariaDbDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two instances install the same new database at once: the second one starts while the first is
 * creating the tables under the lock.
 */
@Tag("database-container")
@Testcontainers
class ConcurrentCreationTest {
	private static final String STREAM = "plugin/database";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

	@Container
	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:12");

	private final AtomicInteger creations = new AtomicInteger();

	@Test
	void createsAPostgresDatabaseOnce() throws Exception {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUser(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());

		installTogether(dataSource, POSTGRES);
	}

	@Test
	void createsAMariaDbDatabaseOnce() throws Exception {
		MariaDbDataSource dataSource = new MariaDbDataSource(MARIADB.getJdbcUrl());
		dataSource.setUser(MARIADB.getUsername());
		dataSource.setPassword(MARIADB.getPassword());

		installTogether(dataSource, MARIADB);
	}

	private void installTogether(DataSource dataSource, JdbcDatabaseContainer<?> database) throws Exception {
		CountDownLatch secondConnected = new CountDownLatch(1);
		AtomicReference<CompletableFuture<MigrationReport>> second = new AtomicReference<>();
		DatabaseConnector signalling = () -> {
			Connection connection = dataSource.getConnection();
			secondConnected.countDown();

			return connection(connection);
		};

		MigrationReport first = migrate(new DatabaseTarget(dataSource), () -> {
			second.set(CompletableFuture.supplyAsync(() -> migrate(new DatabaseTarget(signalling), () -> {})));
			assertTrue(secondConnected.await(30, TimeUnit.SECONDS), "the second instance did not start");
		});

		assertEquals(List.of("1 creation"), versions(first));
		assertTrue(second.get().get(60, TimeUnit.SECONDS).isEmpty(), "the second instance changed something on " + database.getDockerImageName());
		assertEquals(1, creations.get());
		assertEquals(1, HistoryTable.count(dataSource, "accounts"));
		assertEquals(List.of("1 creation"), HistoryTable.entries(dataSource, STREAM));
	}

	/** Upgrades the stream as one instance would; {@code whileCreating} runs inside its creation. */
	private MigrationReport migrate(DatabaseTarget target, ThrowingRunnable whileCreating) {
		MigrationStream<DatabaseContext> stream = MigrationStream.<DatabaseContext>builder()
				.id(STREAM)
				.target(target)
				.baseline((context, latest) -> context.tableExists("accounts") ? OptionalInt.of(0) : OptionalInt.empty())
				.creation(context -> {
					creations.incrementAndGet();
					context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)");
					context.update("INSERT INTO accounts (id) VALUES (?)", 1);
					whileCreating.run();
				})
				.migration(1, "create-accounts", context -> context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)"))
				.build();

		return new Strata(List.of(stream)).migrate();
	}

	private static List<String> versions(MigrationReport report) {
		return report.getApplied().getOrDefault(STREAM, List.of()).stream()
				.map(entry -> entry.getVersion() + " " + entry.getName())
				.toList();
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

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
