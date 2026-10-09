package me.whereareiam.strata.common;

import me.whereareiam.strata.MigrationAction;
import me.whereareiam.strata.MigrationSession;
import me.whereareiam.strata.MigrationTarget;
import me.whereareiam.strata.model.AppliedMigration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A target for these tests: migrations add to a list, and applied entries are kept in order.
 */
final class RecordingTarget implements MigrationTarget<List<String>> {
	final List<String> state = new ArrayList<>();
	final List<AppliedMigration> applied = new ArrayList<>();

	@Override
	public @NotNull MigrationSession<List<String>> open() {
		return new MigrationSession<>() {
			@Override
			public @NotNull List<String> context() {
				return state;
			}

			@Override
			public int version(@NotNull String stream) {
				return applied.stream().mapToInt(AppliedMigration::getVersion).max().orElse(0);
			}

			@Override
			public void apply(
					@NotNull String stream,
					@NotNull MigrationAction<? super List<String>> action,
					@NotNull AppliedMigration entry
			) throws Exception {
				action.apply(state);
				applied.add(entry);
			}

			@Override
			public void close() {
			}
		};
	}
}
