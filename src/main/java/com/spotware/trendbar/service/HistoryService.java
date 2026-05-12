package com.spotware.trendbar.service;

import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;

import java.util.Collection;

public interface HistoryService {

    default Collection<TrendBar> getForPeriod(Symbol symbol, PeriodType periodType, Long from) {
        return getForPeriod(symbol, periodType, from, System.currentTimeMillis());
    }

    Collection<TrendBar> getForPeriod(Symbol symbol, PeriodType periodType, Long from, Long to);

    void save(TrendBar trendBar);
}
