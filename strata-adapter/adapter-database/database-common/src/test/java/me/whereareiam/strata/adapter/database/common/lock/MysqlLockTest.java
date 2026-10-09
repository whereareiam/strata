package me.whereareiam.strata.adapter.database.common.lock;

import me.whereareiam.strata.adapter.database.common.DataSourceConnector;
import me.whereareiam.strata.adapter.database.common.DatabaseSession;
import me.whereareiam.strata.adapter.database.common.TestDatabases;
import me.whereareiam.strata.model.AppliedMigration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.MariaDbDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("database-container")
@Testcontainers
class MysqlLockTest {
	@Container
	private static final MariaDBContainer<?> DATABASE = new MariaDBContainer<>("mariadb:12");

	@Test
	void isHeldByOneConnectionAtATime() throws Exception {
		DataSource dataSource = dataSource();

		try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
			DatabaseLock held = new MysqlLock(first, Duration.ofSeconds(5));

			assertThrows(SQLTimeoutException.class, () -> new MysqlLock(second, Duration.ofSeconds(1)));

			held.close();
			new MysqlLock(second, Duration.ofSeconds(5)).close();
		}
	}

	@Test
	void letsInstancesStartingTogetherRunAMigrationOnce() throws Exception {
		DataSource dataSource = dataSource();

		CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> upgrade(dataSource));
		CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> upgrade(dataSource));

		assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
	}

	/** Does what an upgrade does: opens the database, and runs the migration unless it is recorded. */
	private static boolean upgrade(DataSource dataSource) {
		try (DatabaseSession session = new DatabaseSession(new DataSourceConnector(dataSource).open(), Duration.ofSeconds(30))) {
			if (session.version("plugin/database") > 0) return false;

			session.apply("plugin/database", context -> {
				context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)");
				context.update("INSERT INTO accounts (id) VALUES (?)", 1);
				Thread.sleep(500);
			}, new AppliedMigration(1, "create-accounts", Instant.EPOCH));

			return true;
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static DataSource dataSource() throws Exception {
		MariaDbDataSource dataSource = new MariaDbDataSource();
		dataSource.setUrl(DATABASE.getJdbcUrl());
		dataSource.setUser(DATABASE.getUsername());
		dataSource.setPassword(DATABASE.getPassword());

		return dataSource;
	}
}
