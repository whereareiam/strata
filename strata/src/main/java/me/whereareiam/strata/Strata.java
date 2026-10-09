package me.whereareiam.strata;

import me.whereareiam.strata.common.MigrationEngine;
import me.whereareiam.strata.exception.MigrationException;
import me.whereareiam.strata.model.AppliedMigration;
import me.whereareiam.strata.model.MigrationReport;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings an installation up to date: runs, stream after stream, every migration it has not seen.
 * <p>
 * Call {@link #migrate()} once during startup, before the application reads its configuration or
 * touches its tables.
 *
 * <pre>{@code
 * MigrationReport report = new Strata(List.of(database, configuration)).migrate();
 * }</pre>
 */
public final class Strata {
	private final MigrationEngine engine = new MigrationEngine();
	private final List<MigrationStream<?>> streams;

	/**
	 * Registers the streams of an application.
	 *
	 * @param streams streams in the order they are upgraded; when one stream's migration reads what
	 *                another stream removes, the reading stream comes first
	 * @throws IllegalArgumentException if two streams share an id
	 */
	public Strata(@NotNull List<? extends MigrationStream<?>> streams) {
		Set<String> ids = new HashSet<>();
		for (MigrationStream<?> stream : streams)
			if (!ids.add(stream.getId())) throw new IllegalArgumentException("Duplicate stream " + stream.getId());

		this.streams = List.copyOf(streams);
	}

	/**
	 * Runs every pending migration. A stream is finished before the next one starts, and each
	 * migration is recorded as soon as it has succeeded, so a failed run continues where it stopped
	 * the next time.
	 *
	 * @return the migrations this call ran
	 * @throws MigrationException when a target cannot be opened, its version does not fit this
	 *                            build, or a migration fails
	 */
	public @NotNull MigrationReport migrate() {
		Map<String, List<AppliedMigration>> applied = new LinkedHashMap<>();
		for (MigrationStream<?> stream : streams) {
			List<AppliedMigration> entries = engine.migrate(stream);
			if (!entries.isEmpty()) applied.put(stream.getId(), entries);
		}

		return new MigrationReport(applied);
	}

	/**
	 * Lists what {@link #migrate()} would run, without running or recording anything.
	 *
	 * @return pending migrations by stream id, in upgrade order; up-to-date streams are left out
	 * @throws MigrationException when a target cannot be opened or its version does not fit this build
	 */
	public @NotNull Map<String, List<Migration<?>>> pending() {
		Map<String, List<Migration<?>>> pending = new LinkedHashMap<>();
		for (MigrationStream<?> stream : streams) {
			List<Migration<?>> migrations = engine.pending(stream);
			if (!migrations.isEmpty()) pending.put(stream.getId(), migrations);
		}

		return pending;
	}
}
