package me.whereareiam.strata.adapter.jdbi;

import me.whereareiam.strata.Migration;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.adapter.jdbc.JdbcContext;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.exception.MigrationFailedException;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbiTargetTest {
	private final Jdbi jdbi = Jdbi.create("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");

	@Test
	void handsMigrationsAHandleWithTheApplicationsConfiguration() {
		jdbi.define("accounts", "plugin_accounts");
		MigrationStream<JdbiContext> stream = accounts().build();

		new Strata(List.of(stream)).migrate();

		assertEquals(List.of("first"), names("plugin_accounts"));
		assertEquals(List.of(1, 2), jdbi.withHandle(handle -> handle.createQuery("SELECT version FROM strata_history ORDER BY version").mapTo(Integer.class).list()));
	}

	@Test
	void runsMigrationsWrittenForPlainJdbc() {
		jdbi.define("accounts", "plugin_accounts");
		Migration<JdbcContext> plain = new Migration<>(3, "add-second-account", context -> context.update("INSERT INTO plugin_accounts (name) VALUES (?)", "second"));
		MigrationStream<JdbiContext> stream = accounts()
				.migrations(List.of(plain))
				.build();

		new Strata(List.of(stream)).migrate();

		assertEquals(List.of("first", "second"), names("plugin_accounts"));
	}

	@Test
	void takesBackTheDataOfAFailedMigration() {
		jdbi.define("accounts", "plugin_accounts");
		MigrationStream<JdbiContext> broken = accounts()
				.migration(3, "broken", context -> {
					context.getHandle().execute("INSERT INTO <accounts> (name) VALUES (?)", "lost");
					throw new IllegalStateException("broken");
				})
				.build();

		assertThrows(MigrationFailedException.class, () -> new Strata(List.of(broken)).migrate());

		assertEquals(List.of("first"), names("plugin_accounts"));
	}

	private MigrationStream.Builder<JdbiContext> accounts() {
		return MigrationStream.<JdbiContext>builder()
				.id("plugin/database")
				.target(new JdbiTarget(jdbi))
				.migration(1, "create-accounts", context -> context.getHandle().execute("CREATE TABLE <accounts> (name VARCHAR(32) PRIMARY KEY)"))
				.migration(2, "add-first-account", context -> context.getHandle().execute("INSERT INTO <accounts> (name) VALUES (?)", "first"));
	}

	private List<String> names(String table) {
		return jdbi.withHandle(handle -> handle.createQuery("SELECT name FROM " + table + " ORDER BY name").mapTo(String.class).list());
	}
}
