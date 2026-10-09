package me.whereareiam.strata.adapter.configura;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.whereareiam.configura.Configura;
import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.adapter.configura.journal.CommitJournal;
import me.whereareiam.strata.model.AppliedMigration;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

/**
 * A locked configuration directory. Every migration's changes are backed up, then committed
 * together with the new version through the journal.
 */
final class ConfigSession implements MigrationSession<ConfigContext> {
	private static final String LOCK_FILE = "lock";
	private static final String BACKUP_DIRECTORY = "backup";

	private final ConfiguraTarget target;
	private final Configura configura;
	private final Path directory;
	private final String versionFile;
	private final Path state;
	private final CommitJournal journal;
	private final FileChannel lock;

	ConfigSession(ConfiguraTarget target, Configura configura, Path directory, String versionFile) throws IOException {
		this.target = target;
		this.configura = configura;
		this.directory = directory;
		this.versionFile = versionFile;
		this.state = directory.resolve(ConfigContext.STATE_DIRECTORY);
		this.journal = new CommitJournal(directory, state);

		Files.createDirectories(state);
		this.lock = lock();
		recover();
	}

	@Override
	public @NotNull ConfigContext context() {
		return new ConfigContext(configura, directory);
	}

	@Override
	public int version(@NotNull String stream) {
		target.claim(stream);

		ConfigContext context = context();
		return context.exists(versionFile) ? context.document(versionFile).path(StrataFeature.VERSION_KEY).asInt(0) : 0;
	}

	@Override
	public void apply(
			@NotNull String stream,
			@NotNull MigrationAction<? super ConfigContext> action,
			@NotNull AppliedMigration entry
	) throws Exception {
		target.claim(stream);

		ConfigContext context = context();
		action.apply(context);
		stamp(context.document(versionFile), entry.getVersion());

		Map<Path, byte[]> changes = context.changes();
		backUp(stream, entry.getVersion(), changes.keySet());
		journal.commit(changes);
	}

	@Override
	public void close() throws IOException {
		lock.close();
	}

	/** Writes the version as the first value of the document, where a reader of the file sees it. */
	private static void stamp(ObjectNode document, int version) {
		ObjectNode values = document.deepCopy();
		values.remove(StrataFeature.VERSION_KEY);

		document.removeAll();
		document.put(StrataFeature.VERSION_KEY, version);
		document.setAll(values);
	}

	private FileChannel lock() throws IOException {
		FileChannel channel = FileChannel.open(state.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		try {
			if (channel.tryLock() == null) throw new OverlappingFileLockException();

			return channel;
		} catch (OverlappingFileLockException held) {
			channel.close();
			throw new IllegalStateException("Another instance is migrating " + directory, held);
		} catch (IOException failure) {
			try (channel) {
				throw failure;
			}
		}
	}

	private void recover() throws IOException {
		try {
			journal.recover();
		} catch (IOException failure) {
			try (FileChannel held = lock) {
				throw failure;
			}
		}
	}

	/** Keeps the files as they were before a migration, for a user who wants them back. */
	private void backUp(String stream, int version, Set<Path> files) throws IOException {
		Path backup = state.resolve(BACKUP_DIRECTORY).resolve(stream.replace('/', '.')).resolve("v" + version);
		for (Path file : files) {
			Path original = directory.resolve(file);
			if (!Files.exists(original)) continue;

			Path copy = backup.resolve(file);
			Files.createDirectories(copy.getParent());
			Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		}
	}
}
