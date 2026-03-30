package com.auctionhousepro.api.event;

import com.auctionhousepro.model.Auction;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class AuctionCreateEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Auction auction;
    private boolean cancelled;

    public AuctionCreateEvent(Player player, Auction auction) {
        this.player = player;
        this.auction = auction;
    }

    public Player getPlayer() {
        return player;
    }

    public Auction getAuction() {
        return auction;
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
