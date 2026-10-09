package me.whereareiam.strata.adapter.jdbc.sql;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.adapter.jdbc.JdbcContext;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
	private static final Pattern FILE_NAME = Pattern.compile("V(\\d{1,9})__(.+)\\.sql");
	private static final String EVERY_DIALECT = "";

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
	public static @NotNull List<Migration<JdbcContext>> discover(
			@NotNull ClassLoader loader,
			@NotNull String location
	) throws IOException {
		Map<Integer, Scripts> versions = new TreeMap<>();
		for (Map.Entry<String, String> resource : SqlResources.read(loader, location).entrySet()) {
			String path = resource.getKey();
			int separator = path.lastIndexOf('/');
			Matcher file = match(path.substring(separator + 1));
			int version = Integer.parseInt(file.group(1));
			String dialect = separator < 0 ? EVERY_DIALECT : path.substring(0, separator);

			versions.computeIfAbsent(version, ignored -> new Scripts(version, file.group(2)))
					.add(dialect, file.group(2), resource.getValue());
		}

		if (versions.isEmpty()) throw new IllegalArgumentException("No SQL scripts found at " + location);

		return versions.values().stream()
				.map(scripts -> new Migration<JdbcContext>(scripts.version, scripts.name, scripts))
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
	public static @NotNull Migration<JdbcContext> resource(
			@NotNull ClassLoader loader,
			@NotNull String path
	) throws IOException {
		Matcher file = match(path.substring(path.lastIndexOf('/') + 1));
		Scripts scripts = new Scripts(Integer.parseInt(file.group(1)), file.group(2));
		try (InputStream input = loader.getResourceAsStream(path)) {
			if (input == null) throw new IllegalArgumentException("No SQL script at " + path);

			scripts.add(EVERY_DIALECT, scripts.name, new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}

		return new Migration<>(scripts.version, scripts.name, scripts);
	}

	private static Matcher match(String fileName) {
		Matcher file = FILE_NAME.matcher(fileName);
		if (!file.matches()) throw new IllegalArgumentException("Expected V<version>__<name>.sql, found " + fileName);

		return file;
	}

	/** The scripts of one version, of which the database being migrated picks its own. */
	private static final class Scripts implements MigrationAction<JdbcContext> {
		private final int version;
		private final String name;
		private final Map<String, String> byDialect = new HashMap<>();

		private Scripts(int version, String name) {
			this.version = version;
			this.name = name;
		}

		private void add(String dialect, String name, String script) {
			if (!this.name.equals(name))
				throw new IllegalArgumentException("SQL version " + version + " is named both " + this.name + " and " + name);

			byDialect.put(dialect, script);
			if (byDialect.size() > 1 && byDialect.containsKey(EVERY_DIALECT))
				throw new IllegalArgumentException("SQL version " + version + " has a script for every database and one for a single product");
		}

		@Override
		public void apply(@NotNull JdbcContext context) throws SQLException {
			String dialect = context.getDialect();
			for (String statement : SqlScript.split(script(dialect), dialect))
				context.execute(statement);
		}

		private String script(String dialect) {
			String script = byDialect.get(dialect);
			if (script == null && dialect.equals("mariadb")) script = byDialect.get("mysql");
			if (script == null) script = byDialect.get(EVERY_DIALECT);
			if (script == null)
				throw new IllegalStateException("SQL version " + version + " (" + name + ") has no script for " + dialect);

			return script;
		}
	}
}
