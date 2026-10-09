package me.whereareiam.strata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationStreamTest {
	private static final MigrationTarget<Object> TARGET = () -> {
		throw new UnsupportedOperationException();
	};

	@Test
	void ordersMigrationsByVersion() {
		MigrationStream<Object> stream = stream("plugin/config")
				.migration(2, "second", context -> {})
				.migrations(List.of(new Migration<>(3, "third", context -> {})))
				.migration(1, "first", context -> {})
				.build();

		assertEquals(List.of("first", "second", "third"), stream.getMigrations().stream().map(Migration::getName).toList());
		assertEquals(3, stream.getLatestVersion());
	}

	@Test
	void mayStartAboveVersionOne() {
		MigrationStream<Object> stream = stream("plugin/config")
				.migration(4, "fourth", context -> {})
				.migration(5, "fifth", context -> {})
				.build();

		assertEquals(5, stream.getLatestVersion());
	}

	@Test
	void isAtVersionZeroWithoutMigrations() {
		assertEquals(0, stream("plugin/config").build().getLatestVersion());
	}

	@Test
	void rejectsRepeatedVersions() {
		MigrationStream.Builder<Object> builder = stream("plugin/config")
				.migration(1, "first", context -> {})
				.migration(1, "again", context -> {});

		assertThrows(IllegalArgumentException.class, builder::build);
	}

	@Test
	void rejectsGaps() {
		MigrationStream.Builder<Object> builder = stream("plugin/config")
				.migration(1, "first", context -> {})
				.migration(3, "third", context -> {});

		assertThrows(IllegalArgumentException.class, builder::build);
	}

	@Test
	void rejectsIdsThatCannotBeStored() {
		for (String id : List.of("", "Plugin/Config", "plugin//config", "/plugin", "plugin.config", "x".repeat(101)))
			assertThrows(IllegalArgumentException.class, () -> stream(id).build(), id);
	}

	private static MigrationStream.Builder<Object> stream(String id) {
		return MigrationStream.builder().id(id).target(TARGET);
	}
}
