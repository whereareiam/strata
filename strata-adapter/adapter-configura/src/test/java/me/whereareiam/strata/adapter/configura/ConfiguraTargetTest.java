package me.whereareiam.strata.adapter.configura;

import com.fasterxml.jackson.databind.JsonNode;
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.model.AppliedMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguraTargetTest {
	private static final String STREAM = "plugin/config";
	private static final String SETTINGS = """
			name: lobby
			timeout: 30000
			routing:
			  mode: balanced
			""";
	private static final MigrationAction<ConfigContext> SPLIT_ROUTING =
			files -> files.move("settings.yml", "/routing", "routing.yml", "/routing");
	private static final MigrationAction<ConfigContext> TIMEOUT_IN_SECONDS = files -> {
		var settings = files.document("settings.yml");
		settings.put("timeout", settings.path("timeout").asInt() / 1000);
	};

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

		apply(1, SPLIT_ROUTING);
		apply(2, TIMEOUT_IN_SECONDS);

		assertEquals(OptionalInt.of(2), version());
		assertEquals("_version", settings().fieldNames().next());
		assertEquals(30, settings().path("timeout").asInt());
		assertFalse(settings().has("routing"));
		assertEquals("balanced", configura.readNode(directory.resolve("routing.yml")).at("/routing/mode").asText());
	}

	@Test
	void knowsItsVersionAfterItsWorkingFolderWasDeleted() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);
		apply(1, SPLIT_ROUTING);

		deleteTree(directory.resolve(".strata"));

		assertEquals(OptionalInt.of(1), version());
	}

	@Test
	void takesTheVersionOfAFileBroughtFromElsewhere() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), "_version: 4\ntimeout: 30\n");

		assertEquals(OptionalInt.of(4), version());
	}

	@Test
	void knowsNothingWithoutAFileOrWithoutTheKey() throws Exception {
		assertEquals(OptionalInt.empty(), version());

		Files.writeString(directory.resolve("settings.yml"), SETTINGS);

		assertEquals(OptionalInt.empty(), version());
	}

	@Test
	void recordsVersionZero() throws Exception {
		apply(0, files -> {});

		assertEquals(OptionalInt.of(0), version());
		assertEquals(0, settings().path("_version").asInt());
	}

	@Test
	void keepsTheFilesAsTheyWereBeforeAMigration() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);

		apply(1, SPLIT_ROUTING);
		apply(2, TIMEOUT_IN_SECONDS);

		Path backup = directory.resolve(".strata/backup/plugin.config");
		assertEquals(SETTINGS, Files.readString(backup.resolve("v1/settings.yml")));
		assertFalse(Files.exists(backup.resolve("v1/routing.yml")));
		assertEquals(30000, configura.readNode(backup.resolve("v2/settings.yml")).path("timeout").asInt());
	}

	@Test
	void changesNothingWhenAMigrationFails() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);

		assertThrows(IllegalStateException.class, () -> apply(1, files -> {
			files.document("settings.yml").put("name", "hub");
			files.delete("settings.yml");
			throw new IllegalStateException("broken");
		}));

		assertEquals(SETTINGS, Files.readString(directory.resolve("settings.yml")));
		assertEquals(OptionalInt.empty(), version());
	}

	@Test
	void recordsAVersionInANewDirectoryWithAFileConfiguraCompletes() throws Exception {
		apply(2, files -> {});

		Settings settings = configura.update(directory.resolve("settings.yml"), Settings.class);

		assertEquals("lobby", settings.name);
		assertEquals("lobby", settings().path("name").asText());
		assertEquals(OptionalInt.of(2), version());
	}

	@Test
	void finishesAnInterruptedCommitBeforeAnythingElse() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);
		Files.createDirectories(directory.resolve(".strata/journal/write"));
		Files.writeString(directory.resolve(".strata/journal/write/settings.yml"), "_version: 2\nname: lobby\ntimeout: 30\n");
		Files.writeString(directory.resolve(".strata/journal/write/routing.yml"), "routing:\n  mode: balanced\n");
		Files.writeString(directory.resolve(".strata/journal/delete"), "");

		assertEquals(OptionalInt.of(2), version());
		assertEquals(30, settings().path("timeout").asInt());
		assertTrue(Files.exists(directory.resolve("routing.yml")));
	}

	@Test
	void movesAFileOutOfADirectoryItDeletes() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);
		Files.createDirectories(directory.resolve("providers/capabilities/recognition"));
		Files.writeString(directory.resolve("providers/capabilities/recognition/settings.yml"), "_version: 1\nenabled: true\n");

		apply(1, files -> {
			files.moveFile("providers/capabilities/recognition/settings.yml", "features/recognition/settings.yml");
			files.deleteDirectoryIfEmpty("providers/capabilities");
		});

		assertFalse(Files.exists(directory.resolve("providers/capabilities")));
		assertTrue(Files.isDirectory(directory.resolve("providers")));
		JsonNode moved = configura.readNode(directory.resolve("features/recognition/settings.yml"));
		assertTrue(moved.path("enabled").asBoolean());
		assertFalse(moved.has("_version"));
		assertTrue(Files.exists(directory.resolve(".strata/backup/plugin.config/v1/providers/capabilities/recognition/settings.yml")));
		assertEquals(OptionalInt.of(1), version());
	}

	@Test
	void finishesDeletingADirectoryOfAnInterruptedCommit() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), SETTINGS);
		Files.createDirectories(directory.resolve("verification"));
		Files.createDirectories(directory.resolve(".strata/journal/write"));
		Files.writeString(directory.resolve(".strata/journal/write/settings.yml"), "_version: 1\nname: lobby\n");
		Files.writeString(directory.resolve(".strata/journal/delete"), "verification/settings.yml");
		Files.writeString(directory.resolve(".strata/journal/delete-directory"), "verification");

		assertEquals(OptionalInt.of(1), version());
		assertFalse(Files.exists(directory.resolve("verification")));
	}

	@Test
	void carriesTheVersionOfOneStreamOnly() throws Exception {
		apply(1, files -> {});

		try (MigrationSession<ConfigContext> session = target.open()) {
			assertThrows(IllegalStateException.class, () -> session.version("plugin/other"));
		}
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

	private void apply(int version, MigrationAction<ConfigContext> action) throws Exception {
		try (MigrationSession<ConfigContext> session = target.open()) {
			session.apply(STREAM, action, new AppliedMigration(version, "migration-" + version, Instant.EPOCH));
		}
	}

	private OptionalInt version() throws Exception {
		try (MigrationSession<ConfigContext> session = target.open()) {
			return session.version(STREAM);
		}
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
