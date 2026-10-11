package me.whereareiam.strata.adapter.database.dialectica;

import me.whereareiam.dialectica.Dialectica;
import me.whereareiam.dialectica.schema.Schema;
import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.adapter.database.DatabaseContext;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The creation action of a database stream, written as a Dialectica schema.
 *
 * <pre>{@code
 * MigrationStream.<DatabaseContext>builder()
 *         .id("my-plugin/database")
 *         .target(new DatabaseTarget(dataSource))
 *         .creation(SchemaCreation.of(Schema.of(accounts, links)))
 *         .build();
 * }</pre>
 */
public final class SchemaCreation {
	private SchemaCreation() {
	}

	/**
	 * Creates a schema on a new installation. The tables are created on the creation's connection,
	 * so they belong to its transaction and are made under the target's lock.
	 *
	 * @param schema the newest form of the stream's tables
	 * @return action for {@code MigrationStream.Builder.creation}
	 */
	public static @NotNull MigrationAction<DatabaseContext> of(@NotNull Schema schema) {
		Objects.requireNonNull(schema, "schema");

		return context -> Dialectica.on(context.getConnection()).create(schema);
	}
}
