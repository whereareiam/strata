package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;

/**
 * Tells whether an installation without a recorded version is new, and if not, which version it is
 * already at.
 * <p>
 * It is asked the first time Strata meets a target that knows nothing about the stream. A new
 * installation answers empty: the stream's creation action, if it has one, builds the newest form,
 * and the latest version is recorded without running a migration. An installation that predates
 * Strata answers the version its layout corresponds to, zero to run every migration, or
 * {@code latest} when it already has the newest layout.
 *
 * <pre>{@code
 * .baseline((database, latest) -> database.tableExists("accounts") ? OptionalInt.of(0) : OptionalInt.empty())
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
	 * @return empty for a new installation, otherwise the version between zero and {@code latest}
	 *         the existing installation is at
	 * @throws Exception when the installation cannot be inspected
	 */
	@NotNull OptionalInt version(@NotNull C context, int latest) throws Exception;
}
