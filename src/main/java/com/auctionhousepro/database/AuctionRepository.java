package com.auctionhousepro.database;

import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionFilter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AuctionRepository {
    CompletableFuture<Auction> insert(Auction auction);

    CompletableFuture<Void> update(Auction auction);

    CompletableFuture<Optional<Auction>> findById(long id);

    CompletableFuture<List<Auction>> search(AuctionFilter filter, int limit);

    CompletableFuture<List<Auction>> findBySeller(UUID sellerId);

    CompletableFuture<List<Auction>> claimable(UUID playerId);

    CompletableFuture<List<Auction>> expiringBefore(long epochMillis);

    CompletableFuture<Void> appendLog(UUID actorId, String action, String details);
}
