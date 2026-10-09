package me.whereareiam.strata.adapter.database;

import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.model.AppliedMigration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTargetTest {
	private final JdbcDataSource dataSource = new JdbcDataSource();

	DatabaseTargetTest() {
		dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
	}

	@Test
	void migratesADatabaseReachedThroughADataSource() throws Exception {
		DatabaseTarget target = new DatabaseTarget(dataSource);

		try (MigrationSession<DatabaseContext> session = target.open()) {
			session.apply("plugin/database", database -> database.execute("CREATE TABLE accounts (id INT PRIMARY KEY)"), entry(1));

			assertEquals("h2", session.context().getDialect());
			assertTrue(session.context().tableExists("accounts"));
		}

		try (MigrationSession<DatabaseContext> session = target.open()) {
			assertEquals(1, session.version("plugin/database"));
		}
	}

	@Test
	void opensAndClosesTheConnectionOfItsConnector() throws Exception {
		AtomicInteger open = new AtomicInteger();
		DatabaseConnector connector = () -> {
			Connection connection = dataSource.getConnection();
			open.incrementAndGet();

			return new DatabaseConnection() {
				@Override
				public Connection connection() {
					return connection;
				}

				@Override
				public void close() throws Exception {
					connection.close();
					open.decrementAndGet();
				}
			};
		};

		try (MigrationSession<DatabaseContext> session = new DatabaseTarget(connector).open()) {
			assertEquals(1, open.get());
			assertEquals(0, session.version("plugin/database"));
		}

		assertEquals(0, open.get());
	}

	private static AppliedMigration entry(int version) {
		return new AppliedMigration(version, "migration-" + version, Instant.EPOCH);
	}
}
