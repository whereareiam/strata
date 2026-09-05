# Verification and adoption notes

This record describes validation performed before the 1.0.0 releases.

## Checked

Final suites: **195 tests passed** — Strata 46, Configura 130, Dialectica 19; no failures or skips.
Binary/source/Javadoc artifact names are unique within each repository.

- Strata planning: dependencies, cycles, platform exclusion, version gaps, changed fingerprints,
  legacy adoption, abandoned preparation, repeated execution, final verification failures.
- Configura integration: cross-file moves, conflicts, validation, deletion, fresh installations,
  each replacement interruption boundary, changed inputs/declarations, durable captured inputs,
  and database/config coordination across restart.
- JDBC: H2 and SQLite, plus PostgreSQL 18.1 and MariaDB 12 containers; transactional rollback,
  explicit implicit-DDL recovery, shared history, concurrent runners, read-only detection.
- SQL discovery: filesystem and JAR resources, dialect selection, duplicate versions, checksums,
  quoted semicolons, comments, dollar bodies, SQLite triggers and MySQL delimiter directives.
- Dialectica integration: Jdbi connection ownership and exact legacy history adoption with layout validation.
- Existing Configura and Dialectica suites after removing their migration runners.
- Binary, source and Javadoc JAR generation; standalone Maven dependency resolution using an isolated
  filesystem repository populated by the three builds, without composite-source substitution.
  The 1.0.0 release preflight also passed against Configura 1.0.0 and Dialectica 1.0.0
  downloaded from the public registry.
- All three workflow sets validated using actionlint 1.7.12.

## Publication setup

Release Drafter labels exist on all three GitHub repositories. The default branch is dev.

The approved registry CI mappings match each exact GitHub repository and permit only
workflow_dispatch or release events. Access is limited to the existing public whereareiam
Maven repository. Its verified external URL is https://registry.whereareiam.me/maven/packages.
A live anonymous download of an existing Toolkit artifact succeeded through that alias.
Release publication is performed by the GitHub release workflows.

Strata 1.0.0 depends on Configura 1.0.0 and Dialectica 1.0.0. The repository
variables STRATA_CONFIGURA_VERSION and STRATA_DIALECTICA_VERSION select release dependencies;
manual development publishing accepts these versions as inputs. Normal pull-request verification
uses sibling dev checkouts; standalone verification accepts explicit published dependency versions.

## Boundaries

Java migrations carry explicit fingerprints: keep them immutable after release. SQL scripts are
checksummed automatically. Migration resource contexts are cooperative Java APIs, not a sandbox
for untrusted plugin code. Direct filesystem access or independently opened connections bypass
integration guarantees and must not be used for migration writes.

File/database upgrades are recoverable sequences, not distributed transactions. Implicit-DDL
operations require reviewed recovery actions. Unsupported JDBC lease strategies fail explicitly.
Plugin-specific legacy detection and business transformations stay in their owning plugins;
Identica itself was not changed in this task.

Existing Gradle deprecation and legacy Javadoc warnings remain; builds and Javadoc generation succeed.
No Scriptorium documentation was added. Configura's old migration guide redirects to Strata.
