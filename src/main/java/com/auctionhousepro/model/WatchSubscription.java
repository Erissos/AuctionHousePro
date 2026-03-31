package com.auctionhousepro.model;

import java.time.Instant;
import java.util.UUID;

public record WatchSubscription(
        UUID playerId,
        long auctionId,
        Double targetPrice,
        Instant createdAt
) {
}