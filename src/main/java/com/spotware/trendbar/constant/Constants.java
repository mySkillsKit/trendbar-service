package com.spotware.trendbar.constant;

import com.spotware.trendbar.model.Quote;

public class Constants {
    public static final long SHUTDOWN_TIMEOUT_MS = 5_000L;
    public static final Quote POISON = new Quote(0L, null, Long.MIN_VALUE);
    public static final int QUEUE_CAPACITY = 100_000;
}
