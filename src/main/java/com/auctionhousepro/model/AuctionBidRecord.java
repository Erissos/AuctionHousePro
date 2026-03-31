package com.auctionhousepro.model;

import java.time.Instant;
import java.util.UUID;

public record AuctionBidRecord(
        long id,
        long auctionId,
        UUID bidderId,
        double amount,
        Instant createdAt
) {
}