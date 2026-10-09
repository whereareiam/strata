package me.whereareiam.strata.adapter.configura;

import me.whereareiam.configura.ConfiguraFeature;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Makes Configura leave Strata's version marker in the files it rewrites. Register it on the
 * {@code Configura} instance the application loads its configuration with, which is also the one
 * handed to a {@link ConfiguraTarget}:
 *
 * <pre>{@code
 * Configura configura = Config.builder()
 *         .feature(new StrataFeature())
 *         .build();
 * }</pre>
 */
public final class StrataFeature implements ConfiguraFeature {
	/** Top-level key under which a file carries the version its stream has reached. */
	public static final String VERSION_KEY = "_version";

	@Override
	public @NotNull Set<String> reservedKeys() {
		return Set.of(VERSION_KEY);
	}
}
