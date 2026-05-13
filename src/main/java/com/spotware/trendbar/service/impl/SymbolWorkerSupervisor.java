package com.spotware.trendbar.service.impl;

import com.spotware.trendbar.constant.Constants;
import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.service.HistoryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SymbolWorkerSupervisor {

    @Getter
    private volatile boolean running;

    private final Map<Symbol, BlockingQueue<Quote>> symbolQueues;
    private final Map<Symbol, SymbolWorker> workers;
    private final Map<Symbol, ExecutorService> executors;

    public SymbolWorkerSupervisor(Map<Symbol, BlockingQueue<Quote>> symbolQueues,
                                  HistoryService historyService,
                                  Map<Symbol, ExecutorService> executors) {
        this.symbolQueues = symbolQueues;
        this.workers = new EnumMap<>(Symbol.class);
        this.executors = executors;

        for (Symbol s : Symbol.values()) {
            BlockingQueue<Quote> q = symbolQueues.get(s);
            workers.put(s, new SymbolWorker(s, q, historyService));
        }
    }

    public long totalProcessed() {
        return workers.values().stream()
                .mapToLong(SymbolWorker::processedCount)
                .sum();
    }

    public long queueSize(Symbol symbol) {
        BlockingQueue<Quote> q = symbolQueues.get(symbol);
        return q == null ? 0L : q.size();
    }

    @PostConstruct
    public void start() {
        running = true;
        workers.forEach((symbol, worker) -> executors.get(symbol).submit(worker));
        log.info("SymbolWorkerSupervisor started with {} symbol workers", workers.size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        sendPoisonToAllQueues();

        executors.values().forEach(ExecutorService::shutdown);
        executors.forEach((symbol, executor) ->
                awaitTermination(executor, "QuoteProcessor-" + symbol));

        log.info("SymbolWorkerSupervisor stopped, total processed: {}", totalProcessed());
    }

    private void sendPoisonToAllQueues() {
        for (Map.Entry<Symbol, BlockingQueue<Quote>> entry : symbolQueues.entrySet()) {
            try {
                entry.getValue().put(Constants.POISON);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while sending POISON to {}", entry.getKey());
                return;
            }
        }
    }

    private void awaitTermination(ExecutorService executor, String name) {
        try {
            if (!executor.awaitTermination(Constants.SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("{} did not terminate within {} ms, forcing shutdown", name, Constants.SHUTDOWN_TIMEOUT_MS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for {} termination", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
