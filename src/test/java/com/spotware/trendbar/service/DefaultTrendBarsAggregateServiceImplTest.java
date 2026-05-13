package com.spotware.trendbar.service;

import com.spotware.trendbar.config.NamedThreadFactory;
import com.spotware.trendbar.constant.Constants;
import com.spotware.trendbar.dao.InMemoryTrendBarDao;
import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import com.spotware.trendbar.service.impl.DefaultHistoryServiceImpl;
import com.spotware.trendbar.service.impl.DefaultTrendBarsAggregateServiceImpl;
import com.spotware.trendbar.service.impl.SymbolWorkerSupervisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.awaitility.Awaitility.await;

@DisplayName("DefaultTrendBarsAggregateService")
class DefaultTrendBarsAggregateServiceImplTest {

    // 2024-01-01T00:00:00Z — day-aligned so D1 buckets land on the same boundary
    private static final long BASE_TS = 1_704_067_200_000L;
    private static final long MINUTE_MS = 60_000L;
    private static final Symbol SYMBOL = Symbol.EURUSD;

    private DefaultHistoryServiceImpl history;
    private Map<Symbol, BlockingQueue<Quote>> queues;
    private SymbolWorkerSupervisor supervisor;
    private DefaultTrendBarsAggregateServiceImpl aggregator;
    private AtomicLong consumed;

    @BeforeEach
    void setUp() {
        history = new DefaultHistoryServiceImpl(new InMemoryTrendBarDao());
        queues = newQueues();
        supervisor = new SymbolWorkerSupervisor(queues, history, newExecutors());
        aggregator = new DefaultTrendBarsAggregateServiceImpl(queues, supervisor);
        consumed = new AtomicLong();
        supervisor.start();
    }

    @AfterEach
    void tearDown() {
        if (supervisor.isRunning()) {
            supervisor.stop();
        }
    }

    @Test
    @DisplayName("builds TBs from quotes: maintains current bars, updates on each quote, persists when period expires")
    void buildsTrendBarsBasedOnReceivedQuotes() throws InterruptedException {
        consume(new Quote(100L, SYMBOL, BASE_TS));                 // open
        consume(new Quote(130L, SYMBOL, BASE_TS + 10_000L));       // new high
        consume(new Quote(85L,  SYMBOL, BASE_TS + 30_000L));       // new low
        consume(new Quote(110L, SYMBOL, BASE_TS + 50_000L));       // last close candidate

        waitForProcessed();

        assertThat(barsFor(SYMBOL, PeriodType.M1))
                .as("bar must not be persisted while the period is still in progress")
                .isEmpty();

        consume(new Quote(120L, SYMBOL, BASE_TS + MINUTE_MS));

        waitForProcessed();

        List<TrendBar> bars = barsFor(SYMBOL, PeriodType.M1);
        assertThat(bars).hasSize(1);

        TrendBar completed = bars.getFirst();
        assertThat(completed.getTimestamp()).isEqualTo(BASE_TS);
        assertThat(completed.getOpenPrice()).isEqualTo(100L);
        assertThat(completed.getHighPrice()).isEqualTo(130L);
        assertThat(completed.getLowPrice()).isEqualTo(85L);
        assertThat(completed.getClosePrice()).isEqualTo(110L);
    }

    @Test
    @DisplayName("single quote does not complete any bar")
    void singleQuoteDoesNotCompleteAnyBar() throws InterruptedException {
        consume(new Quote(100L, SYMBOL, BASE_TS + 10L));

        waitForProcessed();

        assertThat(barsFor(SYMBOL, PeriodType.M1)).isEmpty();
    }

    @Test
    @DisplayName("crossing a minute boundary flushes the previous minute bar")
    void crossingMinuteBoundaryFlushesPreviousMinuteBar() throws InterruptedException {
        consume(new Quote(100L, SYMBOL, BASE_TS));
        consume(new Quote(120L, SYMBOL, BASE_TS + 30L));
        consume(new Quote(90L,  SYMBOL, BASE_TS + 55L));
        consume(new Quote(110L, SYMBOL, BASE_TS + MINUTE_MS));

        waitForProcessed();

        List<TrendBar> m1 = barsFor(SYMBOL, PeriodType.M1);
        assertThat(m1).hasSize(1);
        TrendBar bar = m1.getFirst();
        assertThat(bar.getTimestamp()).isEqualTo(BASE_TS);
        assertThat(bar.getOpenPrice()).isEqualTo(100L);
        assertThat(bar.getClosePrice()).isEqualTo(90L);
        assertThat(bar.getHighPrice()).isEqualTo(120L);
        assertThat(bar.getLowPrice()).isEqualTo(90L);
    }

    @Test
    @DisplayName("gap between quotes produces one completed bar, not empty intermediate bars")
    void gapBetweenQuotesProducesSingleCompletedBarNotEmptyOnes() throws InterruptedException {
        consume(new Quote(100L, SYMBOL, BASE_TS));
        consume(new Quote(150L, SYMBOL, BASE_TS + 9L * MINUTE_MS));

        waitForProcessed();

        List<TrendBar> m1 = barsFor(SYMBOL, PeriodType.M1);
        assertThat(m1).hasSize(1);
        assertThat(m1.getFirst().getTimestamp()).isEqualTo(BASE_TS);
    }

    @Test
    @DisplayName("different symbols are aggregated independently")
    void differentSymbolsAreAggregatedIndependently() throws InterruptedException {
        consume(new Quote(100L, SYMBOL, BASE_TS));
        consume(new Quote(200L, Symbol.EURJPY, BASE_TS));
        consume(new Quote(101L, SYMBOL, BASE_TS + MINUTE_MS));
        consume(new Quote(201L, Symbol.EURJPY, BASE_TS + MINUTE_MS));

        waitForProcessed();

        List<TrendBar> eur = barsFor(SYMBOL, PeriodType.M1);
        List<TrendBar> jpy = barsFor(Symbol.EURJPY, PeriodType.M1);

        assertThat(eur).hasSize(1);
        assertThat(jpy).hasSize(1);
        assertThat(eur.getFirst().getOpenPrice()).isEqualTo(100L);
        assertThat(jpy.getFirst().getOpenPrice()).isEqualTo(200L);
    }

    @Test
    @DisplayName("all three period types (M1, H1, D1) are updated for each quote")
    void allThreePeriodsAreUpdatedForEachQuote() throws InterruptedException {
        consume(new Quote(100L, SYMBOL, BASE_TS));
        consume(new Quote(110L, SYMBOL, BASE_TS + TimeUnit.MINUTES.toMillis(1)));
        consume(new Quote(120L, SYMBOL, BASE_TS + TimeUnit.HOURS.toMillis(1)));
        consume(new Quote(130L, SYMBOL, BASE_TS + TimeUnit.DAYS.toMillis(1)));

        waitForProcessed();

        assertThat(barsFor(SYMBOL, PeriodType.M1)).hasSize(3);
        assertThat(barsFor(SYMBOL, PeriodType.H1)).hasSize(2);
        assertThat(barsFor(SYMBOL, PeriodType.D1)).hasSize(1);
    }

    @Test
    @DisplayName("stop() does not flush an incomplete in-progress bar")
    void shutdownDoesNotFlushIncompleteBar() throws InterruptedException {
        consume(new Quote(100L, SYMBOL, BASE_TS));
        consume(new Quote(120L, SYMBOL, BASE_TS + 30_000L));

        waitForProcessed();
        supervisor.stop();

        assertThat(barsFor(SYMBOL, PeriodType.M1)).isEmpty();
    }

    @Test
    @DisplayName("chain of sequential minute quotes produces all completed bars")
    void chainOfSequentialMinutesProducesAllCompletedBars() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            long ts = BASE_TS + i * MINUTE_MS + 1_000L;
            consume(new Quote(100L + i, SYMBOL, ts));
        }
        consume(new Quote(999L, SYMBOL, BASE_TS + 5L * MINUTE_MS));

        waitForProcessed();

        List<TrendBar> m1 = barsFor(SYMBOL, PeriodType.M1);
        assertThat(m1).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(m1.get(i).getTimestamp()).isEqualTo(BASE_TS + i * MINUTE_MS);
        }
    }

    @Test
    @DisplayName("quote arriving exactly on a period boundary starts a new bar")
    void quoteAtExactPeriodBoundaryStartsNewBar() throws InterruptedException {
        consume(new Quote(100L, SYMBOL, BASE_TS));
        consume(new Quote(200L, SYMBOL, BASE_TS + MINUTE_MS));
        consume(new Quote(300L, SYMBOL, BASE_TS + 2L * MINUTE_MS));

        waitForProcessed();

        List<TrendBar> m1 = barsFor(SYMBOL, PeriodType.M1);
        assertThat(m1).hasSize(2);
        assertThat(m1.get(0).getTimestamp()).isEqualTo(BASE_TS);
        assertThat(m1.get(0).getClosePrice()).isEqualTo(100L);
        assertThat(m1.get(1).getTimestamp()).isEqualTo(BASE_TS + MINUTE_MS);
        assertThat(m1.get(1).getClosePrice()).isEqualTo(200L);
    }

    @Test
    @DisplayName("consume() rejects null quote")
    void consumeRejectsNullQuote() {
        assertThatNullPointerException()
                .isThrownBy(() -> aggregator.consume(null))
                .withMessage("quote");
    }

    @Test
    @DisplayName("consume() rejects quote with null symbol")
    void consumeRejectsQuoteWithNullSymbol() {
        assertThatNullPointerException()
                .isThrownBy(() -> aggregator.consume(new Quote(100L, null, BASE_TS)))
                .withMessage("quote.symbol");
    }

    @Test
    @DisplayName("consume() before start() throws IllegalStateException")
    void consumeBeforeStartThrowsIllegalStateException() {
        Map<Symbol, BlockingQueue<Quote>> isolatedQueues = newQueues();
        SymbolWorkerSupervisor unstartedSupervisor = new SymbolWorkerSupervisor(isolatedQueues,
                new DefaultHistoryServiceImpl(new InMemoryTrendBarDao()), newExecutors());
        DefaultTrendBarsAggregateServiceImpl unstarted =
                new DefaultTrendBarsAggregateServiceImpl(isolatedQueues, unstartedSupervisor);

        assertThatIllegalStateException()
                .isThrownBy(() -> unstarted.consume(new Quote(100L, SYMBOL, BASE_TS)))
                .withMessage("Aggregator is not running");
    }

    @Test
    @DisplayName("consume() after stop() throws IllegalStateException")
    void consumeAfterStopThrowsIllegalStateException() {
        supervisor.stop();

        assertThatIllegalStateException()
                .isThrownBy(() -> aggregator.consume(new Quote(100L, SYMBOL, BASE_TS)))
                .withMessage("Aggregator is not running");
    }

    @Test
    @DisplayName("stop() forces shutdown if worker does not exit within SHUTDOWN_TIMEOUT_MS")
    void stopForcesShutdownWhenWorkerHangsInSave() throws Exception {
        // Block the worker for SYMBOL inside HistoryService.save so it never picks up POISON.
        // After SHUTDOWN_TIMEOUT_MS the supervisor must shutdownNow() the executor, which
        // interrupts the worker thread sleeping inside save().
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        HistoryService blocking = new HistoryService() {
            @Override
            public Collection<TrendBar> getForPeriod(Symbol symbol, PeriodType periodType, Long from, Long to) {
                return List.of();
            }

            @Override
            public void save(TrendBar bar) {
                saveStarted.countDown();
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted", e);
                }
            }
        };

        Map<Symbol, BlockingQueue<Quote>> stuckQueues = newQueues();
        SymbolWorkerSupervisor stuckSupervisor = new SymbolWorkerSupervisor(stuckQueues, blocking, newExecutors());
        DefaultTrendBarsAggregateServiceImpl stuck =
                new DefaultTrendBarsAggregateServiceImpl(stuckQueues, stuckSupervisor);
        stuckSupervisor.start();
        try {
            stuck.consume(new Quote(100L, SYMBOL, BASE_TS));
            stuck.consume(new Quote(110L, SYMBOL, BASE_TS + MINUTE_MS));

            assertThat(saveStarted.await(2, TimeUnit.SECONDS))
                    .as("worker should have entered save() before we call stop()")
                    .isTrue();
        } finally {
            stuckSupervisor.stop();
        }

        assertThat(interrupted.await(1, TimeUnit.SECONDS))
                .as("worker should observe the interrupt issued by shutdownNow()")
                .isTrue();
        assertThat(stuckSupervisor.isRunning())
                .as("supervisor must report stopped after stop()")
                .isFalse();
    }

    @Test
    @DisplayName("worker survives RuntimeException thrown by HistoryService")
    void workerSurvivesRuntimeExceptionFromHistoryService() throws InterruptedException {
        FailingHistoryService failing = new FailingHistoryService();
        Map<Symbol, BlockingQueue<Quote>> isolatedQueues = newQueues();
        SymbolWorkerSupervisor isolatedSupervisor = new SymbolWorkerSupervisor(isolatedQueues, failing, newExecutors());
        DefaultTrendBarsAggregateServiceImpl isolated =
                new DefaultTrendBarsAggregateServiceImpl(isolatedQueues, isolatedSupervisor);
        isolatedSupervisor.start();
        try {
            isolated.consume(new Quote(100L, SYMBOL, BASE_TS));
            isolated.consume(new Quote(110L, SYMBOL, BASE_TS + MINUTE_MS));
            isolated.consume(new Quote(120L, SYMBOL, BASE_TS + 2L * MINUTE_MS));

            await().atMost(Duration.ofSeconds(2))
                    .until(() -> isolatedSupervisor.totalProcessed() >= 3);

            assertThat(failing.attempts).isGreaterThanOrEqualTo(1);
            assertThat(failing.successes).isGreaterThanOrEqualTo(1);
        } finally {
            isolatedSupervisor.stop();
        }
    }

    private void consume(Quote quote) throws InterruptedException {
        aggregator.consume(quote);
        consumed.incrementAndGet();
    }

    private void waitForProcessed() {
        await().atMost(Duration.ofSeconds(2))
                .pollDelay(Duration.ofMillis(20))
                .until(() -> supervisor.totalProcessed() >= consumed.get());
    }

    private List<TrendBar> barsFor(Symbol symbol, PeriodType period) {
        return new ArrayList<>(history.getForPeriod(symbol, period, 0L, Long.MAX_VALUE));
    }

    private static Map<Symbol, BlockingQueue<Quote>> newQueues() {
        EnumMap<Symbol, BlockingQueue<Quote>> map = new EnumMap<>(Symbol.class);
        for (Symbol s : Symbol.values()) {
            map.put(s, new ArrayBlockingQueue<>(Constants.QUEUE_CAPACITY));
        }
        return map;
    }

    private static Map<Symbol, ExecutorService> newExecutors() {
        EnumMap<Symbol, ExecutorService> map = new EnumMap<>(Symbol.class);
        for (Symbol s : Symbol.values()) {
            map.put(s, Executors.newSingleThreadExecutor(
                new NamedThreadFactory("QuoteProcessor-" + s)));
        }
        return map;
    }

    private static final class FailingHistoryService implements HistoryService {
        volatile int attempts;
        volatile int successes;

        @Override
        public Collection<TrendBar> getForPeriod(Symbol symbol, PeriodType periodType, Long from, Long to) {
            return List.of();
        }

        @Override
        public synchronized void save(TrendBar bar) {
            attempts++;
            if (attempts == 1) {
                throw new IllegalStateException("simulated failure on first save");
            }
            successes++;
        }
    }
}
