package me.whereareiam.strata;

import org.jetbrains.annotations.NotNull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Dependency-free, versioned history encoding shared by integrations. */
public final class HistoryCodec {
	/** Encodes complete history.
	 * @param history validated history
	 * @return stable UTF-8 representation
	 */
	public static byte @NotNull [] encode(@NotNull MigrationHistory history) {
		StringBuilder out = new StringBuilder("STRATA1\n").append(history.getBaseline()).append('\n');
		for (AppliedMigration entry : history.getApplied())
			out.append(entry.getFromVersion()).append('\t').append(entry.getToVersion()).append('\t')
					.append(encoded(entry.getId())).append('\t').append(encoded(entry.getFingerprint())).append('\n');
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}
	/** Decodes and validates history, rejecting unsupported formats.
	 * @param bytes serialized history
	 * @return history
	 */
	public static @NotNull MigrationHistory decode(byte @NotNull [] bytes) {
		String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
		if (lines.length < 2 || !lines[0].equals("STRATA1")) throw new IllegalArgumentException("Unknown history format");
		List<AppliedMigration> entries = new ArrayList<>();
		for (int i = 2; i < lines.length; i++) {
			String[] fields = lines[i].split("\t", -1);
			if (fields.length != 4) throw new IllegalArgumentException("Invalid history entry");
			entries.add(new AppliedMigration(decoded(fields[2]), Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), decoded(fields[3])));
		}
		return new MigrationHistory(Integer.parseInt(lines[1]), entries);
	}
	private static String encoded(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
	private static String decoded(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
}
