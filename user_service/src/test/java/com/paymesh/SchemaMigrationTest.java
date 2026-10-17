package com.paymesh;

import com.paymesh.support.MySqlTestContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

/** Context start = Flyway migrate + Hibernate validate, so this fails whenever V1 and the entities disagree. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaMigrationTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        MySqlTestContainer.register(registry);
    }

    /** UserService declares jwtEncoder, which the JPA slice still loads; the real key comes from SecurityConfig. */
    @TestConfiguration
    static class Keys {
        @Bean
        SecretKey jwtKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
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
