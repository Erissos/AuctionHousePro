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
        String searchableText,
        int watchCount,
        int viewCount,
        int bidCount,
        double featuredScore
) {
    public Auction withId(long value) {
        return new Auction(value, sellerId, highestBidderId, item, type, status, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, sellerClaimed, buyerClaimed, searchableText, watchCount, viewCount, bidCount, featuredScore);
    }

    public Auction withBid(UUID bidder, double bidAmount, Instant newExpiry) {
        return new Auction(id, sellerId, bidder, item, type, status, category, startingPrice, bidAmount, buyNowPrice, bidIncrement, createdAt, newExpiry, sellerClaimed, buyerClaimed, searchableText, watchCount, viewCount, bidCount, featuredScore);
    }

    public Auction withStatus(AuctionStatus newStatus) {
        return new Auction(id, sellerId, highestBidderId, item, type, newStatus, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, sellerClaimed, buyerClaimed, searchableText, watchCount, viewCount, bidCount, featuredScore);
    }

    public Auction markSellerClaimed() {
        return new Auction(id, sellerId, highestBidderId, item, type, status, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, true, buyerClaimed, searchableText, watchCount, viewCount, bidCount, featuredScore);
    }

    public Auction markBuyerClaimed() {
        return new Auction(id, sellerId, highestBidderId, item, type, status, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, sellerClaimed, true, searchableText, watchCount, viewCount, bidCount, featuredScore);
    }

    public Auction soldTo(UUID buyerId, double finalPrice) {
        return new Auction(id, sellerId, buyerId, item, type, AuctionStatus.SOLD, category, startingPrice, finalPrice, buyNowPrice, bidIncrement, createdAt, Instant.now(), sellerClaimed, buyerClaimed, searchableText, watchCount, viewCount, bidCount, featuredScore);
    }

    public Auction withMetrics(int newWatchCount, int newViewCount, int newBidCount) {
        return new Auction(id, sellerId, highestBidderId, item, type, status, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, sellerClaimed, buyerClaimed, searchableText, newWatchCount, newViewCount, newBidCount, computeFeaturedScore(newWatchCount, newViewCount, newBidCount, displayPrice(), createdAt));
    }

    public Auction incrementBidCount() {
        return withMetrics(watchCount, viewCount, bidCount + 1);
    }

    public Auction incrementViewCount() {
        return withMetrics(watchCount, viewCount + 1, bidCount);
    }

    public Auction adjustWatchCount(int delta) {
        return withMetrics(Math.max(0, watchCount + delta), viewCount, bidCount);
    }

    public Auction refreshFeaturedScore() {
        return new Auction(id, sellerId, highestBidderId, item, type, status, category, startingPrice, currentBid, buyNowPrice, bidIncrement, createdAt, expiresAt, sellerClaimed, buyerClaimed, searchableText, watchCount, viewCount, bidCount, computeFeaturedScore(watchCount, viewCount, bidCount, displayPrice(), createdAt));
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

    private static double computeFeaturedScore(int watchCount, int viewCount, int bidCount, double displayPrice, Instant createdAt) {
        long ageHours = Math.max(1L, Duration.between(createdAt, Instant.now()).toHours());
        double activityScore = (watchCount * 4.0D) + (bidCount * 6.0D) + Math.min(viewCount, 100) * 0.35D;
        double valueScore = Math.log10(Math.max(10.0D, displayPrice) + 1.0D) * 8.0D;
        return (activityScore + valueScore) / Math.max(1.0D, ageHours / 6.0D);
    }
}
