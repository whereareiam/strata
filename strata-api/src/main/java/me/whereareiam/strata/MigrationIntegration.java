package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;

/** Supplies resource access, history and durability independently of any configuration or database library. */
public interface MigrationIntegration<C> {
	/** Returns a stable resource identity used for deterministic lock acquisition.
	 * @return integration instance identity
	 */
	@NotNull String id();
	/** Opens resources. Writable sessions hold an exclusive lock until closed and recover interrupted commits.
	 * Read-only sessions must not modify resources or create history.
	 * @param writable whether preparation and commits will follow
	 * @return session owning its resource lease
	 * @throws Exception if acquisition or recovery fails
	 */
	@NotNull MigrationSession<C> open(boolean writable) throws Exception;
}
