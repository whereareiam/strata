package me.whereareiam.strata.common;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.adapter.memory.MemoryTarget;
import me.whereareiam.strata.exception.MigrationVersionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationPlanTest {
	@Test
	void everythingIsPendingAtVersionZero() {
		assertEquals(List.of(1, 2, 3), pending(stream(1, 3), 0));
	}

	@Test
	void onlyNewerVersionsArePending() {
		assertEquals(List.of(3), pending(stream(1, 3), 2));
		assertTrue(pending(stream(1, 3), 3).isEmpty());
	}

	@Test
	void rejectsAVersionFromANewerBuild() {
		MigrationVersionException failure = assertThrows(MigrationVersionException.class, () -> pending(stream(1, 3), 4));

		assertEquals("plugin/data", failure.getStream());
	}

	@Test
	void rejectsAVersionOlderThanTheOldestMigration() {
		assertThrows(MigrationVersionException.class, () -> pending(stream(4, 6), 2));
		assertEquals(List.of(4, 5, 6), pending(stream(4, 6), 3));
	}

	@Test
	void acceptsAnyVersionOfAStreamWithoutMigrationsExceptANewerOne() {
		assertTrue(pending(stream(1, 0), 0).isEmpty());
		assertThrows(MigrationVersionException.class, () -> pending(stream(1, 0), 1));
	}

	private static List<Integer> pending(MigrationStream<Object> stream, int current) {
		return new MigrationPlan<>(stream, current).getPending().stream()
				.map(Migration::getVersion)
				.toList();
	}

	private static MigrationStream<Object> stream(int first, int last) {
		MigrationStream.Builder<Object> builder = MigrationStream.builder()
				.id("plugin/data")
				.target(new MemoryTarget<>(new Object()));
		for (int version = first; version <= last; version++)
			builder.migration(version, "migration-" + version, context -> {});

		return builder.build();
	}
}
