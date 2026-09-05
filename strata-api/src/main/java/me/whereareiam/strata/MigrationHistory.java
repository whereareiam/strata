package me.whereareiam.strata;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** Verified adoption baseline followed by every applied transition. */
@Getter
public final class MigrationHistory {
	/** Verified source version at adoption. */
	private final int baseline;
	/** Contiguous transitions committed after adoption. */
	private final @NotNull List<AppliedMigration> applied;

	/** Creates validated contiguous history.
	 * @param baseline verified version before Strata adoption
	 * @param applied transitions after adoption
	 */
	public MigrationHistory(int baseline, @NotNull List<AppliedMigration> applied) {
		if (baseline < 0) throw new IllegalArgumentException("Negative baseline");
		int cursor = baseline;
		for (AppliedMigration entry : applied) {
			if (entry.getFromVersion() != cursor || entry.getToVersion() <= cursor)
				throw new IllegalArgumentException("Broken persisted migration history");
			cursor = entry.getToVersion();
		}
		this.baseline = baseline;
		this.applied = List.copyOf(applied);
	}

	/** Returns the latest committed version.
	 * @return layout version
	 */
	public int version() {
		return applied.isEmpty() ? baseline : applied.get(applied.size() - 1).getToVersion();
	}
}
