package me.whereareiam.strata.adapter.jdbc.lock;

import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.adapter.jdbc.JdbcContext;
import me.whereareiam.strata.adapter.jdbc.JdbcTarget;
import me.whereareiam.strata.adapter.jdbc.TestDatabases;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.model.MigrationReport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
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
class PostgresLockTest {
	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

	@Test
	void isHeldByOneConnectionAtATime() throws Exception {
		DataSource dataSource = dataSource();

		try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
			DatabaseLock held = new PostgresLock(first, Duration.ofSeconds(5));

			assertThrows(SQLTimeoutException.class, () -> new PostgresLock(second, Duration.ofMillis(300)));

			held.close();
			new PostgresLock(second, Duration.ofSeconds(5)).close();
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

	private static DataSource dataSource() {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setURL(POSTGRES.getJdbcUrl());
		dataSource.setUser(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());

		return dataSource;
	}
}
