package me.whereareiam.strata.adapter.configura;

import com.fasterxml.jackson.databind.JsonNode;
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.exception.MigrationException;
import me.whereareiam.strata.exception.MigrationFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguraTargetTest {
	private static final String SETTINGS = """
			name: lobby
			timeout: 30000
			routing:
			  mode: balanced
			""";

	private final Configura configura = Config.builder()
			.feature(new StrataFeature())
			.build();

	@TempDir
	Path directory;

	private ConfiguraTarget target;

	@BeforeEach
	void createTarget() {
		target = new ConfiguraTarget(configura, directory, "settings.yml");
	}

	@Test
	void changesSeveralFilesAndWritesTheVersionIntoItsFile() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);
		MigrationStream<ConfigContext> stream = upgrade().build();

		new Strata(List.of(stream)).migrate();

		assertEquals(2, settings().path("_version").asInt());
		assertEquals("_version", settings().fieldNames().next());
		assertEquals(30, settings().path("timeout").asInt());
		assertFalse(settings().has("routing"));
		assertEquals("balanced", configura.readNode(directory.resolve("routing.yml")).at("/routing/mode").asText());
		assertTrue(new Strata(List.of(stream)).migrate().isEmpty());
	}

	@Test
	void runsNothingAgainAfterItsWorkingFolderWasDeleted() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);
		new Strata(List.of(upgrade().build())).migrate();

		deleteTree(directory.resolve(".strata"));

		assertTrue(new Strata(List.of(upgrade().build())).migrate().isEmpty());
		assertEquals(30, settings().path("timeout").asInt());
	}

	@Test
	void continuesFromTheVersionOfAFileBroughtFromElsewhere() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), "_version: 1\ntimeout: 30000\n");

		new Strata(List.of(upgrade().build())).migrate();

		assertEquals(2, settings().path("_version").asInt());
		assertEquals(30, settings().path("timeout").asInt());
	}

	@Test
	void keepsTheFilesAsTheyWereBeforeAMigration() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);

		new Strata(List.of(upgrade().build())).migrate();

		Path backup = directory.resolve(".strata/backup/plugin.config");
		assertEquals(SETTINGS, Files.readString(backup.resolve("v1/settings.yml")));
		assertFalse(Files.exists(backup.resolve("v1/routing.yml")));
		assertEquals(30000, configura.readNode(backup.resolve("v2/settings.yml")).path("timeout").asInt());
	}

	@Test
	void changesNothingWhenAMigrationFails() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);
		MigrationStream<ConfigContext> broken = stream()
				.migration(1, "broken", files -> {
					files.document("settings.yml").put("name", "hub");
					files.delete("settings.yml");
					throw new IllegalStateException("broken");
				})
				.build();

		assertThrows(MigrationFailedException.class, () -> new Strata(List.of(broken)).migrate());

		assertEquals(SETTINGS, Files.readString(directory.resolve("settings.yml")));
	}

	@Test
	void stampsANewDirectoryWithAFileConfiguraCompletes() throws Exception {
		MigrationStream<ConfigContext> stream = upgrade()
				.baseline((files, latest) -> files.exists("settings.yml") ? 0 : latest)
				.build();

		assertTrue(new Strata(List.of(stream)).migrate().isEmpty());
		Settings settings = configura.update(directory.resolve("settings.yml"), Settings.class);

		assertEquals("lobby", settings.name);
		assertEquals(2, settings().path("_version").asInt());
		assertEquals("lobby", settings().path("name").asText());
		assertFalse(Files.exists(directory.resolve("routing.yml")));
		assertTrue(new Strata(List.of(stream)).migrate().isEmpty());
	}

	@Test
	void finishesAnInterruptedCommitBeforeAnythingElse() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);
		Files.createDirectories(directory.resolve(".strata/journal/write"));
		Files.writeString(directory.resolve(".strata/journal/write/settings.yml"), "_version: 2\nname: lobby\ntimeout: 30\n");
		Files.writeString(directory.resolve(".strata/journal/write/routing.yml"), "routing:\n  mode: balanced\n");
		Files.writeString(directory.resolve(".strata/journal/delete"), "");

		assertTrue(new Strata(List.of(upgrade().build())).migrate().isEmpty());

		assertEquals(30, settings().path("timeout").asInt());
		assertTrue(Files.exists(directory.resolve("routing.yml")));
	}

	@Test
	void carriesTheVersionOfOneStreamOnly() {
		MigrationStream<ConfigContext> other = MigrationStream.<ConfigContext>builder()
				.id("plugin/other")
				.target(target)
				.migration(1, "anything", files -> {})
				.build();

		MigrationException failure = assertThrows(MigrationException.class, () -> new Strata(List.of(upgrade().build(), other)).migrate());

		assertEquals("plugin/other", failure.getStream());
		assertInstanceOf(IllegalStateException.class, failure.getCause());
	}

	@Test
	void refusesAConfiguraThatWouldDropTheVersion() {
		assertThrows(IllegalArgumentException.class, () -> new ConfiguraTarget(Config.yaml(), directory, "settings.yml"));
	}

	@Test
	void readsAFileForMigrationsOfOtherTargets() throws IOException {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);

		assertEquals("balanced", target.read("settings.yml").at("/routing/mode").asText());
		assertTrue(target.read("missing.yml").isEmpty());
	}

	@Test
	void isOpenToOneInstanceAtATime() throws Exception {
		try (MigrationSession<ConfigContext> held = target.open()) {
			assertThrows(IllegalStateException.class, target::open);
		}

		target.open().close();
	}

	private MigrationStream.Builder<ConfigContext> upgrade() {
		return stream()
				.migration(1, "split-routing", files -> files.move("settings.yml", "/routing", "routing.yml", "/routing"))
				.migration(2, "timeout-in-seconds", files -> {
					var settings = files.document("settings.yml");
					settings.put("timeout", settings.path("timeout").asInt() / 1000);
				});
	}

	private MigrationStream.Builder<ConfigContext> stream() {
		return MigrationStream.<ConfigContext>builder()
				.id("plugin/config")
				.target(target);
	}

	private JsonNode settings() {
		return configura.readNode(directory.resolve("settings.yml"));
	}

	private static void deleteTree(Path root) throws IOException {
		try (Stream<Path> tree = Files.walk(root)) {
			for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
		}
	}

	public static class Settings {
		public String name = "lobby";
		public int timeout = 30;
	}
}
