package me.whereareiam.strata.common;

import me.whereareiam.strata.*;
import me.whereareiam.strata.testkit.MemoryIntegration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StrataTest {
	private final MemoryIntegration<List<String>> integration = new MemoryIntegration<>("memory", new ArrayList<>(), ArrayList::new);
	private Migration<List<String>> migration(int from, int to, String fingerprint) {
		return new Migration<>("step-" + to, from, to, fingerprint, list -> list.add("v" + to));
	}
	private MigrationStream<List<String>> stream(String id, int target, List<Migration<List<String>>> migrations, Map<String, Integer> dependencies) {
		return new MigrationStream<>(id, integration, target, migrations, dependencies, list -> 0, null);
	}
	private Strata strata(MigrationStream<?>... streams) { return new Strata(List.of(streams), Map.of("platform", "velocity")); }

	@Test void preparesWithoutPublishingAndRunsOnce() throws Exception {
		var runner = strata(stream("config", 2, List.of(migration(1, 2, "b"), migration(0, 1, "a")), Map.of()));
		assertEquals(3, runner.inspect().size());
		assertTrue(integration.snapshot().isEmpty());
		try (var abandoned = runner.prepare()) { assertTrue(integration.snapshot().isEmpty()); }
		runner.execute(); runner.execute();
		assertEquals(List.of("v1", "v2"), integration.snapshot());
		assertTrue(runner.inspect().isEmpty());
	}
	@Test void checksHistoryBeforeAnyNewAction() throws Exception {
		strata(stream("config", 1, List.of(migration(0, 1, "a")), Map.of())).execute();
		assertThrows(IllegalStateException.class, () -> strata(stream("config", 2,
				List.of(migration(0, 1, "changed"), migration(1, 2, "b")), Map.of())).execute());
		assertEquals(List.of("v1"), integration.snapshot());
	}
	@Test void rejectsMissingHistoryDeclarationsAndDowngrades() throws Exception {
		strata(stream("config", 1, List.of(migration(0, 1, "a")), Map.of())).execute();
		assertThrows(IllegalStateException.class, () -> strata(stream("config", 1, List.of(), Map.of())).execute());
		assertThrows(IllegalStateException.class, () -> strata(stream("config", 0, List.of(), Map.of())).execute());
	}
	@Test void rejectsGapsAndDuplicateDeclarations() {
		assertThrows(IllegalStateException.class, () -> strata(stream("config", 2, List.of(migration(1, 2, "b")), Map.of())).prepare());
		assertThrows(IllegalArgumentException.class, () -> stream("config", 2, List.of(migration(0, 1, "a"), migration(0, 2, "b")), Map.of()));
	}
	@Test void resolvesDependenciesAndRejectsCycles() throws Exception {
		var first = stream("first", 1, List.of(new Migration<>("first", 0, 1, "1", list -> list.add("first"))), Map.of());
		var second = stream("second", 1, List.of(new Migration<>("second", 0, 1, "1", list -> {
			assertEquals(List.of("first"), list); list.add("second");
		})), Map.of("first", 1));
		strata(second, first).execute();
		assertEquals(List.of("first", "second"), integration.snapshot());
		assertThrows(IllegalArgumentException.class, () -> strata(stream("a", 0, List.of(), Map.of("b", 0)),
				stream("b", 0, List.of(), Map.of("a", 0))).inspect());
	}
	@Test void excludesPlatformsWithoutConsumingTheirHistory() throws Exception {
		var bungee = new MigrationStream<>("bungee", integration, 1, List.of(migration(0, 1, "a")), Map.of(), list -> 0,
				env -> env.get("platform").equals("bungeecord"));
		strata(bungee).execute();
		assertTrue(integration.snapshot().isEmpty());
		new Strata(List.of(bungee), Map.of("platform", "bungeecord")).execute();
		assertEquals(List.of("v1"), integration.snapshot());
	}
	@Test void missingPlatformDependencyFailsBeforeOpeningResources() {
		assertThrows(IllegalArgumentException.class, () -> strata(stream("velocity", 0, List.of(), Map.of("missing", 1))).inspect());
	}
	@Test void unknownLegacyLayoutDoesNotWriteHistory() throws Exception {
		var unknown = new MigrationStream<>("legacy", integration, 1, List.of(migration(0, 1, "a")), Map.of(), list -> {
			throw new IllegalStateException("Ambiguous layout");
		}, null);
		assertThrows(IllegalStateException.class, () -> strata(unknown).execute());
		try (var session = integration.open(false)) { assertNull(session.history("legacy")); }
	}
	@Test void adoptsVerifiedLegacyBaselineWithoutReplayingIt() throws Exception {
		var legacy = new MigrationStream<>("legacy", integration, 3, List.of(migration(2, 3, "3")), Map.of(), list -> 2, null);
		strata(legacy).execute();
		assertEquals(List.of("v3"), integration.snapshot());
		try (var session = integration.open(false)) { assertEquals(2, session.history("legacy").getBaseline()); }
	}
	@Test void failedPreparationDoesNotPublishEarlierStreams() {
		var fail = new Migration<List<String>>("fail", 0, 1, "1", list -> { throw new IllegalStateException("injected"); });
		assertThrows(IllegalStateException.class, () -> strata(stream("a", 1, List.of(migration(0, 1, "a")), Map.of()),
				stream("b", 1, List.of(fail), Map.of())).execute());
		assertTrue(integration.snapshot().isEmpty());
	}
	@Test void preparedPlanIsSingleUse() throws Exception {
		try (var prepared = strata(stream("a", 1, List.of(migration(0, 1, "a")), Map.of())).prepare()) {
			prepared.commit();
			assertThrows(IllegalStateException.class, prepared::commit);
		}
	}
	@Test void historyRoundTripsAndRejectsCorruption() {
		var history = new MigrationHistory(3, List.of(new AppliedMigration("tab\tline\n", 3, 5, "abc")));
		assertEquals(history.getApplied(), HistoryCodec.decode(HistoryCodec.encode(history)).getApplied());
		assertThrows(IllegalArgumentException.class, () -> HistoryCodec.decode("garbage".getBytes()));
		assertThrows(IllegalArgumentException.class, () -> new MigrationHistory(3, List.of(new AppliedMigration("bad", 1, 2, "x"))));
	}
	@Test void finalVerificationBlocksStartupAndRepeatsWithoutReplayingMigrations() throws Exception {
		var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var runner = new Strata(List.of(stream("config", 1, List.of(migration(0, 1, "1")), Map.of())), Map.of(),
				List.of(() -> { calls.incrementAndGet(); if (fail.get()) throw new IllegalStateException("invalid installation"); }));
		assertThrows(IllegalStateException.class, runner::execute);
		assertEquals(List.of("v1"), integration.snapshot());
		fail.set(false); runner.execute();
		assertEquals(2, calls.get());
		assertEquals(List.of("v1"), integration.snapshot());
	}

}
