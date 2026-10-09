package me.whereareiam.strata.common;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationBaseline;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.exception.MigrationException;
import me.whereareiam.strata.exception.MigrationFailedException;
import me.whereareiam.strata.model.AppliedMigration;
import me.whereareiam.strata.model.MigrationReport;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
	private static final String BASELINE_NAME = "baseline";

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
			List<AppliedMigration> entries = inSession(stream, this::upgrade);
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
			List<Migration<?>> migrations = inSession(stream, this::inspect);
			if (!migrations.isEmpty()) pending.put(stream.getId(), migrations);
		}

		return pending;
	}

	private <C> List<AppliedMigration> upgrade(MigrationStream<C> stream, MigrationSession<C> session) throws Exception {
		List<AppliedMigration> applied = new ArrayList<>();
		for (Migration<? super C> migration : plan(stream, session, true).getPending()) {
			AppliedMigration entry = entry(migration.getVersion(), migration.getName());
			run(stream, session, migration, entry);
			applied.add(entry);
		}

		return applied;
	}

	private <C> List<Migration<?>> inspect(MigrationStream<C> stream, MigrationSession<C> session) throws Exception {
		return List.copyOf(plan(stream, session, false).getPending());
	}

	private <C> MigrationPlan<C> plan(
			MigrationStream<C> stream,
			MigrationSession<C> session,
			boolean recordBaseline
	) throws Exception {
		int recorded = session.version(stream.getId());
		MigrationBaseline<? super C> baseline = stream.getBaseline();
		if (recorded > 0 || baseline == null) return new MigrationPlan<>(stream, recorded);

		int latest = stream.getLatestVersion();
		int version = baseline.version(session.context(), latest);
		if (version < 0 || version > latest)
			throw new IllegalStateException("Baseline of " + stream.getId() + " answered " + version + ", outside 0.." + latest);

		if (version > 0 && recordBaseline) session.apply(stream.getId(), context -> {}, entry(version, BASELINE_NAME));

		return new MigrationPlan<>(stream, version);
	}

	private <C> void run(
			MigrationStream<C> stream,
			MigrationSession<C> session,
			Migration<? super C> migration,
			AppliedMigration entry
	) {
		try {
			session.apply(stream.getId(), migration.getAction(), entry);
		} catch (Exception failure) {
			preserveInterrupt(failure);
			throw new MigrationFailedException(stream.getId(), migration.getVersion(), migration.getName(), failure);
		}
	}

	private <C, R> List<R> inSession(MigrationStream<C> stream, SessionWork<C, R> work) {
		if (stream.getMigrations().isEmpty()) return List.of();

		try (MigrationSession<C> session = stream.getTarget().open()) {
			return work.perform(stream, session);
		} catch (MigrationException failure) {
			throw failure;
		} catch (Exception failure) {
			preserveInterrupt(failure);
			throw new MigrationException(stream.getId(), "Could not upgrade " + stream.getId(), failure);
		}
	}

	private static AppliedMigration entry(int version, String name) {
		return new AppliedMigration(version, name, Instant.now().truncatedTo(ChronoUnit.MILLIS));
	}

	private static void preserveInterrupt(Exception failure) {
		if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
	}

	@FunctionalInterface
	private interface SessionWork<C, R> {
		List<R> perform(MigrationStream<C> stream, MigrationSession<C> session) throws Exception;
	}
}
