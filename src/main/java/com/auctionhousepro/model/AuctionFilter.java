package com.auctionhousepro.model;

import java.util.UUID;

public record AuctionFilter(
        String query,
        AuctionCategory category,
        AuctionSortMode sortMode,
        double minPrice,
        double maxPrice,
        UUID sellerId,
        boolean claimsOnly,
        boolean activeOnly
) {
    public static AuctionFilter defaultFilter() {
        return new AuctionFilter("", AuctionCategory.ALL, AuctionSortMode.NEWEST, 0.0D, Double.MAX_VALUE, null, false, true);
    }

    public AuctionFilter withPageDefaults() {
        return new AuctionFilter(query == null ? "" : query, category == null ? AuctionCategory.ALL : category, sortMode == null ? AuctionSortMode.NEWEST : sortMode, minPrice, maxPrice <= 0.0D ? Double.MAX_VALUE : maxPrice, sellerId, claimsOnly, activeOnly);
    }
}
