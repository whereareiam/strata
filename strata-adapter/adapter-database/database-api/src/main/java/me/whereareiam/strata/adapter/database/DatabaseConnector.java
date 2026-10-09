package me.whereareiam.strata.adapter.database;

import org.jetbrains.annotations.NotNull;

/**
 * Hands the database target the connection its migrations run on. A library an application reaches
 * its database through implements this, so that migrations and the application share that library's
 * connection handling.
 */
@FunctionalInterface
public interface DatabaseConnector {
	/**
	 * Opens a connection for one stream's upgrade.
	 *
	 * @return connection in auto-commit mode, owned by the caller until it is closed
	 * @throws Exception when the database cannot be reached
	 */
	@NotNull DatabaseConnection open() throws Exception;
}
