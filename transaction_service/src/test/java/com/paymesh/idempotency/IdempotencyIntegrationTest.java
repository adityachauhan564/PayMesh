package com.paymesh.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.paymesh.support.MySqlTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL: concurrent duplicates are settled by the unique index, which only a real database shows. */
@SpringBootTest(properties = {"jwt.secret=0123456789abcdef0123456789abcdef",
        "spring.kafka.listener.auto-startup=false", "outbox.poll-interval-ms=3600000"})
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

    private static final String ADD_100 = "{\"type\":\"ADD_MONEY\",\"amount\":100}";
    private static final AtomicLong USERS = new AtomicLong(1000);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestContainer.register(registry);
    }

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IdempotentPayments idempotentPayments;

    private static MockHttpServletRequestBuilder pay(long user, String key, String body) {
        MockHttpServletRequestBuilder request = post("/api/transactions")
                .with(jwt().jwt(j -> j.subject(String.valueOf(user)).claim("name", "User " + user)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private static String id(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.transactionId");
    }

    private long payments(long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE receiver_id = ?", Long.class, user);
    }

    @Test
    void sameKeySameBodyReplays() throws Exception {
        long user = USERS.incrementAndGet();
        MvcResult first = mvc.perform(pay(user, "k1", ADD_100)).andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn();
        MvcResult second = mvc.perform(pay(user, "k1", ADD_100)).andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn();

        assertThat(id(second)).isEqualTo(id(first));
        assertThat(payments(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE message_key = ?", Long.class, id(first)))
                .isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIs422() throws Exception {
        long user = USERS.incrementAndGet();
        mvc.perform(pay(user, "k2", ADD_100)).andExpect(status().isAccepted());

        mvc.perform(pay(user, "k2", "{\"type\":\"ADD_MONEY\",\"amount\":200}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different request")));
    }

    @Test
    void bodyComparisonIgnoresFieldOrder() throws Exception {
        long user = USERS.incrementAndGet();
        MvcResult first = mvc.perform(pay(user, "k3", ADD_100)).andReturn();

        MvcResult second = mvc.perform(pay(user, "k3", "{ \"amount\": 100, \"type\": \"ADD_MONEY\" }"))
                .andExpect(status().isAccepted()).andExpect(header().string("Idempotent-Replayed", "true")).andReturn();

        assertThat(id(second)).isEqualTo(id(first));
    }

    @Test
    void concurrentDuplicatesCreateOnePayment() throws Exception {
        long user = USERS.incrementAndGet();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> mvc.perform(pay(user, "k4", ADD_100)).andReturn());
            }
            Set<String> ids = new HashSet<>();
            for (Future<MvcResult> f : pool.invokeAll(calls, 60, TimeUnit.SECONDS)) {
                MvcResult result = f.get();
                assertThat(result.getResponse().getStatus()).isEqualTo(202);
                ids.add(id(result));
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdown();
        }
        assertThat(payments(user)).isEqualTo(1);
    }

    @Test
    void noKeyBehavesAsBefore() throws Exception {
        long user = USERS.incrementAndGet();
        mvc.perform(pay(user, null, ADD_100)).andExpect(status().isAccepted());
        mvc.perform(pay(user, null, ADD_100)).andExpect(status().isAccepted());

        assertThat(payments(user)).isEqualTo(2);
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        long asha = USERS.incrementAndGet();
        long ravi = USERS.incrementAndGet();
        String a = id(mvc.perform(pay(asha, "shared", ADD_100)).andReturn());
        String r = id(mvc.perform(pay(ravi, "shared", ADD_100)).andReturn());

        assertThat(a).isNotEqualTo(r);
    }

    @Test
    void failedValidationIsNotRemembered() throws Exception {
        long user = USERS.incrementAndGet();
        mvc.perform(pay(user, "k9", "{\"type\":\"ADD_MONEY\",\"amount\":0}")).andExpect(status().isBadRequest());

        mvc.perform(pay(user, "k9", ADD_100)).andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void overlongKeyIs400() throws Exception {
        mvc.perform(pay(USERS.incrementAndGet(), "x".repeat(256), ADD_100)).andExpect(status().isBadRequest());
    }

    @Test
    void expiredKeysAreDeleted() throws Exception {
        long user = USERS.incrementAndGet();
        mvc.perform(pay(user, "k-expire", ADD_100)).andExpect(status().isAccepted());
        java.time.Instant now = java.time.Instant.now();

        assertThat(idempotentPayments.deleteExpiredAsOf(now.plus(java.time.Duration.ofHours(23)))).isZero();
        assertThat(idempotentPayments.deleteExpiredAsOf(now.plus(java.time.Duration.ofHours(25)))).isPositive();

        // expired = forgotten: the same key now creates a new payment
        mvc.perform(pay(user, "k-expire", ADD_100)).andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(payments(user)).isEqualTo(2);
    }
}
