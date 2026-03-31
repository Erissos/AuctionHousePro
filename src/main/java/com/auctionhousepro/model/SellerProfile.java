package com.auctionhousepro.model;

import java.util.UUID;

public record SellerProfile(
        UUID sellerId,
        int activeListings,
        int completedSales,
        double grossSales,
        double averageSale,
        int pendingOffers,
        int watchers
) {
}