package com.spotware.trendbar.integration;

import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.service.QuoteProducer;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public class RandomWalkQuoteProducer implements QuoteProducer {

    private final Symbol[] symbols;
    private final long[] prices;
    private final long stepMs;
    private final Random random;
    private final AtomicLong currentTimestamp;
    private final AtomicLong produced = new AtomicLong();
    private final long totalQuotes;

    public RandomWalkQuoteProducer(long startTimestamp,
                                   long stepMs,
                                   long totalQuotes,
                                   long seed) {
        this.symbols = Symbol.values();
        this.prices = new long[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            this.prices[i] = 1_000_000L + i * 1_000L;
        }
        this.stepMs = stepMs;
        this.random = new Random(seed);
        this.currentTimestamp = new AtomicLong(startTimestamp);
        this.totalQuotes = totalQuotes;
    }

    @Override
    public Quote nextQuote() {
        long index = produced.getAndIncrement();

        if (index >= totalQuotes) {
            return null;
        }

        int symbolIdx = (int) (index % symbols.length);

        prices[symbolIdx] += random.nextInt(11) - 5;

        long ts = currentTimestamp.addAndGet(stepMs);

        return new Quote(prices[symbolIdx], symbols[symbolIdx], ts);
    }

    public long producedCount() {
        return Math.min(produced.get(), totalQuotes);
    }
}
