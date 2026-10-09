package me.whereareiam.strata.adapter.jdbc.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits the text of a script into the statements JDBC has to run one by one.
 * <p>
 * It understands just enough SQL to find the ends of statements: quoted text and identifiers,
 * comments, PostgreSQL dollar quotes, the {@code BEGIN ... END} body of a trigger, and the
 * {@code DELIMITER} lines used around MySQL routines.
 */
final class SqlScript {
	private static final String SEMICOLON = ";";
	private static final String DELIMITER_DIRECTIVE = "DELIMITER ";
	private static final String EXECUTABLE_COMMENT = "/*!";
	private static final Pattern DOLLAR_TAG = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$");

	private final String script;
	private final boolean backslashEscapes;
	private final List<String> statements = new ArrayList<>();
	private final StringBuilder statement = new StringBuilder();
	private final StringBuilder word = new StringBuilder();

	private String delimiter = SEMICOLON;
	private int position;
	private boolean trigger;
	private int bodyDepth;

	private SqlScript(String script, String dialect) {
		this.script = script;
		this.backslashEscapes = dialect.equals("mysql") || dialect.equals("mariadb");
	}

	/**
	 * Splits a script.
	 *
	 * @param script  text of the script
	 * @param dialect database product name in lower case, which decides how strings escape quotes
	 * @return statements in order, without comments and without their terminators
	 * @throws IllegalArgumentException if a string, comment or trigger body is left open
	 */
	static List<String> split(String script, String dialect) {
		return new SqlScript(script, dialect).split();
	}

	private List<String> split() {
		while (position < script.length()) {
			if (!isWordCharacter(script.charAt(position))) endWord();
			if (skipComment() || readQuoted() || readDollarQuoted() || readDelimiterDirective() || endStatement()) continue;

			readCharacter();
		}

		endWord();
		if (bodyDepth > 0) throw new IllegalArgumentException("Unterminated trigger body in SQL script");

		flush();

		return List.copyOf(statements);
	}

	private boolean skipComment() {
		if (startsWith("--")) {
			int end = script.indexOf('\n', position);
			position = end < 0 ? script.length() : end;
			return true;
		}

		if (!startsWith("/*")) return false;

		int end = blockCommentEnd();
		if (startsWith(EXECUTABLE_COMMENT)) statement.append(script, position, end);
		else statement.append(' ');

		position = end;
		return true;
	}

	private int blockCommentEnd() {
		int depth = 0;
		for (int index = position; index < script.length() - 1; index++) {
			if (script.startsWith("/*", index)) {
				depth++;
				index++;
			} else if (script.startsWith("*/", index)) {
				depth--;
				index++;
			}

			if (depth == 0) return index + 1;
		}

		throw new IllegalArgumentException("Unterminated comment in SQL script");
	}

	private boolean readQuoted() {
		char quote = script.charAt(position);
		if (quote != '\'' && quote != '"' && quote != '`') return false;

		boolean escapes = quote != '`' && (backslashEscapes || isEscapeString());
		int index = position + 1;
		while (true) {
			if (index >= script.length()) throw new IllegalArgumentException("Unterminated quote in SQL script");

			char character = script.charAt(index);
			boolean doubled = character == quote && index + 1 < script.length() && script.charAt(index + 1) == quote;
			if (character == quote && !doubled) break;

			index += doubled || (escapes && character == '\\') ? 2 : 1;
		}

		statement.append(script, position, index + 1);
		position = index + 1;
		return true;
	}

	/** Tells whether the quote at the current position opens a PostgreSQL {@code E'...'} string. */
	private boolean isEscapeString() {
		if (position == 0 || Character.toUpperCase(script.charAt(position - 1)) != 'E') return false;

		return position == 1 || !isWordCharacter(script.charAt(position - 2));
	}

	private boolean readDollarQuoted() {
		if (script.charAt(position) != '$') return false;

		Matcher tag = DOLLAR_TAG.matcher(script).region(position, script.length());
		if (!tag.lookingAt()) return false;

		int close = script.indexOf(tag.group(), tag.end());
		if (close < 0) throw new IllegalArgumentException("Unterminated dollar quote in SQL script");

		int end = close + tag.group().length();
		statement.append(script, position, end);
		position = end;
		return true;
	}

	private boolean readDelimiterDirective() {
		boolean lineStart = position == 0 || script.charAt(position - 1) == '\n';
		if (!lineStart || !statement.toString().isBlank()) return false;
		if (!script.regionMatches(true, position, DELIMITER_DIRECTIVE, 0, DELIMITER_DIRECTIVE.length())) return false;

		int lineEnd = script.indexOf('\n', position);
		if (lineEnd < 0) lineEnd = script.length();

		delimiter = script.substring(position + DELIMITER_DIRECTIVE.length(), lineEnd).strip();
		if (delimiter.isEmpty()) throw new IllegalArgumentException("Empty delimiter in SQL script");

		position = lineEnd;
		return true;
	}

	private boolean endStatement() {
		if (!startsWith(delimiter)) return false;
		if (bodyDepth > 0 && delimiter.equals(SEMICOLON)) return false;

		flush();
		position += delimiter.length();
		return true;
	}

	private void readCharacter() {
		char character = script.charAt(position++);
		if (isWordCharacter(character)) word.append(character);

		statement.append(character);
	}

	/** Follows the nesting of a trigger body, whose inner statements end with semicolons too. */
	private void endWord() {
		if (word.isEmpty()) return;

		String keyword = word.toString().toUpperCase(Locale.ROOT);
		word.setLength(0);

		if (keyword.equals("TRIGGER")) trigger = true;
		if (!trigger) return;

		if (keyword.equals("BEGIN") || keyword.equals("CASE")) bodyDepth++;
		if (keyword.equals("END")) bodyDepth--;
	}

	private void flush() {
		String text = statement.toString().strip();
		if (!text.isEmpty()) statements.add(text);

		statement.setLength(0);
		word.setLength(0);
		trigger = false;
		bodyDepth = 0;
	}

	private boolean startsWith(String text) {
		return script.startsWith(text, position);
	}

	private static boolean isWordCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_';
	}
}
