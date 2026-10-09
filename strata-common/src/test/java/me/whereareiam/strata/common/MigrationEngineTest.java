package me.whereareiam.strata.common;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.MigrationTarget;
import me.whereareiam.strata.exception.MigrationException;
import me.whereareiam.strata.exception.MigrationFailedException;
import me.whereareiam.strata.model.AppliedMigration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationEngineTest {
	private final MigrationEngine engine = new MigrationEngine();
	private final RecordingTarget target = new RecordingTarget();

	@Test
	void runsWhatIsPendingAndReturnsIt() {
		List<AppliedMigration> applied = engine.migrate(stream(target, 2).build());

		assertEquals(List.of("1", "2"), target.state);
		assertEquals(List.of(1, 2), applied.stream().map(AppliedMigration::getVersion).toList());
		assertTrue(engine.migrate(stream(target, 2).build()).isEmpty());
	}

	@Test
	void recordsTheBaselineWhenMigratingButNotWhenInspecting() {
		MigrationStream<List<String>> stream = stream(target, 3)
				.baseline((state, latest) -> 2)
				.build();

		assertEquals(List.of(3), engine.pending(stream).stream().map(Migration::getVersion).toList());
		assertTrue(target.applied.isEmpty());

		engine.migrate(stream);

		assertEquals(List.of("3"), target.state);
		assertEquals(List.of(2, 3), target.applied.stream().map(AppliedMigration::getVersion).toList());
	}

	@Test
	void namesTheMigrationThatFailed() {
		MigrationStream<List<String>> stream = stream(target, 1)
				.migration(2, "broken", state -> {
					throw new IllegalStateException("broken");
				})
				.build();

		MigrationFailedException failure = assertThrows(MigrationFailedException.class, () -> engine.migrate(stream));

		assertEquals(2, failure.getVersion());
		assertEquals(List.of("1"), target.state);
	}

	@Test
	void namesTheStreamWhoseTargetCannotBeOpened() {
		MigrationTarget<List<String>> unreachable = () -> {
			throw new IllegalStateException("unreachable");
		};

		MigrationException failure = assertThrows(MigrationException.class, () -> engine.migrate(stream(unreachable, 1).build()));

		assertEquals("plugin/data", failure.getStream());
		assertInstanceOf(IllegalStateException.class, failure.getCause());
	}

	@Test
	void leavesTheTargetOfAStreamWithoutMigrationsClosed() {
		MigrationTarget<List<String>> unreachable = () -> {
			throw new IllegalStateException("unreachable");
		};

		assertTrue(engine.migrate(stream(unreachable, 0).build()).isEmpty());
		assertTrue(engine.pending(stream(unreachable, 0).build()).isEmpty());
	}

	private static MigrationStream.Builder<List<String>> stream(MigrationTarget<List<String>> target, int latest) {
		MigrationStream.Builder<List<String>> builder = MigrationStream.<List<String>>builder()
				.id("plugin/data")
				.target(target);
		for (int version = 1; version <= latest; version++) {
			String mark = Integer.toString(version);
			builder.migration(version, "migration-" + version, state -> state.add(mark));
		}

		return builder;
	}
}
