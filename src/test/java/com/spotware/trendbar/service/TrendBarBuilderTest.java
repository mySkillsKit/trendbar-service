package com.spotware.trendbar.service;

import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TrendBarBuilder")
class TrendBarBuilderTest {

    public static final PeriodType PERIOD_TYPE = PeriodType.M1;
    // 2024-01-01T00:01:00Z — first full minute of 2024
    private static final long MINUTE_START = 1_704_067_260_000L;
    private static final Symbol SYMBOL = Symbol.EURUSD;

    // 2024-01-01T00:00:47.312Z — intra-minute quote: 47 312 ms after the minute boundary
    private static final long INTRA_MINUTE_TS = 1_704_067_247_312L;
    // expected M1 boundary: 2024-01-01T00:00:00Z
    private static final long MINUTE_BOUNDARY = 1_704_067_200_000L;
    // 2024-01-01T00:20:15.500Z — intra-hour quote for H1 tests
    private static final long INTRA_HOUR_TS = 1_704_068_415_500L;
    // expected H1 boundary: 2024-01-01T00:00:00Z
    private static final long HOUR_BOUNDARY = 1_704_067_200_000L;

    @Test
    @DisplayName("building from single quote sets open=close=high=low to that quote price")
    void buildFromSingleQuoteUsesSamePriceForAllFields() {
        TrendBar.TrendBarBuilder builder = createTrendBarBuilder(new Quote(100L, SYMBOL, MINUTE_START + 10), PERIOD_TYPE);

        TrendBar bar = builder.build();

        assertThat(bar.getOpenPrice()).isEqualTo(100L);
        assertThat(bar.getClosePrice()).isEqualTo(100L);
        assertThat(bar.getHighPrice()).isEqualTo(100L);
        assertThat(bar.getLowPrice()).isEqualTo(100L);
        assertThat(bar.getTimestamp()).isEqualTo(MINUTE_START);
        assertThat(bar.getPeriodType()).isEqualTo(PERIOD_TYPE);
    }

    @Test
    @DisplayName("open is fixed by first quote; close, high, low follow subsequent applies")
    void openIsFixedByFirstQuoteCloseFollowsLatest() {
        TrendBar.TrendBarBuilder builder = createTrendBarBuilder(new Quote(100L, SYMBOL, MINUTE_START), PERIOD_TYPE);
        builder.apply(120L);
        builder.apply(90L);
        builder.apply(110L);

        TrendBar bar = builder.build();

        assertThat(bar.getOpenPrice()).isEqualTo(100L);
        assertThat(bar.getClosePrice()).isEqualTo(110L);
        assertThat(bar.getHighPrice()).isEqualTo(120L);
        assertThat(bar.getLowPrice()).isEqualTo(90L);
    }

    @Test
    @DisplayName("open price is immutable across 10 000 random applies")
    void manyUpdatesPreserveOpenPrice() {
        long openPrice = 1_000L;
        TrendBar.TrendBarBuilder builder = createTrendBarBuilder(new Quote(openPrice, SYMBOL, MINUTE_START), PERIOD_TYPE);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 10_000; i++) {
            builder.apply(rnd.nextLong(0, 10_000));
        }

        assertThat(builder.build().getOpenPrice()).isEqualTo(openPrice);
    }

    @DisplayName("isCompletedAt returns true exactly at and after period boundary")
    @ParameterizedTest(name = "{0}: builder opened at {1} completed at {2} -> {3}")
    @CsvSource({
            "M1, 60000,   60000,    false",
            "M1, 60000,   119999,   false",
            "M1, 60000,   120000,   true",
            "M1, 60000,   120001,   true",
            "H1, 3600000, 7199999,  false",
            "H1, 3600000, 7200000,  true",
            "D1, 0,       86399999, false",
            "D1, 0,       86400000, true"
    })
    void isCompletedAtBoundary(PeriodType period, long openTs, long checkTs, boolean expected) {
        TrendBar.TrendBarBuilder builder = createTrendBarBuilder(new Quote(100L, SYMBOL, openTs), period);

        assertThat(builder.isCompletedAt(checkTs)).isEqualTo(expected);
    }

    @Test
    @DisplayName("startTimestamp is floored to M1 boundary when quote arrives mid-minute")
    void startTimestampIsFlooredToMinuteBoundary() {
        Quote quote = new Quote(100L, SYMBOL, INTRA_MINUTE_TS);
        TrendBar.TrendBarBuilder builder = createTrendBarBuilder(quote, PERIOD_TYPE);

        assertThat(builder.build().getTimestamp())
                .as("startTimestamp must be floored to the M1 boundary, not the raw quote ts")
                .isEqualTo(MINUTE_BOUNDARY);
    }

    @Test
    @DisplayName("startTimestamp is floored to H1 boundary when quote arrives mid-hour")
    void startTimestampIsFlooredToHourBoundary() {
        Quote quote = new Quote(100L, SYMBOL, INTRA_HOUR_TS);
        TrendBar.TrendBarBuilder builder = createTrendBarBuilder(quote, PeriodType.H1);

        assertThat(builder.build().getTimestamp())
                .as("startTimestamp must be floored to the H1 boundary, not the raw quote ts")
                .isEqualTo(HOUR_BOUNDARY);
    }

    @Test
    @DisplayName("isCompletedAt fires at the floored period boundary, not rawTs + duration")
    void isCompletedAtUsesPeriodBoundaryNotRawQuoteTimestamp() {
        Quote quote = new Quote(100L, SYMBOL, INTRA_MINUTE_TS);
        TrendBar.TrendBarBuilder builder = createTrendBarBuilder(quote, PERIOD_TYPE);

        long correctCompletionTs = MINUTE_BOUNDARY + PERIOD_TYPE.durationMs();
        long withoutFloorCompletionTs = INTRA_MINUTE_TS + PERIOD_TYPE.durationMs();

        assertThat(correctCompletionTs)
                .as("completion with floor must be earlier than without floor")
                .isLessThan(withoutFloorCompletionTs);

        assertThat(builder.isCompletedAt(correctCompletionTs - 1))
                .as("not yet complete at 00:00:59.999")
                .isFalse();

        assertThat(builder.isCompletedAt(correctCompletionTs))
                .as("complete at 00:01:00.000 — the M1 boundary")
                .isTrue();
    }

    @Test
    @DisplayName("quote exactly on boundary produces startTimestamp equal to that boundary")
    void quoteExactlyOnBoundaryStartsAtThatBoundary() {
        Quote quote = new Quote(100L, SYMBOL, MINUTE_BOUNDARY);
        TrendBar.TrendBarBuilder builder = createTrendBarBuilder(quote, PERIOD_TYPE);

        assertThat(builder.build().getTimestamp()).isEqualTo(MINUTE_BOUNDARY);
    }

    private TrendBar.TrendBarBuilder createTrendBarBuilder(Quote quote, PeriodType period) {
        return new TrendBar.TrendBarBuilder(period)
                .symbol(quote.symbol())
                .initPrice(quote.price())
                .startTimestamp(quote.timestamp());
    }
}
