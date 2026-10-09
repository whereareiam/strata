package me.whereareiam.strata.model;

import lombok.Value;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * A migration an installation has behind it.
 */
@Value
public class AppliedMigration {
	/** Version the stream reached with this entry. */
	int version;

	/** Name the migration had when it was applied. */
	@NotNull String name;

	/** When the entry was recorded, to the millisecond. */
	@NotNull Instant appliedAt;
}
