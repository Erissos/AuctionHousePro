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
        boolean activeOnly,
        boolean buyNowOnly,
        boolean favoritesOnly,
        boolean featuredOnly,
        UUID watcherId
) {
    public static AuctionFilter defaultFilter() {
        return new AuctionFilter("", AuctionCategory.ALL, AuctionSortMode.NEWEST, 0.0D, Double.MAX_VALUE, null, false, true, false, false, false, null);
    }

    public AuctionFilter withPageDefaults() {
        return new AuctionFilter(query == null ? "" : query, category == null ? AuctionCategory.ALL : category, sortMode == null ? AuctionSortMode.NEWEST : sortMode, minPrice, maxPrice <= 0.0D ? Double.MAX_VALUE : maxPrice, sellerId, claimsOnly, activeOnly, buyNowOnly, favoritesOnly, featuredOnly, watcherId);
    }

    public AuctionFilter withQuery(String value) {
        return new AuctionFilter(value, category, sortMode, minPrice, maxPrice, sellerId, claimsOnly, activeOnly, buyNowOnly, favoritesOnly, featuredOnly, watcherId);
    }

    public AuctionFilter withCategory(AuctionCategory value) {
        return new AuctionFilter(query, value, sortMode, minPrice, maxPrice, sellerId, claimsOnly, activeOnly, buyNowOnly, favoritesOnly, featuredOnly, watcherId);
    }

    public AuctionFilter withSort(AuctionSortMode value) {
        return new AuctionFilter(query, category, value, minPrice, maxPrice, sellerId, claimsOnly, activeOnly, buyNowOnly, favoritesOnly, featuredOnly, watcherId);
    }

    public AuctionFilter toggleBuyNowOnly() {
        return new AuctionFilter(query, category, sortMode, minPrice, maxPrice, sellerId, claimsOnly, activeOnly, !buyNowOnly, favoritesOnly, featuredOnly, watcherId);
    }

    public AuctionFilter toggleFavorites(UUID playerId) {
        return new AuctionFilter(query, category, sortMode, minPrice, maxPrice, sellerId, claimsOnly, activeOnly, buyNowOnly, !favoritesOnly, featuredOnly, playerId);
    }

    public AuctionFilter toggleFeatured() {
        return new AuctionFilter(query, category, sortMode, minPrice, maxPrice, sellerId, claimsOnly, activeOnly, buyNowOnly, favoritesOnly, !featuredOnly, watcherId);
    }
}
