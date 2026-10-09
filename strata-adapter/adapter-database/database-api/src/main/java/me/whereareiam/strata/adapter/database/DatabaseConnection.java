package me.whereareiam.strata.adapter.database;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;

/**
 * A connection opened by a {@link DatabaseConnector}, together with whatever the connector has to
 * release when the upgrade is over.
 */
public interface DatabaseConnection extends AutoCloseable {
	/**
	 * Returns the JDBC connection migrations run on.
	 *
	 * @return open connection
	 */
	@NotNull Connection connection();

	/**
	 * Gives the connection back to where it came from.
	 *
	 * @throws Exception when releasing fails
	 */
	@Override
	void close() throws Exception;
}
