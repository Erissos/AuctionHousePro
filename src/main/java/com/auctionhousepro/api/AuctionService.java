package com.auctionhousepro.api;

import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionBidRecord;
import com.auctionhousepro.model.AuctionFilter;
import com.auctionhousepro.model.AuctionOffer;
import com.auctionhousepro.model.DeliveryBoxEntry;
import com.auctionhousepro.model.MarketStatsSnapshot;
import com.auctionhousepro.model.SellerProfile;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AuctionService {
    CompletableFuture<Auction> createAuction(Player seller, ItemStack item, Duration duration, double startPrice, double buyNowPrice, double bidIncrement);

    CompletableFuture<Auction> placeBid(Player bidder, long auctionId, double amount);

    CompletableFuture<Auction> buyNow(Player buyer, long auctionId);

    CompletableFuture<Boolean> cancelAuction(CommandSender actor, long auctionId);

    CompletableFuture<List<Auction>> search(AuctionFilter filter);

    CompletableFuture<List<Auction>> playerListings(UUID playerId);

    CompletableFuture<Boolean> claim(Player player, Long targetAuctionId);

    CompletableFuture<List<Auction>> claimable(UUID playerId);

    CompletableFuture<Optional<Auction>> findAuction(long auctionId);

    CompletableFuture<List<AuctionBidRecord>> bidHistory(long auctionId, int limit);

    CompletableFuture<SellerProfile> sellerProfile(UUID sellerId);

    CompletableFuture<List<Auction>> recentSales(UUID sellerId, int limit);

    CompletableFuture<Boolean> toggleWatch(UUID playerId, long auctionId, Double targetPrice);

    CompletableFuture<Set<Long>> watchedAuctions(UUID playerId);

    CompletableFuture<Optional<Double>> watchTarget(UUID playerId, long auctionId);

    CompletableFuture<List<DeliveryBoxEntry>> deliveryBox(UUID playerId);

    CompletableFuture<Boolean> claimDelivery(Player player, Long deliveryId);

    CompletableFuture<AuctionOffer> createOffer(Player buyer, long auctionId, double amount);

    CompletableFuture<List<AuctionOffer>> offersForSeller(UUID sellerId);

    CompletableFuture<List<AuctionOffer>> offersForBuyer(UUID buyerId);

    CompletableFuture<Boolean> respondToOffer(CommandSender actor, long offerId, boolean accept);

    CompletableFuture<MarketStatsSnapshot> marketStats();

    CompletableFuture<List<String>> recentAuditLines(String query, int limit);

    CompletableFuture<Boolean> forceExpire(CommandSender actor, long auctionId);

    CompletableFuture<Boolean> returnListing(CommandSender actor, long auctionId);

    void warmupCache();

    void recordView(Player viewer, long auctionId);

    Map<String, Long> telemetrySnapshot();

    Optional<Auction> cachedAuction(long auctionId);
}
