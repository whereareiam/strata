package me.whereareiam.strata.integration.configura;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;

/** Files and original bytes remain journaled until a complete replacement set is archived. */
final class DocumentJournal {
	private final Path root;
	private final Path directory;
	private final ConfiguraIntegration.CommitObserver observer;

	DocumentJournal(Path root, Path directory, ConfiguraIntegration.CommitObserver observer) {
		this.root = root;
		this.directory = directory;
		this.observer = observer;
	}

	static String digest(byte[] bytes) {
		if (bytes == null) return "absent";
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (Exception exception) { throw new IllegalStateException(exception); }
	}

	Path safe(Path relative) throws IOException {
		Path result = root.resolve(relative).normalize();
		if (!result.startsWith(root) || result.equals(root)) throw new IOException("Resource escapes installation");
		for (Path cursor = result; !cursor.equals(root); cursor = cursor.getParent())
			if (Files.isSymbolicLink(cursor)) throw new IOException("Symbolic-link resource is not supported: " + relative);
		return result;
	}

	Path prepared(String stream) { return directory.resolve("prepared").resolve(digest(stream.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }

	void stage(Path stage, Map<Path, byte[]> before, Map<Path, byte[]> after) throws Exception {
		Files.createDirectories(stage.getParent());
		Path temporary = Files.createTempDirectory(stage.getParent(), ".preparing-");
		Properties manifest = new Properties();
		int index = 0;
		for (var entry : after.entrySet()) {
			Path relative = entry.getKey();
			byte[] original = before.get(relative);
			byte[] next = entry.getValue();
			manifest.setProperty(index + ".path", relative.toString());
			manifest.setProperty(index + ".before", digest(original));
			manifest.setProperty(index + ".after", digest(next));
			if (original != null) durableWrite(temporary.resolve(index + ".before"), original);
			if (next != null) durableWrite(temporary.resolve(index + ".after"), next);
			index++;
		}
		manifest.setProperty("count", Integer.toString(index));
		manifest.setProperty("format", "1");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		manifest.store(bytes, "Strata prepared document commit");
		durableWrite(temporary.resolve("manifest"), bytes.toByteArray());
		forceDirectory(temporary);
		Files.move(temporary, stage, StandardCopyOption.ATOMIC_MOVE);
		forceDirectory(stage.getParent());
	}

	Map<Path, byte[]> outputs(Path stage) throws Exception {
		Properties manifest = manifest(stage);
		Map<Path, byte[]> outputs = new LinkedHashMap<>();
		int count = Integer.parseInt(manifest.getProperty("count"));
		for (int i = 0; i < count; i++) {
			Path relative = Path.of(manifest.getProperty(i + ".path")); safe(relative);
			String checksum = manifest.getProperty(i + ".after");
			byte[] bytes = checksum.equals("absent") ? null : Files.readAllBytes(stage.resolve(i + ".after"));
			if (!digest(bytes).equals(checksum)) throw new IOException("Corrupt prepared output " + relative);
			outputs.put(relative, bytes);
		}
		return outputs;
	}

	void commit(Path stage) throws Exception {
		verify(stage, false);
		Path committing = directory.resolve("committing");
		Files.move(stage, committing, StandardCopyOption.ATOMIC_MOVE);
		forceDirectory(stage.getParent()); forceDirectory(directory);
		recover();
	}

	void recover() throws Exception {
		Path committing = directory.resolve("committing");
		if (!Files.exists(committing)) return;
		verify(committing, true);
		Map<Path, byte[]> outputs = outputs(committing);
		int index = 0;
		for (var output : outputs.entrySet()) {
			Path target = safe(output.getKey());
			byte[] next = output.getValue();
			if (!digest(read(target)).equals(digest(next))) {
				if (next == null) { Files.deleteIfExists(target); forceDirectory(target.getParent()); }
				else atomicWrite(target, next);
			}
			observer.afterReplacement(index++, target);
		}
		Path archives = directory.resolve("completed");
		Files.createDirectories(archives);
		Files.move(committing, archives.resolve(UUID.randomUUID().toString()), StandardCopyOption.ATOMIC_MOVE);
		forceDirectory(archives); forceDirectory(directory);
	}

	void verify(Path stage, boolean recovering) throws Exception {
		Properties manifest = manifest(stage);
		outputs(stage);
		int count = Integer.parseInt(manifest.getProperty("count"));
		for (int i = 0; i < count; i++) {
			Path target = safe(Path.of(manifest.getProperty(i + ".path")));
			String actual = digest(read(target));
			String before = manifest.getProperty(i + ".before");
			String after = manifest.getProperty(i + ".after");
			if (!actual.equals(before) && !(recovering && actual.equals(after)))
				throw new IOException("Resource changed since preparation: " + target);
			if (!before.equals("absent") && !digest(Files.readAllBytes(stage.resolve(i + ".before"))).equals(before))
				throw new IOException("Corrupt original backup: " + target);
		}
	}

	private Properties manifest(Path stage) throws IOException {
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(stage.resolve("manifest"))) { properties.load(input); }
		if (!"1".equals(properties.getProperty("format"))) throw new IOException("Unsupported document journal");
		return properties;
	}

	static byte[] read(Path path) throws IOException { return Files.exists(path) ? Files.readAllBytes(path) : null; }
	static void atomicWrite(Path target, byte[] bytes) throws IOException {
		Files.createDirectories(target.getParent());
		Path temporary = Files.createTempFile(target.getParent(), ".strata-", ".tmp");
		try {
			durableWrite(temporary, bytes);
			if (Files.exists(target) && Files.getFileStore(target).supportsFileAttributeView("posix"))
				Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target));
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			forceDirectory(target.getParent());
		} finally { Files.deleteIfExists(temporary); }
	}
	static void durableWrite(Path path, byte[] bytes) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) channel.write(buffer);
			channel.force(true);
		}
		if (Files.getFileStore(path).supportsFileAttributeView("posix"))
			Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
	}
	static void forceDirectory(Path directory) throws IOException {
		// Directory fsync is available on the POSIX filesystems supported for durable journals.
		if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
	}
}
