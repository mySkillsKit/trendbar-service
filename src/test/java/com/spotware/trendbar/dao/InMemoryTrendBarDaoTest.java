package com.spotware.trendbar.dao;

import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryTrendBarDao")
class InMemoryTrendBarDaoTest {

    // 2024-01-01T00:00:00Z — day-aligned anchor; t1/t2/t3 are sequential M1 buckets
    private static final long BASE_TS = 1_704_067_200_000L;
    private static final long MINUTE_MS = 60_000L;
    private static final long T1 = BASE_TS;
    private static final long T2 = BASE_TS + MINUTE_MS;
    private static final long T3 = BASE_TS + 2 * MINUTE_MS;
    private static final Symbol SYMBOL = Symbol.EURUSD;
    private static final PeriodType PERIOD_TYPE = PeriodType.M1;

    private InMemoryTrendBarDao dao;

    @BeforeEach
    void setUp() {
        dao = new InMemoryTrendBarDao();
    }

    @Test
    @DisplayName("empty DAO returns empty list")
    void emptyDaoReturnsEmptyList() {
        assertThat(dao.findByPeriod(SYMBOL, PERIOD_TYPE, BASE_TS, Long.MAX_VALUE)).isEmpty();
    }

    @DisplayName("findByPeriod respects [from, to) range")
    @ParameterizedTest(name = "range [{0},{1}) -> {2} bars")
    @CsvSource({
            "1704067140000, 1704067200000, 0",
            "1704067140000, 1704067200001, 1",
            "1704067200000, 1704067260000, 1",
            "1704067200000, 1704067320000, 2",
            "1704067200000, 1704067380000, 3",
            "1704067140000, 1778608994000, 3",
            "1704067320001, 1704067999999, 0",
            "1704067140000, 1704067200000, 0"
    })
    void rangeQueryRespectsFromInclusiveToExclusive(long from, long to, int expectedCount) {
        dao.save(new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1));
        dao.save(new TrendBar(SYMBOL, PERIOD_TYPE, 110, 115, 130, 105, T2));
        dao.save(new TrendBar(SYMBOL, PERIOD_TYPE, 120, 125, 140, 115, T3));

        assertThat(dao.findByPeriod(SYMBOL, PERIOD_TYPE, from, to)).hasSize(expectedCount);
    }

    @Test
    @DisplayName("bars are isolated across symbols")
    void barsAreIsolatedAcrossSymbols() {
        TrendBar barEUR = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);
        TrendBar barJPY = new TrendBar(Symbol.EURJPY, PERIOD_TYPE, 200, 210, 220, 190, T1);

        dao.save(barEUR);
        dao.save(barJPY);

        assertThat(dao.findByPeriod(SYMBOL, PERIOD_TYPE, BASE_TS, Long.MAX_VALUE)).containsExactly(barEUR);
        assertThat(dao.findByPeriod(Symbol.EURJPY, PERIOD_TYPE, BASE_TS, Long.MAX_VALUE)).containsExactly(barJPY);
    }

    @Test
    @DisplayName("bars are isolated across period types")
    void barsAreIsolatedAcrossPeriods() {
        TrendBar m1 = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);
        TrendBar h1 = new TrendBar(SYMBOL, PeriodType.H1, 200, 210, 220, 190, BASE_TS + 3_600_000L);

        dao.save(m1);
        dao.save(h1);

        assertThat(dao.findByPeriod(SYMBOL, PERIOD_TYPE, BASE_TS, Long.MAX_VALUE)).containsExactly(m1);
        assertThat(dao.findByPeriod(SYMBOL, PeriodType.H1, BASE_TS, Long.MAX_VALUE)).containsExactly(h1);
    }

    @Test
    @DisplayName("from > to returns empty list")
    void fromGreaterThanToReturnsEmptyList() {
        dao.save(new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1));

        assertThat(dao.findByPeriod(SYMBOL, PERIOD_TYPE, T2, T1)).isEmpty();
    }

    @Test
    @DisplayName("from == to returns empty list")
    void fromEqualsToReturnsEmptyList() {
        assertThat(dao.findByPeriod(SYMBOL, PERIOD_TYPE, T1, T1)).isEmpty();
    }

    @Test
    @DisplayName("results are ordered by timestamp ascending")
    void resultIsOrderedByTimestamp() {
        TrendBar at1 = new TrendBar(SYMBOL, PERIOD_TYPE, 100, 110, 120, 90, T1);
        TrendBar at2 = new TrendBar(SYMBOL, PERIOD_TYPE, 110, 115, 130, 105, T2);
        TrendBar at3 = new TrendBar(SYMBOL, PERIOD_TYPE, 130, 140, 150, 120, T3);

        dao.save(at1);
        dao.save(at2);
        dao.save(at3);

        List<TrendBar> result = dao.findByPeriod(SYMBOL, PERIOD_TYPE, BASE_TS, Long.MAX_VALUE);

        assertThat(result).containsExactly(at1, at2, at3);
    }
}
