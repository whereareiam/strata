package me.whereareiam.strata;

/** Validates the complete installation after all resource commits, before the plugin becomes available. */
@FunctionalInterface
public interface UpgradeVerification {
	/** Checks final schema, data and configuration invariants without modifying them.
	 * Runs on every execution, including startup with no pending migrations.
	 * @throws Exception when normal plugin startup must remain blocked
	 */
	void verify() throws Exception;
}
