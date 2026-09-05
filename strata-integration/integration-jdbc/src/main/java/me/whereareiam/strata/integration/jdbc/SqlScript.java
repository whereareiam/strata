package me.whereareiam.strata.integration.jdbc;

import org.jetbrains.annotations.NotNull;
import java.util.*;
import java.util.regex.Pattern;

/** Splits SQL without breaking quoted literals, comments, PostgreSQL dollar bodies or SQLite trigger bodies.
 * MySQL DELIMITER directives are supported on their own lines. Unsupported unterminated input fails before execution.
 */
public final class SqlScript {
	/** Parses a script into executable statements.
	 * @param script UTF-8 SQL contents
	 * @return ordered statements without terminators
	 */
	public static @NotNull List<String> statements(@NotNull String script) {
		return statements(script, "standard");
	}

	/** Parses with explicit dialect string-escape rules.
	 * @param script SQL contents
	 * @param dialect standard, postgresql, mysql, mariadb, h2 or sqlite
	 * @return executable statements
	 */
	public static @NotNull List<String> statements(@NotNull String script, @NotNull String dialect) {
		boolean mysql = dialect.toLowerCase(Locale.ROOT).contains("mysql") || dialect.toLowerCase(Locale.ROOT).contains("mariadb");
		boolean backslashEscapes = false;
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		String delimiter = ";";
		String dollar = null;
		char quote = 0;
		int comments = 0;
		boolean lineComment = false;
		boolean trigger = false;
		int triggerDepth = 0;
		StringBuilder word = new StringBuilder();
		for (int i = 0; i < script.length();) {
			char c = script.charAt(i);
			char next = i + 1 < script.length() ? script.charAt(i + 1) : 0;
			if (lineComment) {
				if (c == '\n') { lineComment = false; current.append('\n'); }
				i++; continue;
			}
			if (comments > 0) {
				if (c == '/' && next == '*') { comments++; i += 2; }
				else if (c == '*' && next == '/') { comments--; i += 2; current.append(' '); }
				else i++;
				continue;
			}
			if (dollar != null) {
				if (script.startsWith(dollar, i)) { current.append(dollar); i += dollar.length(); dollar = null; }
				else { current.append(c); i++; }
				continue;
			}
			if (quote != 0) {
				current.append(c); i++;
				if (c == quote) {
					if (next == quote) { current.append(next); i++; }
					else quote = 0;
				} else if (backslashEscapes && c == '\\' && i < script.length()) { current.append(script.charAt(i++)); }
				continue;
			}
			if ((i == 0 || script.charAt(i - 1) == '\n') && current.toString().isBlank()) {
				int end = script.indexOf('\n', i);
				String line = script.substring(i, end < 0 ? script.length() : end).trim();
				if (line.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
					delimiter = line.substring(10).trim();
					if (delimiter.isEmpty()) throw new IllegalArgumentException("Empty SQL delimiter");
					i = end < 0 ? script.length() : end + 1; continue;
				}
			}
			if (c == '-' && next == '-') { lineComment = true; i += 2; continue; }
			if (c == '/' && next == '*') {
				if (i + 2 < script.length() && script.charAt(i + 2) == '!')
					throw new IllegalArgumentException("Executable MySQL comments require an explicit Java migration");
				comments++; i += 2; continue;
			}
			if (!Character.isLetterOrDigit(c) && c != '_' && word.length() > 0) {
				String token = word.toString().toUpperCase(Locale.ROOT); word.setLength(0);
				if (token.equals("TRIGGER") && current.toString().stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE")) trigger = true;
				if (trigger && (token.equals("BEGIN") || token.equals("CASE"))) triggerDepth++;
				if (trigger && token.equals("END")) triggerDepth--;
			}
			if (script.startsWith(delimiter, i) && (!trigger || triggerDepth == 0 || !delimiter.equals(";"))) {
				add(result, current); i += delimiter.length(); trigger = false; triggerDepth = 0; word.setLength(0); continue;
			}
			if (c == '\'' || c == '"' || c == '`' || c == '[') {
				backslashEscapes = mysql || (c == '\'' && i > 0 && (script.charAt(i - 1) == 'E' || script.charAt(i - 1) == 'e') && (i < 2 || !Character.isJavaIdentifierPart(script.charAt(i - 2))));
				quote = c == '[' ? ']' : c; current.append(c); i++; continue;
			}
			if (c == '$') {
				var matcher = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$").matcher(script.substring(i));
				if (matcher.lookingAt()) { dollar = matcher.group(); current.append(dollar); i += dollar.length(); continue; }
			}
			if (Character.isLetterOrDigit(c) || c == '_') word.append(c);
			current.append(c); i++;
		}
		if (quote != 0 || dollar != null || comments != 0 || triggerDepth > 0)
			throw new IllegalArgumentException("Unterminated SQL literal, comment or trigger body");
		add(result, current);
		return List.copyOf(result);
	}

	private static void add(List<String> result, StringBuilder current) {
		String statement = current.toString().trim();
		if (!statement.isEmpty()) result.add(statement);
		current.setLength(0);
	}
}
