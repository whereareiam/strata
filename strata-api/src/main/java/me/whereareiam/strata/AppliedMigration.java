package me.whereareiam.strata;

import lombok.Value;
import org.jetbrains.annotations.NotNull;

/** Immutable history entry. Persist it in the same transaction or recoverable commit as the resource change. */
@Value
public class AppliedMigration {
	/** Stable applied migration identity. */
	@NotNull String id;
	/** Committed source version. */
	int fromVersion;
	/** Committed target version. */
	int toVersion;
	/** Implementation revision recorded at execution. */
	@NotNull String fingerprint;

	/** Captures a declaration for durable history.
	 * @param migration successfully applied declaration
	 * @return immutable entry
	 */
	public static @NotNull AppliedMigration of(@NotNull Migration<?> migration) {
		return new AppliedMigration(migration.getId(), migration.getFromVersion(), migration.getToVersion(), migration.getFingerprint());
	}
}
