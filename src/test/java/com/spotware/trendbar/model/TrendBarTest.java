package com.spotware.trendbar.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("TrendBar")
class TrendBarTest {

    // 2024-01-01T00:00:00Z and the next M1 bucket
    private static final long T1 = 1_704_067_200_000L;
    private static final long T2 = T1 + 60_000L;
    private static final Symbol SYMBOL = Symbol.EURUSD;
    private static final PeriodType PERIOD_TYPE = PeriodType.M1;

    @Test
    @DisplayName("bars with same symbol, period and timestamp are equal and share the same hashCode")
    void equalBarsAreEqualAndHaveSameHash() {
        TrendBar a = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);
        TrendBar b = new TrendBar(SYMBOL, PERIOD_TYPE, 200, 210, 220, 190, T1); // different prices — same identity

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("bars with different symbol are not equal")
    void barsWithDifferentSymbolAreNotEqual() {
        TrendBar eur = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);
        TrendBar jpy = new TrendBar(Symbol.EURJPY, PERIOD_TYPE, 100, 110, 120, 90, T1);

        assertThat(eur).isNotEqualTo(jpy);
    }

    @Test
    @DisplayName("bars with different period type are not equal")
    void barsWithDifferentPeriodAreNotEqual() {
        TrendBar m1 = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);
        TrendBar h1 = new TrendBar(SYMBOL, PeriodType.H1, 100, 110, 120, 90, T1);

        assertThat(m1).isNotEqualTo(h1);
    }

    @Test
    @DisplayName("bars with same identity but different prices are still equal (prices are not part of identity)")
    void barsWithDifferentPricesButSameIdentityAreEqual() {
        TrendBar base = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);

        assertThat(base).isEqualTo(new TrendBar(SYMBOL, PERIOD_TYPE, 101, 110, 120, 90, T1));
        assertThat(base).isEqualTo(new TrendBar(SYMBOL, PERIOD_TYPE, 100, 111, 120, 90, T1));
        assertThat(base).isEqualTo(new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 121, 90, T1));
        assertThat(base).isEqualTo(new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 91, T1));
    }

    @Test
    @DisplayName("bars with different timestamp are not equal")
    void barsWithDifferentTimestampAreNotEqual() {
        TrendBar a = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);
        TrendBar b = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T2);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("null symbol throws NullPointerException")
    void nullSymbolRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TrendBar(null, PERIOD_TYPE, 100, 110, 120, 90, T1))
                .withMessage("symbol");
    }

    @Test
    @DisplayName("null periodType throws NullPointerException")
    void nullPeriodRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TrendBar(SYMBOL, null, 100, 110, 120, 90, T1))
                .withMessage("periodType");
    }

    @Test
    @DisplayName("toString contains all fields")
    void toStringContainsAllFields() {
        TrendBar bar = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);

        assertThat(bar.toString())
                .contains(SYMBOL.name(), PERIOD_TYPE.name(), "100", "110", "120", "90", String.valueOf(T1));
    }
}
