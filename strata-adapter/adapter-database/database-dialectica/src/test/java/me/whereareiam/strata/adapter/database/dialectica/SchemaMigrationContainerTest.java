package me.whereareiam.strata.adapter.database.dialectica;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.MariaDbDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Creates and migrates a schema through Dialectica on PostgreSQL, MariaDB and MySQL. Each test
 * starts from an empty database.
 */
@Tag("database-container")
@Testcontainers
class SchemaMigrationContainerTest {
	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

	@Container
	private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:12");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

	@Test
	void createsTheNewestPostgresSchemaOnANewInstallation() throws Exception {
		SchemaScenarios.createsTheNewestSchemaOnANewInstallation(postgres());
	}

	@Test
	void appliesSchemaChangesToAnExistingPostgresInstallation() throws Exception {
		SchemaScenarios.appliesSchemaChangesToAnExistingInstallation(postgres());
	}

	@Test
	void takesBackAFailedPostgresSchemaMigration() throws Exception {
		SchemaScenarios.takesBackAFailedSchemaMigration(postgres());
	}

	@Test
	void createsAPostgresSchemaOnceWhenTwoInstancesInstallTogether() throws Exception {
		SchemaScenarios.createsOnceWhenTwoInstancesInstallTogether(postgres());
	}

	@Test
	void createsTheNewestMariaDbSchemaOnANewInstallation() throws Exception {
		SchemaScenarios.createsTheNewestSchemaOnANewInstallation(mariadb());
	}

	@Test
	void appliesSchemaChangesToAnExistingMariaDbInstallation() throws Exception {
		SchemaScenarios.appliesSchemaChangesToAnExistingInstallation(mariadb());
	}

	@Test
	void createsAMariaDbSchemaOnceWhenTwoInstancesInstallTogether() throws Exception {
		SchemaScenarios.createsOnceWhenTwoInstancesInstallTogether(mariadb());
	}

	@Test
	void createsTheNewestMysqlSchemaOnANewInstallation() throws Exception {
		SchemaScenarios.createsTheNewestSchemaOnANewInstallation(mysql());
	}

	@Test
	void appliesSchemaChangesToAnExistingMysqlInstallation() throws Exception {
		SchemaScenarios.appliesSchemaChangesToAnExistingInstallation(mysql());
	}

	@Test
	void createsAMysqlSchemaOnceWhenTwoInstancesInstallTogether() throws Exception {
		SchemaScenarios.createsOnceWhenTwoInstancesInstallTogether(mysql());
	}

	private static DataSource postgres() throws Exception {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUser(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());

		return emptied(dataSource, "DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
	}

	private static DataSource mariadb() throws Exception {
		MariaDbDataSource dataSource = new MariaDbDataSource(MARIADB.getJdbcUrl());
		dataSource.setUser(MARIADB.getUsername());
		dataSource.setPassword(MARIADB.getPassword());

		return emptied(dataSource, "DROP TABLE IF EXISTS accounts, strata_history");
	}

	private static DataSource mysql() throws Exception {
		MysqlDataSource dataSource = new MysqlDataSource();
		dataSource.setUrl(MYSQL.getJdbcUrl());
		dataSource.setUser(MYSQL.getUsername());
		dataSource.setPassword(MYSQL.getPassword());

		return emptied(dataSource, "DROP TABLE IF EXISTS accounts, strata_history");
	}

	/** Drops what an earlier test left, so that every test installs a new database. */
	private static DataSource emptied(DataSource dataSource, String... statements) throws Exception {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			for (String sql : statements) statement.execute(sql);
		}

		return dataSource;
	}
}
