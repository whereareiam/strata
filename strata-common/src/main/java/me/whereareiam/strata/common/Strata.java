package me.whereareiam.strata.common;

import me.whereareiam.strata.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/** Plans installation upgrades before normal configuration binding or schema initialization. */
public final class Strata {
	private final List<MigrationStream<?>> streams;
	private final Map<String, String> environment;
	private final List<UpgradeVerification> verifications;

	/** Creates an installation coordinator.
	 * @param streams explicitly registered plugin, feature and platform contributions
	 * @param environment immutable platform attributes
	 */
	public Strata(@NotNull List<MigrationStream<?>> streams, @NotNull Map<String, String> environment) {
		this(streams, environment, List.of());
	}

	/** Creates a coordinator with final installation checks.
	 * @param streams registered contributions
	 * @param environment platform attributes
	 * @param verifications checks repeated after every successful execution
	 */
	public Strata(@NotNull List<MigrationStream<?>> streams, @NotNull Map<String, String> environment,
			@NotNull List<UpgradeVerification> verifications) {
		this.verifications = List.copyOf(verifications);
		this.streams = List.copyOf(streams);
		this.environment = Map.copyOf(environment);
	}

	/** Inspects authoritative history and dependencies without transforming or writing resources.
	 * @return ordered descriptions of pending work
	 * @throws Exception when history or declarations are incompatible
	 */
	public @NotNull List<String> inspect() throws Exception {
		try (PreparedUpgrade upgrade = plan(false)) {
			return upgrade.getPending();
		}
	}

	/** Acquires leases and stages supported transformations; SQL executes only during commit.
	 * Close the result if abandoning preparation. A process crash before commit leaves original resources intact.
	 * @return a single-use prepared upgrade, retaining its leases until closed
	 * @throws Exception when detection, recovery or preparation fails
	 */
	public @NotNull PreparedUpgrade prepare() throws Exception {
		return plan(true);
	}

	/** Recovers interrupted integration commits, prepares pending work, and commits in dependency order.
	 * @throws Exception when the installation cannot safely start
	 */
	public void execute() throws Exception {
		try (PreparedUpgrade upgrade = prepare()) {
			upgrade.commit();
		}
	}

	private PreparedUpgrade plan(boolean writable) throws Exception {
		List<MigrationStream<?>> ordered = order();
		Map<String, MigrationIntegration<?>> integrations = new TreeMap<>();
		for (MigrationStream<?> stream : ordered) {
			MigrationIntegration<?> integration = stream.getIntegration();
			MigrationIntegration<?> previous = integrations.putIfAbsent(integration.id(), integration);
			if (previous != null && previous != integration)
				throw new IllegalArgumentException("Reuse one integration instance for resource " + integration.id());
		}
		Map<String, MigrationSession<?>> sessions = new LinkedHashMap<>();
		try {
			for (var integration : integrations.entrySet())
				sessions.put(integration.getKey(), integration.getValue().open(writable));
			List<String> pending = new ArrayList<>();
			List<PreparedMigration> commits = new ArrayList<>();
			// Validate every stream before invoking any transformation.
			List<Resolved<?>> resolved = new ArrayList<>();
			for (MigrationStream<?> stream : ordered)
				resolved.add(resolve(stream, sessions.get(stream.getIntegration().id())));
			for (Resolved<?> transition : resolved)
				prepare(transition, writable, pending, commits);
			if (writable)
				for (UpgradeVerification verification : verifications) commits.add(verification::verify);
			return new PreparedUpgrade(pending, commits, new ArrayList<>(sessions.values()));
		} catch (Exception | Error failure) {
			List<MigrationSession<?>> opened = new ArrayList<>(sessions.values());
			Collections.reverse(opened);
			for (MigrationSession<?> session : opened)
				try { session.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
			throw failure;
		}
	}

	@SuppressWarnings("unchecked")
	private <C> Resolved<C> resolve(MigrationStream<C> stream, MigrationSession<?> rawSession) throws Exception {
		MigrationSession<C> session = (MigrationSession<C>) rawSession;
		MigrationHistory history = session.history(stream.getId());
		boolean adopting = history == null;
		if (adopting) history = new MigrationHistory(stream.getDetector().detect(session.context()), List.of());
		if (history.version() > stream.getCurrentVersion())
			throw new IllegalStateException("Unsupported future layout: " + stream.getId());
		Map<Integer, Migration<C>> bySource = new HashMap<>();
		for (Migration<C> migration : stream.getMigrations()) bySource.put(migration.getFromVersion(), migration);
		for (AppliedMigration entry : history.getApplied()) {
			Migration<C> declaration = bySource.get(entry.getFromVersion());
			if (declaration == null || !AppliedMigration.of(declaration).equals(entry))
				throw new IllegalStateException("Missing or changed migration: " + stream.getId() + "/" + entry.getId());
		}
		List<Migration<C>> pending = new ArrayList<>();
		List<AppliedMigration> applied = new ArrayList<>(history.getApplied());
		int cursor = history.version();
		while (cursor < stream.getCurrentVersion()) {
			Migration<C> migration = bySource.get(cursor);
			if (migration == null) throw new IllegalStateException("Missing transition from " + stream.getId() + ":" + cursor);
			pending.add(migration);
			applied.add(AppliedMigration.of(migration));
			cursor = migration.getToVersion();
		}
		return new Resolved<>(stream, session, new MigrationHistory(history.getBaseline(), applied), pending, adopting);
	}

	private <C> void prepare(Resolved<C> resolved, boolean writable, List<String> descriptions,
			List<PreparedMigration> commits) throws Exception {
		for (Migration<C> migration : resolved.pending())
			descriptions.add(resolved.stream().getId() + ": " + migration.getFromVersion() + " -> " + migration.getToVersion() + " (" + migration.getId() + ")");
		if (resolved.adopting()) descriptions.add(resolved.stream().getId() + ": adopt baseline " + resolved.target().getBaseline());
		if (writable && (resolved.adopting() || !resolved.pending().isEmpty()))
			commits.add(resolved.session().prepare(resolved.stream().getId(), resolved.target(), resolved.pending()));
	}

	private List<MigrationStream<?>> order() {
		Map<String, MigrationStream<?>> active = new TreeMap<>();
		Set<String> declared = new HashSet<>();
		for (MigrationStream<?> stream : streams) {
			if (!declared.add(stream.getId())) throw new IllegalArgumentException("Duplicate stream " + stream.getId());
			if (stream.getAppliesTo().test(environment)) active.put(stream.getId(), stream);
		}
		List<MigrationStream<?>> ordered = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		for (MigrationStream<?> stream : active.values()) visit(stream, active, visited, new HashSet<>(), ordered);
		return ordered;
	}

	private void visit(MigrationStream<?> stream, Map<String, MigrationStream<?>> active, Set<String> visited,
			Set<String> visiting, List<MigrationStream<?>> ordered) {
		if (visited.contains(stream.getId())) return;
		if (!visiting.add(stream.getId())) throw new IllegalArgumentException("Cyclic dependency at " + stream.getId());
		for (var dependency : new TreeMap<>(stream.getRequires()).entrySet()) {
			MigrationStream<?> required = active.get(dependency.getKey());
			if (required == null || required.getCurrentVersion() < dependency.getValue())
				throw new IllegalArgumentException("Unavailable prerequisite " + dependency.getKey() + " for " + stream.getId());
			visit(required, active, visited, visiting, ordered);
		}
		visiting.remove(stream.getId());
		visited.add(stream.getId());
		ordered.add(stream);
	}

	private record Resolved<C>(MigrationStream<C> stream, MigrationSession<C> session,
			MigrationHistory target, List<Migration<C>> pending, boolean adopting) { }
}
