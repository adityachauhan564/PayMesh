package com.paymesh;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Runs deploy/mysql/init/01-service-schemas.sh exactly as compose does, with passwords a generator might produce. */
class ServiceSchemasInitScriptTest {

    private static final String USER_PW = "pa'ss\\w$rd`x";
    private static final String WALLET_PW = "wallet'\\pw";

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withEnv("USER_DB_PASSWORD", USER_PW)
            .withEnv("WALLET_DB_PASSWORD", WALLET_PW)
            .withEnv("TXN_DB_PASSWORD", "txn-pw")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "deploy", "mysql", "init", "01-service-schemas.sh")),
                    "/docker-entrypoint-initdb.d/01-service-schemas.sh");

    @BeforeAll
    static void start() {
        MYSQL.start();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    private static Connection connect(String schema, String user, String password) throws SQLException {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema;
        return DriverManager.getConnection(url, user, password);
    }

    @Test
    void serviceUserLogsInWithQuotesAndBackslashesInItsPassword() throws SQLException {
        try (Connection c = connect("user_db", "user_svc", USER_PW)) {
            assertThat(c.createStatement().execute("CREATE TABLE probe (id INT)")).isFalse();
        }
    }

    @Test
    void serviceUserCannotReadAnotherServicesSchema() throws SQLException {
        try (Connection c = connect("wallet_db", "wallet_svc", WALLET_PW)) {
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> c.createStatement().executeQuery("SELECT 1 FROM user_db.probe"))
                    .withMessageContaining("denied");
        }
    }
}
