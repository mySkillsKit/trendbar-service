package com.spotware.trendbar.dao;

import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;

import java.util.List;

public interface TrendBarDao {

    void save(TrendBar trendBar);

    List<TrendBar> findByPeriod(Symbol symbol, PeriodType periodType, long from, long to);
}
