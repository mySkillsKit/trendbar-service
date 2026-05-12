package com.spotware.trendbar.model;

import java.util.Objects;

public class TrendBar {

    private final Symbol symbol;
    private final PeriodType periodType;
    private final long openPrice;
    private final long closePrice;
    private final long highPrice;
    private final long lowPrice;
    private final long timestamp;

    public TrendBar(Symbol symbol,
                    PeriodType periodType,
                    long openPrice,
                    long closePrice,
                    long highPrice,
                    long lowPrice,
                    long timestamp) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.periodType = Objects.requireNonNull(periodType, "periodType");
        this.openPrice = openPrice;
        this.closePrice = closePrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.timestamp = timestamp;
    }

    public Symbol getSymbol() {
        return symbol;
    }

    public PeriodType getPeriodType() {
        return periodType;
    }

    public long getOpenPrice() {
        return openPrice;
    }

    public long getClosePrice() {
        return closePrice;
    }

    public long getHighPrice() {
        return highPrice;
    }

    public long getLowPrice() {
        return lowPrice;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TrendBar that = (TrendBar) o;
        return timestamp == that.timestamp
               && symbol == that.symbol
               && periodType == that.periodType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, periodType, timestamp);
    }

    @Override
    public String toString() {
        return "TrendBar["
               + "symbol=" + symbol
               + ", periodType=" + periodType
               + ", openPrice=" + openPrice
               + ", closePrice=" + closePrice
               + ", highPrice=" + highPrice
               + ", lowPrice=" + lowPrice
               + ", timestamp=" + timestamp
               + ']';
    }

    public static class TrendBarBuilder {

        private Symbol symbol;
        private final PeriodType periodType;
        private long startTimestamp;
        private long openPrice;
        private long closePrice;
        private long highPrice;
        private long lowPrice;

        public TrendBarBuilder(PeriodType periodType) {
            this.periodType = Objects.requireNonNull(periodType, "periodType");
        }

        public TrendBarBuilder symbol(Symbol symbol) {
            this.symbol = Objects.requireNonNull(symbol, "symbol");
            return this;
        }

        public TrendBarBuilder startTimestamp(long timestamp) {
            this.startTimestamp = this.periodType.floor(timestamp);
            return this;
        }

        public TrendBarBuilder initPrice(long price) {
            this.openPrice = price;
            this.closePrice = price;
            this.highPrice = price;
            this.lowPrice = price;
            return this;
        }

        public TrendBarBuilder closePrice(long closePrice) {
            this.closePrice = closePrice;
            return this;
        }

        public TrendBarBuilder highPrice(long highPrice) {
            this.highPrice = highPrice;
            return this;
        }


        public TrendBarBuilder lowPrice(long lowPrice) {
            this.lowPrice = lowPrice;
            return this;
        }

        public void apply(long price) {
            this.highPrice = Math.max(this.highPrice, price);
            this.lowPrice = Math.min(this.lowPrice, price);
            this.closePrice = price;
        }

        public boolean isCompletedAt(long timestamp) {
            return timestamp >= this.startTimestamp + this.periodType.durationMs();
        }

        public TrendBar build() {
            return new TrendBar(
                    this.symbol,
                    this.periodType,
                    this.openPrice,
                    this.closePrice,
                    this.highPrice,
                    this.lowPrice,
                    this.startTimestamp
            );
        }
    }
}
