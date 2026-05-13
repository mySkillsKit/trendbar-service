package com.spotware.trendbar.service.impl;

import com.spotware.trendbar.constant.Constants;
import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import com.spotware.trendbar.service.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class SymbolWorker implements Runnable {

    private final Symbol symbol;
    private final BlockingQueue<Quote> queue;
    private final HistoryService historyService;
    private final EnumMap<PeriodType, TrendBar.TrendBarBuilder> bars = new EnumMap<>(PeriodType.class);
    private final AtomicLong processedCount = new AtomicLong();

    @Override
    public void run() {
        log.info("Worker for {} started", symbol);
        try {
            while (true) {
                Quote quote = queue.take();
                if (quote == Constants.POISON) {
                    log.info("Worker for {} received POISON, exiting", symbol);
                    return;
                }
                processQuote(quote);
                processedCount.incrementAndGet();
            }
        } catch (InterruptedException e) {
            log.warn("Worker for {} interrupted", symbol);
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Worker for {} failed unexpectedly", symbol, e);
        }
    }

    private void processQuote(Quote quote) {
        for (PeriodType period : PeriodType.values()) {
            try {
                processPeriod(quote, period);
            } catch (RuntimeException e) {
                log.error("Failed to process quote {} for period {}", quote, period, e);
            }
        }
    }

    private void processPeriod(Quote quote, PeriodType period) {
        TrendBar.TrendBarBuilder builder = bars.get(period);

        if (builder == null) {
            log.debug("Starting new {} bar for {} at ts={}", period, quote.symbol(), quote.timestamp());
            bars.put(period, createBuilder(quote, period));
            return;
        }

        if (builder.isCompletedAt(quote.timestamp())) {
            TrendBar completed = builder.build();
            log.debug("Completed {} bar: {}", period, completed);
            historyService.save(completed);
            bars.put(period, createBuilder(quote, period));
            return;
        }

        builder.apply(quote.price());
    }

    private TrendBar.TrendBarBuilder createBuilder(Quote quote, PeriodType period) {
        return new TrendBar.TrendBarBuilder(period)
                .symbol(quote.symbol())
                .initPrice(quote.price())
                .startTimestamp(quote.timestamp());
    }

    public long processedCount() {
        return processedCount.get();
    }
}
