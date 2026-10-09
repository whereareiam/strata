package me.whereareiam.strata.adapter.configura.journal;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Makes a change to several files all-or-nothing across a crash.
 * <p>
 * The new contents are first written completely into a journal directory. Renaming that directory
 * into place is the commit: before it, no file has changed; after it, the journal is copied over the
 * files, and copied again on the next start if the process died in between.
 */
public final class CommitJournal {
	private static final String WRITES = "write";
	private static final String DELETIONS = "delete";

	private final Path root;
	private final Path preparing;
	private final Path committed;
	private final Path finished;

	/**
	 * Creates the journal of a directory.
	 *
	 * @param root  directory the changed files are relative to
	 * @param state existing directory on the same file system in which the journal is kept
	 */
	public CommitJournal(@NotNull Path root, @NotNull Path state) {
		this.root = root;
		this.preparing = state.resolve("journal.preparing");
		this.committed = state.resolve("journal");
		this.finished = state.resolve("journal.finished");
	}

	/**
	 * Applies a set of changes as one unit.
	 *
	 * @param changes new content by path relative to the root; a null content deletes the file
	 * @throws IOException when the changes cannot be journaled, in which case no file has changed, or
	 *                     cannot be copied into place, in which case {@link #recover()} finishes them
	 */
	public void commit(@NotNull Map<Path, byte[]> changes) throws IOException {
		DurableFiles.deleteTree(preparing);

		List<String> deletions = new ArrayList<>();
		for (Map.Entry<Path, byte[]> change : changes.entrySet()) {
			String file = change.getKey().toString();
			if (change.getValue() == null) deletions.add(file);
			else DurableFiles.write(preparing.resolve(WRITES).resolve(file), change.getValue());
		}

		DurableFiles.write(preparing.resolve(DELETIONS), String.join("\n", deletions).getBytes(StandardCharsets.UTF_8));
		DurableFiles.move(preparing, committed);
		DurableFiles.sync(committed.getParent());

		replay();
	}

	/**
	 * Finishes a commit that was interrupted after it took effect, and discards one that was
	 * interrupted before. Call it before reading any file of the root.
	 *
	 * @throws IOException when the journal cannot be read or copied into place
	 */
	public void recover() throws IOException {
		DurableFiles.deleteTree(preparing);
		DurableFiles.deleteTree(finished);
		if (Files.isDirectory(committed)) replay();
	}

	private void replay() throws IOException {
		Path writes = committed.resolve(WRITES);
		for (Path file : files(writes))
			DurableFiles.replace(root.resolve(writes.relativize(file)), Files.readAllBytes(file));

		for (String file : Files.readAllLines(committed.resolve(DELETIONS)))
			if (!file.isBlank()) Files.deleteIfExists(root.resolve(file));

		// Once renamed, a crash while deleting can no longer be mistaken for a commit to replay.
		DurableFiles.move(committed, finished);
		DurableFiles.deleteTree(finished);
	}

	private static List<Path> files(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) return List.of();

		try (Stream<Path> tree = Files.walk(directory)) {
			return tree.filter(Files::isRegularFile).toList();
		}
	}
}
