package me.whereareiam.strata.adapter.database.common.sql;

import lombok.Getter;
import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scripts of one version: one for every database, or one per database product, of which the
 * database being migrated picks its own. Running the set runs that script statement by statement.
 */
public final class SqlScriptSet implements MigrationAction<DatabaseContext> {
	private static final Pattern FILE_NAME = Pattern.compile("V(\\d{1,9})__(.+)\\.sql");
	private static final String EVERY_DIALECT = "";

	/** Version the file names of the scripts declare. */
	@Getter
	private final int version;

	/** Name the file names of the scripts declare. */
	@Getter
	private final @NotNull String name;

	private final Map<String, String> byDialect = new HashMap<>();

	private SqlScriptSet(int version, String name) {
		this.version = version;
		this.name = name;
	}

	/**
	 * Reads the scripts of a classpath directory: those directly in it run on every database, those
	 * in a subdirectory named after a database product on that product only.
	 *
	 * @param loader   class loader of the application that ships the scripts
	 * @param location classpath directory
	 * @return one set per version, in ascending version order
	 * @throws IOException              when a script cannot be read
	 * @throws IllegalArgumentException if a file name does not follow {@code V<version>__<name>.sql}
	 *                                  or the scripts of one version contradict each other
	 */
	public static @NotNull Collection<SqlScriptSet> discover(@NotNull ClassLoader loader, @NotNull String location) throws IOException {
		Map<Integer, SqlScriptSet> versions = new TreeMap<>();
		for (Map.Entry<String, String> resource : SqlResources.read(loader, location).entrySet()) {
			String path = resource.getKey();
			int separator = path.lastIndexOf('/');
			Matcher file = match(path.substring(separator + 1));
			int version = Integer.parseInt(file.group(1));
			String dialect = separator < 0 ? EVERY_DIALECT : path.substring(0, separator);

			versions.computeIfAbsent(version, ignored -> new SqlScriptSet(version, file.group(2)))
					.add(dialect, file.group(2), resource.getValue());
		}

		return versions.values();
	}

	/**
	 * Reads one script that runs on every database.
	 *
	 * @param loader class loader of the application that ships the script
	 * @param path   classpath location of the script
	 * @return set holding that script
	 * @throws IOException              when the script cannot be read
	 * @throws IllegalArgumentException if the script is missing or its name does not follow the pattern
	 */
	public static @NotNull SqlScriptSet resource(@NotNull ClassLoader loader, @NotNull String path) throws IOException {
		Matcher file = match(path.substring(path.lastIndexOf('/') + 1));
		SqlScriptSet scripts = new SqlScriptSet(Integer.parseInt(file.group(1)), file.group(2));
		try (InputStream input = loader.getResourceAsStream(path)) {
			if (input == null) throw new IllegalArgumentException("No SQL script at " + path);

			scripts.add(EVERY_DIALECT, scripts.name, new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}

		return scripts;
	}

	@Override
	public void apply(@NotNull DatabaseContext context) throws SQLException {
		String dialect = context.getDialect();
		for (String statement : SqlScript.split(script(dialect), dialect))
			context.execute(statement);
	}

	private void add(String dialect, String name, String script) {
		if (!this.name.equals(name))
			throw new IllegalArgumentException("SQL version " + version + " is named both " + this.name + " and " + name);

		byDialect.put(dialect, script);
		if (byDialect.size() > 1 && byDialect.containsKey(EVERY_DIALECT))
			throw new IllegalArgumentException("SQL version " + version + " has a script for every database and one for a single product");
	}

	private String script(String dialect) {
		String script = byDialect.get(dialect);
		if (script == null && dialect.equals("mariadb")) script = byDialect.get("mysql");
		if (script == null) script = byDialect.get(EVERY_DIALECT);
		if (script == null)
			throw new IllegalStateException("SQL version " + version + " (" + name + ") has no script for " + dialect);

		return script;
	}

	private static Matcher match(String fileName) {
		Matcher file = FILE_NAME.matcher(fileName);
		if (!file.matches()) throw new IllegalArgumentException("Expected V<version>__<name>.sql, found " + fileName);

		return file;
	}
}
