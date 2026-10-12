# Strata

Strata upgrades what an application has left on a user's machine: its database tables and its
configuration files. You declare numbered migrations; on startup Strata runs the ones an
installation has not seen yet and remembers them. Java 17 or newer is required.

## Installation

```kotlin
repositories {
    maven("https://registry.whereareiam.me/maven/packages")
}

dependencies {
    implementation("me.whereareiam:strata:3.0.0")

    // One adapter per kind of thing you migrate:
    implementation("me.whereareiam:strata-adapter-database:3.0.0")       // a database
    implementation("me.whereareiam:strata-adapter-database-jdbi:3.0.0")  // ... reached through your Jdbi
    implementation("me.whereareiam:strata-adapter-database-dialectica:3.0.0") // ... changed through Dialectica
    implementation("me.whereareiam:strata-adapter-configura:3.0.0")  // config files, through Configura

    testImplementation("me.whereareiam:strata-adapter-memory:3.0.0") // an in-memory target for tests
}
```

Bring your own JDBC driver. `strata` itself has no dependencies; each adapter brings the library
it is for.

## Upgrade a database and a config directory

A **stream** is the versioned history of one thing you own in one **target**: your tables in a
database, or your files in a config directory. Version `n` is the state after migration `n`.

```java
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.Strata;
import me.whereareiam.strata.adapter.configura.ConfigContext;
import me.whereareiam.strata.adapter.configura.ConfiguraTarget;
import me.whereareiam.strata.adapter.configura.StrataFeature;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import me.whereareiam.strata.adapter.database.DatabaseTarget;
import me.whereareiam.strata.model.MigrationReport;

import java.util.List;
import java.util.OptionalInt;

MigrationStream<DatabaseContext> database = MigrationStream.<DatabaseContext>builder()
        .id("my-plugin/database")
        .target(new DatabaseTarget(dataSource))
        .baseline((db, latest) -> db.tableExists("accounts") ? OptionalInt.of(0) : OptionalInt.empty())
        .creation(db -> db.execute("CREATE TABLE accounts (name VARCHAR(16) PRIMARY KEY, last_login BIGINT)"))
        .migration(1, "add-last-login", db -> db.execute("ALTER TABLE accounts ADD COLUMN last_login BIGINT"))
        .migration(2, "lowercase-names", db -> db.update("UPDATE accounts SET name = LOWER(name)"))
        .build();

// The Configura your application loads its files with. StrataFeature keeps it from dropping the version.
Configura configura = Config.builder().feature(new StrataFeature()).build();

MigrationStream<ConfigContext> configuration = MigrationStream.<ConfigContext>builder()
        .id("my-plugin/config")
        .target(new ConfiguraTarget(configura, dataDirectory, "settings.yml"))
        .baseline((files, latest) -> files.exists("settings.yml") ? OptionalInt.of(0) : OptionalInt.empty())
        .migration(1, "split-routing", files -> files.move("settings.yml", "/connection/routing", "routing.yml", "/routing"))
        .build();

MigrationReport report = new Strata(List.of(database, configuration)).migrate();
```

Call `migrate()` once during startup, before the application reads its configuration or touches
its tables. It upgrades the streams in the order given, returns what it ran, and throws a
`MigrationException` when the installation could not be brought up to date; do not start then.
`pending()` lists what `migrate()` would run without running anything.

### Rules for migrations

- Versions start at 1 and have no gaps. Add new migrations at the end.
- Once released, never change a migration's version or what it does: an installation that ran
  it only remembers the version.
- Stream ids are lowercase segments separated by slashes (`my-plugin/provider/database`) and stay
  the same for the lifetime of the application.
- A migration writes only to its own target. It may read anything.

### New installations and installations older than Strata

When a target has no version for a stream, Strata cannot know where the installation stands, so it
asks the stream's **baseline**. It answers whether the installation is new, and if not, which
version it is already at:

- `OptionalInt.empty()` for a new installation. Strata runs the stream's **creation** action, which
  builds everything in its newest form, and records the latest version. No migration runs.
- `OptionalInt.of(0)` for an existing installation that never ran a migration. Everything runs.
- Anything up to `latest` if an older mechanism left a version you can read, or the installation
  already has the newest layout. The creation action does not run.

The creation action runs like a migration: under the target's lock, in the same transaction as the
version it records, and rolled back as far as the target allows when it throws. Two instances
installing at once therefore create once; the second one waits for the lock and finds the latest
version. `migrate()` reports it as an entry named `creation`; `pending()` lists migrations only, so
a new installation has nothing pending.

Without a baseline, an installation without history is new when the stream has a creation action,
and otherwise starts at 0, which suits a stream whose migrations themselves create the tables.
Without a creation action, a new installation is only recorded at the latest version and the
application creates its tables and files itself.

A stream with a creation action and no migrations yet is recorded at version 0, so its first
migration runs on the installations created before it. A stream with neither is skipped.

## SQL scripts

```text
src/main/resources/strata/my-plugin/database/
├── V001__add_last_login.sql          runs on every database
├── postgresql/V002__widen_name.sql   runs on PostgreSQL only
└── mysql/V002__widen_name.sql        runs on MySQL, and on MariaDB unless there is a mariadb/ script
```

```java
import me.whereareiam.strata.adapter.database.SqlMigration;

.migrations(SqlMigration.discover(getClass().getClassLoader(), "strata/my-plugin/database"))
```

`V<version>__<name>.sql` becomes migration `<version>` named `<name>`. The folder is the database
product in lower case: `postgresql`, `mysql`, `mariadb`, `h2` or `sqlite`. A version has either one
script for every database or one per product. Scripts and Java migrations can share a stream;
`SqlMigration.resource(loader, path)` loads a single script.

Scripts may contain several statements, comments, PostgreSQL dollar-quoted bodies, SQLite trigger
bodies and MySQL `DELIMITER` lines. Do not put `BEGIN`, `COMMIT` or `ROLLBACK` in them.

## Config files

A config migration names files by their path relative to the directory and values by JSON pointer.

| Call | Effect |
| --- | --- |
| `files.document("settings.yml")` | The file as a mutable Jackson `ObjectNode`, for anything the helpers do not cover. A missing file opens empty and is created once something is in it. |
| `files.move(file, pointer, targetFile, targetPointer)` | Moves a value, across files if needed. Returns false and does nothing when the value is not there. |
| `files.rename(file, pointer, targetPointer)` | Moves a value inside one file. |
| `files.put(file, pointer, value)` / `files.remove(file, pointer)` | Sets or takes out a value. |
| `files.delete(file)` / `files.exists(file)` | Deletes a file, or asks whether it is there. |
| `files.moveIfAbsent(file, pointer, targetFile, targetPointer)` | Moves a value only into an empty place. When the new place holds a value already, that one stays and the moved value is dropped. |
| `files.moveSection(file, pointer, targetFile, targetPointer)` | Merges a section into another one, value by value and down through the sections both hold; values the target holds stay. Removes the moved section and the sections above it it leaves empty. An empty pointer names the whole document. |
| `files.moveFile(file, targetFile)` | Moves a whole file into another one the way `moveSection` merges, and deletes it. Its `_version` is not carried over. |
| `files.removeIfEmpty(file, pointer)` | Removes a section that holds nothing, and the sections above it it leaves empty. The document itself stays. |
| `files.removeVersion(file)` | Takes `_version` out of a file that carries one without meaning, such as one an earlier tool stamped. Never call it on another stream's version file. |
| `files.deleteDirectoryIfEmpty(directory)` | Deletes a directory once nothing but empty directories is left in it, counting the migration's own changes. Returns false and leaves it when anything else is in it. |

Pointers name keys as RFC 6901 writes them: `~` as `~0` and `/` as `~1`. Build a pointer to a key
you read from a file with Jackson, `JsonPointer.compile("/rules").appendProperty(key).toString()`.

### Migrating directories older than the version

An installation sometimes has values in more than one place, because earlier builds of the
application read them from different places or a user copied a file. The `IfAbsent`, `IfEmpty` and
section moves treat the value at the newer place as the one that counts: they fill only empty
places and drop what they cannot place. Each of them does nothing when there is nothing to do, so a
migration made of them can run on a directory that is already past it and finds nothing left to
change.

```java
.migration(3, "feature-layout", files -> {
    files.moveFile("verification.yml", "features/verification/settings.yml");
    files.moveSection("settings.yml", "/connection/sentinels", "features/sentinel/settings.yml", "/sentinels");
    files.moveIfAbsent("settings.yml", "/connection/attemptTtl", "providers/providers.yml", "/behavior/attemptTtl");
    files.removeIfEmpty("settings.yml", "/connection");
    files.deleteDirectoryIfEmpty("providers/capabilities");
})
```

A config target takes the file that carries the stream's version as its third argument. Strata
writes the version there as `_version`, the first value in the file, in the same step as the
migration's changes. The version therefore travels with the configuration when a user copies or
restores it, and one config target serves one stream. On a new installation the baseline's answer is
recorded by creating that file with `_version` and whatever the creation action wrote; Configura's
`update` then adds the rest.

`_version` is not part of your model. `StrataFeature` reserves the key, so Configura carries it
through every rewrite of the file; a `ConfiguraTarget` refuses a Configura without the feature.

Only files whose content actually changed are rewritten. Rewriting goes through Configura, so
comments in a rewritten file are lost. Run Strata before Configura's own `update`, which then fills
in defaults for anything new.

## Moving data between a database and a config file

Each migration belongs to one target, so a move across targets is two migrations: one copies in
the destination's stream, a later one deletes in the source's stream. Register the copying stream
first.

```java
ConfiguraTarget configs = new ConfiguraTarget(configura, dataDirectory, "settings.yml");

// database stream, registered first
.migration(3, "import-routes", db -> {
    for (var route : configs.read("settings.yml").path("routes").properties())
        db.update("INSERT INTO routes (name, target) VALUES (?, ?)", route.getKey(), route.getValue().asText());
})

// config stream, registered second
.migration(2, "drop-routes", files -> files.remove("settings.yml", "/routes"))
```

The copy is recorded together with its rows, and the source is untouched until then. If the process
stops in between, the next start skips the copy and runs the delete.

## What happens when something goes wrong

| Target | A migration throws | The process dies mid-migration |
| --- | --- | --- |
| PostgreSQL, SQLite | The whole migration is rolled back, schema changes included. | Same; the transaction never committed. |
| MySQL, MariaDB, H2 | Data changes are rolled back. Schema changes stay, because these databases commit on every DDL statement. | Same. |
| Config directory | No file changes. | Either no file changed, or the next start finishes changing all of them, deleted directories included. |

A creation action behaves the same. In every case the failed migration is not recorded and runs
again next time; migrations before it stay applied. On MySQL, MariaDB and H2, write schema migrations so that a second run succeeds, for
example with `IF NOT EXISTS` or `db.columnExists(...)`, and keep one schema change per migration.

`MigrationFailedException` carries the stream, version and name of the migration that threw.
`MigrationVersionException` means the installation does not fit this build: it was written by a
newer build, or is older than the oldest migration the build still ships.

Two instances starting at once do not migrate twice. PostgreSQL, MySQL and MariaDB are locked for
the duration of the upgrade and the second instance waits, one minute by default
(`new DatabaseTarget(connector, timeout)`). A config directory can be open in one process only; a
second one fails immediately. H2 and SQLite are treated as owned by a single process.

## What Strata stores

- In a database: the table `strata_history` with one row per stream and version, with the
  migration's name (`baseline` or `creation` for the entries Strata adds itself) and the time it
  ran. The highest version is where the stream stands.
- In a config file: `_version`.
- In a config directory: the folder `.strata/`, with `backup/<stream>/v<version>/` holding every
  file as it was before that migration changed it. Strata never reads it back, so the folder can be
  deleted at any time; the backups may contain credentials, like the originals.

## Jdbi

`new DatabaseTarget(dataSource)` reaches the database through plain JDBC. If your application uses
Jdbi, hand the target a `JdbiConnector` instead:

```java
import me.whereareiam.strata.adapter.database.jdbi.JdbiConnector;

MigrationStream<DatabaseContext> database = MigrationStream.<DatabaseContext>builder()
        .id("my-plugin/database")
        .target(new DatabaseTarget(new JdbiConnector(jdbi)))
        .migrations(SqlMigration.discover(loader, "strata/my-plugin/database"))
        .migration(3, "lowercase-names", db -> jdbi.useHandle(handle ->
                handle.execute("UPDATE accounts SET name = LOWER(name)")))
        .build();
```

While a stream is upgraded, your `Jdbi` finds the migration's handle for the current thread. Inside
a migration you therefore use your own `jdbi` as everywhere else, with its plugins, mappers and
DAOs, including Dialectica's `DialecticaPlugin`, and everything runs on the migration's connection and
inside its transaction. A `useTransaction` in a migration joins that transaction and does not
commit it. Stay on the thread the migration was called on.

## Dialectica

With `strata-adapter-database-dialectica`, a database stream's creation and its schema migrations
can be written as [Dialectica](https://github.com/whereareiam/Dialectica) schemas and schema changes
instead of SQL. The module brings Dialectica's entry point and models, but no dialect: add
`dialectica-bundle` for all five databases, or the `dialectica-dialect-<database>` modules of the
databases you support.

```kotlin
implementation("me.whereareiam:strata-adapter-database-dialectica:3.0.0")
implementation("me.whereareiam:dialectica-bundle:3.0.0")             // every dialect, or one per database:
// implementation("me.whereareiam:dialectica-dialect-postgresql:3.0.0")
```

```java
import me.whereareiam.dialectica.schema.IndexDefinition;
import me.whereareiam.dialectica.schema.Schema;
import me.whereareiam.dialectica.schema.change.AddColumn;
import me.whereareiam.dialectica.schema.change.CreateIndex;
import me.whereareiam.strata.adapter.database.dialectica.SchemaCreation;
import me.whereareiam.strata.adapter.database.dialectica.SchemaMigration;

MigrationStream<DatabaseContext> database = MigrationStream.<DatabaseContext>builder()
        .id("my-plugin/database")
        .target(new DatabaseTarget(new JdbiConnector(jdbi)))
        .creation(SchemaCreation.of(Schema.of(accounts)))     // a new installation gets the newest tables
        .migration(SchemaMigration.of(2, "username-key",
                new AddColumn("accounts", usernameKey),
                new CreateIndex("accounts", IndexDefinition.unique("accounts_username_key", "username_key"))))
        .migration(3, "fill-username-keys", db -> db.update("UPDATE accounts SET username_key = LOWER(username)"))
        .build();
```

`SchemaCreation.of(schema)` is a creation action that creates every table of the schema.
`SchemaMigration.of(version, name, changes...)` is a migration that applies the changes in order.
Both write through `Dialectica.on(...)` on the migration's connection, so the schema is written under
the target's lock and inside the migration's transaction. On PostgreSQL and SQLite a failed schema
migration is therefore rolled back completely. On MySQL, MariaDB and H2, Dialectica skips every change
the database already has, so a migration that stopped halfway finishes on the next start. Data steps
stay ordinary Java migrations, and SQL scripts can share the stream.

Keep the creation's schema and the migrations in step: the creation describes what the last
migration leaves behind.

## Testing migrations

`MemoryTarget<C>` hands any object to migrations as their context and keeps the history in memory.
Use it to test code that registers streams without a database. To test a migration itself, run it
against the real adapter with H2, SQLite or a temporary directory.

```java
MemoryTarget<List<String>> target = new MemoryTarget<>(new ArrayList<>());
// build a MigrationStream<List<String>> on it, migrate, then:
assertEquals(List.of(1, 2), target.history("my-plugin/data").stream().map(AppliedMigration::getVersion).toList());
```

## Migrating something else

Implement `MigrationTarget<C>` and `MigrationSession<C>`, depending on `strata-api` only. A session holds the
target's lock, reads the version a stream has reached (empty when it knows nothing about the stream,
which is not the same as a recorded 0), and runs an action and records its version as one unit.
`MemoryTarget` is the smallest example.

To reach a database through another library, implement `DatabaseConnector` from
`strata-adapter-database-api`, as `JdbiConnector` does: it opens the connection migrations run on
and releases it afterwards.

## Building

```sh
./gradlew build
./gradlew test -PintegrationTests=true   # adds the PostgreSQL, MariaDB and MySQL tests, needs Docker
```

The Configura adapter needs Configura 2.0.0 or newer, the first release in which a feature can
reserve keys. The version it is built against is set in `gradle/libs.versions.toml`.

To build against a Configura change that is not released yet, run `./gradlew publishToMavenLocal`
in the Configura checkout, which publishes it as version `dev`, and set `configura = "dev"` in
`gradle/libs.versions.toml` while you work.
