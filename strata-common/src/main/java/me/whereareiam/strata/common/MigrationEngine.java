package me.whereareiam.strata.common;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationBaseline;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.exception.MigrationException;
import me.whereareiam.strata.exception.MigrationFailedException;
import me.whereareiam.strata.model.AppliedMigration;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Upgrades one stream: opens its target, works out what is left to run from the version the target
 * has reached, runs it, and wraps whatever goes wrong in a {@link MigrationException} naming the
 * stream.
 */
public final class MigrationEngine {
	private static final String BASELINE_NAME = "baseline";

	/**
	 * Runs every pending migration of a stream, recording each as soon as it has succeeded.
	 *
	 * @param stream stream to upgrade
	 * @param <C>    context of the stream's target
	 * @return the migrations this call ran, in order
	 * @throws MigrationException when the target cannot be opened, its version does not fit this
	 *                            build, or a migration fails
	 */
	public <C> @NotNull List<AppliedMigration> migrate(@NotNull MigrationStream<C> stream) {
		return inSession(stream, this::upgrade);
	}

	/**
	 * Lists what {@link #migrate(MigrationStream)} would run, without running or recording anything.
	 *
	 * @param stream stream to inspect
	 * @param <C>    context of the stream's target
	 * @return pending migrations, in order
	 * @throws MigrationException when the target cannot be opened or its version does not fit this build
	 */
	public <C> @NotNull List<Migration<?>> pending(@NotNull MigrationStream<C> stream) {
		return inSession(stream, this::inspect);
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
