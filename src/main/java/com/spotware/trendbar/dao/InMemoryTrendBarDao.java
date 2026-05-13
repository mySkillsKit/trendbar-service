package com.spotware.trendbar.dao;

import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

@Component
public class InMemoryTrendBarDao implements TrendBarDao {

    private final Map<SymbolPeriodKey, ConcurrentSkipListMap<Long, TrendBar>> storage;

    public InMemoryTrendBarDao() {
        Map<SymbolPeriodKey, ConcurrentSkipListMap<Long, TrendBar>> map = new ConcurrentHashMap<>();

        for (Symbol symbol : Symbol.values()) {
            for (PeriodType period : PeriodType.values()) {
                map.put(new SymbolPeriodKey(symbol, period), new ConcurrentSkipListMap<>());
            }
        }

        this.storage = Map.copyOf(map);
    }

    @Override
    public void save(TrendBar trendBar) {
        Objects.requireNonNull(trendBar, "trendBar");

        ConcurrentSkipListMap<Long, TrendBar> bucketMap = bucket(trendBar.getSymbol(), trendBar.getPeriodType());

        bucketMap.put(trendBar.getTimestamp(), trendBar);
    }

    @Override
    public List<TrendBar> findByPeriod(Symbol symbol, PeriodType periodType, long from, long to) {
        if (from >= to) {
            return List.of();
        }

        ConcurrentSkipListMap<Long, TrendBar> bucketMap = bucket(symbol, periodType);

        ConcurrentNavigableMap<Long, TrendBar> longTrendBarMap = bucketMap.subMap(from, true, to, false);

        return new ArrayList<>(longTrendBarMap.values());
    }

    private ConcurrentSkipListMap<Long, TrendBar> bucket(Symbol symbol, PeriodType period) {
        return storage.get(new SymbolPeriodKey(symbol, period));
    }
}
