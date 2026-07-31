package com.nexus.analytics.unit;

import com.nexus.analytics.streams.aggregate.CategorySpendingAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryAggregateTest {

    @Test
    void zeroStartsWithZeroTotalsAndMxnCurrency() {
        CategorySpendingAggregate zero = CategorySpendingAggregate.zero();

        assertThat(zero.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.getTransactionCount()).isZero();
        assertThat(zero.getCurrency()).isEqualTo("MXN");
        assertThat(zero.getMerchantBreakdown()).isEmpty();
    }

    @Test
    void addAccumulatesTotalAndCount() {
        CategorySpendingAggregate result = CategorySpendingAggregate.zero()
                .add(new BigDecimal("100.00"), "MXN", "Amazon")
                .add(new BigDecimal("50.00"), "MXN", "Amazon");

        assertThat(result.getTotalAmount()).isEqualByComparingTo("150.00");
        assertThat(result.getTransactionCount()).isEqualTo(2);
    }

    @Test
    void addIsImmutableAndReturnsNewInstance() {
        CategorySpendingAggregate original = CategorySpendingAggregate.zero();

        CategorySpendingAggregate updated = original.add(new BigDecimal("100.00"), "MXN", "Amazon");

        assertThat(original.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(updated.getTotalAmount()).isEqualByComparingTo("100.00");
        assertThat(original).isNotSameAs(updated);
    }

    @Test
    void addAggregatesSameMerchantAcrossCalls() {
        CategorySpendingAggregate result = CategorySpendingAggregate.zero()
                .add(new BigDecimal("30.00"), "MXN", "Uber")
                .add(new BigDecimal("20.00"), "MXN", "Uber")
                .add(new BigDecimal("15.00"), "MXN", "Starbucks");

        assertThat(result.getMerchantBreakdown()).containsEntry("Uber", new BigDecimal("50.00"));
        assertThat(result.getMerchantBreakdown()).containsEntry("Starbucks", new BigDecimal("15.00"));
    }

    @Test
    void addIgnoresBlankOrNullMerchant() {
        CategorySpendingAggregate result = CategorySpendingAggregate.zero()
                .add(new BigDecimal("30.00"), "MXN", null)
                .add(new BigDecimal("20.00"), "MXN", "  ");

        assertThat(result.getMerchantBreakdown()).isEmpty();
        assertThat(result.getTotalAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void addKeepsOnlyTopTenMerchantsByAmount() {
        CategorySpendingAggregate result = CategorySpendingAggregate.zero();
        for (int i = 0; i < 15; i++) {
            result = result.add(BigDecimal.valueOf(i + 1), "MXN", "Merchant" + i);
        }

        assertThat(result.getMerchantBreakdown()).hasSize(10);
        // Highest-value merchants (Merchant14 down to Merchant5) should survive
        assertThat(result.getMerchantBreakdown()).containsKey("Merchant14");
        assertThat(result.getMerchantBreakdown()).doesNotContainKey("Merchant0");
    }

    @Test
    void addTracksMaxTransactionAmount() {
        CategorySpendingAggregate result = CategorySpendingAggregate.zero()
                .add(new BigDecimal("30.00"), "MXN", "A")
                .add(new BigDecimal("500.00"), "MXN", "B")
                .add(new BigDecimal("10.00"), "MXN", "C");

        assertThat(result.getMaxTransactionAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void addComputesRunningAverage() {
        CategorySpendingAggregate result = CategorySpendingAggregate.zero()
                .add(new BigDecimal("100.00"), "MXN", "A")
                .add(new BigDecimal("200.00"), "MXN", "B");

        assertThat(result.getAverageTransactionAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void addPreservesFirstTransactionTimestampAcrossUpdates() {
        CategorySpendingAggregate first = CategorySpendingAggregate.zero().add(new BigDecimal("1.00"), "MXN", "A");
        var firstTimestamp = first.getFirstTransactionAt();

        CategorySpendingAggregate second = first.add(new BigDecimal("2.00"), "MXN", "B");

        assertThat(second.getFirstTransactionAt()).isEqualTo(firstTimestamp);
        assertThat(second.getLastUpdatedAt()).isAfterOrEqualTo(first.getLastUpdatedAt());
    }

    @Test
    void getTopMerchantsSortedOrdersByAmountDescending() {
        CategorySpendingAggregate result = CategorySpendingAggregate.zero()
                .add(new BigDecimal("10.00"), "MXN", "Low")
                .add(new BigDecimal("100.00"), "MXN", "High")
                .add(new BigDecimal("50.00"), "MXN", "Mid");

        List<Map.Entry<String, BigDecimal>> sorted = result.getTopMerchantsSorted();

        assertThat(sorted).extracting(Map.Entry::getKey).containsExactly("High", "Mid", "Low");
    }

    @Test
    void addRetainsCurrencyWhenNotOverridden() {
        CategorySpendingAggregate result = CategorySpendingAggregate.zero()
                .add(new BigDecimal("10.00"), null, "A");

        assertThat(result.getCurrency()).isEqualTo("MXN");
    }
}
