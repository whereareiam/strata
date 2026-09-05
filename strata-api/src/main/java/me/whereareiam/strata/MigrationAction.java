package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;

/** A transformation owned by a plugin or integration. External writes must use the integration's context. */
@FunctionalInterface
public interface MigrationAction<C> {
	/** Applies the transformation; the integration decides whether this stages changes or runs in a transaction.
	 * @param context declared integration resources
	 * @throws Exception when transformation or validation fails
	 */
	void apply(@NotNull C context) throws Exception;
}
