package me.whereareiam.strata.adapter.configura;

import com.fasterxml.jackson.databind.node.ObjectNode;
import me.whereareiam.configura.Configura;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationTarget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A directory of configuration files in the format of a {@link Configura} instance, migrated by
 * one stream.
 * <p>
 * The version the stream has reached is written into one of the files, as {@code _version} at its
 * top, so it stays with the configuration when a user copies or restores it. {@link StrataFeature}
 * keeps Configura from dropping it when the application rewrites that file.
 * <p>
 * All files a migration changes are replaced together; if the process dies halfway, the next start
 * completes the replacement. The directory's {@code .strata} folder holds only working state and
 * {@code backup/<stream>/v<version>/}, every file as it was before the migration to that version
 * changed it. Deleting the folder loses nothing Strata needs.
 */
public final class ConfiguraTarget implements MigrationTarget<ConfigContext> {
	private final Configura configura;
	private final Path directory;
	private final String versionFile;

	private @Nullable String stream;

	/**
	 * Uses a configuration directory.
	 *
	 * @param configura   reads and writes the files; its format must match their extension, and it
	 *                    must have a {@link StrataFeature}
	 * @param directory   directory the file paths of migrations are relative to; created when missing
	 * @param versionFile path, relative to the directory, of the file that carries the version, such
	 *                    as {@code settings.yml}; it is created when a version is recorded and it is
	 *                    missing
	 * @throws IllegalArgumentException if the Configura instance would drop the version
	 */
	public ConfiguraTarget(@NotNull Configura configura, @NotNull Path directory, @NotNull String versionFile) {
		if (!configura.reservedKeys().contains(StrataFeature.VERSION_KEY))
			throw new IllegalArgumentException("Register a StrataFeature on the Configura instance, or it drops the version from " + versionFile);

		this.configura = configura;
		this.directory = directory.toAbsolutePath().normalize();
		this.versionFile = versionFile;
	}

	/**
	 * Reads a file as it is on disk right now, for a migration of another target that needs a value
	 * from the configuration.
	 *
	 * @param file path relative to the configuration directory
	 * @return a detached copy of the document, empty when the file does not exist
	 */
	public @NotNull ObjectNode read(@NotNull String file) {
		return new ConfigContext(configura, directory).document(file);
	}

	@Override
	public @NotNull MigrationSession<ConfigContext> open() throws IOException {
		return new ConfigSession(this, configura, directory, versionFile);
	}

	/** A file holds one version, so a second stream on the same target would overwrite the first one's. */
	void claim(String stream) {
		if (this.stream == null) this.stream = stream;
		if (!this.stream.equals(stream))
			throw new IllegalStateException(
					versionFile + " already carries the version of " + this.stream + "; give " + stream + " a target of its own"
			);
	}
}
