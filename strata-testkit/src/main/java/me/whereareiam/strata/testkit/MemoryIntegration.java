package me.whereareiam.strata.testkit;

import me.whereareiam.strata.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.*;
import java.util.function.UnaryOperator;

/** In-memory integration for plugin migration tests. Never use it for production history. */
public final class MemoryIntegration<C> implements MigrationIntegration<C> {
	private final String id;
	private final UnaryOperator<C> copy;
	private final Map<String, MigrationHistory> histories = new HashMap<>();
	private C value;

	/** Creates an isolated test store.
	 * @param id integration identity
	 * @param initial initial resources
	 * @param copy deep-copy function used to isolate preparation and abandoned changes
	 */
	public MemoryIntegration(@NotNull String id, @NotNull C initial, @NotNull UnaryOperator<C> copy) {
		this.id = id;
		this.copy = copy;
		this.value = copy.apply(initial);
	}
	/** Reads a detached snapshot for assertions.
	 * @return copied committed resources
	 */
	public @NotNull C snapshot() { return copy.apply(value); }
	@Override public @NotNull String id() { return id; }
	@Override public @NotNull MigrationSession<C> open(boolean writable) {
		return new MigrationSession<>() {
			private final C context = copy.apply(value);
			@Override public @NotNull C context() { return context; }
			@Override public @Nullable MigrationHistory history(@NotNull String stream) { return histories.get(stream); }
			@Override public @NotNull PreparedMigration prepare(@NotNull String stream, @NotNull MigrationHistory target,
					@NotNull List<Migration<C>> pending) throws Exception {
				if (!writable) throw new IllegalStateException("Read-only session");
				for (Migration<C> migration : pending) migration.getAction().apply(context);
				C prepared = copy.apply(context);
				return () -> { value = copy.apply(prepared); histories.put(stream, target); };
			}
			@Override public void close() { }
		};
	}
}
