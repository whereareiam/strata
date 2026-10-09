package me.whereareiam.strata.adapter.configura;

import com.fasterxml.jackson.databind.node.TextNode;
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigContextTest {
	private static final Path SETTINGS = Path.of("settings.yml");
	private static final Path ROUTING = Path.of("routing.yml");

	private final Configura configura = Config.yaml();

	@TempDir
	Path directory;

	private ConfigContext context;

	@BeforeEach
	void createContext() throws IOException {
		Files.writeString(directory.resolve(SETTINGS), """
				name: lobby
				connection:
				  timeout: 30
				  routing:
				    mode: balanced
				""");
		context = new ConfigContext(configura, directory);
	}

	@Test
	void reportsNothingWhenNothingChanged() {
		context.document("settings.yml").path("name");
		context.document("missing.yml");

		assertTrue(context.changes().isEmpty());
	}

	@Test
	void stagesChangesToAnExistingFile() {
		context.document("settings.yml").put("name", "hub");

		Map<Path, byte[]> changes = context.changes();

		assertEquals(Set.of(SETTINGS), changes.keySet());
		assertEquals("hub", configura.readNode(changes.get(SETTINGS)).path("name").asText());
		assertEquals(30, configura.readNode(changes.get(SETTINGS)).at("/connection/timeout").asInt());
	}

	@Test
	void createsAFileOnceSomethingIsInIt() {
		assertFalse(context.exists("routing.yml"));

		context.document("routing.yml").put("mode", "balanced");

		assertTrue(context.exists("routing.yml"));
		assertEquals("balanced", configura.readNode(context.changes().get(ROUTING)).path("mode").asText());
	}

	@Test
	void deletesFiles() {
		context.delete("settings.yml");
		context.delete("missing.yml");

		assertFalse(context.exists("settings.yml"));
		assertEquals(Set.of(SETTINGS), context.changes().keySet());
		assertNull(context.changes().get(SETTINGS));
	}

	@Test
	void movesAValueToAnotherFile() {
		assertTrue(context.move("settings.yml", "/connection/routing", "routing.yml", "/routing"));

		assertTrue(context.document("settings.yml").at("/connection/routing").isMissingNode());
		assertEquals(30, context.document("settings.yml").at("/connection/timeout").asInt());
		assertEquals("balanced", context.document("routing.yml").at("/routing/mode").asText());
	}

	@Test
	void movesAValueInsideItsFile() {
		assertTrue(context.rename("settings.yml", "/name", "/server/display/name"));

		assertEquals("lobby", context.document("settings.yml").at("/server/display/name").asText());
		assertFalse(context.document("settings.yml").has("name"));
	}

	@Test
	void leavesEverythingAloneWhenTheValueToMoveIsNotThere() {
		assertFalse(context.move("settings.yml", "/connection/proxy", "routing.yml", "/proxy"));
		assertFalse(context.move("missing.yml", "/anything", "routing.yml", "/anything"));
		assertFalse(context.rename("settings.yml", "/name/first", "/first"));

		assertTrue(context.changes().isEmpty());
	}

	@Test
	void putsAndRemovesValuesByPointer() {
		context.put("settings.yml", "/connection/name", TextNode.valueOf("replaced"));
		context.put("settings.yml", "/with~1slash", TextNode.valueOf("escaped"));

		assertEquals("replaced", context.remove("settings.yml", "/connection/name").asText());
		assertEquals("escaped", context.document("settings.yml").path("with/slash").asText());
		assertNull(context.remove("settings.yml", "/connection/name"));
	}

	@Test
	void rejectsPointersToTheDocumentItself() {
		assertThrows(IllegalArgumentException.class, () -> context.remove("settings.yml", ""));
		assertThrows(IllegalArgumentException.class, () -> context.put("settings.yml", "", TextNode.valueOf("x")));
	}

	@Test
	void rejectsFilesOutsideTheDirectory() {
		for (String file : List.of("../settings.yml", "nested/../../settings.yml", ".strata/history.tsv", directory.resolve("settings.yml").toString(), ""))
			assertThrows(IllegalArgumentException.class, () -> context.exists(file), file);
	}

	@Test
	void opensAnEmptyFileAsAnEmptyDocument() throws IOException {
		Files.write(directory.resolve("empty.yml"), new byte[0]);

		assertTrue(context.exists("empty.yml"));
		assertTrue(context.document("empty.yml").isEmpty());
	}

	@Test
	void rejectsAFileWithoutNamedValuesAtItsRoot() throws IOException {
		Files.writeString(directory.resolve("list.yml"), "- first\n- second\n", StandardCharsets.UTF_8);

		assertThrows(IllegalStateException.class, () -> context.document("list.yml"));
	}
}
