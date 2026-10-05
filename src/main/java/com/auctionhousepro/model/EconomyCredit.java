package com.auctionhousepro.model;
import java.util.UUID;
public record EconomyCredit(String operationId, UUID playerId, double amount) { }
