package com.auctionhousepro.model;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
public record DeliveryPayload(UUID playerId, ItemStack item, long auctionId, String reason) { }
