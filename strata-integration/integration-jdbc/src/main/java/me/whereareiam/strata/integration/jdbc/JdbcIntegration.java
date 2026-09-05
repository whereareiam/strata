package me.whereareiam.strata.integration.jdbc;

import me.whereareiam.strata.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;

/** JDBC history, database-scoped leases and transactional or explicitly recoverable migrations. */
public final class JdbcIntegration implements MigrationIntegration<JdbcContext> {
	private static final Map<String, ReentrantLock> SQLITE_LOCKS = new ConcurrentHashMap<>();
	private final String id;
	private final DataSource dataSource;

	/** Registers one database. Reuse this integration for every stream sharing it.
	 * @param id stable resource identity, consistent across processes
	 * @param dataSource connection source
	 */
	public JdbcIntegration(@NotNull String id, @NotNull DataSource dataSource) {
		this.id = id;
		this.dataSource = dataSource;
	}

	@Override public @NotNull String id() { return id; }
	@Override public @NotNull MigrationSession<JdbcContext> open(boolean writable) throws Exception {
		return new Session(writable);
	}

	private final class Session implements MigrationSession<JdbcContext> {
		private final Connection connection;
		private final JdbcContext context;
		private final boolean writable;
		private Connection lease;
		private FileChannel fileChannel;
		private FileLock fileLock;
		private ReentrantLock processLock;
		private String product;

		Session(boolean writable) throws Exception {
			this.writable = writable;
			connection = dataSource.getConnection();
			context = new JdbcContext(connection);
			try {
				product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
				if (writable) {
					acquire();
					try (Statement statement = connection.createStatement()) {
						statement.execute("CREATE TABLE IF NOT EXISTS strata_history (stream VARCHAR(160) PRIMARY KEY, history TEXT NOT NULL)");
						statement.execute("CREATE TABLE IF NOT EXISTS strata_pending (stream VARCHAR(160) PRIMARY KEY, history TEXT NOT NULL)");
					}
				}
			} catch (Exception failure) {
				try { close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
				throw failure;
			}
		}

		private void acquire() throws Exception {
			if (product.contains("sqlite")) {
				String url = connection.getMetaData().getURL();
				String location = url.substring("jdbc:sqlite:".length());
				if (location.startsWith("file:") || location.contains("?") || location.equals(":memory:"))
					throw new SQLException("SQLite migrations require a plain file JDBC URL for a shared filesystem lease");
				Path database = Path.of(location).toAbsolutePath().toRealPath();
				processLock = SQLITE_LOCKS.computeIfAbsent(database.toString(), ignored -> new ReentrantLock());
				if (!processLock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS)) throw new SQLException("SQLite migration lease timed out");
				fileChannel = FileChannel.open(database.resolveSibling(database.getFileName() + ".strata.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
				while ((fileLock = fileChannel.tryLock()) == null) {
					if (System.nanoTime() >= deadline) throw new SQLException("SQLite filesystem lease timed out");
					Thread.sleep(50);
				}
				return;
			}
			lease = dataSource.getConnection();
			if (product.contains("postgresql")) {
				try (Statement statement = lease.createStatement()) {
					statement.setQueryTimeout(30);
					statement.execute("SELECT pg_advisory_lock(1398035017)");
				}
			} else if (product.contains("mysql") || product.contains("mariadb")) {
				try (Statement statement = lease.createStatement(); ResultSet result = statement.executeQuery("SELECT GET_LOCK('strata-migrations', 30)")) {
					if (!result.next() || result.getInt(1) != 1) throw new SQLException("Database migration lease timed out");
				}
			} else if (product.contains("h2")) {
				try (Statement statement = lease.createStatement()) {
					statement.execute("CREATE TABLE IF NOT EXISTS strata_lock (id INT PRIMARY KEY)");
					try { statement.execute("INSERT INTO strata_lock (id) VALUES (1)"); }
					catch (SQLException duplicate) { if (!"23505".equals(duplicate.getSQLState())) throw duplicate; }
				}
				lease.setAutoCommit(false);
				try (Statement statement = lease.createStatement()) {
					statement.setQueryTimeout(30);
					statement.executeQuery("SELECT id FROM strata_lock WHERE id = 1 FOR UPDATE").close();
				}
			} else throw new SQLException("No migration lease strategy for " + product);
		}

		@Override public @NotNull JdbcContext context() { return context; }
		@Override public @Nullable MigrationHistory history(@NotNull String stream) throws Exception { return read("strata_history", stream); }

		private MigrationHistory read(String table, String stream) throws SQLException {
			if (!context.tableExists(table)) return null;
			try (PreparedStatement statement = connection.prepareStatement("SELECT history FROM " + table + " WHERE stream = ?")) {
				statement.setString(1, stream);
				try (ResultSet result = statement.executeQuery()) {
					return result.next() ? HistoryCodec.decode(Base64.getDecoder().decode(result.getString(1))) : null;
				}
			}
		}

		private void write(String table, String stream, MigrationHistory history) throws SQLException {
			String encoded = Base64.getEncoder().encodeToString(HistoryCodec.encode(history));
			try (PreparedStatement update = connection.prepareStatement("UPDATE " + table + " SET history = ? WHERE stream = ?")) {
				update.setString(1, encoded); update.setString(2, stream);
				if (update.executeUpdate() != 0) return;
			}
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " (stream, history) VALUES (?, ?)")) {
				insert.setString(1, stream); insert.setString(2, encoded); insert.executeUpdate();
			}
		}

		@Override
		public @NotNull PreparedMigration prepare(@NotNull String stream, @NotNull MigrationHistory target,
				@NotNull List<Migration<JdbcContext>> pending) throws Exception {
			if (!writable) throw new IllegalStateException("Read-only session");
			MigrationHistory interrupted = read("strata_pending", stream);
			if (interrupted != null) {
				if (pending.isEmpty() || !(pending.get(0).getAction() instanceof RecoverableJdbcAction)
						|| !interrupted.getApplied().get(interrupted.getApplied().size() - 1).equals(AppliedMigration.of(pending.get(0))))
					throw new IllegalStateException("Unresolved or changed recovery operation for " + stream);
			}
			for (Migration<JdbcContext> migration : pending)
				if (migration.getAction() instanceof SqlMigrationSource.ScriptAction script)
					script.validate(connection.getMetaData().getDatabaseProductName());
			return () -> {
				MigrationHistory existing = history(stream);
				List<AppliedMigration> applied = existing == null ? new ArrayList<>() : new ArrayList<>(existing.getApplied());
				for (Migration<JdbcContext> migration : pending) {
					applied.add(AppliedMigration.of(migration));
					MigrationHistory next = new MigrationHistory(target.getBaseline(), applied);
					boolean recoverable = migration.getAction() instanceof RecoverableJdbcAction;
					context.executing(true, recoverable);
					try {
						if (recoverable) {
							boolean recovering = read("strata_pending", stream) != null;
							// Persist adoption before any implicitly committed DDL changes source detection.
							if (history(stream) == null) write("strata_history", stream, new MigrationHistory(target.getBaseline(), List.of()));
							write("strata_pending", stream, next);
							if (recovering) ((RecoverableJdbcAction) migration.getAction()).recover(context);
							else migration.getAction().apply(context);
						} else {
							connection.setAutoCommit(false);
							migration.getAction().apply(context);
						}
						if (connection.getAutoCommit()) connection.setAutoCommit(false);
						write("strata_history", stream, next);
						try (PreparedStatement statement = connection.prepareStatement("DELETE FROM strata_pending WHERE stream = ?")) {
							statement.setString(1, stream); statement.executeUpdate();
						}
						connection.commit();
					} catch (Exception | Error failure) {
						if (!connection.getAutoCommit()) connection.rollback();
						throw failure;
					} finally {
						context.executing(false, false);
						connection.setAutoCommit(true);
					}
				}
				if (pending.isEmpty()) write("strata_history", stream, target);
			};
		}

		@Override public void close() throws Exception {
			Exception failure = null;
			try { connection.close(); } catch (Exception exception) { failure = exception; }
			try {
				if (lease != null) {
					try {
						if (product.contains("postgresql"))
							try (Statement statement = lease.createStatement()) { statement.execute("SELECT pg_advisory_unlock(1398035017)"); }
						else if (product.contains("mysql") || product.contains("mariadb"))
							try (Statement statement = lease.createStatement()) { statement.execute("SELECT RELEASE_LOCK('strata-migrations')"); }
						else if (!lease.getAutoCommit()) lease.rollback();
					} finally { lease.close(); }
				}
			} catch (Exception exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
			try { if (fileLock != null) fileLock.close(); }
			finally {
				try { if (fileChannel != null) fileChannel.close(); }
				finally { if (processLock != null && processLock.isHeldByCurrentThread()) processLock.unlock(); }
			}
			if (failure != null) throw failure;
		}
	}
}
