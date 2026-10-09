package me.whereareiam.strata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationTest {
	@Test
	void keepsItsDeclaration() {
		Migration<Object> migration = new Migration<>(3, "split-routing", context -> {});

		assertEquals(3, migration.getVersion());
		assertEquals("split-routing", migration.getName());
	}

	@Test
	void rejectsVersionsBelowOne() {
		assertThrows(IllegalArgumentException.class, () -> new Migration<>(0, "initial", context -> {}));
	}

	@Test
	void rejectsNamesThatCannotBeStored() {
		assertThrows(IllegalArgumentException.class, () -> new Migration<>(1, "", context -> {}));
		assertThrows(IllegalArgumentException.class, () -> new Migration<>(1, "tab\tseparated", context -> {}));
		assertThrows(IllegalArgumentException.class, () -> new Migration<>(1, "x".repeat(101), context -> {}));
	}
}
