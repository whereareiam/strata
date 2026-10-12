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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

		journal.commit(changes, List.of());

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

	@Test
	void deletesEmptyDirectoriesAfterTheFilesInThem() throws IOException {
		Files.createDirectories(root.resolve("providers/capabilities/recognition"));
		Files.writeString(root.resolve("providers/capabilities/recognition/settings.yml"), "old");
		Map<Path, byte[]> changes = new LinkedHashMap<>();
		changes.put(Path.of("features", "recognition", "settings.yml"), bytes("moved"));
		changes.put(Path.of("providers", "capabilities", "recognition", "settings.yml"), null);

		journal.commit(changes, List.of(Path.of("providers", "capabilities", "recognition"), Path.of("providers", "capabilities")));

		assertEquals("moved", Files.readString(root.resolve("features/recognition/settings.yml")));
		assertFalse(Files.exists(root.resolve("providers/capabilities")));
		assertTrue(Files.isDirectory(root.resolve("providers")));
		assertEquals(List.of(), leftovers());
	}

	@Test
	void keepsADirectoryThatIsNoLongerEmptyOrAlreadyGone() throws IOException {
		Files.createDirectories(root.resolve("legacy"));
		Files.writeString(root.resolve("legacy/notes.txt"), "kept");

		journal.commit(Map.of(), List.of(Path.of("legacy"), Path.of("missing")));

		assertEquals("kept", Files.readString(root.resolve("legacy/notes.txt")));
		assertFalse(Files.exists(root.resolve("missing")));
		assertEquals(List.of(), leftovers());
	}

	@Test
	void finishesDeletingDirectoriesOfACommitInterruptedAfterItTookEffect() throws IOException {
		Files.createDirectories(root.resolve("verification"));
		Files.writeString(root.resolve("verification/settings.yml"), "old");
		Files.createDirectories(state.resolve("journal/write/features/verification"));
		Files.writeString(state.resolve("journal/write/features/verification/settings.yml"), "moved");
		Files.writeString(state.resolve("journal/delete"), "verification/settings.yml");
		Files.writeString(state.resolve("journal/delete-directory"), "verification");

		journal.recover();

		assertEquals("moved", Files.readString(root.resolve("features/verification/settings.yml")));
		assertFalse(Files.exists(root.resolve("verification")));
		assertEquals(List.of(), leftovers());
	}

	@Test
	void finishesACommitInterruptedWhileDeletingDirectories() throws IOException {
		Files.createDirectories(root.resolve("providers/capabilities"));
		Files.createDirectories(state.resolve("journal/write"));
		Files.writeString(state.resolve("journal/delete"), "providers/capabilities/recognition/settings.yml");
		Files.writeString(state.resolve("journal/delete-directory"), "providers/capabilities/recognition\nproviders/capabilities");

		journal.recover();

		assertFalse(Files.exists(root.resolve("providers/capabilities")));
		assertEquals(List.of(), leftovers());
	}

	@Test
	void replaysAJournalWithoutDirectories() throws IOException {
		Files.createDirectories(state.resolve("journal/write"));
		Files.writeString(state.resolve("journal/write/settings.yml"), "new");
		Files.writeString(state.resolve("journal/delete"), "");

		journal.recover();

		assertEquals("new", Files.readString(root.resolve("settings.yml")));
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
