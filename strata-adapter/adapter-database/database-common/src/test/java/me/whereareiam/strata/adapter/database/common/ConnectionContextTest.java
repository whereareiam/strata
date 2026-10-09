package me.whereareiam.strata.adapter.database.common;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionContextTest {
	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void namesTheDatabaseProduct(String dialect) throws Exception {
		try (Connection connection = TestDatabases.create(dialect, directory).getConnection()) {
			assertEquals(dialect, new ConnectionContext(connection).getDialect());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void findsTablesAndColumnsIgnoringCase(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);
		TestDatabases.execute(dataSource, "CREATE TABLE user_accounts (id INT PRIMARY KEY, last_login BIGINT)");
		TestDatabases.execute(dataSource, "CREATE TABLE userXaccounts (other INT)");

		try (Connection connection = dataSource.getConnection()) {
			ConnectionContext context = new ConnectionContext(connection);

			assertTrue(context.tableExists("USER_ACCOUNTS"));
			assertFalse(context.tableExists("accounts"));
			assertTrue(context.columnExists("user_accounts", "LAST_LOGIN"));
			assertFalse(context.columnExists("user_accounts", "other"));
			assertFalse(context.columnExists("missing", "id"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"h2", "sqlite"})
	void runsStatementsWithAndWithoutParameters(String dialect) throws Exception {
		DataSource dataSource = TestDatabases.create(dialect, directory);

		try (Connection connection = dataSource.getConnection()) {
			ConnectionContext context = new ConnectionContext(connection);
			context.execute("CREATE TABLE accounts (id INT PRIMARY KEY, name VARCHAR(32))");

			assertEquals(1, context.update("INSERT INTO accounts (id, name) VALUES (?, ?)", 1, "first"));
			assertEquals(1, context.update("UPDATE accounts SET name = ? WHERE id = ?", "renamed", 1));
			assertEquals(0, context.update("DELETE FROM accounts WHERE name = ?", "first"));
		}

		assertEquals(1, TestDatabases.count(dataSource, "accounts"));
	}
}
