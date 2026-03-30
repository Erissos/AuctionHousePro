package com.auctionhousepro.api.event;

import com.auctionhousepro.model.Auction;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class AuctionExpireEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Auction auction;

    public AuctionExpireEvent(Auction auction) {
        this.auction = auction;
    }

    public Auction getAuction() {
        return auction;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
