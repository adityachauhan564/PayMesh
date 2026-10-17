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
                .containsExactly("1");
    }

    @Test
    void secondMigrateIsNoOp(@Autowired Flyway flyway) {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
