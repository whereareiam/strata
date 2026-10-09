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
    implementation("me.whereareiam:strata-common:2.0.0")

    // One adapter per kind of thing you migrate:
    implementation("me.whereareiam:strata-adapter-jdbc:2.0.0")       // a database, plain JDBC
    implementation("me.whereareiam:strata-adapter-jdbi:2.0.0")       // a database, through your Jdbi
    implementation("me.whereareiam:strata-adapter-configura:2.0.0")  // config files, through Configura

    testImplementation("me.whereareiam:strata-adapter-memory:2.0.0") // an in-memory target for tests
}
```

Bring your own JDBC driver. `strata-api` and `strata-common` have no dependencies.

## Upgrade a database and a config directory

A **stream** is the versioned history of one thing you own in one **target**: your tables in a
database, or your files in a config directory. Version `n` is the state after migration `n`.

```java
import me.whereareiam.configura.Config;
import me.whereareiam.configura.Configura;
import me.whereareiam.strata.MigrationStream;
import me.whereareiam.strata.adapter.configura.ConfigContext;
import me.whereareiam.strata.adapter.configura.ConfiguraTarget;
import me.whereareiam.strata.adapter.configura.StrataFeature;
import me.whereareiam.strata.adapter.jdbc.JdbcContext;
import me.whereareiam.strata.adapter.jdbc.JdbcTarget;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.model.MigrationReport;

import java.util.List;

MigrationStream<JdbcContext> database = MigrationStream.<JdbcContext>builder()
        .id("my-plugin/database")
        .target(new JdbcTarget(dataSource))
        .baseline((db, latest) -> db.tableExists("accounts") ? 0 : latest)
        .migration(1, "add-last-login", db -> db.execute("ALTER TABLE accounts ADD COLUMN last_login BIGINT"))
        .migration(2, "lowercase-names", db -> db.update("UPDATE accounts SET name = LOWER(name)"))
        .build();

// The Configura your application loads its files with. StrataFeature keeps it from dropping the version.
Configura configura = Config.builder().feature(new StrataFeature()).build();

MigrationStream<ConfigContext> configuration = MigrationStream.<ConfigContext>builder()
        .id("my-plugin/config")
        .target(new ConfiguraTarget(configura, dataDirectory, "settings.yml"))
        .baseline((files, latest) -> files.exists("settings.yml") ? 0 : latest)
        .migration(1, "split-routing", files -> files.move("settings.yml", "/connection/routing", "routing.yml", "/routing"))
        .build();

MigrationReport report = new Strata(List.of(database, configuration)).migrate();
```

Call `migrate()` once during startup, before the application reads its configuration or creates
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
asks the stream's **baseline** once. It answers with the version the installation is already at:

- `latest` for a new installation, whose tables and files the application is about to create in
  their current form. Strata records that version and runs nothing.
- `0` for an existing installation that never ran a migration. Everything runs.
- Anything in between if an older mechanism left a version you can read.

A stream without a baseline starts every installation at 0. Use that when the migrations themselves
create the tables.

## SQL scripts

```text
src/main/resources/strata/my-plugin/database/
├── V001__add_last_login.sql          runs on every database
├── postgresql/V002__widen_name.sql   runs on PostgreSQL only
└── mysql/V002__widen_name.sql        runs on MySQL, and on MariaDB unless there is a mariadb/ script
```

```java
import me.whereareiam.strata.adapter.jdbc.sql.SqlMigration;

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

A config target takes the file that carries the stream's version as its third argument. Strata
writes the version there as `_version`, the first value in the file, in the same step as the
migration's changes. The version therefore travels with the configuration when a user copies or
restores it, and one config target serves one stream. On a new installation the baseline's answer is
recorded by creating that file with only `_version` in it; Configura's `update` then adds the rest.

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
| Config directory | No file changes. | Either no file changed, or the next start finishes replacing all of them. |

In every case the failed migration is not recorded and runs again next time; migrations before it
stay applied. On MySQL, MariaDB and H2, write schema migrations so that a second run succeeds, for
example with `IF NOT EXISTS` or `db.columnExists(...)`, and keep one schema change per migration.

`MigrationFailedException` carries the stream, version and name of the migration that threw.
`MigrationVersionException` means the installation does not fit this build: it was written by a
newer build, or is older than the oldest migration the build still ships.

Two instances starting at once do not migrate twice. PostgreSQL, MySQL and MariaDB are locked for
the duration of the upgrade and the second instance waits, one minute by default
(`new JdbcTarget(dataSource, timeout)`). A config directory can be open in one process only; a
second one fails immediately. H2 and SQLite are treated as owned by a single process.

## What Strata stores

- In a database: the table `strata_history` with one row per stream and version, with the
  migration's name and the time it ran. The highest version is where the stream stands.
- In a config file: `_version`.
- In a config directory: the folder `.strata/`, with `backup/<stream>/v<version>/` holding every
  file as it was before that migration changed it. Strata never reads it back, so the folder can be
  deleted at any time; the backups may contain credentials, like the originals.

## Jdbi

`JdbiTarget` opens its handle from your `Jdbi`, so migrations see the plugins, mappers and
arguments you registered, including Dialectica's `DialectPlugin`.

```java
MigrationStream<JdbiContext> database = MigrationStream.<JdbiContext>builder()
        .id("my-plugin/database")
        .target(new JdbiTarget(jdbi))
        .migration(1, "lowercase-names", db -> db.getHandle().execute("UPDATE accounts SET name = LOWER(name)"))
        .migrations(SqlMigration.discover(loader, "strata/my-plugin/database"))
        .build();
```

A `JdbiContext` is a `JdbcContext` with a handle, so SQL scripts and migrations written for plain
JDBC work unchanged. Do not open transactions on the handle or close it.

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

Implement `MigrationTarget<C>` and `MigrationSession<C>` from `strata-api`. A session holds the
target's lock, reads the version a stream has reached, and runs an action and records its version as
one unit.
`MemoryTarget` is the smallest example; `JdbcSession` can be reused by libraries built on JDBC, as
the Jdbi adapter does.

## Building

```sh
./gradlew build
./gradlew test -PintegrationTests=true   # adds the PostgreSQL and MariaDB tests, needs Docker
```

The Configura adapter is built against Configura 2.0.0 and needs 2.0.0 or newer, the first release
in which a feature can reserve keys. Pass `-PconfiguraVersion=<version>` for another release, or
`-Pstrata.siblings=true` to build against a checkout at `../Configura`.
