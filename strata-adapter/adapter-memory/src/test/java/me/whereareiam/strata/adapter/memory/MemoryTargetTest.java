package me.whereareiam.strata.adapter.memory;

import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.model.AppliedMigration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTargetTest {
	private final MemoryTarget<List<String>> target = new MemoryTarget<>(new ArrayList<>());
	private final AppliedMigration entry = new AppliedMigration(1, "first", Instant.EPOCH);

	@Test
	void runsActionsOnItsStateAndRemembersThem() throws Exception {
		try (MigrationSession<List<String>> session = target.open()) {
			assertEquals(0, session.version("plugin/data"));

			session.apply("plugin/data", state -> state.add("changed"), entry);

			assertEquals(1, session.version("plugin/data"));
		}

		assertEquals(List.of("changed"), target.getState());
		assertEquals(List.of(entry), target.history("plugin/data"));
		assertTrue(target.history("plugin/other").isEmpty());
	}

	@Test
	void doesNotRememberFailedActions() throws Exception {
		try (MigrationSession<List<String>> session = target.open()) {
			assertThrows(IllegalStateException.class, () -> session.apply("plugin/data", state -> {
				throw new IllegalStateException("broken");
			}, entry));
		}

		assertTrue(target.history("plugin/data").isEmpty());
	}
}
