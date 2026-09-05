package me.whereareiam.strata.integration.jdbc;

import me.whereareiam.strata.MigrationAction;
import org.jetbrains.annotations.NotNull;

/** An explicitly restart-safe operation for databases with implicit DDL commits.
 * Strata records intent before calling it. After interruption, recovery verifies the desired postcondition
 * or safely completes remaining work. Implementations must not blindly repeat partially completed operations.
 */
public interface RecoverableJdbcAction extends MigrationAction<JdbcContext> {
	/** Recovers a previously started operation, returning only after validating the resulting layout.
	 * @param context active database resources
	 * @throws Exception if recovery cannot complete safely
	 */
	void recover(@NotNull JdbcContext context) throws Exception;
}
