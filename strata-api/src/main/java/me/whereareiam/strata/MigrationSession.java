package me.whereareiam.strata;

import me.whereareiam.strata.model.AppliedMigration;
import org.jetbrains.annotations.NotNull;

/**
 * Exclusive access to an open {@link MigrationTarget}.
 *
 * @param <C> context handed to migrations of the target
 */
public interface MigrationSession<C> extends AutoCloseable {
	/**
	 * Returns a context for inspecting the target outside a migration, as a
	 * {@link MigrationBaseline} does. Changes made through it are not kept.
	 *
	 * @return context of the open target
	 * @throws Exception when the target cannot be read
	 */
	@NotNull C context() throws Exception;

	/**
	 * Reads the version a stream has reached on this target.
	 *
	 * @param stream stream id
	 * @return highest recorded version, or zero when the target knows nothing about the stream
	 * @throws Exception when the version cannot be read
	 */
	int version(@NotNull String stream) throws Exception;

	/**
	 * Runs an action and records its entry as one unit: when the action throws, its changes are
	 * discarded as far as the technology allows and nothing is recorded.
	 *
	 * @param stream stream id
	 * @param action change to run with this session's context
	 * @param entry  what to record once the action has succeeded; a target stores at least its version
	 * @throws Exception when the action fails or its result cannot be stored
	 */
	void apply(
			@NotNull String stream,
			@NotNull MigrationAction<? super C> action,
			@NotNull AppliedMigration entry
	) throws Exception;

	/**
	 * Releases the lock and the resources of the session.
	 *
	 * @throws Exception when releasing fails
	 */
	@Override
	void close() throws Exception;
}
