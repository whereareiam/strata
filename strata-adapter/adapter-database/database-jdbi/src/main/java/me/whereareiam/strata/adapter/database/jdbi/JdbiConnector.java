package me.whereareiam.strata.adapter.database.jdbi;

import lombok.RequiredArgsConstructor;
import me.whereareiam.strata.adapter.database.DatabaseConnection;
import me.whereareiam.strata.adapter.database.DatabaseConnector;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;

/**
 * Runs migrations on a handle of the application's {@link Jdbi}.
 * <p>
 * While a stream is upgraded, the handle is the one Jdbi finds for the current thread. A migration
 * therefore uses the application's Jdbi as the rest of the application does, with its plugins,
 * mappers and DAOs, and everything it does runs on the migration's connection and inside its
 * transaction:
 *
 * <pre>{@code
 * new DatabaseTarget(new JdbiConnector(jdbi));
 *
 * .migration(3, "lowercase-names", database -> jdbi.useHandle(handle ->
 *         handle.execute("UPDATE accounts SET name = LOWER(name)")))
 * }</pre>
 * <p>
 * A migration must stay on the thread it was called on to see the handle.
 */
@RequiredArgsConstructor
public final class JdbiConnector implements DatabaseConnector {
	private final @NotNull Jdbi jdbi;

	@Override
	public @NotNull DatabaseConnection open() {
		Handle handle = jdbi.open();
		jdbi.getHandleScope().set(handle);

		return new DatabaseConnection() {
			@Override
			public @NotNull Connection connection() {
				return handle.getConnection();
			}

			@Override
			public void close() {
				jdbi.getHandleScope().clear();
				handle.close();
			}
		};
	}
}
