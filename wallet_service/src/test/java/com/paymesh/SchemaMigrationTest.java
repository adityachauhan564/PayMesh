package com.paymesh;

import com.paymesh.support.MySqlTestContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Context start = Flyway migrate + Hibernate validate, so this fails whenever V1 and the entities disagree. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaMigrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        MySqlTestContainer.register(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayBuildsTheSchemaHibernateExpects() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1", String.class))
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void secondMigrateIsNoOp(@Autowired Flyway flyway) {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    /** An event still waiting in the outbox when V2 is deployed must get an eventId and stay publishable. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // Flyway's ALTER would wait forever on the test transaction's locks
    void rowsWrittenBeforeV2GetAnEventId(@Autowired DataSource dataSource) {
        Flyway toV1 = Flyway.configure().dataSource(dataSource).cleanDisabled(false).target("1").load();
        toV1.clean();
        toV1.migrate();
        jdbc.update("INSERT INTO outbox_event (producer, topic, message_key, payload) VALUES ('p', 't', 'k', '{}')");

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE producer = 'p'", String.class)).hasSize(36);
    }

    /** Money that existed before the ledger must appear in it, balanced against OPENING_BALANCE. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void openingBalancesAreBackfilled(@Autowired DataSource dataSource) {
        Flyway toV3 = Flyway.configure().dataSource(dataSource).cleanDisabled(false).target("3").load();
        toV3.clean();
        toV3.migrate();
        jdbc.update("INSERT INTO wallet (user_id, balance, daily_limit, daily_transaction_limit, spent_today, txn_count_today) "
                + "VALUES (900, 250.00, 10000, 10, 0, 0)");
        Long walletId = jdbc.queryForObject("SELECT wallet_id FROM wallet WHERE user_id = 900", Long.class);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForList("SELECT code FROM ledger_account WHERE user_id = 900", String.class)).containsExactly("USER:900");
        assertThat(jdbc.queryForList("SELECT CONCAT(a.code, ' ', p.direction, ' ', p.amount) FROM ledger_posting p "
                        + "JOIN journal_entry e ON e.id = p.entry_id JOIN ledger_account a ON a.id = p.account_id "
                        + "WHERE e.transaction_id = ?", String.class, "opening-" + walletId))
                .containsExactlyInAnyOrder("OPENING_BALANCE DEBIT 250.00", "USER:900 CREDIT 250.00");
    }
}
