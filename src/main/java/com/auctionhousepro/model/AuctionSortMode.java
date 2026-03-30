package com.auctionhousepro.model;

import java.util.Comparator;

public enum AuctionSortMode {
    NEWEST(Comparator.comparing(Auction::createdAt).reversed()),
    PRICE_ASC(Comparator.comparingDouble(Auction::displayPrice)),
    PRICE_DESC(Comparator.comparingDouble(Auction::displayPrice).reversed()),
    ENDING_SOON(Comparator.comparing(Auction::expiresAt)),
    RARITY(Comparator.comparingInt((Auction auction) -> auction.item().getType().getMaxStackSize()).reversed());

    private final Comparator<Auction> comparator;

    AuctionSortMode(Comparator<Auction> comparator) {
        this.comparator = comparator;
    }

    public Comparator<Auction> comparator() {
        return comparator;
    }
}
