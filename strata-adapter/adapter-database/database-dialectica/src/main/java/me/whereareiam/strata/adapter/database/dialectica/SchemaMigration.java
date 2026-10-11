package me.whereareiam.strata.adapter.database.dialectica;

import me.whereareiam.dialectica.Dialectica;
import me.whereareiam.dialectica.schema.change.SchemaChange;
import me.whereareiam.strata.Migration;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

/**
 * Database migrations written as Dialectica schema changes.
 *
 * <pre>{@code
 * .migration(SchemaMigration.of(2, "last-seen",
 *         new AddColumn("accounts", ColumnDefinition.builder("last_seen_at", ColumnType.bigint()).build()),
 *         new CreateIndex("accounts", IndexDefinition.plain("accounts_last_seen_at", "last_seen_at"))))
 * }</pre>
 */
public final class SchemaMigration {
	private SchemaMigration() {
	}

	/**
	 * Declares a migration that applies schema changes in order, on the migration's connection and
	 * therefore inside its transaction. Dialectica skips a change the database already has, so a
	 * migration that stopped halfway on a database without transactional DDL finishes on the next
	 * run.
	 *
	 * @param version version of the stream after this migration, at least one
	 * @param name    label of up to 100 letters, digits, spaces, dots, underscores and hyphens
	 * @param changes the changes, applied in the order given
	 * @return the migration
	 * @throws IllegalArgumentException if the version or the name is not acceptable, or there is no change
	 */
	public static @NotNull Migration<DatabaseContext> of(int version, @NotNull String name, @NotNull SchemaChange... changes) {
		SchemaChange[] applied = changes.clone();
		if (applied.length == 0) throw new IllegalArgumentException("Schema migration " + name + " has no change");
		Arrays.stream(applied).forEach(change -> Objects.requireNonNull(change, "change"));

		return new Migration<>(version, name, context -> Dialectica.on(context.getConnection()).apply(applied));
	}
}
