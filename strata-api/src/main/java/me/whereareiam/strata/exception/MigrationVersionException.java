package me.whereareiam.strata.exception;

import org.jetbrains.annotations.NotNull;

/**
 * The version of an installation does not fit the migrations this build declares: the installation
 * was written by a newer build, or is older than the oldest migration the build still ships.
 * Nothing was changed.
 */
public class MigrationVersionException extends MigrationException {
	/**
	 * Creates the failure.
	 *
	 * @param stream  id of the stream
	 * @param message how the installation and the declaration differ
	 */
	public MigrationVersionException(@NotNull String stream, @NotNull String message) {
		super(stream, message, null);
	}
}
