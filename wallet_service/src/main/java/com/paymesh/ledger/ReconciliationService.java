package com.paymesh.ledger;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Proves the ledger: every wallet's cached balance must equal its credits minus debits, and all postings together
 * must net to zero. Runs nightly on one replica (ShedLock); a dirty result is logged at ERROR with each difference.
 */
@Service
@Slf4j
public class ReconciliationService {

    private static final String MISMATCHES = """
            SELECT w.user_id, w.balance, COALESCE(p.total, 0) AS ledger
            FROM wallet w
            LEFT JOIN ledger_account a ON a.user_id = w.user_id
            LEFT JOIN (SELECT account_id, SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS total
                       FROM ledger_posting GROUP BY account_id) p ON p.account_id = a.id
            WHERE w.balance <> COALESCE(p.total, 0)
            ORDER BY w.user_id""";

    private static final String NET = "SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0) FROM ledger_posting";

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReconciliationReport reconcile() {
        List<ReconciliationReport.Mismatch> mismatches = jdbc.query(MISMATCHES, (rs, row) -> new ReconciliationReport.Mismatch(
                rs.getLong("user_id"), rs.getBigDecimal("balance"), rs.getBigDecimal("ledger")));
        return new ReconciliationReport(mismatches, jdbc.queryForObject(NET, BigDecimal.class));
    }

    @Scheduled(cron = "${ledger.reconcile-cron}")
    @SchedulerLock(name = "ledger-reconcile", lockAtMostFor = "PT10M")
    public void nightly() {
        ReconciliationReport report = reconcile();
        if (report.clean()) {
            log.info("Ledger reconciled: every wallet matches its postings");
        } else {
            log.error("Ledger reconciliation FAILED: net of all postings {}, mismatches {}", report.netOfAllPostings().toPlainString(), report.mismatches());
        }
    }
}
