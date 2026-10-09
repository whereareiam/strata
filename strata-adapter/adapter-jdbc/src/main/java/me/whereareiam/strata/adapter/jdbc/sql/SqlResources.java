package me.whereareiam.strata.adapter.jdbc.sql;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads the SQL files below a classpath directory, whether it sits in a folder or in a JAR.
 */
final class SqlResources {
	private static final String EXTENSION = ".sql";
	private static final int DEPTH = 2;

	private SqlResources() {
	}

	/**
	 * Reads every script in a directory and in its direct subdirectories.
	 *
	 * @param loader   class loader of the application that ships the scripts
	 * @param location classpath directory
	 * @return script texts by their path below the directory, with forward slashes
	 * @throws IOException              when a script cannot be read
	 * @throws IllegalArgumentException if two classpath entries provide the same script
	 */
	static Map<String, String> read(ClassLoader loader, String location) throws IOException {
		String directory = location.replaceAll("^/+|/+$", "");
		Map<String, String> scripts = new TreeMap<>();
		for (URL url : Collections.list(loader.getResources(directory)))
			readLocation(url, scripts);

		if (scripts.isEmpty() && loader instanceof URLClassLoader archives)
			for (URL url : archives.getURLs())
				readUnlistedDirectory(url, directory, scripts);

		return scripts;
	}

	private static void readLocation(URL url, Map<String, String> scripts) throws IOException {
		switch (url.getProtocol()) {
			case "file" -> readDirectory(path(url), scripts);
			case "jar" -> {
				JarURLConnection connection = (JarURLConnection) url.openConnection();
				readArchive(path(connection.getJarFileURL()), connection.getEntryName(), scripts);
			}
			default -> throw new IOException("Cannot list SQL scripts at " + url);
		}
	}

	/** A JAR written without directory entries does not answer for the directory, only for its files. */
	private static void readUnlistedDirectory(URL url, String directory, Map<String, String> scripts) throws IOException {
		if (!url.getProtocol().equals("file")) return;

		Path archive = path(url);
		if (Files.isRegularFile(archive)) readArchive(archive, directory, scripts);
	}

	private static void readArchive(Path archive, String directory, Map<String, String> scripts) throws IOException {
		try (FileSystem contents = FileSystems.newFileSystem(archive, (ClassLoader) null)) {
			Path root = contents.getPath(directory);
			if (Files.isDirectory(root)) readDirectory(root, scripts);
		}
	}

	private static void readDirectory(Path root, Map<String, String> scripts) throws IOException {
		List<Path> files;
		try (Stream<Path> paths = Files.walk(root, DEPTH)) {
			files = paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(EXTENSION))
					.toList();
		}

		for (Path file : files) {
			String name = root.relativize(file).toString().replace(root.getFileSystem().getSeparator(), "/");
			if (scripts.putIfAbsent(name, Files.readString(file)) != null)
				throw new IllegalArgumentException("SQL script " + name + " is on the classpath twice");
		}
	}

	private static Path path(URL url) throws IOException {
		try {
			return Path.of(url.toURI());
		} catch (URISyntaxException failure) {
			throw new IOException("Cannot locate " + url, failure);
		}
	}
}
