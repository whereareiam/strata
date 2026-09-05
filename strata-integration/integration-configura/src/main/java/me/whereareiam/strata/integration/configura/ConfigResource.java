package me.whereareiam.strata.integration.configura;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.util.function.Consumer;

/** A declared document, with an explicit path including its extension and a validation callback. */
@Getter
public final class ConfigResource {
	private final String name;
	private final Path path;
	private final boolean required;
	private final boolean writable;
	private final Consumer<ObjectNode> validator;

	/** Declares a document relative to the installation directory.
	 * @param name stable resource name
	 * @param path relative path including extension
	 * @param required whether an existing source must be present
	 * @param writable whether migrations may change the document
	 * @param validator validation applied before persistence; null means tree-shape validation only
	 */
	@Builder
	public ConfigResource(@NotNull String name, @NotNull Path path, boolean required, boolean writable,
			Consumer<ObjectNode> validator) {
		if (name.isBlank() || path.isAbsolute() || path.normalize().startsWith("..") || path.normalize().startsWith(".strata"))
			throw new IllegalArgumentException("Invalid configuration resource " + name);
		this.name = name;
		this.path = path.normalize();
		this.required = required;
		this.writable = writable;
		this.validator = validator == null ? node -> { } : validator;
	}
}
