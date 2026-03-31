package com.auctionhousepro.model;

import java.time.Instant;
import java.util.UUID;

public record AuctionOffer(
        long id,
        long auctionId,
        UUID sellerId,
        UUID buyerId,
        double amount,
        AuctionOfferStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public AuctionOffer withStatus(AuctionOfferStatus newStatus) {
        return new AuctionOffer(id, auctionId, sellerId, buyerId, amount, newStatus, createdAt, Instant.now());
    }
}