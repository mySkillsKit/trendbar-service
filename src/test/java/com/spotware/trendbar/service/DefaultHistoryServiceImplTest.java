package com.spotware.trendbar.service;

import com.spotware.trendbar.dao.InMemoryTrendBarDao;
import com.spotware.trendbar.dao.TrendBarDao;
import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import com.spotware.trendbar.service.impl.DefaultHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("DefaultHistoryService")
class DefaultHistoryServiceImplTest {

    // 2024-01-01T00:00:00Z — day-aligned anchor for fixture data
    private static final long BASE_TS = 1_704_067_200_000L;
    private static final long MINUTE_MS = 60_000L;
    private static final Symbol SYMBOL = Symbol.EURUSD;
    private static final PeriodType PERIOD_TYPE = PeriodType.M1;

    private TrendBarDao trendBarDao;
    private DefaultHistoryServiceImpl history;

    static Stream<Arguments> nullArguments() {
        return Stream.of(
                Arguments.of(null, PERIOD_TYPE, BASE_TS, BASE_TS + 100L, "symbol"),
                Arguments.of(SYMBOL, null, BASE_TS, BASE_TS + 100L, "periodType"),
                Arguments.of(SYMBOL, PERIOD_TYPE, null, BASE_TS + 100L, "from"),
                Arguments.of(SYMBOL, PERIOD_TYPE, BASE_TS, null, "to")
        );
    }

    @BeforeEach
    void setUp() {
        trendBarDao = new InMemoryTrendBarDao();
        history = new DefaultHistoryServiceImpl(trendBarDao);
    }

    @Test
    @DisplayName("provides TB history by symbol, period, and [from, to) range; omitting 'to' returns bars up to now")
    void providesTrendBarHistoryUponRequest() {
        // Bars for the target symbol and period
        TrendBar t1 = createTrendBarBuilder(new Quote(100L, SYMBOL, BASE_TS), PERIOD_TYPE).build();
        TrendBar t2 = createTrendBarBuilder(new Quote(110L, SYMBOL, BASE_TS + MINUTE_MS), PERIOD_TYPE).build();
        TrendBar t3 = createTrendBarBuilder(new Quote(120L, SYMBOL, BASE_TS + 2 * MINUTE_MS), PERIOD_TYPE).build();
        // Bar for a different symbol — must not appear in EURUSD results
        TrendBar otherSymbol = createTrendBarBuilder(new Quote(200L, Symbol.EURJPY, BASE_TS), PERIOD_TYPE).build();
        // Bar for a different period — must not appear in M1 results
        TrendBar otherPeriod = createTrendBarBuilder(new Quote(300L, SYMBOL, BASE_TS), PeriodType.H1).build();

        history.save(t1);
        history.save(t2);
        history.save(t3);
        history.save(otherSymbol);
        history.save(otherPeriod);

        // Range query: [BASE_TS, BASE_TS + 2*MINUTE_MS) — returns t1 and t2, excludes t3
        assertThat(history.getForPeriod(SYMBOL, PERIOD_TYPE, BASE_TS, BASE_TS + 2 * MINUTE_MS))
                .as("range [from, to) must include t1 and t2 but exclude t3, otherSymbol, otherPeriod")
                .containsExactly(t1, t2);

        // Open-ended query: omit 'to' → returns all bars from BASE_TS up to now
        assertThat(history.getForPeriod(SYMBOL, PERIOD_TYPE, BASE_TS))
                .as("3-arg overload must return all bars from 'from' up to current time")
                .containsExactly(t1, t2, t3);

        // Different symbol returns no EURUSD bars
        assertThat(history.getForPeriod(Symbol.EURJPY, PERIOD_TYPE, BASE_TS, Long.MAX_VALUE))
                .as("query for EURJPY must not return EURUSD bars")
                .containsExactly(otherSymbol);

        // Different period returns no M1 bars
        assertThat(history.getForPeriod(SYMBOL, PeriodType.H1, BASE_TS, Long.MAX_VALUE))
                .as("query for H1 must not return M1 bars")
                .containsExactly(otherPeriod);
    }

    @Test
    @DisplayName("3-arg overload returns bars from given timestamp up to now")
    void threeArgOverloadReturnsBarsUpToNow() {
        long longAgo = System.currentTimeMillis() - 86_400_000L;
        TrendBar past = createTrendBarBuilder(new Quote(100L, SYMBOL, longAgo), PERIOD_TYPE).build();
        history.save(past);

        assertThat(history.getForPeriod(SYMBOL, PERIOD_TYPE, PERIOD_TYPE.floor(longAgo))).containsExactly(past);
    }

    @Test
    @DisplayName("4-arg overload excludes bars at or after explicit 'to'")
    void fourArgOverloadHonorsExplicitTo() {
        TrendBar.TrendBarBuilder insideBuilder = createTrendBarBuilder(new Quote(100L, SYMBOL, BASE_TS), PERIOD_TYPE);
        insideBuilder.apply(120L);
        insideBuilder.apply(90L);
        insideBuilder.apply(110L);
        TrendBar inside = insideBuilder.build();

        TrendBar.TrendBarBuilder outsideBuilder = createTrendBarBuilder(new Quote(110L, SYMBOL, BASE_TS + MINUTE_MS), PERIOD_TYPE);
        outsideBuilder.apply(130L);
        outsideBuilder.apply(105L);
        outsideBuilder.apply(115L);
        TrendBar outside = outsideBuilder.build();
        history.save(inside);
        history.save(outside);

        assertThat(history.getForPeriod(SYMBOL, PERIOD_TYPE, BASE_TS, BASE_TS + MINUTE_MS))
                .containsExactly(inside);
    }

    @Test
    @DisplayName("empty history returns empty collection")
    void emptyHistoryReturnsEmpty() {
        assertThat(history.getForPeriod(SYMBOL, PERIOD_TYPE, BASE_TS, Long.MAX_VALUE))
                .isEmpty();
    }

    @DisplayName("null argument throws NullPointerException")
    @ParameterizedTest(name = "null {4} -> NPE")
    @MethodSource("nullArguments")
    void nullArgumentThrowsNpe(Symbol symbol, PeriodType period, Long from, Long to, String missing) {
        assertThatNullPointerException()
                .isThrownBy(() -> history.getForPeriod(symbol, period, from, to))
                .withMessage(missing);
    }

    @Test
    @DisplayName("from > to throws IllegalArgumentException")
    void fromStrictlyGreaterThanToThrowsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> history.getForPeriod(SYMBOL, PERIOD_TYPE, BASE_TS + MINUTE_MS, BASE_TS))
                .withMessage("'from' must be < 'to'");
    }

    @Test
    @DisplayName("from > to by 1ms throws IllegalArgumentException")
    void fromGreaterThanToCheckRunsBeforeDaoCall() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> history.getForPeriod(SYMBOL, PERIOD_TYPE, BASE_TS + 1L, BASE_TS))
                .withMessage("'from' must be < 'to'");
    }

    @Test
    @DisplayName("from == to throws IllegalArgumentException")
    void fromEqualsToThrowsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> history.getForPeriod(SYMBOL, PERIOD_TYPE, BASE_TS, BASE_TS))
                .withMessage("'from' must be < 'to'");
    }

    private TrendBar.TrendBarBuilder createTrendBarBuilder(Quote quote, PeriodType period) {
        return new TrendBar.TrendBarBuilder(period)
                .symbol(quote.symbol())
                .initPrice(quote.price())
                .startTimestamp(quote.timestamp());
    }
}
