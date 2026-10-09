package me.whereareiam.strata.adapter.database.jdbi;

import me.whereareiam.strata.adapter.database.DatabaseConnection;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbiConnectorTest {
	private final Jdbi jdbi = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
	private final JdbiConnector connector = new JdbiConnector(jdbi);

	@Test
	void letsTheApplicationsJdbiWorkOnTheOpenedConnection() throws Exception {
		jdbi.define("accounts", "plugin_accounts");

		try (DatabaseConnection opened = connector.open()) {
			Connection connection = opened.connection();

			jdbi.useHandle(handle -> {
				assertSame(connection, handle.getConnection());
				handle.execute("CREATE TABLE <accounts> (name VARCHAR(32) PRIMARY KEY)");
			});
		}

		assertEquals(List.of(), names());
	}

	@Test
	void keepsTheApplicationsTransactionsInsideTheSurroundingOne() throws Exception {
		jdbi.useHandle(handle -> handle.execute("CREATE TABLE plugin_accounts (name VARCHAR(32) PRIMARY KEY)"));

		try (DatabaseConnection opened = connector.open()) {
			Connection connection = opened.connection();
			connection.setAutoCommit(false);

			jdbi.useTransaction(handle -> handle.execute("INSERT INTO plugin_accounts (name) VALUES (?)", "lost"));

			assertFalse(connection.getAutoCommit());
			connection.rollback();
			connection.setAutoCommit(true);
		}

		assertEquals(List.of(), names());
	}

	@Test
	void releasesTheHandleWhenClosed() throws Exception {
		Connection connection;
		try (DatabaseConnection opened = connector.open()) {
			connection = opened.connection();
		}

		assertTrue(connection.isClosed());
		assertNull(jdbi.getHandleScope().get());
		jdbi.useHandle(handle -> assertNotSame(connection, handle.getConnection()));
	}

	private List<String> names() {
		return jdbi.withHandle(handle -> handle.createQuery("SELECT name FROM plugin_accounts").mapTo(String.class).list());
	}
}
