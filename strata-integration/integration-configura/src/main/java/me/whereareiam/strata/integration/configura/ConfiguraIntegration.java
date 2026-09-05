package me.whereareiam.strata.integration.configura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.whereareiam.configura.Configura;
import me.whereareiam.strata.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Coordinates transformations across declared documents with durable staging, original backups and recovery.
 * Use one integration per installation directory. Preparation retains snapshots for restart after database commits.
 */
public final class ConfiguraIntegration implements MigrationIntegration<ConfigContext> {
	private final String id;
	private final Configura configura;
	private final Path directory;
	private final Map<String, ConfigResource> resources;
	private final CommitObserver observer;

	/** Registers documents with a no-op commit observer.
	 * @param id stable resource identity
	 * @param configura configured serializers and hooks
	 * @param directory installation root
	 * @param resources all permitted documents
	 */
	public ConfiguraIntegration(@NotNull String id, @NotNull Configura configura, @NotNull Path directory,
			@NotNull List<ConfigResource> resources) {
		this(id, configura, directory, resources, (index, path) -> { });
	}

	/** Registers documents and a commit progress observer, also useful for fault-injection tests.
	 * @param id stable identity
	 * @param configura configuration library instance
	 * @param directory installation root
	 * @param resources permitted documents
	 * @param observer callback after each replacement
	 */
	public ConfiguraIntegration(@NotNull String id, @NotNull Configura configura, @NotNull Path directory,
			@NotNull List<ConfigResource> resources, @NotNull CommitObserver observer) {
		this.id = id;
		this.configura = configura;
		this.directory = directory.toAbsolutePath().normalize();
		this.observer = observer;
		Map<String, ConfigResource> registered = new LinkedHashMap<>();
		Set<Path> paths = new HashSet<>();
		for (ConfigResource resource : resources)
			if (registered.putIfAbsent(resource.getName(), resource) != null || !paths.add(resource.getPath()))
				throw new IllegalArgumentException("Duplicate config resource " + resource.getName());
		this.resources = Collections.unmodifiableMap(registered);
	}

	@Override public @NotNull String id() { return id; }
	@Override public @NotNull MigrationSession<ConfigContext> open(boolean writable) throws Exception { return new Session(writable); }

	/** Reads a durably prepared input, or its committed copy after config publication.
	 * Call this during a later migration action after Strata has prepared all streams.
	 * @param stream stream that captured the input
	 * @param key captured input name
	 * @return detached captured value
	 * @throws Exception if preparation has not captured the input or its bytes are corrupt
	 */
	public @NotNull JsonNode input(@NotNull String stream, @NotNull String key) throws Exception {
		DocumentJournal journal = new DocumentJournal(directory.toRealPath(), directory.resolve(".strata/configura"), observer);
		Path path = inputPath(stream, key);
		Path prepared = journal.prepared(stream);
		byte[] bytes = Files.exists(prepared) ? journal.outputs(prepared).get(path) : DocumentJournal.read(journal.safe(path));
		if (bytes == null) throw new IllegalStateException("Missing prepared input " + stream + "/" + key);
		return configura.mapper().readTree(bytes);
	}
	private Path inputPath(String stream, String key) {
		if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IllegalArgumentException("Invalid input name");
		return Path.of(".strata/configura/inputs", DocumentJournal.digest(stream.getBytes(java.nio.charset.StandardCharsets.UTF_8)), key);
	}

	/** Observes completed replacements without changing resource contents. */
	@FunctionalInterface
	public interface CommitObserver {
		/** Receives progress after one durable replacement.
		 * @param index zero-based replacement index
		 * @param path replaced file
		 * @throws Exception to stop the commit; reopening recovers it
		 */
		void afterReplacement(int index, @NotNull Path path) throws Exception;
	}

	private final class Session implements MigrationSession<ConfigContext> {
		private final boolean writable;
		private final DocumentJournal journal;
		private final ConfigContext context;
		private final Map<Path, byte[]> expected = new LinkedHashMap<>();
		private FileChannel channel;
		private FileLock lock;

		Session(boolean writable) throws Exception {
			this.writable = writable;
			if (writable) Files.createDirectories(directory);
			Path root = Files.exists(directory) ? directory.toRealPath() : directory;
			journal = new DocumentJournal(root, root.resolve(".strata/configura"), observer);
			Map<String, ObjectNode> documents = new LinkedHashMap<>();
			Set<String> absent = new HashSet<>();
			try {
				Path state = journal.safe(Path.of(".strata/configura"));
				if (writable) {
					Files.createDirectories(state);
					channel = FileChannel.open(journal.safe(Path.of(".strata/configura.lock")), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
					try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { throw new IllegalStateException("Configuration migration is already running", busy); }
					if (lock == null) throw new IllegalStateException("Configuration migration is already running");
					journal.recover();
				} else if (Files.exists(state.resolve("committing"))) throw new IllegalStateException("Interrupted config commit requires recovery before inspection");
				for (ConfigResource resource : resources.values()) {
					byte[] bytes = DocumentJournal.read(journal.safe(resource.getPath()));
					if (bytes == null) absent.add(resource.getName());
					expected.put(resource.getPath(), bytes);
					documents.put(resource.getName(), object(bytes));
				}
				context = new ConfigContext(configura, resources, documents, absent);
			} catch (Exception failure) {
				try { close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
				throw failure;
			}
		}

		private ObjectNode object(byte[] bytes) throws Exception {
			if (bytes == null) return configura.mapper().createObjectNode();
			JsonNode value = configura.mapper().readTree(bytes);
			if (!(value instanceof ObjectNode object)) throw new IllegalStateException("Configuration root must be an object");
			return object;
		}
		private Path historyPath(String stream) { return Path.of(".strata/configura/history", DocumentJournal.digest(stream.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
		@Override public @NotNull ConfigContext context() { return context; }
		@Override public @Nullable MigrationHistory history(@NotNull String stream) throws Exception {
			byte[] bytes = DocumentJournal.read(journal.safe(historyPath(stream)));
			return bytes == null ? null : HistoryCodec.decode(bytes);
		}

		@Override public @NotNull PreparedMigration prepare(@NotNull String stream, @NotNull MigrationHistory target,
				@NotNull List<Migration<ConfigContext>> pending) throws Exception {
			if (!writable) throw new IllegalStateException("Read-only session");
			Path stage = journal.prepared(stream);
			Path history = historyPath(stream);
			Map<Path, byte[]> after;
			if (Files.exists(stage)) {
				after = journal.outputs(stage);
				Set<Path> declared = new HashSet<>();
				for (ConfigResource resource : resources.values()) declared.add(resource.getPath());
				Path inputs = inputPath(stream, "input").getParent();
				for (Path path : after.keySet())
					if (!declared.contains(path) && !path.equals(history) && !java.util.Objects.equals(path.getParent(), inputs))
						throw new IllegalStateException("Prepared resource is no longer declared: " + path);
				if (!Arrays.equals(after.get(history), HistoryCodec.encode(target)))
					throw new IllegalStateException("Prepared config migration differs from this build: " + stream);
				for (ConfigResource resource : resources.values())
					if (after.containsKey(resource.getPath())) {
						byte[] bytes = after.get(resource.getPath());
						context.restore(resource.getName(), object(bytes), bytes == null);
					}
			} else {
				context.inputs().clear();
				for (Migration<ConfigContext> migration : pending) migration.getAction().apply(context);
				after = new LinkedHashMap<>();
				for (ConfigResource resource : resources.values()) {
					if (!resource.isWritable()) { after.put(resource.getPath(), expected.get(resource.getPath())); continue; }
					byte[] bytes = null;
					if (!context.deleted().contains(resource.getName())) {
						ObjectNode document;
						if (!context.exists(resource.getName()) && resource.isRequired()) { after.put(resource.getPath(), null); continue; }
						document = context.read(resource.getName());
						resource.getValidator().accept(document);
						bytes = configura.writeNodeBytes(document);
						byte[] original = expected.get(resource.getPath());
						if (document.equals(object(original))) bytes = original;
						object(bytes); // Verify the serialized output before any publication.
					}
					after.put(resource.getPath(), bytes);
				}
				for (var input : context.inputs().entrySet())
					after.put(inputPath(stream, input.getKey()), configura.writeNodeBytes(input.getValue()));
				after.put(history, HistoryCodec.encode(target));
				Map<Path, byte[]> before = new LinkedHashMap<>(expected);
				before.put(history, DocumentJournal.read(journal.safe(history)));
				for (Path path : after.keySet())
					if (!before.containsKey(path)) before.put(path, DocumentJournal.read(journal.safe(path)));
				journal.stage(stage, before, after);
			}
			expected.putAll(after);
			return () -> journal.commit(stage);
		}

		@Override public void close() throws Exception {
			try { if (lock != null) lock.close(); }
			finally { if (channel != null) channel.close(); }
		}
	}
}
