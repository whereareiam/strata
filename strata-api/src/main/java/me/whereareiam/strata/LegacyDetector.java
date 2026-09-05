package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;

/** Identifies an installation without Strata history. Unknown or ambiguous layouts must throw. */
@FunctionalInterface
public interface LegacyDetector<C> {
	/** Returns the verified layout version; zero represents an empty installation.
	 * @param context resources to inspect without modifying them
	 * @return verified version
	 * @throws Exception if the source cannot be safely identified
	 */
	int detect(@NotNull C context) throws Exception;
}
