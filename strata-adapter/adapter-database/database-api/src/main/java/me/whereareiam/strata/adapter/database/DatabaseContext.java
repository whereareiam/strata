package me.whereareiam.strata.adapter.database;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * What a database migration works with: the connection its transaction runs on, and the questions
 * migrations commonly ask about the database.
 */
public interface DatabaseContext {
	/**
	 * Returns the connection of the running migration. It is borrowed: do not commit, roll back,
	 * close it or change its auto-commit mode.
	 *
	 * @return connection inside the migration's transaction
	 */
	@NotNull Connection getConnection();

	/**
	 * Names the database product, to branch where the SQL differs.
	 *
	 * @return product name in lower case: {@code postgresql}, {@code mysql}, {@code mariadb},
	 * {@code h2}, {@code sqlite}, or whatever another driver reports
	 * @throws SQLException when the database cannot be asked
	 */
	@NotNull String getDialect() throws SQLException;

	/**
	 * Runs one statement as written, without parameters.
	 *
	 * @param sql a single statement
	 * @throws SQLException when the database rejects it
	 */
	void execute(@NotNull String sql) throws SQLException;

	/**
	 * Runs one data-changing statement with positional parameters.
	 *
	 * @param sql        a single statement with a {@code ?} per parameter
	 * @param parameters values in the order of their placeholders
	 * @return number of rows changed
	 * @throws SQLException when the database rejects it
	 */
	int update(@NotNull String sql, Object... parameters) throws SQLException;

	/**
	 * Looks for a table or view in the current schema, ignoring case.
	 *
	 * @param table table name
	 * @return whether it exists
	 * @throws SQLException when the database cannot be inspected
	 */
	boolean tableExists(@NotNull String table) throws SQLException;

	/**
	 * Looks for a column of a table in the current schema, ignoring case.
	 *
	 * @param table  table name
	 * @param column column name
	 * @return whether the table exists and has the column
	 * @throws SQLException when the database cannot be inspected
	 */
	boolean columnExists(@NotNull String table, @NotNull String column) throws SQLException;
}
