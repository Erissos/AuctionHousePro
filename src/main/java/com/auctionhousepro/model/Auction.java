package com.auctionhousepro.model;

import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Auction(
        long id,
        UUID sellerId,
        UUID highestBidderId,
        ItemStack item,
        AuctionType type,
        AuctionStatus status,
        AuctionCategory category,
        double startingPrice,
        double currentBid,
        double buyNowPrice,
        double bidIncrement,
        Instant createdAt,
        Instant expiresAt,
        boolean sellerClaimed,
        boolean buyerClaimed,
        String searchableText
) {
    public Auction withId(long value) {
        return new Auction(value, sellerId, highestBidderId, item, type, status, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, sellerClaimed, buyerClaimed, searchableText);
    }

    public Auction withBid(UUID bidder, double bidAmount, Instant newExpiry) {
        return new Auction(id, sellerId, bidder, item, type, status, category, startingPrice, bidAmount, buyNowPrice, bidIncrement, createdAt, newExpiry, sellerClaimed, buyerClaimed, searchableText);
    }

    public Auction withStatus(AuctionStatus newStatus) {
        return new Auction(id, sellerId, highestBidderId, item, type, newStatus, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, sellerClaimed, buyerClaimed, searchableText);
    }

    public Auction markSellerClaimed() {
        return new Auction(id, sellerId, highestBidderId, item, type, status, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, true, buyerClaimed, searchableText);
    }

    public Auction markBuyerClaimed() {
        return new Auction(id, sellerId, highestBidderId, item, type, status, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, sellerClaimed, true, searchableText);
    }

    public Auction soldTo(UUID buyerId, double finalPrice) {
        return new Auction(id, sellerId, buyerId, item, type, AuctionStatus.SOLD, category, startingPrice, finalPrice, buyNowPrice, bidIncrement, createdAt, Instant.now(), sellerClaimed, buyerClaimed, searchableText);
    }

    public boolean hasBuyNow() {
        return buyNowPrice > 0.0D;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public double minimumNextBid() {
        return Math.max(startingPrice, currentBid) + bidIncrement;
    }

    public double displayPrice() {
        return currentBid > 0.0D ? currentBid : startingPrice;
    }

    public Duration timeLeft() {
        return Duration.between(Instant.now(), expiresAt).isNegative() ? Duration.ZERO : Duration.between(Instant.now(), expiresAt);
    }
}
