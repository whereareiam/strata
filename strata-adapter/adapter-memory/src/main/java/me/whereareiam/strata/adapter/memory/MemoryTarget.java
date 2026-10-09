package me.whereareiam.strata.adapter.memory;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationTarget;
import me.whereareiam.strata.model.AppliedMigration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A target that lives in memory, for testing migrations and the code that registers them. What it
 * recorded is gone with the instance, and a failed migration's changes to the state are not taken
 * back.
 *
 * @param <C> the state migrations work on
 */
@RequiredArgsConstructor
public final class MemoryTarget<C> implements MigrationTarget<C> {
	/** The object handed to every migration as its context. */
	@Getter
	private final @NotNull C state;

	private final Map<String, List<AppliedMigration>> history = new HashMap<>();

	/**
	 * Returns what has been applied to a stream, for assertions.
	 *
	 * @param stream stream id
	 * @return entries in the order they were recorded
	 */
	public @NotNull List<AppliedMigration> history(@NotNull String stream) {
		return List.copyOf(history.getOrDefault(stream, List.of()));
	}

	@Override
	public @NotNull MigrationSession<C> open() {
		return new Session();
	}

	private final class Session implements MigrationSession<C> {
		@Override
		public @NotNull C context() {
			return state;
		}

		@Override
		public int version(@NotNull String stream) {
			return history(stream).stream()
					.mapToInt(AppliedMigration::getVersion)
					.max()
					.orElse(0);
		}

		@Override
		public void apply(
				@NotNull String stream,
				@NotNull MigrationAction<? super C> action,
				@NotNull AppliedMigration entry
		) throws Exception {
			action.apply(state);
			history.computeIfAbsent(stream, ignored -> new ArrayList<>()).add(entry);
		}

		@Override
		public void close() {
		}
	}
}
