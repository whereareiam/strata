# AGENTS.md

Strata upgrades what an application has left on a user's machine: its database tables and its
configuration files. Applications declare numbered migrations per stream; on startup Strata runs the
ones an installation has not seen yet and remembers them.

## Modules

- `strata-api`: streams, migrations, baselines, the target and session contracts, reports and
  exceptions. Public API: every change is compatibility-sensitive and needs complete Javadocs.
- `strata-common`: the migration engine, which plans and runs a stream against its target.
- `strata`: the entry point applications depend on (`Strata`).
- `strata-adapter/*`: one module per kind of target, published as `strata-adapter-<name>`.
  - `adapter-configura`: config files through Configura.
  - `adapter-database`: databases through JDBC, with SQL scripts; its members are
    `database-api` (the `DatabaseConnector` contract), `database-common`, `database-jdbi` (a
    connector through Jdbi) and `database-dialectica` (creation and schema migrations as Dialectica
    schemas).
  - `adapter-memory`: an in-memory target for tests.
- `build-logic`: the `library` convention every module applies (Java 17, publishing, the version
  from `VERSION`).

Keep an adapter's library out of every module but its own; `strata` itself has no dependencies.

## Build and test

- `./gradlew test build` builds everything and runs the fast tests.
- `-PintegrationTests=true` also runs the tests tagged `database-container` against PostgreSQL,
  MariaDB and MySQL; they need Docker.
- `VERSION=<version> ./gradlew publishToMavenLocal` lets an application build against a local
  version.

## Pull requests and releases

Build each change in its own `feature/...` branch and merge it into `dev` through a pull request.
Every pull request is checked for its title and label and runs the fast tests. Publishing a release
draft tags `dev`, runs the container tests, publishes the Maven artifacts and attaches the jars. A
manual run of `Development build` publishes the selected branch as `<branch>-<short commit>`.

### Release notes

A merged pull request lands in the release draft as `* <title> ([#<number>](<url>))`, under the
category of its label. Readers are application developers.

- Titles use `Area: Description`, with a past-tense description of what changed for an application:
  `Introduced`, `Changed`, `Fixed`, `Removed`.
- Areas: `Migrations` (streams, baselines, creation, ordering and reports), `Database`, `SQL scripts`,
  `Jdbi`, `Dialectica`, `Configuration` (config files through Configura), `Memory`. Add an area only
  for a new adapter or kind of target, and add it to this list in the same pull request.
- Never use module or class names such as `Common` or `Adapter` as an area.

| Label | Use for |
| --- | --- |
| `feature` | something new for applications |
| `change` | changed behavior or API |
| `bug` | a fix for a bug that shipped in a release |
| `dependencies` | a library update |
| `skip-changelog` | refactors, build, CI, tests and documentation, and fixes of bugs that never shipped |

Add `major` next to the category when applications must change their code or migrations on
upgrade, or when stored history is not carried over.

Text written by hand, the subtitle, the summary and upgrade notes, lives in `.github/release-notes.md`;
it replaces the draft's placeholders while its `version` matches the drafted version. The release
workflow stops while a placeholder is left.
