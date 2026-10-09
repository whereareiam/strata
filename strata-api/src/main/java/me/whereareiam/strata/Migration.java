package me.whereareiam.strata;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A numbered step that takes one stream from version {@code version - 1} to {@code version}.
 * <p>
 * Once a release containing a migration has shipped, its version and what it does must stay as
 * they are: installations that ran it only remember the version.
 *
 * @param <C> context the migration needs
 */
@Getter
public final class Migration<C> {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}");

	/** Version of the stream after this migration, starting at one. */
	private final int version;

	/** Short label for reports and failures, such as {@code split-routing}. */
	private final @NotNull String name;

	/** The change itself. */
	private final @NotNull MigrationAction<C> action;

	/**
	 * Declares a migration.
	 *
	 * @param version version of the stream after this migration, at least one
	 * @param name    label of up to 100 letters, digits, spaces, dots, underscores and hyphens
	 * @param action  the change
	 * @throws IllegalArgumentException if the version or the name is not acceptable
	 */
	public Migration(int version, @NotNull String name, @NotNull MigrationAction<C> action) {
		if (version < 1) throw new IllegalArgumentException("Migration version must be at least 1: " + version);
		if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Invalid migration name: " + name);

		this.version = version;
		this.name = name;
		this.action = Objects.requireNonNull(action, "action");
	}
}
