package me.whereareiam.strata.integration.configura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.whereareiam.configura.Configura;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/** A staged document workspace. Only explicitly registered writable resources are persisted. */
public final class ConfigContext {
	private final Configura configura;
	private final Map<String, ConfigResource> resources;
	private final Map<String, ObjectNode> documents;
	private final Map<String, JsonNode> inputs = new LinkedHashMap<>();
	private final Set<String> absent;
	private final Set<String> deleted = new HashSet<>();

	ConfigContext(Configura configura, Map<String, ConfigResource> resources, Map<String, ObjectNode> documents, Set<String> absent) {
		this.absent = absent;
		this.configura = configura;
		this.resources = resources;
		this.documents = documents;
	}

	/** Opens a declared writable document for arbitrary Jackson transformations.
	 * @param name declared resource name
	 * @return mutable staged object tree
	 */
	public @NotNull ObjectNode document(@NotNull String name) {
		ConfigResource resource = require(name);
		if (resource.isRequired() && absent.contains(name)) throw new IllegalStateException("Required configuration missing: " + name);
		if (!resource.isWritable()) throw new IllegalArgumentException("Read-only resource: " + name);
		if (deleted.contains(name)) throw new IllegalStateException("Deleted document: " + name);
		return documents.get(name);
	}

	/** Reads a detached tree, including read-only resources.
	 * @param name resource name
	 * @return detached tree
	 */
	public @NotNull ObjectNode read(@NotNull String name) { ConfigResource resource = require(name);
		if (resource.isRequired() && absent.contains(name)) throw new IllegalStateException("Required configuration missing: " + name);
		return documents.get(name).deepCopy(); }

	/** Marks an entire document for deletion in the recoverable commit.
	 * @param name writable document name
	 */
	public void delete(@NotNull String name) { document(name); deleted.add(name); }

	/** Copies a value between declared documents. JSON pointers address object members; arrays remain whole values.
	 * @param source source document
	 * @param sourcePointer required value pointer
	 * @param destination destination document
	 * @param destinationPointer object-member destination pointer
	 * @param policy explicit conflict policy
	 */
	public void copy(@NotNull String source, @NotNull String sourcePointer, @NotNull String destination,
			@NotNull String destinationPointer, @NotNull ConflictPolicy policy) {
		JsonNode value = read(source).at(sourcePointer);
		if (value.isMissingNode()) throw new IllegalStateException("Missing source " + source + sourcePointer);
		ObjectNode target = document(destination);
		JsonNode existing = target.at(destinationPointer);
		if (!existing.isMissingNode() && !existing.equals(value)) {
			if (policy == ConflictPolicy.FAIL_IF_DIFFERENT) throw new IllegalStateException("Conflicting destination " + destination + destinationPointer);
			if (policy == ConflictPolicy.PRESERVE_DESTINATION) return;
		}
		parent(target, destinationPointer, true).set(leaf(destinationPointer), value.deepCopy());
	}

	/** Moves a value between documents, removing the source only from the staged workspace.
	 * @param source source document
	 * @param sourcePointer required source pointer
	 * @param destination destination document
	 * @param destinationPointer destination pointer
	 * @param policy explicit conflict behavior
	 */
	public void move(@NotNull String source, @NotNull String sourcePointer, @NotNull String destination,
			@NotNull String destinationPointer, @NotNull ConflictPolicy policy) {
		document(source);
		if (source.equals(destination) && (sourcePointer.equals(destinationPointer)
				|| destinationPointer.startsWith(sourcePointer + "/") || sourcePointer.startsWith(destinationPointer + "/")))
			throw new IllegalArgumentException("Move pointers must not overlap");
		copy(source, sourcePointer, destination, destinationPointer, policy);
		parent(document(source), sourcePointer, false).remove(leaf(sourcePointer));
	}

	/** Renames or moves an object member inside one document.
	 * @param document resource name
	 * @param source source pointer
	 * @param destination destination pointer
	 * @param policy conflict behavior
	 */
	public void rename(@NotNull String document, @NotNull String source, @NotNull String destination, @NotNull ConflictPolicy policy) {
		move(document, source, document, destination, policy);
	}

	/** Merges the members of a source object into a destination object using explicit conflict handling.
	 * @param source source resource
	 * @param sourcePointer object to merge
	 * @param destination destination resource
	 * @param destinationPointer target object pointer
	 * @param policy conflict behavior for each member
	 */
	public void merge(@NotNull String source, @NotNull String sourcePointer, @NotNull String destination,
			@NotNull String destinationPointer, @NotNull ConflictPolicy policy) {
		JsonNode value = read(source).at(sourcePointer);
		if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("Merge source must be an object");
		for (String field : object.properties().stream().map(Map.Entry::getKey).toList()) {
			String escaped = field.replace("~", "~0").replace("/", "~1");
			copy(source, sourcePointer + "/" + escaped, destination, destinationPointer + "/" + escaped, policy);
		}
	}

	/** Reads an existing integer version marker for explicit legacy detection. Never assumes a missing version.
	 * @param resource declared document
	 * @param pointer version field pointer
	 * @return verified non-negative integer
	 */
	public int version(@NotNull String resource, @NotNull String pointer) {
		JsonNode value = read(resource).at(pointer);
		if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
			throw new IllegalStateException("Unknown document version at " + resource + pointer);
		return value.intValue();
	}

	/** Reports original document presence for legacy detection.
	 * @param name declared resource
	 * @return whether the document exists in the staged installation
	 */
	public boolean exists(@NotNull String name) { require(name); return !absent.contains(name) && !deleted.contains(name); }

	/** Applies Configura defaults and binding hooks to a staged document without writing it.
	 * @param name writable resource name
	 * @param type current configuration model
	 * @param <T> model type
	 */
	public <T> void defaults(@NotNull String name, @NotNull Class<T> type) {
		ObjectNode prepared = configura.prepareNode(document(name), type);
		documents.put(name, prepared);
	}

	/** Captures a stable value for a later database phase. The integration persists it during preparation.
	 * @param key stable input name within the current stream
	 * @param value value to retain across process restarts
	 */
	public void capture(@NotNull String key, @NotNull JsonNode value) {
		if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IllegalArgumentException("Invalid input name");
		inputs.put(key, value.deepCopy());
	}
	Map<String, JsonNode> inputs() { return inputs; }

	private ConfigResource require(String name) {
		ConfigResource resource = resources.get(name);
		if (resource == null) throw new IllegalArgumentException("Undeclared resource " + name);
		return resource;
	}
	private static String leaf(String pointer) {
		if (!pointer.startsWith("/") || pointer.endsWith("/")) throw new IllegalArgumentException("Expected a nonempty object-member JSON pointer");
		return pointer.substring(pointer.lastIndexOf('/') + 1).replace("~1", "/").replace("~0", "~");
	}
	private ObjectNode parent(ObjectNode root, String pointer, boolean create) {
		leaf(pointer);
		String parent = pointer.substring(0, pointer.lastIndexOf('/'));
		ObjectNode cursor = root;
		if (parent.isEmpty()) return cursor;
		for (String part : parent.substring(1).split("/")) {
			String key = part.replace("~1", "/").replace("~0", "~");
			JsonNode child = cursor.get(key);
			if (child == null && create) child = cursor.putObject(key);
			if (!(child instanceof ObjectNode object)) throw new IllegalArgumentException("Pointer parent is not an object: " + pointer);
			cursor = object;
		}
		return cursor;
	}
	Set<String> deleted() { return deleted; }
	void restore(String name, ObjectNode node, boolean delete) {
		documents.put(name, node);
		if (delete) absent.add(name); else absent.remove(name);
		if (delete) deleted.add(name); else deleted.remove(name);
	}
}
