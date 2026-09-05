package me.whereareiam.strata;

/** A prepared operation whose commit is recoverable according to its integration's contract. */
@FunctionalInterface
public interface PreparedMigration {
	/** Publishes prepared changes and their history. May be called once.
	 * @throws Exception when commit fails; stop startup and reopen Strata to recover
	 */
	void commit() throws Exception;
}
