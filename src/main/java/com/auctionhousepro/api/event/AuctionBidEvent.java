package com.auctionhousepro.api.event;

import com.auctionhousepro.model.Auction;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class AuctionBidEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player bidder;
    private final Auction auction;
    private final double amount;
    private boolean cancelled;

    public AuctionBidEvent(Player bidder, Auction auction, double amount) {
        this.bidder = bidder;
        this.auction = auction;
        this.amount = amount;
    }

    public Player getBidder() {
        return bidder;
    }

    public Auction getAuction() {
        return auction;
    }

    public double getAmount() {
        return amount;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
