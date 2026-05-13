package com.spotware.trendbar.service.impl;

import com.spotware.trendbar.dao.TrendBarDao;
import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import com.spotware.trendbar.service.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultHistoryServiceImpl implements HistoryService {

    private final TrendBarDao trendBarDao;

    @Override
    public Collection<TrendBar> getForPeriod(Symbol symbol, PeriodType periodType, Long from, Long to) {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(periodType, "periodType");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        if (from >= to) {
            throw new IllegalArgumentException("'from' must be < 'to'");
        }

        Collection<TrendBar> result = trendBarDao.findByPeriod(symbol, periodType, from, to);

        log.debug("History query: symbol={} period={} from={} to={} -> {} bars",
                symbol, periodType, from, to, result.size());

        return result;
    }

    @Override
    public void save(TrendBar trendBar) {
        log.debug("Saving bar: {}", trendBar);

        trendBarDao.save(trendBar);
    }
}
