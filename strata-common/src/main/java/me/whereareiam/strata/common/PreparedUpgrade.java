package me.whereareiam.strata.common;

import lombok.Getter;
import me.whereareiam.strata.*;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/** A single-use prepared plan. Close it to release all resource leases, including after failure. */
public final class PreparedUpgrade implements AutoCloseable {
	/** Ordered pending transition descriptions. */
	@Getter private final List<String> pending;
	private final List<PreparedMigration> commits;
	private final List<MigrationSession<?>> sessions;
	private boolean attempted;
	private boolean closed;

	PreparedUpgrade(List<String> pending, List<PreparedMigration> commits, List<MigrationSession<?>> sessions) {
		this.pending = List.copyOf(pending);
		this.commits = List.copyOf(commits);
		this.sessions = List.copyOf(sessions);
	}

	/** Commits in prerequisite order. On failure, stop startup and reopen Strata rather than reusing this plan.
	 * @throws Exception if any commit fails
	 */
	public void commit() throws Exception {
		if (attempted || closed) throw new IllegalStateException("Prepared upgrade is no longer available");
		attempted = true;
		for (PreparedMigration commit : commits) commit.commit();
	}

	/** Releases leases without committing abandoned work.
	 * @throws Exception if resource release fails
	 */
	@Override
	public void close() throws Exception {
		if (closed) return;
		closed = true;
		Exception failure = null;
		for (int i = sessions.size() - 1; i >= 0; i--)
			try { sessions.get(i).close(); }
			catch (Exception exception) {
				if (failure == null) failure = exception;
				else failure.addSuppressed(exception);
			}
		if (failure != null) throw failure;
	}
}
