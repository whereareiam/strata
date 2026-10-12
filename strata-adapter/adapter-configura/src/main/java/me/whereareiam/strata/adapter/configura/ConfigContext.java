package me.whereareiam.strata.adapter.configura;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import me.whereareiam.configura.Configura;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a configuration migration works with: the files of the configuration directory as document
 * trees. Changes are staged in memory and reach the disk together once the migration has returned.
 * <p>
 * Files are named by their path relative to the directory, extension included. Values inside a
 * file are addressed with JSON pointers such as {@code /connection/routing}; a key that holds
 * {@code /} or {@code ~} is escaped as RFC 6901 says, for example with Jackson's
 * {@code JsonPointer.compile("/rules").appendProperty(key)}.
 * <p>
 * Migrations usually have to cope with a user's edits and with earlier versions of the application
 * that left values in more than one place. The {@code IfAbsent}, {@code IfEmpty} and section moves
 * do nothing where there is nothing to do, so a migration built from them finds nothing left to
 * change when it meets a directory already past it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class ConfigContext {
	static final String STATE_DIRECTORY = ".strata";

	private final Configura configura;
	private final Path directory;
	private final Map<Path, Document> documents = new LinkedHashMap<>();
	private final Set<Path> deletedDirectories = new LinkedHashSet<>();

	/**
	 * Tells whether a file is there, counting what this migration created and deleted so far.
	 *
	 * @param file path relative to the configuration directory
	 * @return whether the file exists
	 */
	public boolean exists(@NotNull String file) {
		return open(file).staged != null;
	}

	/**
	 * Opens a file for reading and changing. A file that does not exist opens as an empty document
	 * and is created when the migration leaves something in it.
	 *
	 * @param file path relative to the configuration directory
	 * @return the staged tree; changing it changes the file
	 * @throws IllegalStateException if the file does not hold a document with named values at its root
	 */
	public @NotNull ObjectNode document(@NotNull String file) {
		Document document = open(file);
		if (document.staged == null) document.staged = configura.mapper().createObjectNode();

		return document.staged;
	}

	/**
	 * Deletes a file.
	 *
	 * @param file path relative to the configuration directory
	 */
	public void delete(@NotNull String file) {
		open(file).staged = null;
	}

	/**
	 * Takes a value out of a file.
	 *
	 * @param file    path relative to the configuration directory
	 * @param pointer JSON pointer to the value
	 * @return the removed value, or null when the file or the value is not there
	 */
	public @Nullable JsonNode remove(@NotNull String file, @NotNull String pointer) {
		if (!exists(file)) return null;

		JsonPointer path = compile(pointer);
		JsonNode parent = document(file).at(path.head());

		return parent instanceof ObjectNode object ? object.remove(path.last().getMatchingProperty()) : null;
	}

	/**
	 * Sets a value in a file, creating the file and the objects on the way as needed and replacing a
	 * value that is already there.
	 *
	 * @param file    path relative to the configuration directory
	 * @param pointer JSON pointer to the value
	 * @param value   the value to set
	 */
	public void put(@NotNull String file, @NotNull String pointer, @NotNull JsonNode value) {
		JsonPointer path = compile(pointer);
		document(file).withObject(path.head()).set(path.last().getMatchingProperty(), value);
	}

	/**
	 * Moves a value to another place, in the same file or in another one. Nothing happens when the
	 * value is not there, as users remove settings they do not need.
	 *
	 * @param file          path of the file holding the value
	 * @param pointer       JSON pointer to the value
	 * @param targetFile    path of the file receiving the value
	 * @param targetPointer JSON pointer to the new place
	 * @return whether there was a value to move
	 */
	public boolean move(
			@NotNull String file,
			@NotNull String pointer,
			@NotNull String targetFile,
			@NotNull String targetPointer
	) {
		JsonNode value = remove(file, pointer);
		if (value == null) return false;

		put(targetFile, targetPointer, value);
		return true;
	}

	/**
	 * Moves a value to another place in its file.
	 *
	 * @param file          path relative to the configuration directory
	 * @param pointer       JSON pointer to the value
	 * @param targetPointer JSON pointer to the new place
	 * @return whether there was a value to move
	 */
	public boolean rename(@NotNull String file, @NotNull String pointer, @NotNull String targetPointer) {
		return move(file, pointer, file, targetPointer);
	}

	/**
	 * Moves a value to a place that is still empty, in the same file or in another one. When the new
	 * place already holds a value, that value is taken as the newer one and stays; the value at the
	 * old place is removed either way. Nothing happens when there is no value to move.
	 *
	 * @param file          path of the file holding the value
	 * @param pointer       JSON pointer to the value
	 * @param targetFile    path of the file receiving the value
	 * @param targetPointer JSON pointer to the new place
	 * @return whether the value now is at the new place; false when there was none to move, or when
	 *         the new place held one and the moved value was dropped
	 */
	public boolean moveIfAbsent(
			@NotNull String file,
			@NotNull String pointer,
			@NotNull String targetFile,
			@NotNull String targetPointer
	) {
		JsonNode value = remove(file, pointer);
		if (value == null) return false;
		if (!at(targetFile, compile(targetPointer)).isMissingNode()) return false;

		put(targetFile, targetPointer, value);
		return true;
	}

	/**
	 * Moves the values of a section into another section, each into a place that is still empty.
	 * Where both sections hold a section under the same key, their values are merged the same way,
	 * down to the last level; anywhere else a value the target already holds stays, and the moved
	 * one is dropped. The moved section is removed, together with the sections above it it leaves
	 * empty. Nothing happens when there is no section to move.
	 * <p>
	 * An empty pointer names the whole document. Moving from it leaves the document's own
	 * {@code _version}, which belongs to the file, where it is, and leaves the document itself; see
	 * {@link #moveFile(String, String)} to move a whole file.
	 *
	 * @param file          path of the file holding the section
	 * @param pointer       JSON pointer to the section, or an empty one for the whole document
	 * @param targetFile    path of the file receiving the values
	 * @param targetPointer JSON pointer to the receiving section, or an empty one for the whole
	 *                      document; it is created when missing
	 * @return whether there was a section to move
	 * @throws IllegalArgumentException if one section lies inside the other
	 */
	public boolean moveSection(
			@NotNull String file,
			@NotNull String pointer,
			@NotNull String targetFile,
			@NotNull String targetPointer
	) {
		JsonPointer from = JsonPointer.compile(pointer);
		JsonPointer to = JsonPointer.compile(targetPointer);
		if (relative(file).equals(relative(targetFile)) && overlap(from, to))
			throw new IllegalArgumentException("Cannot move " + pointer + " into " + targetPointer + " of the same file");

		if (!(at(file, from) instanceof ObjectNode section)) return false;

		JsonNode present = at(targetFile, to);
		if (present.isMissingNode()) {
			ObjectNode values = configura.mapper().createObjectNode();
			merge(section, values, from.matches());
			if (!values.isEmpty()) place(targetFile, to, values);
		} else if (present instanceof ObjectNode receiving) {
			merge(section, receiving, from.matches());
		} else {
			dropValues(section, from.matches());
		}

		removeIfEmpty(file, pointer);
		return true;
	}

	/**
	 * Moves a whole file into another one and deletes it. When the other file does not exist, it is
	 * created with the moved values; when it does, the values are merged into it as
	 * {@link #moveSection(String, String, String, String)} merges sections, so every value it holds
	 * already stays. The moved file's {@code _version} is not carried over. Nothing happens when the
	 * file is not there.
	 *
	 * @param file       path of the file to move
	 * @param targetFile path of the file receiving its values
	 * @return whether there was a file to move
	 * @throws IllegalArgumentException if both paths name the same file
	 */
	public boolean moveFile(@NotNull String file, @NotNull String targetFile) {
		if (relative(file).equals(relative(targetFile)))
			throw new IllegalArgumentException("Cannot move " + file + " into itself");
		if (!exists(file)) return false;

		moveSection(file, "", targetFile, "");
		delete(file);
		return true;
	}

	/**
	 * Removes a section that holds nothing, and then each section above it that it leaves empty.
	 * The document itself always stays, so an empty pointer does nothing. Nothing happens when the
	 * value is not there, is not a section, or still holds something.
	 *
	 * @param file    path relative to the configuration directory
	 * @param pointer JSON pointer to the section
	 * @return whether the section was removed
	 */
	public boolean removeIfEmpty(@NotNull String file, @NotNull String pointer) {
		JsonPointer path = JsonPointer.compile(pointer);
		if (path.matches()) return false;
		if (!(at(file, path) instanceof ObjectNode section) || !section.isEmpty()) return false;

		remove(file, pointer);
		removeIfEmpty(file, path.head().toString());
		return true;
	}

	/**
	 * Takes the {@code _version} out of the top of a file, for files that carry a version without
	 * meaning, such as those an earlier tool stamped. A file that carries the version of a stream
	 * loses it: on this stream's own version file, Strata writes the new version again once the
	 * migration has returned, but the version file of another stream would lose its version for
	 * good, so call this only on files no stream keeps its version in.
	 *
	 * @param file path relative to the configuration directory
	 * @return whether the file had a {@code _version}
	 */
	public boolean removeVersion(@NotNull String file) {
		if (!exists(file)) return false;

		return document(file).remove(StrataFeature.VERSION_KEY) != null;
	}

	/**
	 * Deletes a directory of the configuration directory when nothing is left in it, counting what
	 * this migration created and deleted so far. A directory that holds only empty directories is
	 * empty and is deleted with them. A directory with any other file in it, a document or not, is
	 * left alone.
	 *
	 * @param directory path relative to the configuration directory
	 * @return whether the directory is deleted; false when it is not there or not empty
	 * @throws IllegalArgumentException if the path is the configuration directory itself, lies
	 *                                  outside it, or names a file
	 */
	public boolean deleteDirectoryIfEmpty(@NotNull String directory) {
		Path relative = relative(directory);
		Path path = this.directory.resolve(relative);
		if (Files.exists(path) && !Files.isDirectory(path))
			throw new IllegalArgumentException("Not a directory: " + directory);
		if (!Files.isDirectory(path) || deletedDirectories.contains(relative)) return false;

		boolean created = documents.entrySet().stream()
				.anyMatch(entry -> entry.getKey().startsWith(relative) && entry.getValue().isWritten());
		if (created) return false;

		List<Path> directories = new ArrayList<>();
		try (Stream<Path> tree = Files.walk(path)) {
			for (Path entry : (Iterable<Path>) tree::iterator) {
				Path inside = this.directory.relativize(entry);
				if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) directories.add(inside);
				else if (!isDeleted(inside)) return false;
			}
		} catch (IOException failure) {
			throw new UncheckedIOException("Cannot read " + path, failure);
		}

		deletedDirectories.addAll(directories);
		return true;
	}

	/** Returns what the migration changed: new content by relative path, null for a deleted file. */
	@NotNull Map<Path, byte[]> changes() {
		Map<Path, byte[]> changes = new LinkedHashMap<>();
		documents.forEach((file, document) -> {
			if (document.isUnchanged()) return;

			changes.put(file, document.staged == null ? null : configura.writeNodeBytes(document.staged));
		});

		return changes;
	}

	/**
	 * Returns the directories the migration deleted, relative to the configuration directory and
	 * each before the directory holding it. One a later change wrote a file into again is left out.
	 */
	@NotNull List<Path> deletedDirectories() {
		return deletedDirectories.stream()
				.filter(deleted -> documents.entrySet().stream()
						.noneMatch(entry -> entry.getKey().startsWith(deleted) && entry.getValue().isWritten()))
				.sorted(Comparator.comparingInt(Path::getNameCount).reversed().thenComparing(Comparator.naturalOrder()))
				.toList();
	}

	private Document open(String file) {
		return documents.computeIfAbsent(relative(file), this::load);
	}

	private static Path relative(String path) {
		Path relative = Path.of(path).normalize();
		if (relative.isAbsolute() || relative.startsWith("..") || relative.startsWith(STATE_DIRECTORY) || relative.toString().isEmpty())
			throw new IllegalArgumentException("Not a path inside the configuration directory: " + path);

		return relative;
	}

	private boolean isDeleted(Path relative) {
		Document document = documents.get(relative);
		return document != null && !document.isWritten();
	}

	private JsonNode at(String file, JsonPointer pointer) {
		return exists(file) ? document(file).at(pointer) : MissingNode.getInstance();
	}

	private void place(String file, JsonPointer pointer, ObjectNode values) {
		if (pointer.matches()) document(file).setAll(values);
		else document(file).withObject(pointer.head()).set(pointer.last().getMatchingProperty(), values);
	}

	/** Moves every value of a section into places of another one that are still empty. */
	private static void merge(ObjectNode section, ObjectNode receiving, boolean document) {
		for (String key : keys(section)) {
			if (document && key.equals(StrataFeature.VERSION_KEY)) continue;

			JsonNode value = section.remove(key);
			JsonNode present = receiving.get(key);
			if (present == null) receiving.set(key, value);
			else if (value instanceof ObjectNode inner && present instanceof ObjectNode receivingInner)
				merge(inner, receivingInner, false);
		}
	}

	/** Drops the values of a section whose target place holds a value that is not a section. */
	private static void dropValues(ObjectNode section, boolean document) {
		for (String key : keys(section))
			if (!document || !key.equals(StrataFeature.VERSION_KEY)) section.remove(key);
	}

	private static List<String> keys(ObjectNode section) {
		List<String> keys = new ArrayList<>();
		section.fieldNames().forEachRemaining(keys::add);

		return keys;
	}

	/** Tells whether one pointer names a place inside the other, or the same place. */
	private static boolean overlap(JsonPointer first, JsonPointer second) {
		String a = first.toString();
		String b = second.toString();

		return a.equals(b) || a.isEmpty() || b.isEmpty() || a.startsWith(b + "/") || b.startsWith(a + "/");
	}

	private Document load(Path relative) {
		Path path = directory.resolve(relative);
		if (!Files.exists(path)) return new Document(null);

		JsonNode root;
		try {
			root = configura.mapper().readTree(Files.readAllBytes(path));
		} catch (IOException failure) {
			throw new UncheckedIOException("Cannot read " + path, failure);
		}

		if (root == null || root.isMissingNode() || root.isNull()) return new Document(configura.mapper().createObjectNode());
		if (root instanceof ObjectNode object) return new Document(object);

		throw new IllegalStateException(path + " does not hold named values at its root");
	}

	private static JsonPointer compile(String pointer) {
		JsonPointer path = JsonPointer.compile(pointer);
		if (path.matches()) throw new IllegalArgumentException("The pointer must name a value inside the document");

		return path;
	}

	private static final class Document {
		private final @Nullable ObjectNode original;
		private @Nullable ObjectNode staged;

		private Document(@Nullable ObjectNode original) {
			this.original = original;
			this.staged = original == null ? null : original.deepCopy();
		}

		/** Tells whether the file is there once the migration's changes are written. */
		private boolean isWritten() {
			if (staged == null) return false;

			return original != null || !staged.isEmpty();
		}

		private boolean isUnchanged() {
			if (original == null) return staged == null || staged.isEmpty();

			return original.equals(staged);
		}
	}
}
