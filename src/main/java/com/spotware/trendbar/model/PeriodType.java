package com.spotware.trendbar.model;

import java.util.concurrent.TimeUnit;

public enum PeriodType {

    M1(TimeUnit.MINUTES.toMillis(1)),
    H1(TimeUnit.HOURS.toMillis(1)),
    D1(TimeUnit.DAYS.toMillis(1));

    private final long durationMs;

    PeriodType(long durationMs) {
        this.durationMs = durationMs;
    }

    public long durationMs() {
        return durationMs;
    }

    public long floor(long timestamp) {
        return timestamp - Math.floorMod(timestamp, durationMs);
    }
}
