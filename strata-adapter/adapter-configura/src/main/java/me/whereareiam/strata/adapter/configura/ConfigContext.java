package me.whereareiam.strata.adapter.configura;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import me.whereareiam.configura.Configura;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a configuration migration works with: the files of the configuration directory as document
 * trees. Changes are staged in memory and reach the disk together once the migration has returned.
 * <p>
 * Files are named by their path relative to the directory, extension included. Values inside a
 * file are addressed with JSON pointers such as {@code /connection/routing}.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class ConfigContext {
	static final String STATE_DIRECTORY = ".strata";

	private final Configura configura;
	private final Path directory;
	private final Map<Path, Document> documents = new LinkedHashMap<>();

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

	/** Returns what the migration changed: new content by relative path, null for a deleted file. */
	@NotNull Map<Path, byte[]> changes() {
		Map<Path, byte[]> changes = new LinkedHashMap<>();
		documents.forEach((file, document) -> {
			if (document.isUnchanged()) return;

			changes.put(file, document.staged == null ? null : configura.writeNodeBytes(document.staged));
		});

		return changes;
	}

	private Document open(String file) {
		Path relative = Path.of(file).normalize();
		if (relative.isAbsolute() || relative.startsWith("..") || relative.startsWith(STATE_DIRECTORY) || relative.toString().isEmpty())
			throw new IllegalArgumentException("Not a file of the configuration directory: " + file);

		return documents.computeIfAbsent(relative, this::load);
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

		private boolean isUnchanged() {
			if (original == null) return staged == null || staged.isEmpty();

			return original.equals(staged);
		}
	}
}
