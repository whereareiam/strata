package me.whereareiam.strata.adapter.configura;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
	private Path formatDirectory;

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

	@ParameterizedTest
	@EnumSource(Format.class)
	void movesAValueOnlyIntoAnEmptyPlace(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"settings", "connection:\n  attemptTtl: 30\n  reservationTtl: 60\nidentity:\n  reservationTtl: 90\n"
		));

		assertTrue(files.moveIfAbsent(format.file("settings"), "/connection/attemptTtl", format.file("providers/providers"), "/behavior/attemptTtl"));
		assertFalse(files.moveIfAbsent(format.file("settings"), "/connection/reservationTtl", format.file("settings"), "/identity/reservationTtl"));
		assertFalse(files.moveIfAbsent(format.file("settings"), "/connection/missing", format.file("settings"), "/identity/missing"));

		ObjectNode settings = written(format, files, "settings");
		assertEquals(90, settings.at("/identity/reservationTtl").asInt());
		assertTrue(settings.at("/connection/reservationTtl").isMissingNode());
		assertTrue(settings.at("/identity/missing").isMissingNode());
		assertEquals(30, written(format, files, "providers/providers").at("/behavior/attemptTtl").asInt());
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void mergesASectionIntoTheValuesThatAreThere(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"settings", "connection:\n  routing:\n    mode: balanced\n    targets:\n      lobby: lobby-1\n      hub: hub-1\n    fallback: [limbo]\n  timeout: 30\n",
				"routing", "targets:\n  hub: hub-2\nfallback: [hub]\n"
		));

		assertTrue(files.moveSection(format.file("settings"), "/connection/routing", format.file("routing"), ""));

		ObjectNode routing = written(format, files, "routing");
		assertEquals("balanced", routing.path("mode").asText());
		assertEquals("lobby-1", routing.at("/targets/lobby").asText());
		assertEquals("hub-2", routing.at("/targets/hub").asText());
		assertEquals("hub", routing.at("/fallback/0").asText());
		assertEquals(1, routing.path("fallback").size());

		ObjectNode settings = written(format, files, "settings");
		assertTrue(settings.at("/connection/routing").isMissingNode());
		assertEquals(30, settings.at("/connection/timeout").asInt());
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void removesTheSectionsAMovedSectionLeavesEmpty(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"settings", "name: lobby\nsessions:\n  recognition:\n    enabled: true\n"
		));

		assertTrue(files.moveSection(format.file("settings"), "/sessions/recognition", format.file("features/recognition/settings"), ""));

		ObjectNode settings = written(format, files, "settings");
		assertFalse(settings.has("sessions"));
		assertEquals("lobby", settings.path("name").asText());
		assertTrue(written(format, files, "features/recognition/settings").path("enabled").asBoolean());
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void dropsASectionWhoseNewPlaceHoldsAValue(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"settings", "sentinels:\n  window: 10\nlegacy:\n  window: 5\n",
				"features", "sentinels: disabled\n"
		));

		assertTrue(files.moveSection(format.file("settings"), "/sentinels", format.file("features"), "/sentinels"));

		assertFalse(written(format, files, "settings").has("sentinels"));
		assertEquals("disabled", written(format, files, "features").path("sentinels").asText());
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void leavesEverythingAloneWhenThereIsNoSectionToMove(Format format) throws IOException {
		ConfigContext files = context(format, Map.of("settings", "name: lobby\n"));

		assertFalse(files.moveSection(format.file("settings"), "/connection", format.file("routing"), ""));
		assertFalse(files.moveSection(format.file("settings"), "/name", format.file("routing"), "/name"));
		assertFalse(files.moveSection(format.file("missing"), "", format.file("routing"), ""));

		assertTrue(files.changes().isEmpty());
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void movesAWholeDocumentKeepingItsVersionBehind(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"settings", "_version: 7\nname: lobby\nconnection:\n  timeout: 30\n"
		));

		assertTrue(files.moveSection(format.file("settings"), "", format.file("copy"), "/settings"));

		ObjectNode settings = written(format, files, "settings");
		assertEquals(7, settings.path("_version").asInt());
		assertEquals(1, settings.size());
		ObjectNode copy = written(format, files, "copy");
		assertEquals("lobby", copy.at("/settings/name").asText());
		assertTrue(copy.at("/settings/_version").isMissingNode());
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void movesAFileIntoANewFile(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"verification", "_version: 1\nmethods:\n  email: true\n"
		));

		assertTrue(files.moveFile(format.file("verification"), format.file("features/verification/settings")));

		assertFalse(files.exists(format.file("verification")));
		Map<Path, byte[]> changes = files.changes();
		assertNull(changes.get(Path.of(format.file("verification"))));
		JsonNode moved = format.configura.readNode(changes.get(Path.of(format.file("features/verification/settings"))));
		assertTrue(moved.at("/methods/email").asBoolean());
		assertFalse(moved.has("_version"));
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void movesAFileIntoAFileThatKeepsItsValues(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"verification", "methods:\n  email: true\n  code: false\nattempts: 3\n",
				"features/verification/settings", "_version: 2\nmethods:\n  email: false\n"
		));

		assertTrue(files.moveFile(format.file("verification"), format.file("features/verification/settings")));

		ObjectNode settings = written(format, files, "features/verification/settings");
		assertEquals(2, settings.path("_version").asInt());
		assertFalse(settings.at("/methods/email").asBoolean());
		assertFalse(settings.at("/methods/code").asBoolean(true));
		assertEquals(3, settings.path("attempts").asInt());
		assertFalse(files.exists(format.file("verification")));
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void leavesEverythingAloneWhenTheFileToMoveIsNotThere(Format format) throws IOException {
		ConfigContext files = context(format, Map.of());

		assertFalse(files.moveFile(format.file("verification"), format.file("features/verification/settings")));

		assertTrue(files.changes().isEmpty());
	}

	@Test
	void refusesToMoveIntoItself() {
		assertThrows(IllegalArgumentException.class, () -> context.moveFile("settings.yml", "./settings.yml"));
		assertThrows(IllegalArgumentException.class, () -> context.moveSection("settings.yml", "/connection", "settings.yml", "/connection/routing"));
		assertThrows(IllegalArgumentException.class, () -> context.moveSection("settings.yml", "/connection/routing", "settings.yml", ""));
		assertThrows(IllegalArgumentException.class, () -> context.moveSection("settings.yml", "/connection", "settings.yml", "/connection"));
	}

	@Test
	void movesASectionNextToItself() {
		assertTrue(context.moveSection("settings.yml", "/connection/routing", "settings.yml", "/connection/route"));

		assertEquals("balanced", context.document("settings.yml").at("/connection/route/mode").asText());
		assertTrue(context.document("settings.yml").at("/connection/routing").isMissingNode());
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void removesEmptySectionsUpToTheDocument(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"settings", "connection:\n  scenarios:\n    login: {}\n    register: {}\nempty: {}\nname: lobby\n"
		));
		String settings = format.file("settings");

		assertTrue(files.removeIfEmpty(settings, "/connection/scenarios/login"));
		assertEquals(1, files.document(settings).at("/connection/scenarios").size());
		assertTrue(files.removeIfEmpty(settings, "/connection/scenarios/register"));
		assertTrue(files.removeIfEmpty(settings, "/empty"));

		ObjectNode written = written(format, files, "settings");
		assertEquals(List.of("name"), keys(written));
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void keepsSectionsThatAreNotEmpty(Format format) throws IOException {
		ConfigContext files = context(format, Map.of("settings", "connection:\n  timeout: 30\nname: lobby\nlist: []\n"));
		String settings = format.file("settings");

		assertFalse(files.removeIfEmpty(settings, "/connection"));
		assertFalse(files.removeIfEmpty(settings, "/name"));
		assertFalse(files.removeIfEmpty(settings, "/list"));
		assertFalse(files.removeIfEmpty(settings, "/missing"));
		assertFalse(files.removeIfEmpty(settings, ""));
		assertFalse(files.removeIfEmpty(format.file("missing"), "/anything"));

		assertTrue(files.changes().isEmpty());
	}

	@Test
	void keepsAnEmptyDocument() throws IOException {
		Files.writeString(directory.resolve("empty.yml"), "{}\n");

		assertFalse(context.removeIfEmpty("empty.yml", ""));
		assertTrue(context.exists("empty.yml"));
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void removesAStaleVersion(Format format) throws IOException {
		ConfigContext files = context(format, Map.of("messages", "_version: 1\nprefix: '>'\n", "commands", "aliases: {}\n"));

		assertTrue(files.removeVersion(format.file("messages")));
		assertFalse(files.removeVersion(format.file("commands")));
		assertFalse(files.removeVersion(format.file("missing")));

		assertEquals(List.of("prefix"), keys(written(format, files, "messages")));
		assertEquals(Set.of(Path.of(format.file("messages"))), files.changes().keySet());
	}

	@ParameterizedTest
	@EnumSource(Format.class)
	void deletesADirectoryTheMigrationEmptied(Format format) throws IOException {
		ConfigContext files = context(format, Map.of(
				"providers/capabilities/recognition/settings", "enabled: true\n",
				"providers/capabilities/restriction/settings", "ttl: 10\n"
		));
		Files.createDirectories(formatDirectory.resolve("providers/capabilities/restriction/empty"));

		assertFalse(files.deleteDirectoryIfEmpty("providers/capabilities"));

		files.moveFile(format.file("providers/capabilities/recognition/settings"), format.file("features/recognition/settings"));
		files.moveFile(format.file("providers/capabilities/restriction/settings"), format.file("features/restriction/settings"));

		assertTrue(files.deleteDirectoryIfEmpty("providers/capabilities"));
		assertFalse(files.deleteDirectoryIfEmpty("providers/capabilities/recognition"));
		assertEquals(
				List.of(
						Path.of("providers/capabilities/restriction/empty"),
						Path.of("providers/capabilities/recognition"),
						Path.of("providers/capabilities/restriction"),
						Path.of("providers/capabilities")
				),
				files.deletedDirectories()
		);
	}

	@Test
	void keepsADirectoryWithAnyOtherFileInIt() throws IOException {
		Files.createDirectories(directory.resolve("legacy/nested"));
		Files.writeString(directory.resolve("legacy/nested/notes.txt"), "kept");

		assertFalse(context.deleteDirectoryIfEmpty("legacy"));
		assertFalse(context.deleteDirectoryIfEmpty("missing"));
		assertTrue(context.deletedDirectories().isEmpty());
	}

	@Test
	void keepsADirectoryTheMigrationWritesInto() throws IOException {
		Files.createDirectories(directory.resolve("features/verification"));

		context.put("features/verification/settings.yml", "/enabled", TextNode.valueOf("yes"));
		assertFalse(context.deleteDirectoryIfEmpty("features"));

		Files.createDirectories(directory.resolve("legacy"));
		assertTrue(context.deleteDirectoryIfEmpty("legacy"));
		context.put("legacy/settings.yml", "/enabled", TextNode.valueOf("yes"));
		assertTrue(context.deletedDirectories().isEmpty());
	}

	@Test
	void countsADocumentOpenedButLeftEmptyAsNothing() throws IOException {
		Files.createDirectories(directory.resolve("legacy"));
		context.document("legacy/settings.yml");

		assertTrue(context.deleteDirectoryIfEmpty("legacy"));
	}

	@Test
	void rejectsDirectoriesItMayNotDelete() {
		for (String path : List.of("", ".", "..", "../other", ".strata", "nested/../../other", directory.toString()))
			assertThrows(IllegalArgumentException.class, () -> context.deleteDirectoryIfEmpty(path), path);
		assertThrows(IllegalArgumentException.class, () -> context.deleteDirectoryIfEmpty("settings.yml"));
	}

	@Test
	void escapesKeysWithJacksonPointers() {
		String pointer = JsonPointer.compile("/rules").appendProperty("a/b~c").toString();

		context.put("settings.yml", pointer, IntNode.valueOf(1));

		assertEquals("/rules/a~1b~0c", pointer);
		assertEquals(1, context.document("settings.yml").path("rules").path("a/b~c").asInt());
	}

	/** The formats Configura reads and writes, with the extension of their files. */
	enum Format {
		YAML(Config.yaml(), ".yml"),
		JSON(Config.json(), ".json");

		private final Configura configura;
		private final String extension;

		Format(Configura configura, String extension) {
			this.configura = configura;
			this.extension = extension;
		}

		String file(String name) {
			return name + extension;
		}
	}

	/** Writes each document, given as YAML, in the format and opens a context on them. */
	private ConfigContext context(Format format, Map<String, String> documents) throws IOException {
		Path root = Files.createDirectories(directory.resolve(format.name().toLowerCase()));
		for (Map.Entry<String, String> document : documents.entrySet()) {
			Path file = root.resolve(format.file(document.getKey()));
			Files.createDirectories(file.getParent());
			Files.write(file, format.configura.writeNodeBytes((ObjectNode) configura.readNode(document.getValue().getBytes(StandardCharsets.UTF_8))));
		}

		formatDirectory = root;
		return new ConfigContext(format.configura, root);
	}

	/** Returns a document as the migration's changes write it, or as it is on disk when unchanged. */
	private ObjectNode written(Format format, ConfigContext files, String name) throws IOException {
		Path file = Path.of(format.file(name));
		byte[] bytes = files.changes().containsKey(file) ? files.changes().get(file) : Files.readAllBytes(formatDirectory.resolve(file));

		return (ObjectNode) format.configura.readNode(bytes);
	}

	private static List<String> keys(ObjectNode node) {
		List<String> keys = new ArrayList<>();
		node.fieldNames().forEachRemaining(keys::add);

		return keys;
	}
}
