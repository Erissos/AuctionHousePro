package com.auctionhousepro.database;

import com.auctionhousepro.model.AuctionBidRecord;
import com.auctionhousepro.model.AuctionOffer;
import com.auctionhousepro.model.AuctionOfferStatus;
import com.auctionhousepro.model.DeliveryBoxEntry;
import com.auctionhousepro.model.MarketStatsSnapshot;
import com.auctionhousepro.model.WatchSubscription;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface MarketRepository {
    CompletableFuture<Void> recordBid(long auctionId, UUID bidderId, double amount);

    CompletableFuture<List<AuctionBidRecord>> bidHistory(long auctionId, int limit);

    CompletableFuture<Void> watchAuction(UUID playerId, long auctionId, Double targetPrice);

    CompletableFuture<Void> unwatchAuction(UUID playerId, long auctionId);

    CompletableFuture<Set<Long>> watchedAuctionIds(UUID playerId);

    CompletableFuture<Optional<Double>> watchTarget(UUID playerId, long auctionId);

    CompletableFuture<List<WatchSubscription>> watchSubscriptions(long auctionId);

    CompletableFuture<Void> clearWatchTarget(UUID playerId, long auctionId);

    CompletableFuture<Void> storeDelivery(UUID playerId, ItemStack itemStack, Long sourceAuctionId, String reason);

    CompletableFuture<List<DeliveryBoxEntry>> deliveries(UUID playerId);

    CompletableFuture<Void> removeDelivery(long deliveryId);

    CompletableFuture<AuctionOffer> createOffer(long auctionId, UUID sellerId, UUID buyerId, double amount);

    CompletableFuture<List<AuctionOffer>> offersForSeller(UUID sellerId);

    CompletableFuture<List<AuctionOffer>> offersForBuyer(UUID buyerId);

    CompletableFuture<List<AuctionOffer>> offersForAuction(long auctionId);

    CompletableFuture<Optional<AuctionOffer>> findOffer(long offerId);

    CompletableFuture<Void> updateOfferStatus(long offerId, AuctionOfferStatus status);

    CompletableFuture<List<String>> recentAuditLines(String query, int limit);

    CompletableFuture<MarketStatsSnapshot> marketStats();
}