package com.paymesh.ledger;

import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.enums.TransactionType;
import com.paymesh.repository.WalletRepository;
import com.paymesh.service.WalletOperations;
import com.paymesh.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL, real locking: the ledger must agree with every wallet after any mix of concurrent payments. */
@SpringBootTest(properties = {"jwt.secret=0123456789abcdef0123456789abcdef",
        "spring.kafka.listener.auto-startup=false", "outbox.poll-interval-ms=3600000"})
class LedgerIntegrationTest {

    private static final long[] USERS = {1, 2, 3, 4, 5};

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestContainer.register(registry);
    }

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private WalletOperations walletOperations;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private Ledger ledger;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void wallets() {
        for (long user : USERS) {
            walletOperations.createWalletForNewUser(new UserWalletCreationRequest(user, "User " + user, user + "@example.com"));
            walletOperations.updateLimits(user, new BigDecimal("1000000"), 1000);
        }
    }

    private static InitiateTransactionDto payment(TransactionType type, Long from, Long to, String amount) {
        return new InitiateTransactionDto(UUID.randomUUID().toString(), from, to, new BigDecimal(amount), type);
    }

    @Test
    void randomPaymentsKeepEveryBalanceEqualToItsPostings() throws Exception {
        Random random = new Random(42);
        List<InitiateTransactionDto> payments = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long a = USERS[random.nextInt(USERS.length)];
            long b = USERS[random.nextInt(USERS.length)];
            String amount = (1 + random.nextInt(3000)) + "." + String.format("%02d", random.nextInt(100));
            TransactionType type = TransactionType.values()[random.nextInt(TransactionType.values().length)];
            payments.add(switch (type) {
                case ADD_MONEY -> payment(type, null, a, amount);
                case TRANSFER -> payment(type, a, a == b ? (a % 5) + 1 : b, amount);
                case WITHDRAW, BILL_PAYMENT -> payment(type, a, null, amount);
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (InitiateTransactionDto p : payments) {
                done.add(pool.submit(() -> applyWithRetry(p)));
            }
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        for (long user : USERS) {
            assertThat(walletRepository.findByUserId(user).orElseThrow().getBalance())
                    .as("user %d", user).isEqualByComparingTo(ledger.balanceOf(user));
        }
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0) "
                + "FROM ledger_posting", BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT entry_id FROM ledger_posting GROUP BY entry_id "
                + "HAVING COUNT(*) <> 2 OR SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0) bad", Long.class))
                .isZero();
    }

    /** What the Kafka error handler does in production when MySQL picks a deadlock victim. */
    private void applyWithRetry(InitiateTransactionDto payment) {
        for (int attempt = 1; ; attempt++) {
            try {
                walletOperations.applyTransaction(payment);
                return;
            } catch (ConcurrencyFailureException e) {
                if (attempt == 3) {
                    throw e;
                }
            }
        }
    }

    @Test
    void redeliveryPostsOnce() {
        InitiateTransactionDto addMoney = payment(TransactionType.ADD_MONEY, null, 2L, "100.00");

        walletOperations.applyTransaction(addMoney);
        walletOperations.applyTransaction(addMoney);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry WHERE transaction_id = ?", Long.class,
                addMoney.transactionId())).isEqualTo(1);
    }

    @Test
    void postingCreatesAMissingUserAccount() {
        walletOperations.createWalletForNewUser(new UserWalletCreationRequest(66L, "Racer", "racer@example.com"));
        jdbc.update("DELETE FROM ledger_account WHERE user_id = 66"); // as if the wallet's event raced the migration

        walletOperations.applyTransaction(payment(TransactionType.ADD_MONEY, null, 66L, "70.00"));

        assertThat(ledger.balanceOf(66)).isEqualByComparingTo("70.00");
    }
}
