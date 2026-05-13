package com.spotware.trendbar.service;

import com.spotware.trendbar.dao.TrendBarDao;
import com.spotware.trendbar.model.PeriodType;
import com.spotware.trendbar.model.Symbol;
import com.spotware.trendbar.model.TrendBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
public class DefaultHistoryService implements HistoryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultHistoryService.class);

    private final TrendBarDao trendBarDao;

    public DefaultHistoryService(TrendBarDao trendBarDao) {
        this.trendBarDao = trendBarDao;
    }

    @Override
    public Collection<TrendBar> getForPeriod(Symbol symbol, PeriodType periodType, Long from, Long to) {
        return null;
    }

    @Override
    public void save(TrendBar trendBar) {
        log.debug("Saving bar: {}", trendBar);

        trendBarDao.save(trendBar);
    }
}
