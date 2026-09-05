package me.whereareiam.strata;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/** An immutable, forward-only layout transition. Fingerprints must cover Java behavior and associated resources.
 * @param <C> integration context type
 */
@Getter
public final class Migration<C> {
	/** Stable migration identity within its stream. */
	private final @NotNull String id;
	/** Required source layout version. */
	private final int fromVersion;
	/** Resulting layout version. */
	private final int toVersion;
	/** Immutable implementation revision or digest. */
	private final @NotNull String fingerprint;
	/** Transformation using the declared integration context. */
	private final @NotNull MigrationAction<C> action;

	/** Creates a transition, usually through the generated builder.
	 * @param id stable identity independent of class name
	 * @param fromVersion required source version
	 * @param toVersion resulting version
	 * @param fingerprint immutable implementation digest or explicitly maintained revision
	 * @param action transformation
	 */
	@Builder
	public Migration(@NotNull String id, int fromVersion, int toVersion,
			@NotNull String fingerprint, @NotNull MigrationAction<C> action) {
		if (id.isBlank() || fingerprint.isBlank() || fromVersion < 0 || toVersion <= fromVersion)
			throw new IllegalArgumentException("Invalid migration declaration: " + id);
		this.id = id;
		this.fromVersion = fromVersion;
		this.toVersion = toVersion;
		this.fingerprint = fingerprint;
		this.action = java.util.Objects.requireNonNull(action);
	}
}
