package me.whereareiam.strata.integration.configura;

/** Explicit resolution when a transfer encounters an existing destination. */
public enum ConflictPolicy {
	/** Accept an identical value, otherwise stop before persistence. */
	FAIL_IF_DIFFERENT,
	/** Retain the destination value. A move still removes the source. */
	PRESERVE_DESTINATION,
	/** Replace the destination value with the source value. */
	REPLACE_DESTINATION
}
