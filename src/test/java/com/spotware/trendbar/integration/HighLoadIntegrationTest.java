package com.spotware.trendbar.integration;

import com.spotware.trendbar.dao.InMemoryTrendBarDao;
import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import com.spotware.trendbar.service.DefaultHistoryService;
import com.spotware.trendbar.service.DefaultTrendBarsAggregateService;
import com.spotware.trendbar.service.HistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HighLoadIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HighLoadIntegrationTest.class);
    // 2024-01-01T00:00:00Z — day-aligned start so D1 floor lines up
    private static final long START_TS = 1_704_067_200_000L;
    private static final long STEP_MS = 4L;
    private static final long QUOTES_TO_PRODUCE = 1_000_000L;
    private static final int READER_THREADS = 4;

    private DefaultTrendBarsAggregateService aggregator;
    private HistoryService history;

    @BeforeEach
    void setUp() {
        InMemoryTrendBarDao dao = new InMemoryTrendBarDao();
        history = new DefaultHistoryService(dao);
        aggregator = new DefaultTrendBarsAggregateService(history);
        aggregator.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        aggregator.stop();
    }

    @Test
    void aggregatesMillionQuotesAndServesConcurrentHistoryReaders() throws Exception {
        RandomWalkQuoteProducer producer = new RandomWalkQuoteProducer(START_TS, STEP_MS, QUOTES_TO_PRODUCE, 42L);

        AtomicBoolean producerDone = new AtomicBoolean(false);
        AtomicLong readerErrors = new AtomicLong();
        AtomicLong readerCalls = new AtomicLong();

        ExecutorService readers = Executors.newFixedThreadPool(READER_THREADS);
        CountDownLatch readersReady = new CountDownLatch(READER_THREADS);

        for (int i = 0; i < READER_THREADS; i++) {
            final Symbol symbol = Symbol.values()[i % Symbol.values().length];
            readers.submit(() -> {
                readersReady.countDown();
                int previousSize = 0;
                while (!producerDone.get() || readerCalls.get() < 1) {
                    try {
                        Collection<TrendBar> snapshot =
                                history.getForPeriod(symbol, PeriodType.M1, START_TS, Long.MAX_VALUE);
                        readerCalls.incrementAndGet();
                        List<TrendBar> ordered = new ArrayList<>(snapshot);
                        long prevTs = Long.MIN_VALUE;
                        for (TrendBar bar : ordered) {
                            if (bar.getTimestamp() <= prevTs) {
                                readerErrors.incrementAndGet();
                            }
                            prevTs = bar.getTimestamp();
                        }
                        if (ordered.size() < previousSize) {
                            readerErrors.incrementAndGet();
                        }
                        previousSize = ordered.size();
                    } catch (Exception ex) {
                        log.error("Reader error", ex);
                        readerErrors.incrementAndGet();
                    }
                }
            });
        }
        readersReady.await();

        long startNanos = System.nanoTime();
        Quote q;
        while ((q = producer.nextQuote()) != null) {
            aggregator.consume(q);
        }
        long produced = producer.producedCount();
        producerDone.set(true);

        await().atMost(Duration.ofSeconds(30))
                .pollDelay(Duration.ofMillis(50))
                .until(() -> aggregator.queueSize() == 0);

        readers.shutdown();
        readers.awaitTermination(5, TimeUnit.SECONDS);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        log.info("Produced {} quotes in {} ms ({} quotes/sec)",
                produced, elapsedMs, produced * 1000L / Math.max(elapsedMs, 1));
        log.info("Reader calls: {}, errors: {}", readerCalls.get(), readerErrors.get());

        assertThat(readerErrors).hasValue(0);
        assertThat(readerCalls.get()).isGreaterThan(0);

        long virtualSpanMs = produced * STEP_MS;
        long expectedM1Closed = virtualSpanMs / PeriodType.M1.durationMs();

        for (Symbol symbol : Symbol.values()) {
            Collection<TrendBar> m1 = history.getForPeriod(symbol, PeriodType.M1, START_TS, Long.MAX_VALUE);
            assertThat((long) m1.size())
                    .as("M1 bars for %s", symbol)
                    .isBetween(expectedM1Closed - 1, expectedM1Closed);
        }
    }
}
