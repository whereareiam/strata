package me.whereareiam.strata.exception;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * A migration threw. Earlier migrations of the run stay applied; this one and all later ones did
 * not take effect, except for changes the technology cannot take back.
 */
@Getter
public class MigrationFailedException extends MigrationException {
	/** Version the failed migration would have reached. */
	private final int version;

	/** Name of the failed migration. */
	private final @NotNull String name;

	/**
	 * Creates the failure.
	 *
	 * @param stream  id of the stream
	 * @param version version the migration would have reached
	 * @param name    name of the migration
	 * @param cause   what the migration threw
	 */
	public MigrationFailedException(@NotNull String stream, int version, @NotNull String name, @NotNull Throwable cause) {
		super(stream, "Migration " + version + " (" + name + ") of " + stream + " failed", cause);
		this.version = version;
		this.name = name;
	}
}
