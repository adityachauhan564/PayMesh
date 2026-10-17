package com.paymesh.ledger;

import com.paymesh.enums.TransactionType;
import com.paymesh.ledger.LedgerEntries.Direction;
import com.paymesh.ledger.LedgerEntries.Line;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The posting rules, for every amount: balanced, users move the right way, the right system account. */
class LedgerEntriesProperties {

    private static final long FROM = 11L;
    private static final long TO = 22L;

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals().between(new BigDecimal("0.01"), new BigDecimal("1000000")).ofScale(2);
    }

    private static List<Line> lines(TransactionType type, BigDecimal amount) {
        return switch (type) {
            case TRANSFER -> LedgerEntries.forPayment(type, FROM, TO, amount);
            case ADD_MONEY -> LedgerEntries.forPayment(type, null, TO, amount);
            case WITHDRAW, BILL_PAYMENT -> LedgerEntries.forPayment(type, FROM, null, amount);
        };
    }

    private static BigDecimal sum(List<Line> lines, Direction direction) {
        return lines.stream().filter(l -> l.direction() == direction).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Property(tries = 500)
    void everyEntryBalances(@ForAll TransactionType type, @ForAll("amounts") BigDecimal amount) {
        List<Line> lines = lines(type, amount);

        assertThat(lines).hasSize(2);
        assertThat(sum(lines, Direction.DEBIT)).isEqualByComparingTo(amount);
        assertThat(sum(lines, Direction.CREDIT)).isEqualByComparingTo(amount);
    }

    @Property(tries = 500)
    void usersMoveTheRightWay(@ForAll TransactionType type, @ForAll("amounts") BigDecimal amount) {
        for (Line line : lines(type, amount)) {
            if (line.accountCode().equals(LedgerEntries.userAccount(FROM))) {
                assertThat(line.direction()).isEqualTo(Direction.DEBIT);
            }
            if (line.accountCode().equals(LedgerEntries.userAccount(TO))) {
                assertThat(line.direction()).isEqualTo(Direction.CREDIT);
            }
        }
    }

    @Property(tries = 200)
    void systemCounterpartyMatchesTheType(@ForAll("amounts") BigDecimal amount) {
        assertThat(lines(TransactionType.ADD_MONEY, amount))
                .contains(new Line(LedgerEntries.PG_CLEARING, Direction.DEBIT, amount));
        assertThat(lines(TransactionType.WITHDRAW, amount))
                .contains(new Line(LedgerEntries.BANK_PAYOUT_CLEARING, Direction.CREDIT, amount));
        assertThat(lines(TransactionType.BILL_PAYMENT, amount))
                .contains(new Line(LedgerEntries.BILLER_PAYABLE, Direction.CREDIT, amount));
        assertThat(lines(TransactionType.TRANSFER, amount)).extracting(Line::accountCode)
                .containsExactly(LedgerEntries.userAccount(FROM), LedgerEntries.userAccount(TO));
    }

    @Example
    void inconsistentPartiesAreRefused() {
        BigDecimal ten = new BigDecimal("10.00");
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntries.forPayment(TransactionType.TRANSFER, FROM, null, ten));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntries.forPayment(TransactionType.ADD_MONEY, FROM, TO, ten));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntries.forPayment(TransactionType.WITHDRAW, null, null, ten));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntries.forPayment(TransactionType.BILL_PAYMENT, FROM, TO, ten));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntries.forPayment(TransactionType.TRANSFER, FROM, FROM, ten));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntries.forPayment(TransactionType.TRANSFER, FROM, TO, BigDecimal.ZERO));
    }
}
