package com.auctionhousepro.model;

import java.util.Comparator;

public enum AuctionSortMode {
    NEWEST(Comparator.comparing(Auction::createdAt).reversed()),
    PRICE_ASC(Comparator.comparingDouble(Auction::displayPrice)),
    PRICE_DESC(Comparator.comparingDouble(Auction::displayPrice).reversed()),
    ENDING_SOON(Comparator.comparing(Auction::expiresAt)),
    RARITY(Comparator.comparingInt((Auction auction) -> auction.item().getType().getMaxStackSize()).reversed()),
    MOST_WATCHED(Comparator.comparingInt(Auction::watchCount).reversed().thenComparing(Auction::createdAt).reversed()),
    HOTTEST(Comparator.comparingInt(Auction::bidCount).reversed().thenComparingInt(Auction::viewCount).reversed()),
    FEATURED(Comparator.comparingDouble(Auction::featuredScore).reversed());

    private final Comparator<Auction> comparator;

    AuctionSortMode(Comparator<Auction> comparator) {
        this.comparator = comparator;
    }

    public Comparator<Auction> comparator() {
        return comparator;
    }
}
