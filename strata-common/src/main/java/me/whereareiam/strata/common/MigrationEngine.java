package me.whereareiam.strata.common;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationAction;
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
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Upgrades one stream: opens its target, works out what is left to run from the version the target
 * has reached, runs it, and wraps whatever goes wrong in a {@link MigrationException} naming the
 * stream.
 */
public final class MigrationEngine {
	private static final String BASELINE_NAME = "baseline";
	private static final String CREATION_NAME = "creation";

	/**
	 * Runs every pending migration of a stream, recording each as soon as it has succeeded. On a new
	 * installation it runs the stream's creation action instead and records the latest version.
	 *
	 * @param stream stream to upgrade
	 * @param <C>    context of the stream's target
	 * @return the migrations this call ran, in order, or the creation entry on a new installation
	 * @throws MigrationException when the target cannot be opened, its version does not fit this
	 *                            build, or a migration or the creation fails
	 */
	public <C> @NotNull List<AppliedMigration> migrate(@NotNull MigrationStream<C> stream) {
		return inSession(stream, this::upgrade);
	}

	/**
	 * Lists what {@link #migrate(MigrationStream)} would run, without running or recording anything.
	 *
	 * @param stream stream to inspect
	 * @param <C>    context of the stream's target
	 * @return pending migrations, in order; none on a new installation, whose creation is not a migration
	 * @throws MigrationException when the target cannot be opened or its version does not fit this build
	 */
	public <C> @NotNull List<Migration<?>> pending(@NotNull MigrationStream<C> stream) {
		return inSession(stream, this::inspect);
	}

	private <C> List<AppliedMigration> upgrade(MigrationStream<C> stream, MigrationSession<C> session) throws Exception {
		OptionalInt recorded = session.version(stream.getId());
		if (recorded.isPresent()) return runPending(stream, session, recorded.getAsInt());

		OptionalInt found = inspectInstallation(stream, session);
		if (found.isEmpty()) return install(stream, session);

		int version = found.getAsInt();
		if (version > 0) session.apply(stream.getId(), context -> {}, entry(version, BASELINE_NAME));

		return runPending(stream, session, version);
	}

	private <C> List<Migration<?>> inspect(MigrationStream<C> stream, MigrationSession<C> session) throws Exception {
		OptionalInt recorded = session.version(stream.getId());
		OptionalInt start = recorded.isPresent() ? recorded : inspectInstallation(stream, session);
		if (start.isEmpty()) return List.of();

		return List.copyOf(new MigrationPlan<>(stream, start.getAsInt()).getPending());
	}

	/** Asks the baseline where an installation without history stands; empty means it is new. */
	private <C> OptionalInt inspectInstallation(MigrationStream<C> stream, MigrationSession<C> session) throws Exception {
		MigrationBaseline<? super C> baseline = stream.getBaseline();
		if (baseline == null) return stream.getCreation() == null ? OptionalInt.of(0) : OptionalInt.empty();

		int latest = stream.getLatestVersion();
		OptionalInt version = Objects.requireNonNull(baseline.version(session.context(), latest), "baseline answer");
		if (version.isPresent() && (version.getAsInt() < 0 || version.getAsInt() > latest))
			throw new IllegalStateException("Baseline of " + stream.getId() + " answered " + version.getAsInt() + ", outside 0.." + latest);

		return version;
	}

	/** Records a new installation at the latest version, building it first when the stream can. */
	private <C> List<AppliedMigration> install(MigrationStream<C> stream, MigrationSession<C> session) throws Exception {
		int latest = stream.getLatestVersion();
		MigrationAction<? super C> creation = stream.getCreation();
		if (creation == null) {
			session.apply(stream.getId(), context -> {}, entry(latest, BASELINE_NAME));
			return List.of();
		}

		AppliedMigration entry = entry(latest, CREATION_NAME);
		run(stream, session, creation, entry);

		return List.of(entry);
	}

	private <C> List<AppliedMigration> runPending(MigrationStream<C> stream, MigrationSession<C> session, int current) {
		List<AppliedMigration> applied = new ArrayList<>();
		for (Migration<? super C> migration : new MigrationPlan<>(stream, current).getPending()) {
			AppliedMigration entry = entry(migration.getVersion(), migration.getName());
			run(stream, session, migration.getAction(), entry);
			applied.add(entry);
		}

		return applied;
	}

	private <C> void run(
			MigrationStream<C> stream,
			MigrationSession<C> session,
			MigrationAction<? super C> action,
			AppliedMigration entry
	) {
		try {
			session.apply(stream.getId(), action, entry);
		} catch (Exception failure) {
			preserveInterrupt(failure);
			throw new MigrationFailedException(stream.getId(), entry.getVersion(), entry.getName(), failure);
		}
	}

	private <C, R> List<R> inSession(MigrationStream<C> stream, SessionWork<C, R> work) {
		if (stream.getMigrations().isEmpty() && stream.getCreation() == null) return List.of();

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
