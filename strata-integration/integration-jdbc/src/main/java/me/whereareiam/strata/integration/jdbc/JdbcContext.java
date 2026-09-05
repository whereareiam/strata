package me.whereareiam.strata.integration.jdbc;

import org.jetbrains.annotations.NotNull;
import java.sql.*;
import java.util.Locale;
import java.lang.reflect.*;

/** Connection shared by every table operation in a migration. Never commit, close or replace this connection. */
public final class JdbcContext {
	private final Connection connection;
	private boolean executing;
	private boolean recoverable;

	JdbcContext(Connection connection) { this.connection = connection; }
	void executing(boolean value, boolean recoverable) { this.executing = value; this.recoverable = recoverable; }

	/** Returns a borrowed, guarded connection suitable for JDBC or Jdbi.
	 * During detection/preparation only queries are allowed. Transaction control belongs to Strata.
	 * @return guarded connection
	 */
	public @NotNull Connection connection() {
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
			String name = method.getName();
			if (name.equals("close")) return null;
			if (name.equals("commit") || name.equals("rollback") || name.equals("setAutoCommit") || name.equals("unwrap") || name.equals("abort"))
				throw new SQLException("Strata owns the migration connection");
			if ((name.equals("prepareStatement") || name.equals("prepareCall")) && args[0] instanceof String sql) validate(sql);
			Object value = invoke(method, connection, args);
			if (value instanceof Statement statement) {
				Class<?> type = value instanceof CallableStatement ? CallableStatement.class : value instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
				return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (statementProxy, operation, parameters) -> {
					if (operation.getName().equals("getConnection")) return connection();
					if (operation.getName().equals("unwrap")) throw new SQLException("Cannot unwrap migration statement");
					if (parameters != null && parameters.length > 0 && parameters[0] instanceof String sql
							&& (operation.getName().startsWith("execute") || operation.getName().equals("addBatch"))) validate(sql);
					return invoke(operation, statement, parameters);
				});
			}
			return value;
		});
	}

	/** Executes one SQL statement on the active migration connection.
	 * @param sql statement without transaction control
	 * @throws SQLException on failure or unsupported transactional DDL
	 */
	public void execute(@NotNull String sql) throws SQLException {
		validate(sql);
		try (Statement statement = connection.createStatement()) { statement.execute(sql); }
	}

	/** Checks exact table names through metadata, escaping metadata wildcard characters.
	 * @param table table name
	 * @return whether the table exists
	 * @throws SQLException when inspection fails
	 */
	public boolean tableExists(@NotNull String table) throws SQLException {
		DatabaseMetaData metadata = connection.getMetaData();
		try (ResultSet result = metadata.getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
			while (result.next()) if (table.equalsIgnoreCase(result.getString("TABLE_NAME"))) return true;
		}
		return false;
	}

	void validate(String sql) throws SQLException {
		var statements = SqlScript.statements(sql, connection.getMetaData().getDatabaseProductName());
		if (statements.size() != 1) throw new SQLException("Execute exactly one statement at a time");
		String text = statements.get(0).stripLeading().toUpperCase(Locale.ROOT);
		String verb = text.split("\\s+", 2)[0];
		if (java.util.Set.of("BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "START", "SET", "PRAGMA", "VACUUM", "ATTACH", "DETACH").contains(verb))
			throw new SQLException("Transaction/session control is owned by Strata: " + verb);
		if (!executing && !verb.equals("SELECT") && !verb.equals("EXPLAIN"))
			throw new SQLException("Preparation and detection permit only read queries");
		if (!recoverable && !java.util.Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE").contains(verb)) {
			String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
			if (!product.contains("postgresql") && !product.contains("sqlite"))
				throw new SQLException("This database requires RecoverableJdbcAction for DDL or non-DML statements");
		}
	}

	private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
		try { return method.invoke(target, args); }
		catch (InvocationTargetException exception) { throw exception.getCause(); }
	}
}
