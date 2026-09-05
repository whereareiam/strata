# Strata

Strata is a small Java library for upgrading persisted plugin state: configuration files, database tables, and resources supplied by other
libraries. Java 17 or newer is required.

Strata coordinates migration streams. Configura and Dialectica remain responsible
for convenient configuration and database access. Their Strata integrations live
under `strata-integration`; neither library depends on Strata.

## Installation

```kotlin
repositories {
    maven("https://registry.whereareiam.me/maven/packages")
}

dependencies {
    implementation("me.whereareiam:strata-common:1.0.0")
    implementation("me.whereareiam:strata-integration-configura:1.0.0")
    // Choose JDBC alone or the Dialectica integration, which includes JDBC support.
    implementation("me.whereareiam:strata-integration-dialectica:1.0.0")
}
```

Supply your database's JDBC driver separately. Minecraft plugins using a dependency
loader can load these artifacts through that loader instead of bundling them.
Each integration is optional; `strata-api` and `strata-common` have no Jackson,
Jdbi, JDBC-driver, or Minecraft dependencies.

## Move a setting between files

This example upgrades an existing version-1 `settings.yml` into version 2 and
moves `connection.routing` into a separate document. The destination can be absent.
The resource names and paths are explicit; include the actual file extension.

```java
import me.whereareiam.configura.Config;
import me.whereareiam.strata.*;
import me.whereareiam.strata.common.Strata;
import me.whereareiam.strata.integration.configura.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

var configs = new ConfiguraIntegration(
        "plugin-files", Config.yaml(), dataDirectory,
        List.of(
                ConfigResource.builder()
                        .name("settings").path(Path.of("settings.yml"))
                        .required(true).writable(true).build(),
                ConfigResource.builder()
                        .name("routing").path(Path.of("routing.yml"))
                        .writable(true).build()
        )
);

var splitRouting = Migration.<ConfigContext>builder()
        .id("split-routing")
        .fromVersion(1).toVersion(2)
        .fingerprint("split-routing-revision-1")
        .action(context -> {
            context.move("settings", "/connection/routing",
                    "routing", "/routing", ConflictPolicy.FAIL_IF_DIFFERENT);
            context.document("settings").put("_version", 2);
        })
        .build();

var configuration = MigrationStream.<ConfigContext>builder()
        .id("my-plugin/config")
        .integration(configs)
        .currentVersion(2)
        .detector(context -> context.version("settings", "/_version"))
        .migrations(List.of(splitRouting))
        .build();

var strata = new Strata(List.of(configuration), Map.of("platform", "velocity"));
strata.execute();
```

`move`, `copy`, `rename`, and `merge` use JSON pointers to object members. Arrays
can be transferred as whole values; use Jackson's tree API for element-level
transformations. `delete(name)` retires an entire document.

`context.document(name)` exposes a mutable staged `ObjectNode`;
`context.read(name)` returns a detached snapshot, including read-only resources.
Unregistered resources are rejected. Set `writable(false)` for input-only files.
All writable resources should belong to the same integration for their installation.

The default conflict policy shown above accepts an identical destination but
rejects a different value. `PRESERVE_DESTINATION` and `REPLACE_DESTINATION` are
explicit alternatives. A move removes the source even when preserving the destination.
Missing required values fail rather than being mistaken for successful earlier work.

Use `context.defaults("settings", Settings.class)` to apply Configura's defaults,
merge policies, and binding hooks to the staged tree. A resource's `validator`
callback can enforce additional invariants before anything is published. Strata
never infers a model from old contents or silently assigns a document version.

## Java migration classes

For larger changes, implement `MigrationAction<ConfigContext>` or
`MigrationAction<JdbcContext>` and register an instance with `.action(...)`.
The definition owns the stable identity and version; package names and class names
are not history identities. Constructors should not need active plugin services.

Java fingerprints are explicit revisions or build-generated digests. Update the
fingerprint whenever behavior, validation, or associated resources change. Once
published, keep a migration immutable and add a new transition instead. Strata
cannot infer arbitrary Java behavior from a class name.

## SQL resources

```text
src/main/resources/strata/my-plugin/database/
├── common/
│   └── V001__create_profiles.sql
├── postgresql/
│   └── V002__change_profile_key.sql
└── sqlite/
    └── V002__change_profile_key.sql
```

```java
import me.whereareiam.strata.integration.jdbc.*;

var database = new JdbcIntegration("main-database", dataSource);
var scripts = SqlMigrationSource.discover(
        getClass().getClassLoader(), "strata/my-plugin/database", "postgresql");

var databaseStream = MigrationStream.<JdbcContext>builder()
        .id("my-plugin/database")
        .integration(database)
        .currentVersion(2)
        .detector(context -> {
            if (context.tableExists("profiles"))
                throw new IllegalStateException("Existing schema needs a legacy detector");
            return 0; // The application's detector must check all its relevant state.
        })
        .migrations(scripts)
        .build();
```

Direct scripts, `common/`, and the selected dialect directory are combined.
Duplicate versions fail; a dialect file never silently replaces a common file.
`V003__description.sql` declares a `2 -> 3` transition. Java transitions can bridge
larger version gaps explicitly. Resources are discovered in exploded classes and
plugin JARs, including JARs without directory entries. Explicit registration is
also available through `SqlMigrationSource.resource(loader, path)`.

Every SQL resource gets a SHA-256 checksum. Scripts may operate across multiple
tables on the same connection. SQL and Java actions can share a stream; duplicate
source versions or missing transitions fail before execution.

The parser supports quoted literals, comments, PostgreSQL dollar bodies, SQLite
trigger bodies, and MySQL `DELIMITER` directives. Executable MySQL comments are
rejected; express those operations through a reviewed Java action. Scripts cannot
manage transactions, connection settings, or attach another database.

### Transactions and DDL

PostgreSQL and SQLite support the ordinary transactional path for supported DDL.
H2 and MySQL/MariaDB require explicit recovery for operations that may implicitly
commit. Unsupported transactional SQL fails rather than promising rollback.

```java
var create = SqlMigrationSource.resource(loader, "strata/db/V001__create_profiles.sql");
var recoverable = SqlMigrationSource.recoverable(create, context -> {
    // Inspect the actual table, columns and constraints. Complete partial work
    // safely, then verify the intended layout. Do not simply rerun the script.
    verifyOrCompleteProfiles(context);
}, "profiles-recovery-revision-1");
```

For Java, implement `RecoverableJdbcAction`. Strata records intent before the first
attempt. After interruption it calls `recover`, validating the stored fingerprint
first. Transactional data changes and history commit together; recoverable DDL has
durable intent and a checked completion instead.

JDBC leases currently support PostgreSQL, MySQL/MariaDB, H2, and SQLite. SQLite
requires a plain file JDBC URL and a filesystem supporting exclusive locks; memory
and URI-style URLs are rejected for writable sessions. Connection pools need at
least two connections for the separate lease used by server databases and H2.

### Dialectica and Jdbi

```java
var database = new DialecticaIntegration("main-database", dataSource);
var action = DialecticaIntegration.action("postgres", handle -> {
    handle.createUpdate("INSERT INTO profiles (id) SELECT id FROM legacy_profiles")
            .execute();
});
```

Register the action in a `Migration<JdbcContext>`. All operations use Strata's
borrowed connection. Do not begin/end transactions or open an independent handle.
Normal application schema initialization happens after Strata finishes.

## Streams, platforms, and startup

Use stable stream names such as `my-plugin/config`, `my-plugin/database`, and
`my-plugin/platform/velocity/config`. Keep migrations with their owning plugin,
feature, provider, or platform module, normally in an `upgrade` package.

Platform bootstraps explicitly collect contributors before normal configuration
binding, database schema initialization, repository creation, or listener registration.
Shared migrations are registered on both platforms. Platform migrations can be
registered only by that platform, or use:

```java
.appliesTo(environment -> "velocity".equals(environment.get("platform")))
.requires(Map.of("my-plugin/config", 2))
```

Excluded streams consume no history. Required streams must be available and reach
the declared version. Cycles, missing prerequisites, future versions, missing
historical declarations, and changed fingerprints stop the upgrade.

Local files migrate once per installation. Database history lives in the database,
so multiple proxies sharing it apply each transition once. Migration leases coordinate
upgraders; incompatible old plugin instances must stop accessing the schema during
an upgrade. Platform changes need explicit conversion when their stored formats differ.

## Preparation and recovery

```java
List<String> pending = strata.inspect(); // Read-only; no transformations or history creation.
try (var prepared = strata.prepare()) {
    // Config transformations and validation have completed; SQL has not run.
    prepared.commit();
}
```

An optional third `Strata` constructor argument accepts `List<UpgradeVerification>`
for final schema/data/config checks. They run on every execution, including when
no migrations are pending; a failure blocks startup without replaying committed work.

`execute()` performs preparation and commit and always releases leases. Prepared
plans are single-use. Closing an uncommitted plan releases leases but retains durable
config staging, so the next run can resume with the original prepared inputs.

All pending declarations are validated before transformation. Config preparations
happen before any SQL actions. Commits follow stream dependencies. To migrate a
database before publishing prepared files, make the config stream require that
database stream. Preparation actions must not assume prerequisite SQL has already
run: SQL runs during commit.

Strata does not expose a global transaction across files and databases. A database
commit is authoritative in its own history; a subsequent restart resumes prepared
config publication. For config-derived database inputs, call `context.capture("old-value", node)` in
the config action, then `configs.input("my-plugin/config", "old-value")` inside
the database action. Captured values are persisted during preparation and restored
without rerunning the config action after restart. Do not capture transient Java
values that could change after restart.

Config state is stored under `.strata/configura/`:

- `prepared/`: original and transformed bytes, before publication.
- `committing/`: durable intent; reopening completes interrupted replacements.
- `completed/`: archived originals and manifests for recovery evidence.
- `history/`: committed stream history.

The engine rejects changed inputs, altered prepared definitions, corrupted backups,
and symbolic-link resource paths. Keep the journal with the installation and protect
it like the original configs: it may contain credentials. Atomic file replacement
must be supported by the filesystem. Multi-file replacement is recoverable, not
simultaneously visible; keep the plugin unavailable until execution succeeds.

Prepared state is deliberately retained on failure. Do not delete it to bypass an
error after a database commit. Restore the matching inputs/build or perform a reviewed
recovery using the original backups. Completed archives can be retained according to
your backup policy after the installation has been verified.

## Fresh and legacy installations

Every stream requires a detector when history is absent. For a fresh installation,
verify relevant resources are absent, return `0`, and supply creation transitions.
For a known existing installation, verify its layout and return its baseline.
Never identify an existing installation as current merely because history is missing.

For Configura, `context.version("settings", "/_version")` reads a strict integer
marker. Add structural checks when historical releases reused a marker. Document
markers remain portable metadata; Strata history is authoritative after adoption.

`LegacyDialectica.detector(table, scope, expectedVersionsAndNames, baseline,
validateLayout)` checks the exact old scope entries and then invokes your actual
schema/data validator. Old history lacks checksums, so the validator is required.
The old table remains untouched. No old migration runner executes alongside Strata.

## Other libraries and tests

Implement `MigrationIntegration<C>` and `MigrationSession<C>` for another library.
The session supplies typed resource access, authoritative history, preparation,
commit/recovery behavior, and lease release. Writable sessions recover their pending
commits; read-only sessions must not mutate anything. Reuse one integration instance
for streams sharing a resource. Lock ordering uses stable integration identities.

`strata-testkit` provides `MemoryIntegration<C>` for plugin migration tests. Supply
a deep-copy function to isolate preparation from committed state. It is for tests,
not durable installation history.

## Building and publishing

During coordinated development, place the three checkouts side by side:

```sh
./gradlew -Pstrata.siblings=true test build
./gradlew -Pstrata.siblings=true -PintegrationTests=true test
```

The second command includes Docker-backed PostgreSQL/MariaDB tests. The default
suite uses H2/SQLite and fault-injection tests without containers. Configure
`DOCKER_HOST` if Docker uses a non-default socket.

Standalone builds use the published Configura 1.0.0 and Dialectica 1.0.0 artifacts:

```sh
./gradlew test build
```

Override them with `-PconfiguraVersion=<version>` and
`-PdialecticaVersion=<version>` when testing a different compatible release.

Publish the cleaned-up Configura and Dialectica versions first. Strata development
publishing is manual and asks for those versions. For releases, set repository
variables `STRATA_CONFIGURA_VERSION` and `STRATA_DIALECTICA_VERSION` to the compatible
published versions. The release workflow uses them in the published dependency metadata.
Do not publish Strata against unspecified `dev` dependencies.

PR checks run fast tests. Release workflows run the complete test suite, publish
public Maven packages through `whereareiam/devops` OIDC, and attach binary, source,
and Javadoc JARs. Release Drafter follows `dev`; label changes with `feature`,
`change`, `bug`, or `dependencies`, plus `major` for breaking changes or
`skip-changelog` when appropriate. No publishing happens on a development push.
