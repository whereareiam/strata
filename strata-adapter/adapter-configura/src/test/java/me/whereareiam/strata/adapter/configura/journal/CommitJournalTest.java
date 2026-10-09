package me.whereareiam.strata.adapter.configura.journal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CommitJournalTest {
	@TempDir
	Path root;

	private Path state;
	private CommitJournal journal;

	@BeforeEach
	void createJournal() throws IOException {
		state = Files.createDirectories(root.resolve(".strata"));
		journal = new CommitJournal(root, state);
	}

	@Test
	void writesReplacesAndDeletesFiles() throws IOException {
		Files.writeString(root.resolve("settings.yml"), "old");
		Files.writeString(root.resolve("legacy.yml"), "old");
		Map<Path, byte[]> changes = new LinkedHashMap<>();
		changes.put(Path.of("settings.yml"), bytes("new"));
		changes.put(Path.of("features", "routing.yml"), bytes("created"));
		changes.put(Path.of("legacy.yml"), null);

		journal.commit(changes);

		assertEquals("new", Files.readString(root.resolve("settings.yml")));
		assertEquals("created", Files.readString(root.resolve("features/routing.yml")));
		assertFalse(Files.exists(root.resolve("legacy.yml")));
		assertEquals(List.of(), leftovers());
	}

	@Test
	void finishesACommitInterruptedAfterItTookEffect() throws IOException {
		Files.writeString(root.resolve("settings.yml"), "old");
		Files.writeString(root.resolve("legacy.yml"), "old");
		Files.createDirectories(state.resolve("journal/write/features"));
		Files.writeString(state.resolve("journal/write/settings.yml"), "new");
		Files.writeString(state.resolve("journal/write/features/routing.yml"), "created");
		Files.writeString(state.resolve("journal/delete"), "legacy.yml");

		journal.recover();

		assertEquals("new", Files.readString(root.resolve("settings.yml")));
		assertEquals("created", Files.readString(root.resolve("features/routing.yml")));
		assertFalse(Files.exists(root.resolve("legacy.yml")));
		assertEquals(List.of(), leftovers());
	}

	@Test
	void discardsACommitInterruptedBeforeItTookEffect() throws IOException {
		Files.writeString(root.resolve("settings.yml"), "old");
		Files.createDirectories(state.resolve("journal.preparing/write"));
		Files.writeString(state.resolve("journal.preparing/write/settings.yml"), "new");

		journal.recover();

		assertEquals("old", Files.readString(root.resolve("settings.yml")));
		assertEquals(List.of(), leftovers());
	}

	@Test
	void doesNotReplayACommitInterruptedWhileCleaningUp() throws IOException {
		Files.writeString(root.resolve("settings.yml"), "edited since");
		Files.createDirectories(state.resolve("journal.finished/write"));
		Files.writeString(state.resolve("journal.finished/write/settings.yml"), "new");

		journal.recover();

		assertEquals("edited since", Files.readString(root.resolve("settings.yml")));
		assertEquals(List.of(), leftovers());
	}

	private List<Path> leftovers() throws IOException {
		try (Stream<Path> entries = Files.list(state)) {
			return entries.toList();
		}
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
