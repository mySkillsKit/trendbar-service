package com.spotware.trendbar.dao;

import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Symbol;

public record SymbolPeriodKey(Symbol symbol, PeriodType periodType) {
}
