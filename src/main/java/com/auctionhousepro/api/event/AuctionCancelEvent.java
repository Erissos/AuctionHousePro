package com.auctionhousepro.api.event;

import com.auctionhousepro.model.Auction;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class AuctionCancelEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender actor;
    private final Auction auction;
    private boolean cancelled;

    public AuctionCancelEvent(CommandSender actor, Auction auction) {
        this.actor = actor;
        this.auction = auction;
    }

    public CommandSender getActor() {
        return actor;
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
