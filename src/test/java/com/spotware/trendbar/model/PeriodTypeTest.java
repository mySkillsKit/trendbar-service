package com.spotware.trendbar.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PeriodType")
class PeriodTypeTest {

    @DisplayName("floor aligns timestamp to period start")
    @ParameterizedTest(name = "{0}.floor({1}) = {2}")
    @CsvSource({
            "M1, 0,           0",
            "M1, 60000,       60000",
            "M1, 91999,       60000",
            "M1, 119999,      60000",
            "M1, 120000,      120000",
            "H1, 0,           0",
            "H1, 3600000,     3600000",
            "H1, 5823000,     3600000",
            "H1, 7199999,     3600000",
            "D1, 0,           0",
            "D1, 86400000,    86400000",
            "D1, 134000000,   86400000",
            "D1, 172799999,   86400000"
    })
    void floorAlignsToPeriodStart(PeriodType period, long input, long expected) {
        assertThat(period.floor(input)).isEqualTo(expected);
    }

    @DisplayName("durationMs matches period length")
    @ParameterizedTest(name = "{0}.durationMs() = {1}")
    @CsvSource({
            "M1, 60000",
            "H1, 3600000",
            "D1, 86400000"
    })
    void durationMatchesPeriod(PeriodType period, long expectedMs) {
        assertThat(period.durationMs()).isEqualTo(expectedMs);
    }

    @DisplayName("floor does not overflow for large timestamps")
    @ParameterizedTest(name = "{0}.floor({1}) stays within period")
    @CsvSource({
            "M1, 1778608994000",
            "H1, 1778608994000",
            "D1, 1778608994000"
    })
    void floorOfLongMaxValueDoesNotOverflow(PeriodType period, long ts) {
        long floored = period.floor(ts);
        assertThat(floored).isLessThanOrEqualTo(ts);
        assertThat(ts - floored).isLessThan(period.durationMs());
    }
}
