package me.whereareiam.strata;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The versioned history of one thing an application owns in one target: its tables in a database,
 * or its files in a configuration directory.
 *
 * <pre>{@code
 * MigrationStream<JdbcContext> database = MigrationStream.<JdbcContext>builder()
 *         .id("my-plugin/database")
 *         .target(new JdbcTarget(dataSource))
 *         .baseline((context, latest) -> context.tableExists("accounts") ? 0 : latest)
 *         .migration(1, "add-last-login", context -> context.execute("ALTER TABLE accounts ADD last_login BIGINT"))
 *         .build();
 * }</pre>
 *
 * @param <C> context the target hands to this stream's migrations
 */
@Getter
public final class MigrationStream<C> {
	private static final Pattern ID = Pattern.compile("[a-z0-9_-]+(/[a-z0-9_-]+)*");
	private static final int MAX_ID_LENGTH = 100;

	/** Stable name under which the target remembers this stream's version, such as {@code my-plugin/database}. */
	private final @NotNull String id;

	/** Where the migrations run and where the reached version is kept. */
	private final @NotNull MigrationTarget<C> target;

	/** Declared migrations in ascending version order, without gaps. */
	private final @NotNull List<Migration<? super C>> migrations;

	/** Decides the starting version of an installation without history; without it, that version is zero. */
	private final @Nullable MigrationBaseline<? super C> baseline;

	private MigrationStream(Builder<C> builder) {
		String id = Objects.requireNonNull(builder.id, "id");
		if (id.length() > MAX_ID_LENGTH || !ID.matcher(id).matches())
			throw new IllegalArgumentException("Invalid stream id: " + id);

		this.id = id;
		this.target = Objects.requireNonNull(builder.target, "target");
		this.migrations = ordered(id, builder.migrations);
		this.baseline = builder.baseline;
	}

	/**
	 * Starts the declaration of a stream.
	 *
	 * @param <C> context the target hands to the stream's migrations
	 * @return empty builder
	 */
	public static <C> @NotNull Builder<C> builder() {
		return new Builder<>();
	}

	/**
	 * Returns the version an up-to-date installation is at.
	 *
	 * @return highest declared version, or zero when the stream declares no migration
	 */
	public int getLatestVersion() {
		return migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).getVersion();
	}

	private static <C> List<Migration<? super C>> ordered(String id, List<Migration<? super C>> migrations) {
		List<Migration<? super C>> ordered = new ArrayList<>(migrations);
		ordered.sort(Comparator.comparingInt(Migration::getVersion));

		for (int index = 1; index < ordered.size(); index++) {
			int previous = ordered.get(index - 1).getVersion();
			int version = ordered.get(index).getVersion();
			if (version == previous)
				throw new IllegalArgumentException("Stream " + id + " declares version " + version + " twice");

			if (version != previous + 1)
				throw new IllegalArgumentException("Stream " + id + " has no migration to version " + (previous + 1));
		}

		return List.copyOf(ordered);
	}

	/**
	 * Collects the declaration of a stream.
	 *
	 * @param <C> context the target hands to the stream's migrations
	 */
	public static final class Builder<C> {
		private final List<Migration<? super C>> migrations = new ArrayList<>();
		private String id;
		private MigrationTarget<C> target;
		private MigrationBaseline<? super C> baseline;

		private Builder() {
		}

		/**
		 * Names the stream.
		 *
		 * @param id up to 100 characters of lowercase letters, digits, underscores and hyphens, in
		 *           segments separated by slashes; stable for the lifetime of the application
		 * @return this builder
		 */
		public @NotNull Builder<C> id(@NotNull String id) {
			this.id = id;
			return this;
		}

		/**
		 * Sets where the migrations run and where the reached version is kept.
		 *
		 * @param target target of the stream
		 * @return this builder
		 */
		public @NotNull Builder<C> target(@NotNull MigrationTarget<C> target) {
			this.target = target;
			return this;
		}

		/**
		 * Sets how the starting version of an installation without history is found. Without a
		 * baseline such an installation starts at zero and runs every migration.
		 *
		 * @param baseline inspection of an installation without history
		 * @return this builder
		 */
		public @NotNull Builder<C> baseline(@NotNull MigrationBaseline<? super C> baseline) {
			this.baseline = baseline;
			return this;
		}

		/**
		 * Declares a migration in place.
		 *
		 * @param version version of the stream after the migration
		 * @param name    short label for reports and failures
		 * @param action  the change
		 * @return this builder
		 */
		public @NotNull Builder<C> migration(int version, @NotNull String name, @NotNull MigrationAction<? super C> action) {
			return migration(new Migration<>(version, name, action));
		}

		/**
		 * Adds a migration declared elsewhere.
		 *
		 * @param migration migration of this stream
		 * @return this builder
		 */
		public @NotNull Builder<C> migration(@NotNull Migration<? super C> migration) {
			migrations.add(Objects.requireNonNull(migration, "migration"));
			return this;
		}

		/**
		 * Adds migrations declared elsewhere, such as discovered SQL scripts.
		 *
		 * @param migrations migrations of this stream, in any order
		 * @return this builder
		 */
		public @NotNull Builder<C> migrations(@NotNull Collection<? extends Migration<? super C>> migrations) {
			migrations.forEach(this::migration);
			return this;
		}

		/**
		 * Checks and creates the stream.
		 *
		 * @return immutable stream
		 * @throws IllegalArgumentException if the id is not acceptable, or the versions repeat or
		 *                                  leave a gap
		 */
		public @NotNull MigrationStream<C> build() {
			return new MigrationStream<>(this);
		}
	}
}
