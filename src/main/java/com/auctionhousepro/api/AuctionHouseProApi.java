package com.auctionhousepro.api;

public final class AuctionHouseProApi {
    private static AuctionService provider;

    private AuctionHouseProApi() {
    }

    public static AuctionService provider() {
        if (provider == null) {
            throw new IllegalStateException("AuctionHousePro API has not been initialized yet");
        }
        return provider;
    }

    public static void setProvider(AuctionService auctionService) {
        provider = auctionService;
    }
}
