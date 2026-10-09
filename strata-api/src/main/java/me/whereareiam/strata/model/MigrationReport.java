package me.whereareiam.strata.model;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one upgrade run changed.
 */
@Getter
public final class MigrationReport {
	/** Migrations run by this upgrade, by stream id in the order the streams were upgraded. */
	private final @NotNull Map<String, List<AppliedMigration>> applied;

	/**
	 * Creates a report.
	 *
	 * @param applied migrations run, by stream id; streams without changes are left out
	 */
	public MigrationReport(@NotNull Map<String, List<AppliedMigration>> applied) {
		Map<String, List<AppliedMigration>> copy = new LinkedHashMap<>();
		applied.forEach((stream, entries) -> copy.put(stream, List.copyOf(entries)));

		this.applied = Collections.unmodifiableMap(copy);
	}

	/**
	 * Tells whether the installation was already up to date.
	 *
	 * @return true when no migration ran
	 */
	public boolean isEmpty() {
		return applied.isEmpty();
	}
}
