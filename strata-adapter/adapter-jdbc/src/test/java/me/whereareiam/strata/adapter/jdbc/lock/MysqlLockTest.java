package me.whereareiam.strata.adapter.jdbc.lock;

import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.adapter.jdbc.JdbcContext;
import me.whereareiam.strata.adapter.jdbc.JdbcTarget;
import me.whereareiam.strata.adapter.jdbc.TestDatabases;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.model.MigrationReport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.MariaDbDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.List;
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
	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:12");

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
		MigrationStream<JdbcContext> stream = MigrationStream.<JdbcContext>builder()
				.id("plugin/database")
				.target(new JdbcTarget(dataSource))
				.migration(1, "create-accounts", context -> {
					context.execute("CREATE TABLE accounts (id INT PRIMARY KEY)");
					context.update("INSERT INTO accounts (id) VALUES (?)", 1);
					Thread.sleep(500);
				})
				.build();

		CompletableFuture<MigrationReport> first = CompletableFuture.supplyAsync(() -> new Strata(List.of(stream)).migrate());
		CompletableFuture<MigrationReport> second = CompletableFuture.supplyAsync(() -> new Strata(List.of(stream)).migrate());

		assertNotEquals(first.get(30, TimeUnit.SECONDS).isEmpty(), second.get(30, TimeUnit.SECONDS).isEmpty());
		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
	}

	private static DataSource dataSource() throws SQLException {
		MariaDbDataSource dataSource = new MariaDbDataSource(MARIADB.getJdbcUrl());
		dataSource.setUser(MARIADB.getUsername());
		dataSource.setPassword(MARIADB.getPassword());

		return dataSource;
	}
}
