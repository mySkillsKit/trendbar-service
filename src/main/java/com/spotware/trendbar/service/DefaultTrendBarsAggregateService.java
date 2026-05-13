package com.spotware.trendbar.service;

import com.spotware.trendbar.model.Quote;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DefaultTrendBarsAggregateService implements TrendBarsAggregateService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTrendBarsAggregateService.class);

    private final HistoryService historyService;

    public DefaultTrendBarsAggregateService(HistoryService historyService) {
        this.historyService = historyService;
    }

    @Override
    public void consume(Quote quote) throws InterruptedException {
        log.trace("Received quote: {}", quote);
    }

    @PostConstruct
    public void start() {
        log.info("Trend bar aggregator started");
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        log.info("Trend bar aggregator stopped");
    }

    public int queueSize() {
        return 0;
    }
}
