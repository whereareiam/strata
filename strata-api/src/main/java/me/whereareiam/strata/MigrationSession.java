package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;

/** A leased integration session. Implementations must reject writes outside declared resources. */
public interface MigrationSession<C> extends AutoCloseable {
	/** Returns resources for detection and migration actions.
	 * @return integration context
	 */
	@NotNull C context();
	/** Reads authoritative history without inventing a baseline.
	 * @param stream stable stream identifier
	 * @return history, or null when adoption is required
	 * @throws Exception when history cannot be read
	 */
	@Nullable MigrationHistory history(@NotNull String stream) throws Exception;
	/** Prepares a transition without publishing resource changes. JDBC actions are deferred until commit.
	 * @param stream stream identifier
	 * @param target complete resulting history, including the verified baseline
	 * @param pending ordered transformations
	 * @return operation that atomically records history with data where supported, or has durable recovery
	 * @throws Exception when preparation fails
	 */
	@NotNull PreparedMigration prepare(@NotNull String stream, @NotNull MigrationHistory target,
			@NotNull List<Migration<C>> pending) throws Exception;
	/** Releases resources without committing unfinished work.
	 * @throws Exception if release fails
	 */
	@Override void close() throws Exception;
}
