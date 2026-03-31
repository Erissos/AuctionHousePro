package com.auctionhousepro.model;

import org.bukkit.inventory.ItemStack;

import java.time.Instant;

public record DeliveryBoxEntry(
        long id,
        java.util.UUID playerId,
        ItemStack item,
        Long sourceAuctionId,
        String reason,
        Instant createdAt
) {
}