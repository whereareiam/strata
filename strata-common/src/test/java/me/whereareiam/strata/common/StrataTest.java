package me.whereareiam.strata.common;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.adapter.memory.MemoryTarget;
import me.whereareiam.strata.exception.MigrationException;
import me.whereareiam.strata.exception.MigrationFailedException;
import me.whereareiam.strata.exception.MigrationVersionException;
import me.whereareiam.strata.model.AppliedMigration;
import me.whereareiam.strata.model.MigrationReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrataTest {
	private final MemoryTarget<List<String>> target = new MemoryTarget<>(new ArrayList<>());

	@Test
	void runsPendingMigrationsInVersionOrder() {
		MigrationReport report = new Strata(List.of(stream("plugin/data", 2).build())).migrate();

		assertEquals(List.of("plugin/data 1", "plugin/data 2"), target.getState());
		assertEquals(List.of(1, 2), versions(report.getApplied().get("plugin/data")));
		assertEquals(List.of(1, 2), versions(target.history("plugin/data")));
	}

	@Test
	void runsNothingTwice() {
		new Strata(List.of(stream("plugin/data", 2).build())).migrate();
		MigrationReport report = new Strata(List.of(stream("plugin/data", 3).build())).migrate();

		assertEquals(List.of("plugin/data 1", "plugin/data 2", "plugin/data 3"), target.getState());
		assertEquals(List.of(3), versions(report.getApplied().get("plugin/data")));
		assertTrue(new Strata(List.of(stream("plugin/data", 3).build())).migrate().isEmpty());
	}

	@Test
	void upgradesStreamsInRegistrationOrder() {
		new Strata(List.of(stream("plugin/second", 1).build(), stream("plugin/first", 1).build())).migrate();

		assertEquals(List.of("plugin/second 1", "plugin/first 1"), target.getState());
	}

	@Test
	void stampsAnInstallationTheBaselineCallsCurrent() {
		MigrationStream<List<String>> stream = stream("plugin/data", 3)
				.baseline((state, latest) -> latest)
				.build();

		assertTrue(new Strata(List.of(stream)).migrate().isEmpty());
		assertTrue(target.getState().isEmpty());
		assertEquals(List.of(3), versions(target.history("plugin/data")));
	}

	@Test
	void continuesFromTheVersionTheBaselineFinds() {
		MigrationStream<List<String>> stream = stream("plugin/data", 3)
				.baseline((state, latest) -> 1)
				.build();

		new Strata(List.of(stream)).migrate();

		assertEquals(List.of("plugin/data 2", "plugin/data 3"), target.getState());
		assertEquals(List.of(1, 2, 3), versions(target.history("plugin/data")));
	}

	@Test
	void asksTheBaselineOnlyWithoutHistory() {
		new Strata(List.of(stream("plugin/data", 1).build())).migrate();
		MigrationStream<List<String>> stream = stream("plugin/data", 2)
				.baseline((state, latest) -> latest)
				.build();

		new Strata(List.of(stream)).migrate();

		assertEquals(List.of("plugin/data 1", "plugin/data 2"), target.getState());
	}

	@Test
	void rejectsABaselineOutsideTheDeclaredVersions() {
		MigrationStream<List<String>> stream = stream("plugin/data", 2)
				.baseline((state, latest) -> latest + 1)
				.build();

		MigrationException failure = assertThrows(MigrationException.class, () -> new Strata(List.of(stream)).migrate());
		assertInstanceOf(IllegalStateException.class, failure.getCause());
	}

	@Test
	void stopsAtAFailedMigrationAndKeepsTheEarlierOnes() {
		MigrationStream<List<String>> stream = stream("plugin/data", 1)
				.migration(2, "broken", state -> {
					throw new IllegalStateException("broken");
				})
				.migration(3, "unreached", state -> state.add("unreached"))
				.build();

		MigrationFailedException failure = assertThrows(MigrationFailedException.class, () -> new Strata(List.of(stream)).migrate());

		assertEquals("plugin/data", failure.getStream());
		assertEquals(2, failure.getVersion());
		assertEquals("broken", failure.getName());
		assertInstanceOf(IllegalStateException.class, failure.getCause());
		assertEquals(List.of("plugin/data 1"), target.getState());
		assertEquals(List.of(1), versions(target.history("plugin/data")));
	}

	@Test
	void refusesAnInstallationWrittenByANewerBuild() {
		new Strata(List.of(stream("plugin/data", 3).build())).migrate();

		assertThrows(MigrationVersionException.class, () -> new Strata(List.of(stream("plugin/data", 2).build())).migrate());
	}

	@Test
	void listsPendingMigrationsWithoutRunningThem() {
		new Strata(List.of(stream("plugin/data", 1).build())).migrate();
		MigrationStream<List<String>> fresh = stream("plugin/fresh", 2)
				.baseline((state, latest) -> latest)
				.build();

		Map<String, List<Migration<?>>> pending = new Strata(List.of(stream("plugin/data", 3).build(), fresh)).pending();

		assertEquals(List.of("plugin/data"), List.copyOf(pending.keySet()));
		assertEquals(List.of(2, 3), pending.get("plugin/data").stream().map(Migration::getVersion).toList());
		assertEquals(List.of("plugin/data 1"), target.getState());
		assertTrue(target.history("plugin/fresh").isEmpty());
	}

	@Test
	void rejectsStreamsSharingAnId() {
		List<MigrationStream<?>> streams = List.of(stream("plugin/data", 1).build(), stream("plugin/data", 2).build());

		assertThrows(IllegalArgumentException.class, () -> new Strata(streams));
	}

	private MigrationStream.Builder<List<String>> stream(String id, int latest) {
		MigrationStream.Builder<List<String>> builder = MigrationStream.<List<String>>builder()
				.id(id)
				.target(target);
		for (int version = 1; version <= latest; version++) {
			String mark = id + " " + version;
			builder.migration(version, "migration-" + version, state -> state.add(mark));
		}

		return builder;
	}

	private static List<Integer> versions(List<AppliedMigration> entries) {
		return entries.stream()
				.map(AppliedMigration::getVersion)
				.toList();
	}
}
