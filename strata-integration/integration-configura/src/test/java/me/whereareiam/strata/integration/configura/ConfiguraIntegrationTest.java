package me.whereareiam.strata.integration.configura;

import me.whereareiam.configura.Config;
import me.whereareiam.strata.*;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.integration.jdbc.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguraIntegrationTest {
	@TempDir Path directory;
	private ConfigResource resource(String name, boolean required) {
		return new ConfigResource(name, Path.of(name + ".yml"), required, true, null);
	}
	private ConfiguraIntegration integration(ConfiguraIntegration.CommitObserver observer) {
		return new ConfiguraIntegration("files", Config.yaml(), directory,
				List.of(resource("settings", true), resource("routing", false)), observer);
	}
	private ConfiguraIntegration integration() { return integration((index, path) -> { }); }
	private MigrationStream<ConfigContext> stream(ConfiguraIntegration integration, MigrationAction<ConfigContext> action) {
		return new MigrationStream<>("plugin/config", integration, 2,
				List.of(new Migration<>("split", 1, 2, "split-v1", action)), Map.of(), context -> context.version("settings", "/_version"), null);
	}
	private MigrationAction<ConfigContext> move() {
		return context -> {
			context.move("settings", "/connection/routing", "routing", "/routing", ConflictPolicy.FAIL_IF_DIFFERENT);
			context.document("settings").put("_version", 2);
		};
	}
	private Strata runner(ConfiguraIntegration integration, MigrationAction<ConfigContext> action) { return new Strata(List.of(stream(integration, action)), Map.of()); }
	private void source() throws Exception { Files.writeString(directory.resolve("settings.yml"), "_version: 1\nconnection:\n  routing:\n    lobby: hub\n"); }

	@Test void movesAcrossFilesAndPersistsLatestVersionOnce() throws Exception {
		source(); var runner = runner(integration(), move());
		runner.execute(); runner.execute();
		assertEquals("hub", Config.yaml().readNode(directory.resolve("routing.yml")).at("/routing/lobby").textValue());
		assertTrue(Config.yaml().readNode(directory.resolve("settings.yml")).at("/connection/routing").isMissingNode());
		assertTrue(runner.inspect().isEmpty());
		assertTrue(Files.isDirectory(directory.resolve(".strata/configura/completed")));
	}
	@Test void conflictAndValidationFailuresLeaveOriginalsUntouched() throws Exception {
		source(); Files.writeString(directory.resolve("routing.yml"), "routing:\n  lobby: custom\n");
		String before = Files.readString(directory.resolve("settings.yml"));
		assertThrows(IllegalStateException.class, () -> runner(integration(), move()).execute());
		assertEquals(before, Files.readString(directory.resolve("settings.yml")));
		Files.delete(directory.resolve("routing.yml"));
		var invalid = new ConfiguraIntegration("files", Config.yaml(), directory, List.of(resource("settings", true),
				new ConfigResource("routing", Path.of("routing.yml"), false, true, node -> { throw new IllegalStateException("invalid"); })));
		assertThrows(IllegalStateException.class, () -> runner(invalid, move()).execute());
		assertFalse(Files.exists(directory.resolve("routing.yml")));
	}
	@Test void recoversAfterEveryReplacementBoundary() throws Exception {
		for (int boundary = 0; boundary < 3; boundary++) {
			Path installation = directory.resolve("case" + boundary); Files.createDirectories(installation);
			Files.writeString(installation.resolve("settings.yml"), "_version: 1\nconnection:\n  routing: {lobby: hub}\n");
			int failAt = boundary;
			var broken = new ConfiguraIntegration("files", Config.yaml(), installation,
					List.of(resource("settings", true), resource("routing", false)), (index, path) -> {
				if (index == failAt) throw new IllegalStateException("crash");
			});
			assertThrows(IllegalStateException.class, () -> runner(broken, move()).execute());
			var recovered = new ConfiguraIntegration("files", Config.yaml(), installation, List.of(resource("settings", true), resource("routing", false)));
			runner(recovered, context -> fail("committed action must not replay")).execute();
			assertEquals("hub", Config.yaml().readNode(installation.resolve("routing.yml")).at("/routing/lobby").textValue());
		}
	}
	@Test void preparationSurvivesRestartWithoutReevaluatingInput() throws Exception {
		source(); AtomicInteger invocations = new AtomicInteger();
		var action = (MigrationAction<ConfigContext>) context -> { invocations.incrementAndGet(); move().apply(context); };
		try (var prepared = runner(integration(), action).prepare()) { assertFalse(Files.exists(directory.resolve("routing.yml"))); }
		runner(integration(), action).execute();
		assertEquals(1, invocations.get());
	}
	@Test void externalEditsAfterPreparationStopCommit() throws Exception {
		source();
		try (var prepared = runner(integration(), move()).prepare()) {
			Files.writeString(directory.resolve("settings.yml"), "_version: 1\nconnection: {routing: changed}\n");
			assertThrows(java.io.IOException.class, prepared::commit);
		}
		assertFalse(Files.exists(directory.resolve("routing.yml")));
	}
	@Test void rejectsChangedPreparedDeclaration() throws Exception {
		source(); try (var prepared = runner(integration(), move()).prepare()) { }
		var changed = new MigrationStream<>("plugin/config", integration(), 2,
				List.of(new Migration<ConfigContext>("split", 1, 2, "changed", move())), Map.of(), context -> 1, null);
		assertThrows(IllegalStateException.class, () -> new Strata(List.of(changed), Map.of()).execute());
	}
	@Test void rejectsUndeclaredReadOnlyAndEscapingResources() throws Exception {
		source();
		assertThrows(IllegalArgumentException.class, () -> runner(integration(), context -> context.document("unknown")).execute());
		assertThrows(IllegalArgumentException.class, () -> new ConfigResource("outside", Path.of("../outside.yml"), false, true, null));
		var readOnly = new ConfiguraIntegration("files", Config.yaml(), directory,
				List.of(new ConfigResource("settings", Path.of("settings.yml"), true, false, null)));
		assertThrows(IllegalArgumentException.class, () -> runner(readOnly, context -> context.document("settings")).execute());
	}
	@Test void deletesRetiredRequiredSourceWithoutBreakingNextStartup() throws Exception {
		source();
		var runner = runner(integration(), context -> context.delete("settings"));
		runner.execute(); runner.execute();
		assertFalse(Files.exists(directory.resolve("settings.yml")));
	}
	@Test void requiresExplicitLegacyVersionAndRejectsFractionalMarkers() throws Exception {
		Files.writeString(directory.resolve("settings.yml"), "_version: 1.5\n");
		assertThrows(IllegalStateException.class, () -> runner(integration(), move()).execute());
		Files.writeString(directory.resolve("settings.yml"), "routing: legacy\n");
		assertThrows(IllegalStateException.class, () -> runner(integration(), move()).execute());
	}
	@Test void preparesConfigurationsBeforeSqlAndResumesAfterDatabaseCommit() throws Exception {
		source(); var dataSource = new JdbcDataSource(); dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) { statement.execute("CREATE TABLE profiles (id INT PRIMARY KEY)"); }
		var database = new MigrationStream<>("plugin/database", new JdbcIntegration("db", dataSource), 1,
				List.of(new Migration<JdbcContext>("insert", 0, 1, "1", context -> context.execute("INSERT INTO profiles VALUES (1)"))), Map.of(), context -> 0, null);
		AtomicBoolean fail = new AtomicBoolean(true);
		var files = integration((index, path) -> { if (fail.get()) throw new IllegalStateException("after database"); });
		var config = new MigrationStream<>("plugin/config", files, 2, stream(files, move()).getMigrations(),
				Map.of("plugin/database", 1), context -> context.version("settings", "/_version"), null);
		assertThrows(IllegalStateException.class, () -> new Strata(List.of(config, database), Map.of()).execute());
		fail.set(false);
		new Strata(List.of(config, database), Map.of()).execute();
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM profiles")) {
			result.next(); assertEquals(1, result.getInt(1));
		}
		assertTrue(Files.exists(directory.resolve("routing.yml")));
	}
	@Test void capturedInputSurvivesRestartWithoutReexecutingPreparation() throws Exception {
		source();
		var integration = integration();
		AtomicInteger invocations = new AtomicInteger();
		var action = (MigrationAction<ConfigContext>) context -> {
			invocations.incrementAndGet();
			context.capture("old-routing", context.read("settings").at("/connection/routing"));
			move().apply(context);
		};
		try (var abandoned = runner(integration, action).prepare()) {
			assertEquals("hub", integration.input("plugin/config", "old-routing").get("lobby").textValue());
		}
		var reopened = integration();
		try (var prepared = runner(reopened, action).prepare()) {
			assertEquals("hub", reopened.input("plugin/config", "old-routing").get("lobby").textValue());
			prepared.commit();
		}
		assertEquals("hub", reopened.input("plugin/config", "old-routing").get("lobby").textValue());
		assertEquals(1, invocations.get());
	}
	@Test void defaultsBindStagedTreeWithoutTouchingFiles() throws Exception {
		source();
		var runner = runner(integration(), context -> {
			context.document("routing").put("count", 7);
			context.defaults("routing", TestSettings.class);
			assertFalse(Files.exists(directory.resolve("routing.yml")));
		});
		runner.execute();
		assertEquals(7, Config.yaml().read(directory.resolve("routing.yml"), TestSettings.class).count);
	}
	public static class TestSettings { public int count; }

	@Test void inspectsAndInitializesAnEntirelyAbsentInstallation() throws Exception {
		Path root = directory.resolve("fresh");
		var integration = new ConfiguraIntegration("files", Config.yaml(), root, List.of(resource("settings", false)));
		var create = new Migration<ConfigContext>("create", 0, 1, "1", context -> context.document("settings").put("_version", 1));
		var stream = new MigrationStream<>("plugin/config", integration, 1, List.of(create), Map.of(), context -> {
			if (context.exists("settings")) throw new IllegalStateException("Unexpected existing layout");
			return 0;
		}, null);
		var runner = new Strata(List.of(stream), Map.of());
		assertFalse(runner.inspect().isEmpty());
		assertFalse(Files.exists(root));
		runner.execute(); runner.execute();
		assertEquals(1, Config.yaml().readNode(root.resolve("settings.yml")).get("_version").intValue());
	}

	@Test void databaseConsumesDurableInputAfterFailedFirstAttempt() throws Exception {
		source();
		var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
		try (var connection = source.getConnection(); var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE imported_settings (setting_value VARCHAR(80))");
		}
		AtomicBoolean fail = new AtomicBoolean(true);
		AtomicInteger transforms = new AtomicInteger();
		for (int attempt = 0; attempt < 2; attempt++) {
			var files = integration();
			var configAction = (MigrationAction<ConfigContext>) context -> {
				transforms.incrementAndGet();
				context.capture("lobby", context.read("settings").at("/connection/routing/lobby"));
				move().apply(context);
			};
			var database = new MigrationStream<>("plugin/database", new JdbcIntegration("db", source), 1,
					List.of(new Migration<JdbcContext>("import", 0, 1, "1", context -> {
						try (var statement = context.connection().prepareStatement("INSERT INTO imported_settings VALUES (?)")) {
							statement.setString(1, files.input("plugin/config", "lobby").textValue()); statement.executeUpdate();
						}
						if (fail.get()) throw new IllegalStateException("before database commit");
					})), Map.of(), context -> 0, null);
			var config = new MigrationStream<>("plugin/config", files, 2, stream(files, configAction).getMigrations(),
					Map.of("plugin/database", 1), context -> context.version("settings", "/_version"), null);
			var runner = new Strata(List.of(config, database), Map.of());
			if (attempt == 0) { assertThrows(IllegalStateException.class, runner::execute); fail.set(false); }
			else runner.execute();
		}
		assertEquals(1, transforms.get());
		try (var connection = source.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT setting_value FROM imported_settings")) {
			assertTrue(result.next()); assertEquals("hub", result.getString(1)); assertFalse(result.next());
		}
	}

	@Test void preparedResourcesCannotDisappearFromDeclarations() throws Exception {
		source();
		try (var prepared = runner(integration(), move()).prepare()) { }
		var fewer = new ConfiguraIntegration("files", Config.yaml(), directory, List.of(resource("settings", true)));
		assertThrows(IllegalStateException.class, () -> runner(fewer, move()).execute());
		assertFalse(Files.exists(directory.resolve("routing.yml")));
	}

}
