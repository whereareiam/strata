package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;

/**
 * Tells which version an installation without a recorded version is already at.
 * <p>
 * It is asked once per stream, the first time Strata meets a target that knows nothing about it.
 * A new installation, whose files or tables the application creates in their current form, answers
 * {@code latest} so that nothing runs. An installation that predates Strata answers the version
 * its layout corresponds to, or zero to run every migration.
 *
 * <pre>{@code
 * .baseline((database, latest) -> database.tableExists("accounts") ? 0 : latest)
 * }</pre>
 *
 * @param <C> context of the target being inspected
 */
@FunctionalInterface
public interface MigrationBaseline<C> {
	/**
	 * Inspects the installation without changing it.
	 *
	 * @param context access to the target
	 * @param latest  highest version the stream declares
	 * @return version between zero and {@code latest} the installation is at
	 * @throws Exception when the installation cannot be inspected
	 */
	int version(@NotNull C context, int latest) throws Exception;
}
