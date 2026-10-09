package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;

/**
 * One change to an installation, written against the context of the target it changes.
 *
 * @param <C> context the target hands to its migrations
 */
@FunctionalInterface
public interface MigrationAction<C> {
	/**
	 * Applies the change. Everything written through the context belongs to this migration: the
	 * target keeps it together with the migration's history entry, or discards it when this method
	 * throws. Other resources may be read, but must not be written.
	 *
	 * @param context access to the target being migrated
	 * @throws Exception when the change cannot be applied; the upgrade stops at this migration
	 */
	void apply(@NotNull C context) throws Exception;
}
