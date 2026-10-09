package me.whereareiam.strata.adapter.jdbi;

import lombok.Getter;
import me.whereareiam.strata.adapter.jdbc.JdbcContext;
import org.jdbi.v3.core.Handle;
import org.jetbrains.annotations.NotNull;

/**
 * What a database migration works with when the application uses Jdbi: everything a
 * {@link JdbcContext} offers, and a handle carrying the application's Jdbi configuration.
 */
@Getter
public final class JdbiContext extends JdbcContext {
	/**
	 * The handle of the running migration, on the migration's connection. It is borrowed: do not
	 * close it, and do not begin, commit or roll back transactions on it.
	 */
	private final @NotNull Handle handle;

	JdbiContext(@NotNull Handle handle) {
		super(handle.getConnection());
		this.handle = handle;
	}
}
