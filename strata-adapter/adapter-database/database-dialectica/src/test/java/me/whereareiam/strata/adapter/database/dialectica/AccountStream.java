package me.whereareiam.strata.adapter.database.dialectica;

import me.whereareiam.dialectica.schema.Column;
import me.whereareiam.dialectica.schema.Index;
import me.whereareiam.dialectica.schema.Schema;
import me.whereareiam.dialectica.schema.Table;
import me.whereareiam.dialectica.schema.change.AddColumn;
import me.whereareiam.dialectica.schema.change.CreateIndex;
import me.whereareiam.dialectica.schema.change.CreateTable;
import me.whereareiam.dialectica.schema.type.ColumnType;
import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.Strata;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import me.whereareiam.strata.adapter.database.DatabaseTarget;
import me.whereareiam.strata.model.MigrationReport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The stream the tests upgrade: an accounts table that a later build gives a unique, lower-case
 * username key.
 */
final class AccountStream {
	static final String STREAM = "plugin/accounts";

	/** The table as the first build created it. */
	static final Table FIRST = Table.builder("accounts")
			.column(Column.builder("id", ColumnType.integer()).notNull().build())
			.column(Column.builder("username", ColumnType.varchar(32)).notNull().build())
			.primaryKey("id")
			.build();

	static final Column USERNAME_KEY = Column.builder("username_key", ColumnType.varchar(32)).build();
	private static final Index USERNAME_KEY_INDEX = Index.unique("accounts_username_key", "username_key");

	/** The table as the newest build describes it. */
	static final Schema NEWEST = Schema.of(Table.builder("accounts")
			.column(Column.builder("id", ColumnType.integer()).notNull().build())
			.column(Column.builder("username", ColumnType.varchar(32)).notNull().build())
			.column(USERNAME_KEY)
			.primaryKey("id")
			.index(USERNAME_KEY_INDEX)
			.build());

	private AccountStream() {
	}

	/** The stream of the first build: one migration that creates the table, nothing for new installations. */
	static MigrationStream<DatabaseContext> first(DatabaseTarget target) {
		return MigrationStream.<DatabaseContext>builder()
				.id(STREAM)
				.target(target)
				.migration(SchemaMigration.of(1, "create-accounts", new CreateTable(FIRST)))
				.build();
	}

	/** The stream of the newest build, creating new installations with {@code creation}. */
	static MigrationStream<DatabaseContext> newest(DatabaseTarget target, MigrationAction<DatabaseContext> creation) {
		return MigrationStream.<DatabaseContext>builder()
				.id(STREAM)
				.target(target)
				.creation(creation)
				.migration(SchemaMigration.of(1, "create-accounts", new CreateTable(FIRST)))
				.migration(SchemaMigration.of(2, "username-key",
						new AddColumn("accounts", USERNAME_KEY),
						new CreateIndex("accounts", USERNAME_KEY_INDEX)))
				.migration(3, "fill-username-keys", context -> context.update("UPDATE accounts SET username_key = LOWER(username)"))
				.build();
	}

	/** The stream of the newest build, creating new installations from {@link #NEWEST}. */
	static MigrationStream<DatabaseContext> newest(DatabaseTarget target) {
		return newest(target, SchemaCreation.of(NEWEST));
	}

	static MigrationReport migrate(MigrationStream<DatabaseContext> stream) {
		return new Strata(List.of(stream)).migrate();
	}

	/** Lists what a report ran for the stream as {@code "<version> <name>"}. */
	static List<String> applied(MigrationReport report) {
		return report.getApplied().getOrDefault(STREAM, List.of()).stream()
				.map(entry -> entry.getVersion() + " " + entry.getName())
				.toList();
	}

	/** Lists the history rows of the stream as {@code "<version> <name>"}, in version order. */
	static List<String> history(DataSource dataSource) throws SQLException {
		return strings(dataSource, "SELECT version, name FROM strata_history WHERE stream = '" + STREAM + "' ORDER BY version");
	}

	/** Lists the accounts as {@code "<username> <username_key>"}, in id order. */
	static List<String> accounts(DataSource dataSource) throws SQLException {
		return strings(dataSource, "SELECT username, username_key FROM accounts ORDER BY id");
	}

	static void insert(DataSource dataSource, int id, String username, String usernameKey) throws SQLException {
		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement("INSERT INTO accounts (id, username, username_key) VALUES (?, ?, ?)")) {
			statement.setInt(1, id);
			statement.setString(2, username);
			statement.setString(3, usernameKey);
			statement.executeUpdate();
		}
	}

	static void insertWithoutKey(DataSource dataSource, int id, String username) throws SQLException {
		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement("INSERT INTO accounts (id, username) VALUES (?, ?)")) {
			statement.setInt(1, id);
			statement.setString(2, username);
			statement.executeUpdate();
		}
	}

	static boolean columnExists(DataSource dataSource, String table, String column) throws Exception {
		try (MigrationSession<DatabaseContext> session = new DatabaseTarget(dataSource).open()) {
			return session.context().columnExists(table, column);
		}
	}

	private static List<String> strings(DataSource dataSource, String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement(sql);
		     ResultSet rows = statement.executeQuery()) {
			List<String> values = new ArrayList<>();
			while (rows.next()) values.add(rows.getString(1) + " " + rows.getString(2));

			return values;
		}
	}
}
