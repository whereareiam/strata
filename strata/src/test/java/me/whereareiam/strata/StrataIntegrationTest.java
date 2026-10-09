package me.whereareiam.strata;

import com.fasterxml.jackson.databind.JsonNode;
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import me.whereareiam.strata.adapter.configura.ConfigContext;
import me.whereareiam.strata.adapter.configura.ConfiguraTarget;
import me.whereareiam.strata.adapter.configura.StrataFeature;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import me.whereareiam.strata.adapter.database.DatabaseTarget;
import me.whereareiam.strata.adapter.database.jdbi.JdbiConnector;
import me.whereareiam.strata.exception.MigrationFailedException;
import me.whereareiam.strata.model.MigrationReport;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs Strata the way an application does: a database reached through the application's Jdbi and a
 * Configura directory, upgraded together.
 */
class StrataIntegrationTest {
	private final Jdbi jdbi = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
	private final Configura configura = Config.builder()
			.feature(new StrataFeature())
			.build();

	@TempDir
	Path directory;

	private ConfiguraTarget configs;

	@BeforeEach
	void createTargets() {
		configs = new ConfiguraTarget(configura, directory, "settings.yml");
	}

	@Test
	void movesASettingFromAConfigurationFileIntoTheDatabase() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), "name: lobby\nroutes:\n  hub: lobby-1\n  arena: arena-1\n");

		MigrationReport report = new Strata(List.of(database().build(), configuration().build())).migrate();

		assertEquals(List.of("plugin/database", "plugin/config"), List.copyOf(report.getApplied().keySet()));
		assertEquals(List.of("arena=arena-1", "hub=lobby-1"), routes());
		assertFalse(settings().has("routes"));
		assertEquals(1, settings().path("_version").asInt());
		assertTrue(new Strata(List.of(database().build(), configuration().build())).migrate().isEmpty());
	}

	@Test
	void runsNothingOnANewInstallation() {
		MigrationStream<DatabaseContext> database = database()
				.baseline((context, latest) -> context.tableExists("routes") ? 0 : latest)
				.build();
		MigrationStream<ConfigContext> configuration = configuration()
				.baseline((files, latest) -> files.exists("settings.yml") ? 0 : latest)
				.build();

		assertTrue(new Strata(List.of(database, configuration)).migrate().isEmpty());

		int tables = jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ROUTES'").mapTo(Integer.class).one());
		assertEquals(0, tables);
		assertEquals(1, settings().path("_version").asInt());
		assertTrue(new Strata(List.of(database, configuration)).pending().isEmpty());
	}

	@Test
	void leavesTheSourceInPlaceWhenTheCopyFails() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), "routes:\n  hub: lobby-1\n");
		MigrationStream<DatabaseContext> broken = MigrationStream.<DatabaseContext>builder()
				.id("plugin/database")
				.target(new DatabaseTarget(new JdbiConnector(jdbi)))
				.migration(1, "create-routes", context -> context.execute("CREATE TABLE routes (name VARCHAR(32) PRIMARY KEY, target VARCHAR(32))"))
				.migration(2, "import-routes", context -> {
					jdbi.useHandle(handle -> handle.execute("INSERT INTO routes (name, target) VALUES (?, ?)", "hub", "lobby-1"));
					throw new IllegalStateException("broken");
				})
				.build();

		assertThrows(MigrationFailedException.class, () -> new Strata(List.of(broken, configuration().build())).migrate());

		assertEquals(List.of(), routes());
		assertTrue(settings().has("routes"));
		assertFalse(settings().has("_version"));
	}

	private MigrationStream.Builder<DatabaseContext> database() {
		return MigrationStream.<DatabaseContext>builder()
				.id("plugin/database")
				.target(new DatabaseTarget(new JdbiConnector(jdbi)))
				.migration(1, "create-routes", context -> context.execute("CREATE TABLE routes (name VARCHAR(32) PRIMARY KEY, target VARCHAR(32))"))
				.migration(2, "import-routes", context -> jdbi.useHandle(handle -> {
					for (var route : configs.read("settings.yml").path("routes").properties())
						handle.execute("INSERT INTO routes (name, target) VALUES (?, ?)", route.getKey(), route.getValue().asText());
				}));
	}

	private MigrationStream.Builder<ConfigContext> configuration() {
		return MigrationStream.<ConfigContext>builder()
				.id("plugin/config")
				.target(configs)
				.migration(1, "drop-routes", files -> files.remove("settings.yml", "/routes"));
	}

	private List<String> routes() {
		return jdbi.withHandle(handle -> handle.createQuery("SELECT name || '=' || target FROM routes ORDER BY name").mapTo(String.class).list());
	}

	private JsonNode settings() {
		return configura.readNode(directory.resolve("settings.yml"));
	}
}
