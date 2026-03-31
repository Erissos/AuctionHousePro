package com.auctionhousepro.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MarketTelemetryService {
    private final AtomicLong searches = new AtomicLong();
    private final AtomicLong bids = new AtomicLong();
    private final AtomicLong sales = new AtomicLong();
    private final AtomicLong claims = new AtomicLong();
    private final AtomicLong watched = new AtomicLong();
    private final Map<String, AtomicLong> timings = new ConcurrentHashMap<>();

    public void markSearch(long millis) {
        searches.incrementAndGet();
        recordTiming("search-ms", millis);
    }

    public void markBid(long millis) {
        bids.incrementAndGet();
        recordTiming("bid-ms", millis);
    }

    public void markSale(long millis) {
        sales.incrementAndGet();
        recordTiming("sale-ms", millis);
    }

    public void markClaim(long millis) {
        claims.incrementAndGet();
        recordTiming("claim-ms", millis);
    }

    public void markWatch() {
        watched.incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("searches", searches.get());
        snapshot.put("bids", bids.get());
        snapshot.put("sales", sales.get());
        snapshot.put("claims", claims.get());
        snapshot.put("watch-actions", watched.get());
        timings.forEach((key, value) -> snapshot.put(key, value.get()));
        return snapshot;
    }

    private void recordTiming(String key, long millis) {
        timings.computeIfAbsent(key, unused -> new AtomicLong()).addAndGet(millis);
    }
}