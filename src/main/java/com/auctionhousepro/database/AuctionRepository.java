package com.auctionhousepro.database;

import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionFilter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AuctionRepository {
    CompletableFuture<Auction> insert(Auction auction);

    default CompletableFuture<Auction> insertWithReceipt(Auction auction, String debitId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Atomic debit receipts are required"));
    }

    default CompletableFuture<Boolean> transition(Auction expected, Auction updated,
            List<com.auctionhousepro.model.EconomyCredit> credits, Long acceptedOfferId,
            com.auctionhousepro.model.DeliveryPayload delivery, String debitId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Atomic auction transitions are required"));
    }

    CompletableFuture<Void> update(Auction auction);

    CompletableFuture<Optional<Auction>> findById(long id);

    CompletableFuture<List<Auction>> search(AuctionFilter filter, int limit);

    CompletableFuture<List<Auction>> findBySeller(UUID sellerId);

    CompletableFuture<List<Auction>> claimable(UUID playerId);

    CompletableFuture<List<Auction>> expiringBefore(long epochMillis);

    CompletableFuture<List<Auction>> activeAuctions();

    CompletableFuture<Void> adjustWatchCount(long auctionId, int delta);

    CompletableFuture<Void> incrementViewCount(long auctionId);

    CompletableFuture<Void> appendLog(UUID actorId, String action, String details);
}
