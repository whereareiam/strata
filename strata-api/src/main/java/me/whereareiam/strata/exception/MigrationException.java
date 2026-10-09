package me.whereareiam.strata.exception;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An upgrade could not be completed. The application must not start on this installation.
 */
@Getter
public class MigrationException extends RuntimeException {
	/** Id of the stream the upgrade stopped at. */
	private final @NotNull String stream;

	/**
	 * Creates the failure.
	 *
	 * @param stream  id of the stream the upgrade stopped at
	 * @param message what went wrong
	 * @param cause   underlying failure, if any
	 */
	public MigrationException(@NotNull String stream, @NotNull String message, @Nullable Throwable cause) {
		super(message, cause);
		this.stream = stream;
	}
}
