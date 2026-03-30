package com.auctionhousepro.api;

import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionFilter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AuctionService {
    CompletableFuture<Auction> createAuction(Player seller, ItemStack item, Duration duration, double startPrice, double buyNowPrice, double bidIncrement);

    CompletableFuture<Auction> placeBid(Player bidder, long auctionId, double amount);

    CompletableFuture<Auction> buyNow(Player buyer, long auctionId);

    CompletableFuture<Boolean> cancelAuction(CommandSender actor, long auctionId);

    CompletableFuture<List<Auction>> search(AuctionFilter filter);

    CompletableFuture<List<Auction>> playerListings(UUID playerId);

    CompletableFuture<List<Auction>> claimable(UUID playerId);

    Optional<Auction> cachedAuction(long auctionId);

    void openBrowser(Player player);
}
