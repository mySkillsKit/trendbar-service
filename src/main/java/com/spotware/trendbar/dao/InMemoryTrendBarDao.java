package com.spotware.trendbar.dao;

import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InMemoryTrendBarDao implements TrendBarDao {

    public InMemoryTrendBarDao() {
    }

    @Override
    public void save(TrendBar trendBar) {
    }

    @Override
    public List<TrendBar> findByPeriod(Symbol symbol, PeriodType periodType, long from, long to) {
        return List.of();
    }
}
