package me.whereareiam.strata.adapter.jdbi;

import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationTarget;
import me.whereareiam.strata.adapter.jdbc.JdbcSession;
import me.whereareiam.strata.adapter.jdbc.JdbcTarget;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * A database reached through the application's {@link Jdbi}, so migrations use the plugins, mappers
 * and arguments registered on it. Locking, transactions and history are those of {@link JdbcTarget},
 * and migrations written for a {@code JdbcContext}, such as SQL scripts, run here unchanged.
 */
public final class JdbiTarget implements MigrationTarget<JdbiContext> {
	private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(60);

	private final Jdbi jdbi;
	private final Duration lockTimeout;

	/**
	 * Uses a database, waiting up to a minute for another instance that is migrating it.
	 *
	 * @param jdbi the application's configured Jdbi; one handle is opened per upgraded stream
	 */
	public JdbiTarget(@NotNull Jdbi jdbi) {
		this(jdbi, DEFAULT_LOCK_TIMEOUT);
	}

	/**
	 * Uses a database.
	 *
	 * @param jdbi        the application's configured Jdbi; one handle is opened per upgraded stream
	 * @param lockTimeout how long to wait for another instance that is migrating the database
	 */
	public JdbiTarget(@NotNull Jdbi jdbi, @NotNull Duration lockTimeout) {
		this.jdbi = jdbi;
		this.lockTimeout = lockTimeout;
	}

	@Override
	public @NotNull MigrationSession<JdbiContext> open() throws Exception {
		Handle handle = jdbi.open();

		return new JdbcSession<>(new JdbiContext(handle), lockTimeout, handle::close);
	}
}
