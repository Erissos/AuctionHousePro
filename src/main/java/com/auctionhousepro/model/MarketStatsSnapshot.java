package com.auctionhousepro.model;

public record MarketStatsSnapshot(
        long activeAuctions,
        long soldAuctions,
        long totalWatchlistEntries,
        long pendingOffers,
        double grossVolume,
        double averageSale,
        long totalBids,
        long deliveriesWaiting
) {
}