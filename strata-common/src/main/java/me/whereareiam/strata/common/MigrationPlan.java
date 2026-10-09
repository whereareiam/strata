package me.whereareiam.strata.common;

import lombok.Getter;
import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.exception.MigrationVersionException;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Compares what a stream declares with the version an installation has reached, and keeps what is
 * left to run.
 */
@Getter
final class MigrationPlan<C> {
	private final @NotNull List<Migration<? super C>> pending;

	MigrationPlan(@NotNull MigrationStream<C> stream, int current) {
		verifyReachable(stream, current);

		this.pending = stream.getMigrations().stream()
				.filter(migration -> migration.getVersion() > current)
				.toList();
	}

	private static void verifyReachable(MigrationStream<?> stream, int current) {
		int latest = stream.getLatestVersion();
		if (current > latest)
			throw new MigrationVersionException(
					stream.getId(),
					stream.getId() + " is at version " + current + ", but this build only knows version " + latest
							+ "; it was written by a newer build"
			);

		if (stream.getMigrations().isEmpty()) return;

		int oldest = stream.getMigrations().get(0).getVersion();
		if (current < oldest - 1)
			throw new MigrationVersionException(
					stream.getId(),
					stream.getId() + " is at version " + current + ", but this build starts at migration " + oldest
							+ "; upgrade through an older build first"
			);
	}
}
