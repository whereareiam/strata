package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;

/**
 * Something that can be migrated and that remembers what was applied to it: one database, one
 * configuration directory. Adapters implement this for a technology.
 *
 * @param <C> context handed to migrations of this target
 */
public interface MigrationTarget<C> {
	/**
	 * Opens the target for one stream's upgrade and takes its exclusive lock, so that two instances
	 * starting at the same time do not migrate it twice.
	 *
	 * @return session that holds the lock until it is closed
	 * @throws Exception when the target cannot be reached or stays locked by another instance
	 */
	@NotNull MigrationSession<C> open() throws Exception;
}
