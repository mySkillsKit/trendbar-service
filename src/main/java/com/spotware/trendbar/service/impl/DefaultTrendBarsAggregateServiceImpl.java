package com.spotware.trendbar.service.impl;

import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.service.TrendBarsAggregateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTrendBarsAggregateServiceImpl implements TrendBarsAggregateService {

    private final Map<Symbol, BlockingQueue<Quote>> symbolQueues;
    private final SymbolWorkerSupervisor supervisor;

    @Override
    public void consume(Quote quote) throws InterruptedException {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(quote.symbol(), "quote.symbol");

        if (!supervisor.isRunning()) {
            throw new IllegalStateException("Aggregator is not running");
        }

        BlockingQueue<Quote> queue = symbolQueues.get(quote.symbol());
        if (queue == null) {
            throw new IllegalArgumentException("No queue registered for symbol: " + quote.symbol());
        }

        log.trace("Routing quote {} to {} queue", quote, quote.symbol());
        queue.put(quote);
    }
}
