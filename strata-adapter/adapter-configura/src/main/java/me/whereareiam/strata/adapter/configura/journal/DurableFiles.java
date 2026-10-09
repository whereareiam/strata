package me.whereareiam.strata.adapter.configura.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * File operations that are still true after a power loss.
 */
final class DurableFiles {
	private static final String TEMPORARY_SUFFIX = ".strata-tmp";

	private DurableFiles() {
	}

	/** Writes a file and its missing parent directories, and forces the content to disk. */
	static void write(Path file, byte[] bytes) throws IOException {
		Files.createDirectories(file.getParent());
		try (FileChannel channel = FileChannel.open(
				file,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
		)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) channel.write(buffer);

			channel.force(true);
		}
	}

	/** Swaps a file's content in one step, so a reader sees the old or the new content, never a part. */
	static void replace(Path target, byte[] bytes) throws IOException {
		Path temporary = target.resolveSibling(target.getFileName() + TEMPORARY_SUFFIX);
		try {
			write(temporary, bytes);
			copyPermissions(target, temporary);
			move(temporary, target);
		} finally {
			Files.deleteIfExists(temporary);
		}

		sync(target.getParent());
	}

	/** Moves a file or directory in one step where the file system can, and plainly where it cannot. */
	static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Forces a directory's entries to disk. Platforms that cannot do this for directories are left alone. */
	static void sync(Path directory) {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException unsupported) {
			// Windows refuses to open a directory; its renames are durable without this.
		}
	}

	static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;

		List<Path> paths;
		try (Stream<Path> tree = Files.walk(root)) {
			paths = tree.sorted(Comparator.reverseOrder()).toList();
		}

		for (Path path : paths) Files.delete(path);
	}

	private static void copyPermissions(Path from, Path to) throws IOException {
		if (!Files.exists(from)) return;

		try {
			Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
		} catch (UnsupportedOperationException notPosix) {
			// Permissions of other file systems are inherited from the directory.
		}
	}
}
