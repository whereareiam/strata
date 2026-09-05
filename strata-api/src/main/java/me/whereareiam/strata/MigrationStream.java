package me.whereareiam.strata;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import java.util.*;
import java.util.function.Predicate;

/** Independently versioned resources owned by a plugin, feature or platform.
 * @param <C> integration context type
 */
@Getter
public final class MigrationStream<C> {
	/** Stable stream identity, independent of release version or package. */
	private final @NotNull String id;
	/** Integration owning this stream’s resources and history. */
	private final @NotNull MigrationIntegration<C> integration;
	/** Highest layout version understood by this build. */
	private final int currentVersion;
	/** Available immutable transitions. */
	private final @NotNull List<Migration<C>> migrations;
	/** Minimum target versions of required streams. */
	private final @NotNull Map<String, Integer> requires;
	/** Read-only verification for installations without Strata history. */
	private final @NotNull LegacyDetector<C> detector;
	/** Environment predicate; excluded streams consume no history. */
	private final @NotNull Predicate<Map<String, String>> appliesTo;

	/** Declares a stream. Detection is mandatory, including an explicit empty-installation check.
	 * @param id stable stream identity
	 * @param integration resource owner
	 * @param currentVersion supported target layout
	 * @param migrations immutable transitions; order is resolved by source version
	 * @param requires minimum versions of prerequisite streams
	 * @param detector verifies installations lacking history
	 * @param appliesTo optional environment predicate; absent means all platforms
	 */
	@Builder
	public MigrationStream(@NotNull String id, @NotNull MigrationIntegration<C> integration, int currentVersion,
			@NotNull List<Migration<C>> migrations, Map<String, Integer> requires,
			@NotNull LegacyDetector<C> detector, Predicate<Map<String, String>> appliesTo) {
		if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,159}") || currentVersion < 0)
			throw new IllegalArgumentException("Invalid stream: " + id);
		this.id = id;
		this.integration = Objects.requireNonNull(integration);
		this.currentVersion = currentVersion;
		this.migrations = List.copyOf(migrations);
		this.requires = requires == null ? Map.of() : Map.copyOf(requires);
		this.detector = Objects.requireNonNull(detector);
		this.appliesTo = appliesTo == null ? environment -> true : appliesTo;
		Set<String> ids = new HashSet<>();
		Set<Integer> sources = new HashSet<>();
		for (Migration<C> migration : migrations) {
			if (!ids.add(migration.getId()) || !sources.add(migration.getFromVersion()) || migration.getToVersion() > currentVersion)
				throw new IllegalArgumentException("Duplicate or overshooting migration in " + id);
		}
		for (int version : this.requires.values())
			if (version < 0) throw new IllegalArgumentException("Negative dependency version");
	}
}
