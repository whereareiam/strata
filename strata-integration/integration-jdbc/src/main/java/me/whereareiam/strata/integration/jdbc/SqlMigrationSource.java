package me.whereareiam.strata.integration.jdbc;

import me.whereareiam.strata.*;
import org.jetbrains.annotations.NotNull;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

/** Discovers versioned SQL in exploded classes and plugin JARs, including JARs without directory entries. */
public final class SqlMigrationSource {
	private static final Pattern NAME = Pattern.compile("V([0-9]+)__([A-Za-z0-9_-]+)\\.sql");

	/** Discovers direct scripts and common/dialect variants. Duplicate versions fail; no silent overrides.
	 * @param loader owning plugin classloader
	 * @param location classpath directory without a leading slash
	 * @param dialect selected subdirectory, such as postgresql, mysql, mariadb, h2 or sqlite
	 * @return ordered migrations; the owning stream checks the complete version chain
	 * @throws Exception if resources are ambiguous, malformed or unreadable
	 */
	public static @NotNull List<Migration<JdbcContext>> discover(@NotNull ClassLoader loader,
			@NotNull String location, @NotNull String dialect) throws Exception {
		if (location.startsWith("/") || location.contains("..") || !dialect.matches("[a-z0-9-]+"))
			throw new IllegalArgumentException("Invalid SQL resource location");
		String prefix = location.replaceAll("/+$", "") + "/";
		Map<String, URL> resources = new TreeMap<>();
		Enumeration<URL> directories = loader.getResources(location);
		while (directories.hasMoreElements()) scan(directories.nextElement(), prefix, resources);
		for (ClassLoader cursor = loader; cursor != null; cursor = cursor.getParent())
			if (cursor instanceof URLClassLoader urls)
				for (URL url : urls.getURLs()) scanRoot(url, prefix, resources);
		if (loader == ClassLoader.getSystemClassLoader() || loader == Thread.currentThread().getContextClassLoader())
			for (String entry : System.getProperty("java.class.path").split(File.pathSeparator))
				scanRoot(Path.of(entry).toUri().toURL(), prefix, resources);
		Map<Integer, Migration<JdbcContext>> migrations = new TreeMap<>();
		for (var entry : resources.entrySet()) {
			String relative = entry.getKey().substring(prefix.length());
			String[] parts = relative.split("/");
			if (parts.length > 2 || (parts.length == 2 && !parts[0].equals("common") && !parts[0].equals(dialect))) continue;
			if (!relative.endsWith(".sql")) continue;
			Migration<JdbcContext> migration;
			try (InputStream input = entry.getValue().openStream()) { migration = parse(parts[parts.length - 1], input.readAllBytes(), dialect); }
			if (migrations.putIfAbsent(migration.getToVersion(), migration) != null)
				throw new IllegalArgumentException("Duplicate SQL migration version " + migration.getToVersion());
		}
		if (migrations.isEmpty()) throw new IllegalArgumentException("No SQL migrations at " + location + " for " + dialect);
		return List.copyOf(migrations.values());
	}

	/** Loads one explicitly named script through the owning classloader.
	 * @param loader resource owner
	 * @param path full classpath resource name
	 * @return versioned and checksummed migration
	 * @throws Exception if the script is missing or duplicated
	 */
	public static @NotNull Migration<JdbcContext> resource(@NotNull ClassLoader loader, @NotNull String path) throws Exception {
		List<URL> matches = Collections.list(loader.getResources(path));
		if (matches.size() != 1) throw new IllegalArgumentException("Expected exactly one resource: " + path);
		try (InputStream input = matches.get(0).openStream()) { return parse(path.substring(path.lastIndexOf('/') + 1), input.readAllBytes(), "standard"); }
	}

	/** Adds an explicit recovery action to a script or Java migration requiring implicit DDL commits.
	 * The recovery implementation must inspect partial state and complete or validate the operation safely.
	 * @param migration original operation
	 * @param recovery recovery behavior
	 * @param recoveryFingerprint revision covering recovery behavior as well as the original action
	 * @return migration with durable intent and recovery
	 */
	public static @NotNull Migration<JdbcContext> recoverable(@NotNull Migration<JdbcContext> migration,
			@NotNull MigrationAction<JdbcContext> recovery, @NotNull String recoveryFingerprint) {
		return new Migration<>(migration.getId(), migration.getFromVersion(), migration.getToVersion(),
				migration.getFingerprint() + ":recovery:" + recoveryFingerprint, new RecoverableJdbcAction() {
			@Override public void apply(@NotNull JdbcContext context) throws Exception { migration.getAction().apply(context); }
			@Override public void recover(@NotNull JdbcContext context) throws Exception { recovery.apply(context); }
		});
	}

	private static Migration<JdbcContext> parse(String name, byte[] bytes, String dialect) throws Exception {
		Matcher matcher = NAME.matcher(name);
		if (!matcher.matches()) throw new IllegalArgumentException("Expected V001__description.sql: " + name);
		int version = Integer.parseInt(matcher.group(1));
		String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		return new Migration<>(matcher.group(2), version - 1, version, fingerprint,
				new ScriptAction(SqlScript.statements(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), dialect)));
	}

	static final class ScriptAction implements MigrationAction<JdbcContext> {
		private final List<String> statements;
		ScriptAction(List<String> statements) {
			if (statements.isEmpty()) throw new IllegalArgumentException("Empty SQL migration");
			this.statements = statements;
		}
		void validate(String product) {
			String database = product.toLowerCase(Locale.ROOT);
			for (String sql : statements) {
				String verb = sql.stripLeading().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
				if (Set.of("BEGIN", "COMMIT", "ROLLBACK", "START", "SET", "PRAGMA", "VACUUM").contains(verb))
					throw new IllegalArgumentException("SQL scripts cannot manage transactions or session state");
				if (!database.contains("postgresql") && !database.contains("sqlite")
						&& !Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE").contains(verb))
					throw new IllegalArgumentException("DDL script on " + product + " requires explicit recovery");
			}
		}
		@Override public void apply(@NotNull JdbcContext context) throws Exception {
			for (String statement : statements) context.execute(statement);
		}
	}

	private static void put(Map<String, URL> resources, String name, URL url) {
		URL previous = resources.putIfAbsent(name, url);
		if (previous != null && !previous.toExternalForm().equals(url.toExternalForm()))
			throw new IllegalArgumentException("Ambiguous classpath migration: " + name);
	}
	private static void scanRoot(URL url, String prefix, Map<String, URL> resources) throws Exception {
		if (!url.getProtocol().equals("file")) return;
		Path root = Path.of(url.toURI());
		if (Files.isDirectory(root)) {
			Path directory = root.resolve(prefix);
			if (Files.isDirectory(directory)) scan(directory.toUri().toURL(), prefix, resources);
		} else if (root.toString().endsWith(".jar")) scan(new URL("jar:" + url + "!/" + prefix), prefix, resources);
	}
	private static void scan(URL url, String prefix, Map<String, URL> resources) throws Exception {
		if (url.getProtocol().equals("file")) {
			Path directory = Path.of(url.toURI());
			try (var paths = Files.walk(directory, 2)) {
				for (Path path : paths.filter(Files::isRegularFile).toList())
					put(resources, prefix + directory.relativize(path).toString().replace(File.separatorChar, '/'), path.toUri().toURL());
			}
		} else if (url.getProtocol().equals("jar")) {
			JarURLConnection connection = (JarURLConnection) url.openConnection();
			connection.setUseCaches(false);
			// Opening the JAR directly also handles archives with no explicit directory entries.
			try (JarFile jar = new JarFile(Path.of(connection.getJarFileURL().toURI()).toFile())) {
				for (JarEntry entry : Collections.list(jar.entries()))
					if (!entry.isDirectory() && entry.getName().startsWith(prefix))
						put(resources, entry.getName(), new URL("jar:" + connection.getJarFileURL() + "!/" + entry.getName()));
			}
		}
	}
}
