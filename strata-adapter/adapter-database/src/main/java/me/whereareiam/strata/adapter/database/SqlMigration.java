package me.whereareiam.strata.adapter.database;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.adapter.database.common.sql.SqlScriptSet;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Migrations written as SQL scripts on the classpath.
 * <p>
 * A script is named {@code V<version>__<name>.sql}. A script placed directly in the directory runs
 * on every database; one placed in a subdirectory named after a database product runs on that
 * product only, so a version can have one script per product instead:
 *
 * <pre>{@code
 * strata/my-plugin/database/
 *   V001__add_last_login.sql
 *   postgresql/V002__widen_name.sql
 *   mysql/V002__widen_name.sql
 * }</pre>
 * <p>
 * MariaDB uses the {@code mysql} script when it has none of its own.
 */
public final class SqlMigration {
	private SqlMigration() {
	}

	/**
	 * Turns every script of a classpath directory into a migration.
	 *
	 * @param loader   class loader of the application that ships the scripts
	 * @param location classpath directory, such as {@code strata/my-plugin/database}
	 * @return migrations in ascending version order
	 * @throws IOException              when a script cannot be read
	 * @throws IllegalArgumentException if there is no script, a file name does not follow the
	 *                                  pattern, or the scripts of one version contradict each other
	 */
	public static @NotNull List<Migration<DatabaseContext>> discover(
			@NotNull ClassLoader loader,
			@NotNull String location
	) throws IOException {
		Collection<SqlScriptSet> versions = SqlScriptSet.discover(loader, location);
		if (versions.isEmpty()) throw new IllegalArgumentException("No SQL scripts found at " + location);

		return versions.stream()
				.map(SqlMigration::migration)
				.toList();
	}

	/**
	 * Turns one script into a migration that runs on every database.
	 *
	 * @param loader class loader of the application that ships the script
	 * @param path   classpath location of the script, such as {@code strata/V001__add_last_login.sql}
	 * @return the migration
	 * @throws IOException              when the script cannot be read
	 * @throws IllegalArgumentException if the script is missing or its name does not follow the pattern
	 */
	public static @NotNull Migration<DatabaseContext> resource(
			@NotNull ClassLoader loader,
			@NotNull String path
	) throws IOException {
		return migration(SqlScriptSet.resource(loader, path));
	}

	private static Migration<DatabaseContext> migration(SqlScriptSet scripts) {
		return new Migration<>(scripts.getVersion(), scripts.getName(), scripts);
	}
}
