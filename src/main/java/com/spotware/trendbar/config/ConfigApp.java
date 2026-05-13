package com.spotware.trendbar.config;

import com.spotware.trendbar.constant.Constants;
import com.spotware.trendbar.model.Quote;
import com.spotware.trendbar.model.Symbol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ConfigApp {

    @Bean
    public Map<Symbol, BlockingQueue<Quote>> symbolQueues() {
        EnumMap<Symbol, BlockingQueue<Quote>> queues = new EnumMap<>(Symbol.class);
        for (Symbol s : Symbol.values()) {
            queues.put(s, new ArrayBlockingQueue<>(Constants.QUEUE_CAPACITY));
        }
        return queues;
    }

    @Bean
    public Map<Symbol, ExecutorService> symbolExecutors() {
        EnumMap<Symbol, ExecutorService> executors = new EnumMap<>(Symbol.class);
        for (Symbol s : Symbol.values()) {
            executors.put(s, Executors.newSingleThreadExecutor(new NamedThreadFactory("QuoteProcessor-" + s)));
        }
        return executors;
    }
}
