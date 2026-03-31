package com.auctionhousepro.service.impl;

import com.auctionhousepro.AuctionHouseProPlugin;
import com.auctionhousepro.api.AuctionService;
import com.auctionhousepro.api.event.AuctionBidEvent;
import com.auctionhousepro.api.event.AuctionCancelEvent;
import com.auctionhousepro.api.event.AuctionCreateEvent;
import com.auctionhousepro.api.event.AuctionExpireEvent;
import com.auctionhousepro.api.event.AuctionWinEvent;
import com.auctionhousepro.config.ConfigManager;
import com.auctionhousepro.database.AuctionRepository;
import com.auctionhousepro.database.MarketRepository;
import com.auctionhousepro.discord.DiscordWebhookService;
import com.auctionhousepro.economy.EconomyService;
import com.auctionhousepro.exception.LocalizedException;
import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionBidRecord;
import com.auctionhousepro.model.AuctionCategory;
import com.auctionhousepro.model.AuctionFilter;
import com.auctionhousepro.model.AuctionOffer;
import com.auctionhousepro.model.AuctionOfferStatus;
import com.auctionhousepro.model.AuctionStatus;
import com.auctionhousepro.model.AuctionType;
import com.auctionhousepro.model.DeliveryBoxEntry;
import com.auctionhousepro.model.MarketStatsSnapshot;
import com.auctionhousepro.model.SellerProfile;
import com.auctionhousepro.model.WatchSubscription;
import com.auctionhousepro.service.AuditLogService;
import com.auctionhousepro.service.MarketTelemetryService;
import com.auctionhousepro.service.NotificationService;
import com.auctionhousepro.util.SearchTextUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public final class AuctionServiceImpl implements AuctionService {
    private final AuctionHouseProPlugin plugin;
    private final ConfigManager configManager;
    private final AuctionRepository repository;
    private final MarketRepository marketRepository;
    private final EconomyService economyService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final DiscordWebhookService discordWebhookService;
    private final MarketTelemetryService telemetryService;
    private final Cache<Long, Auction> auctionCache;
    private final Map<UUID, Long> bidCooldowns;
    private final Map<UUID, Long> listingCooldowns;
    private final Map<Long, Semaphore> auctionLocks;
    private int expireTaskId = -1;

    public AuctionServiceImpl(AuctionHouseProPlugin plugin,
                              ConfigManager configManager,
                              AuctionRepository repository,
                              MarketRepository marketRepository,
                              EconomyService economyService,
                              NotificationService notificationService,
                              AuditLogService auditLogService,
                              DiscordWebhookService discordWebhookService,
                              MarketTelemetryService telemetryService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.repository = repository;
        this.marketRepository = marketRepository;
        this.economyService = economyService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.discordWebhookService = discordWebhookService;
        this.telemetryService = telemetryService;
        this.auctionCache = Caffeine.newBuilder().maximumSize(10000).build();
        this.bidCooldowns = new ConcurrentHashMap<>();
        this.listingCooldowns = new ConcurrentHashMap<>();
        this.auctionLocks = new ConcurrentHashMap<>();
    }

    public void startSchedulers() {
        this.expireTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tickExpirations, configManager.expireCheckTicks(), configManager.expireCheckTicks());
    }

    public void shutdown() {
        if (expireTaskId != -1) {
            Bukkit.getScheduler().cancelTask(expireTaskId);
        }
    }

    @Override
    public void warmupCache() {
        repository.activeAuctions().thenAccept(auctions -> auctions.forEach(auction -> auctionCache.put(auction.id(), auction.refreshFeaturedScore()))).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to preload auction cache: " + throwable.getMessage());
            return null;
        });
    }

    @Override
    public CompletableFuture<Auction> createAuction(Player seller, ItemStack item, Duration duration, double startPrice, double buyNowPrice, double bidIncrement) {
        if (item == null || item.getType().isAir()) {
            return CompletableFuture.failedFuture(new LocalizedException("messages.hold-item"));
        }
        if (isCoolingDown(seller.getUniqueId(), listingCooldowns, configManager.listCooldownMillis())) {
            return CompletableFuture.failedFuture(new LocalizedException("messages.listing-cooldown"));
        }
        long minutes = duration.toMinutes();
        if (minutes < configManager.minDurationMinutes() || minutes > configManager.maxDurationMinutes()) {
            return CompletableFuture.failedFuture(new LocalizedException("messages.invalid-duration"));
        }
        if (!isAllowedItem(item)) {
            return CompletableFuture.failedFuture(new LocalizedException("messages.item-blocked"));
        }

        double listingFee = configManager.listingFee(seller);
        if (!seller.hasPermission("auctionhousepro.bypass.fees") && listingFee > 0.0D) {
            if (!economyService.has(seller, listingFee) || !economyService.withdraw(seller, listingFee)) {
                return CompletableFuture.failedFuture(new LocalizedException("messages.not-enough-money"));
            }
        }

        return repository.findBySeller(seller.getUniqueId()).thenCompose(existing -> onMainThread(() -> {
            long activeCount = existing.stream().filter(auction -> auction.status() == AuctionStatus.ACTIVE).count();
            if (activeCount >= configManager.maxActiveListings(seller)) {
                throw new LocalizedException("messages.max-active-listings");
            }

            Auction auction = new Auction(
                    0L,
                    seller.getUniqueId(),
                    null,
                    item.clone(),
                    buyNowPrice > 0.0D ? AuctionType.HYBRID : AuctionType.BID,
                    AuctionStatus.ACTIVE,
                    AuctionCategory.fromMaterial(item.getType()),
                    startPrice,
                    0.0D,
                    buyNowPrice,
                    bidIncrement,
                    Instant.now(),
                    Instant.now().plus(duration),
                    false,
                    false,
                    SearchTextUtil.build(item),
                    0,
                    0,
                    0,
                    0.0D
            ).refreshFeaturedScore();

            AuctionCreateEvent event = new AuctionCreateEvent(seller, auction);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                throw new LocalizedException("messages.action-cancelled");
            }

            seller.getInventory().setItemInMainHand(null);
            return new PendingAuctionInsert(auction, seller.getName(), item.getType().name(), listingFee);
        })).thenCompose(pending -> repository.insert(pending.auction()).handle((inserted, throwable) -> {
            if (throwable != null) {
                restoreFailedListing(seller, item, pending.listingFee());
                throw new CompletionException(throwable);
            }
            runCreateAuctionSideEffects(seller.getUniqueId(), pending.sellerName(), pending.itemTypeName(), inserted);
            return inserted;
        }));
    }

    @Override
    public CompletableFuture<Auction> placeBid(Player bidder, long auctionId, double amount) {
        if (isCoolingDown(bidder.getUniqueId(), bidCooldowns, configManager.bidCooldownMillis())) {
            return CompletableFuture.failedFuture(new LocalizedException("messages.bid-cooldown"));
        }

        long start = System.currentTimeMillis();
        return withAuctionLock(auctionId, () -> fetchAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
            if (auction.status() != AuctionStatus.ACTIVE || auction.isExpired()) {
                throw new LocalizedException("messages.auction-inactive");
            }
            if (auction.sellerId().equals(bidder.getUniqueId())) {
                throw new LocalizedException("messages.cannot-bid-own");
            }
            if (amount < auction.minimumNextBid()) {
                throw new LocalizedException("messages.bid-too-low", Map.of("amount", String.format(Locale.US, "%.2f", auction.minimumNextBid())));
            }
            if (!economyService.has(bidder, amount) || !economyService.withdraw(bidder, amount)) {
                throw new LocalizedException("messages.not-enough-money");
            }

            AuctionBidEvent event = new AuctionBidEvent(bidder, auction, amount);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                economyService.deposit(bidder, amount);
                throw new LocalizedException("messages.action-cancelled");
            }

            if (auction.highestBidderId() != null && auction.currentBid() > 0.0D) {
                economyService.deposit(Bukkit.getOfflinePlayer(auction.highestBidderId()), auction.currentBid());
                notificationService.notify(auction.highestBidderId(), "messages.outbid", Map.of("item", auction.item().getType().name()));
            }

            Instant expiry = auction.expiresAt();
            long remainingSeconds = Duration.between(Instant.now(), auction.expiresAt()).toSeconds();
            if (remainingSeconds <= configManager.antiSnipeWindowSeconds()) {
                expiry = expiry.plusSeconds(configManager.antiSnipeExtensionSeconds());
            }
            return auction.withBid(bidder.getUniqueId(), amount, expiry).incrementBidCount().refreshFeaturedScore();
        })).thenCompose(updated -> repository.update(updated)
                .thenCompose(unused -> marketRepository.recordBid(updated.id(), bidder.getUniqueId(), amount))
                .thenCompose(unused -> repository.appendLog(bidder.getUniqueId(), "auction-bid", "id=" + updated.id() + ", bid=" + amount))
                .thenCompose(unused -> notifyWatchers(updated, bidder.getUniqueId()))
                .thenApply(unused -> updated))).thenApply(updated -> {
                    cacheUpdated(updated);
                    auditLogService.append(bidder.getUniqueId(), "auction-bid", "id=" + updated.id() + ", bid=" + amount);
                    telemetryService.markBid(System.currentTimeMillis() - start);
                    return updated;
                });
    }

    @Override
    public CompletableFuture<Auction> buyNow(Player buyer, long auctionId) {
        return withAuctionLock(auctionId, () -> fetchAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
            if (auction.status() != AuctionStatus.ACTIVE || !auction.hasBuyNow()) {
                throw new LocalizedException("messages.buy-now-unavailable");
            }
            if (auction.sellerId().equals(buyer.getUniqueId())) {
                throw new LocalizedException("messages.cannot-buy-own");
            }
            if (!economyService.has(buyer, auction.buyNowPrice()) || !economyService.withdraw(buyer, auction.buyNowPrice())) {
                throw new LocalizedException("messages.not-enough-money");
            }
            if (auction.highestBidderId() != null && auction.currentBid() > 0.0D) {
                economyService.deposit(Bukkit.getOfflinePlayer(auction.highestBidderId()), auction.currentBid());
            }
            return auction.soldTo(buyer.getUniqueId(), auction.buyNowPrice()).refreshFeaturedScore();
        })).thenCompose(updated -> repository.update(updated)
                .thenCompose(unused -> settlePendingOffers(updated.id(), 0L, true))
                .thenApply(unused -> updated))).thenApply(updated -> {
                    cacheUpdated(updated);
                    processSale(updated);
                    return updated;
                });
    }

    @Override
    public CompletableFuture<Boolean> cancelAuction(CommandSender actor, long auctionId) {
        return withAuctionLock(auctionId, () -> fetchAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
            boolean admin = actor.hasPermission("auctionhousepro.admin");
            UUID actorId = actor instanceof Player player ? player.getUniqueId() : null;
            if (!admin && actorId != null && !auction.sellerId().equals(actorId)) {
                throw new LocalizedException("messages.cannot-cancel-auction");
            }

            AuctionCancelEvent event = new AuctionCancelEvent(actor, auction);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                throw new LocalizedException("messages.action-cancelled");
            }

            if (auction.highestBidderId() != null && auction.currentBid() > 0.0D) {
                economyService.deposit(Bukkit.getOfflinePlayer(auction.highestBidderId()), auction.currentBid());
            }
            return auction.withStatus(AuctionStatus.CANCELLED);
        })).thenCompose(updated -> repository.update(updated)
                .thenCompose(unused -> repository.appendLog(actor instanceof Player player ? player.getUniqueId() : null, "auction-cancel", "id=" + updated.id()))
                .thenCompose(unused -> settlePendingOffers(updated.id(), 0L, true))
                .thenApply(unused -> true))).thenApply(success -> {
                    auctionCache.invalidate(auctionId);
                    return success;
                });
    }

    @Override
    public CompletableFuture<List<Auction>> search(AuctionFilter filter) {
        long start = System.currentTimeMillis();
        return repository.search(filter, configManager.maxSearchResults()).thenApply(results -> results.stream()
                .map(Auction::refreshFeaturedScore)
                .sorted(filter.withPageDefaults().sortMode().comparator())
                .toList()).thenApply(results -> {
                    telemetryService.markSearch(System.currentTimeMillis() - start);
                    return results;
                });
    }

    @Override
    public CompletableFuture<List<Auction>> playerListings(UUID playerId) {
        return repository.findBySeller(playerId).thenApply(results -> results.stream()
                .sorted(Comparator.comparing(Auction::createdAt).reversed())
                .toList());
    }

    @Override
    public CompletableFuture<Boolean> claim(Player player, Long targetAuctionId) {
        long start = System.currentTimeMillis();
        return claimable(player.getUniqueId()).thenCompose(auctions -> {
            List<Auction> targets = targetAuctionId == null ? auctions : auctions.stream().filter(auction -> auction.id() == targetAuctionId).toList();
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }

            List<CompletableFuture<Void>> updates = new ArrayList<>();
            for (Auction auction : targets) {
                updates.add(onMainThread(() -> applyClaim(player, auction)).thenCompose(updated -> repository.update(updated)));
            }
            return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new)).thenApply(unused -> true);
        }).thenApply(success -> {
            telemetryService.markClaim(System.currentTimeMillis() - start);
            return success;
        });
    }

    @Override
    public CompletableFuture<List<Auction>> claimable(UUID playerId) {
        return repository.claimable(playerId).thenApply(auctions -> auctions.stream()
                .sorted(Comparator.comparing(Auction::createdAt).reversed())
                .toList());
    }

    @Override
    public CompletableFuture<Optional<Auction>> findAuction(long auctionId) {
        return fetchAuction(auctionId).thenApply(Optional::of).exceptionally(throwable -> Optional.empty());
    }

    @Override
    public CompletableFuture<List<AuctionBidRecord>> bidHistory(long auctionId, int limit) {
        return marketRepository.bidHistory(auctionId, limit);
    }

    @Override
    public CompletableFuture<SellerProfile> sellerProfile(UUID sellerId) {
        return repository.findBySeller(sellerId).thenCompose(listings -> marketRepository.offersForSeller(sellerId).thenApply(offers -> {
            int activeListings = (int) listings.stream().filter(auction -> auction.status() == AuctionStatus.ACTIVE).count();
            List<Auction> completed = listings.stream().filter(auction -> auction.status() == AuctionStatus.SOLD || auction.status() == AuctionStatus.CLAIMED).toList();
            double grossSales = completed.stream().mapToDouble(Auction::currentBid).sum();
            int watchers = listings.stream().mapToInt(Auction::watchCount).sum();
            return new SellerProfile(sellerId, activeListings, completed.size(), grossSales, completed.isEmpty() ? 0.0D : grossSales / completed.size(), (int) offers.stream().filter(offer -> offer.status() == AuctionOfferStatus.PENDING).count(), watchers);
        }));
    }

    @Override
    public CompletableFuture<List<Auction>> recentSales(UUID sellerId, int limit) {
        return repository.findBySeller(sellerId).thenApply(listings -> listings.stream()
                .filter(auction -> auction.status() == AuctionStatus.SOLD || auction.status() == AuctionStatus.CLAIMED)
                .sorted(Comparator.comparing(Auction::createdAt).reversed())
                .limit(limit)
                .toList());
    }

    @Override
    public CompletableFuture<Boolean> toggleWatch(UUID playerId, long auctionId, Double targetPrice) {
        return marketRepository.watchedAuctionIds(playerId).thenCompose(ids -> {
            boolean remove = ids.contains(auctionId);
            CompletableFuture<Void> action = remove ? marketRepository.unwatchAuction(playerId, auctionId).thenCompose(unused -> repository.adjustWatchCount(auctionId, -1)) : marketRepository.watchAuction(playerId, auctionId, targetPrice).thenCompose(unused -> repository.adjustWatchCount(auctionId, 1));
            return action.thenApply(unused -> !remove);
        }).thenApply(watching -> {
            Optional.ofNullable(auctionCache.getIfPresent(auctionId)).ifPresent(auction -> cacheUpdated(watching ? auction.adjustWatchCount(1) : auction.adjustWatchCount(-1)));
            telemetryService.markWatch();
            return watching;
        });
    }

    @Override
    public CompletableFuture<Set<Long>> watchedAuctions(UUID playerId) {
        return marketRepository.watchedAuctionIds(playerId);
    }

    @Override
    public CompletableFuture<Optional<Double>> watchTarget(UUID playerId, long auctionId) {
        return marketRepository.watchTarget(playerId, auctionId);
    }

    @Override
    public CompletableFuture<List<DeliveryBoxEntry>> deliveryBox(UUID playerId) {
        return marketRepository.deliveries(playerId);
    }

    @Override
    public CompletableFuture<Boolean> claimDelivery(Player player, Long deliveryId) {
        return marketRepository.deliveries(player.getUniqueId()).thenCompose(entries -> {
            List<DeliveryBoxEntry> targets = deliveryId == null ? entries : entries.stream().filter(entry -> entry.id() == deliveryId).toList();
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }

            List<CompletableFuture<Void>> actions = new ArrayList<>();
            for (DeliveryBoxEntry entry : targets) {
                actions.add(onMainThread(() -> tryClaimDelivery(player, entry)).thenCompose(claimed -> claimed ? marketRepository.removeDelivery(entry.id()) : CompletableFuture.completedFuture(null)));
            }
            return CompletableFuture.allOf(actions.toArray(CompletableFuture[]::new)).thenApply(unused -> true);
        });
    }

    @Override
    public CompletableFuture<AuctionOffer> createOffer(Player buyer, long auctionId, double amount) {
        return withAuctionLock(auctionId, () -> fetchAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
            if (auction.status() != AuctionStatus.ACTIVE) {
                throw new LocalizedException("messages.auction-inactive");
            }
            if (auction.sellerId().equals(buyer.getUniqueId())) {
                throw new LocalizedException("messages.cannot-buy-own");
            }
            if (amount < auction.displayPrice()) {
                throw new LocalizedException("messages.offer-too-low", Map.of("amount", String.format(Locale.US, "%.2f", auction.displayPrice())));
            }
            if (!economyService.has(buyer, amount) || !economyService.withdraw(buyer, amount)) {
                throw new LocalizedException("messages.not-enough-money");
            }
            return auction;
        })).thenCompose(auction -> marketRepository.createOffer(auction.id(), auction.sellerId(), buyer.getUniqueId(), amount).thenCompose(offer -> repository.appendLog(buyer.getUniqueId(), "auction-offer", "auction=" + offer.auctionId() + ", amount=" + offer.amount()).thenApply(unused -> offer))));
    }

    @Override
    public CompletableFuture<List<AuctionOffer>> offersForSeller(UUID sellerId) {
        return marketRepository.offersForSeller(sellerId);
    }

    @Override
    public CompletableFuture<List<AuctionOffer>> offersForBuyer(UUID buyerId) {
        return marketRepository.offersForBuyer(buyerId);
    }

    @Override
    public CompletableFuture<Boolean> respondToOffer(CommandSender actor, long offerId, boolean accept) {
        return marketRepository.findOffer(offerId).thenCompose(optional -> optional.map(offer -> withAuctionLock(offer.auctionId(), () -> handleOfferResponse(actor, offer, accept))).orElseGet(() -> CompletableFuture.failedFuture(new LocalizedException("messages.offer-not-found"))));
    }

    @Override
    public CompletableFuture<MarketStatsSnapshot> marketStats() {
        return marketRepository.marketStats();
    }

    @Override
    public CompletableFuture<List<String>> recentAuditLines(String query, int limit) {
        return marketRepository.recentAuditLines(query, limit);
    }

    @Override
    public CompletableFuture<Boolean> forceExpire(CommandSender actor, long auctionId) {
        return withAuctionLock(auctionId, () -> fetchAuction(auctionId).thenCompose(auction -> {
            Auction finalState = auction.highestBidderId() == null ? auction.withStatus(AuctionStatus.EXPIRED) : auction.withStatus(AuctionStatus.SOLD);
            return repository.update(finalState).thenApply(unused -> finalState);
        }).thenApply(finalState -> {
            cacheUpdated(finalState);
            if (finalState.status() == AuctionStatus.SOLD) {
                processSale(finalState);
            }
            auditLogService.append(actor instanceof Player player ? player.getUniqueId() : null, "auction-force-expire", "id=" + auctionId);
            return true;
        }));
    }

    @Override
    public CompletableFuture<Boolean> returnListing(CommandSender actor, long auctionId) {
        return withAuctionLock(auctionId, () -> fetchAuction(auctionId).thenCompose(auction -> {
            if (auction.highestBidderId() != null && auction.currentBid() > 0.0D) {
                economyService.deposit(Bukkit.getOfflinePlayer(auction.highestBidderId()), auction.currentBid());
            }
            return repository.update(auction.withStatus(AuctionStatus.CANCELLED))
                    .thenCompose(unused -> marketRepository.storeDelivery(auction.sellerId(), auction.item(), auction.id(), "admin-return"))
                    .thenApply(unused -> true);
        }).thenApply(success -> {
            auditLogService.append(actor instanceof Player player ? player.getUniqueId() : null, "auction-return", "id=" + auctionId);
            auctionCache.invalidate(auctionId);
            return success;
        }));
    }

    @Override
    public void recordView(Player viewer, long auctionId) {
        Optional.ofNullable(auctionCache.getIfPresent(auctionId)).ifPresent(auction -> cacheUpdated(auction.incrementViewCount()));
        repository.incrementViewCount(auctionId).exceptionally(throwable -> null);
    }

    @Override
    public Map<String, Long> telemetrySnapshot() {
        return telemetryService.snapshot();
    }

    @Override
    public Optional<Auction> cachedAuction(long auctionId) {
        return Optional.ofNullable(auctionCache.getIfPresent(auctionId));
    }

    private CompletableFuture<Boolean> handleOfferResponse(CommandSender actor, AuctionOffer offer, boolean accept) {
        UUID actorId = actor instanceof Player player ? player.getUniqueId() : null;
        boolean admin = actor.hasPermission("auctionhousepro.admin");
        boolean seller = actorId != null && actorId.equals(offer.sellerId());
        boolean buyer = actorId != null && actorId.equals(offer.buyerId());
        if (!admin && !seller && !buyer) {
            return CompletableFuture.failedFuture(new LocalizedException("messages.no-permission"));
        }
        if (offer.status() != AuctionOfferStatus.PENDING) {
            return CompletableFuture.failedFuture(new LocalizedException("messages.offer-no-longer-pending"));
        }

        if (accept) {
            if (!admin && !seller) {
                return CompletableFuture.failedFuture(new LocalizedException("messages.no-permission"));
            }
            return fetchAuction(offer.auctionId()).thenCompose(auction -> {
                if (auction.status() != AuctionStatus.ACTIVE) {
                    return CompletableFuture.failedFuture(new LocalizedException("messages.auction-inactive"));
                }
                if (auction.highestBidderId() != null && auction.currentBid() > 0.0D) {
                    economyService.deposit(Bukkit.getOfflinePlayer(auction.highestBidderId()), auction.currentBid());
                }
                Auction sold = auction.soldTo(offer.buyerId(), offer.amount()).refreshFeaturedScore();
                return repository.update(sold)
                        .thenCompose(unused -> marketRepository.updateOfferStatus(offer.id(), AuctionOfferStatus.ACCEPTED))
                        .thenCompose(unused -> settlePendingOffers(offer.auctionId(), offer.id(), false))
                        .thenApply(unused -> sold);
            }).thenApply(updated -> {
                cacheUpdated(updated);
                processSale(updated);
                return true;
            });
        }

        AuctionOfferStatus newStatus = buyer && !seller && !admin ? AuctionOfferStatus.CANCELLED : AuctionOfferStatus.REJECTED;
        economyService.deposit(Bukkit.getOfflinePlayer(offer.buyerId()), offer.amount());
        return marketRepository.updateOfferStatus(offer.id(), newStatus).thenApply(unused -> true);
    }

    private CompletableFuture<Void> settlePendingOffers(long auctionId, long keepOfferId, boolean refundAll) {
        return marketRepository.offersForAuction(auctionId).thenCompose(offers -> {
            List<CompletableFuture<Void>> actions = new ArrayList<>();
            for (AuctionOffer offer : offers) {
                if (offer.status() != AuctionOfferStatus.PENDING) {
                    continue;
                }
                if (!refundAll && offer.id() == keepOfferId) {
                    continue;
                }
                economyService.deposit(Bukkit.getOfflinePlayer(offer.buyerId()), offer.amount());
                actions.add(marketRepository.updateOfferStatus(offer.id(), AuctionOfferStatus.REJECTED));
            }
            return CompletableFuture.allOf(actions.toArray(CompletableFuture[]::new));
        });
    }

    private void tickExpirations() {
        repository.expiringBefore(System.currentTimeMillis()).thenAccept(auctions -> auctions.forEach(auction -> withAuctionLock(auction.id(), () -> fetchAuction(auction.id()).thenCompose(current -> {
            if (current.status() != AuctionStatus.ACTIVE) {
                return CompletableFuture.completedFuture(null);
            }
            Auction finalState = current.highestBidderId() == null ? current.withStatus(AuctionStatus.EXPIRED) : current.withStatus(AuctionStatus.SOLD);
            return repository.update(finalState).thenApply(unused -> finalState);
        }).thenApply(finalState -> {
            if (finalState == null) {
                return null;
            }
            cacheUpdated(finalState);
            if (finalState.status() == AuctionStatus.SOLD) {
                processSale(finalState);
                Bukkit.getPluginManager().callEvent(new AuctionWinEvent(finalState));
            } else {
                notificationService.notify(finalState.sellerId(), "messages.expired-auction", Map.of("item", finalState.item().getType().name()));
                Bukkit.getPluginManager().callEvent(new AuctionExpireEvent(finalState));
            }
            return null;
        }))));
    }

    private void processSale(Auction auction) {
        OfflinePlayer seller = Bukkit.getOfflinePlayer(auction.sellerId());
        double sellerCut = auction.currentBid() * Math.max(0.0D, 1.0D - configManager.taxRate(seller) - configManager.commissionRate(seller));
        notificationService.notify(auction.sellerId(), "messages.sold-auction", Map.of("item", auction.item().getType().name(), "amount", economyService.format(auction.currentBid())));
        if (auction.highestBidderId() != null) {
            notificationService.notify(auction.highestBidderId(), "messages.won-auction", Map.of("item", auction.item().getType().name()));
        }
        auditLogService.append(auction.sellerId(), "auction-sold", "id=" + auction.id() + ", sellerCut=" + sellerCut);
        repository.appendLog(auction.sellerId(), "auction-sold", "id=" + auction.id() + ", gross=" + auction.currentBid());

        String sellerName = Optional.ofNullable(seller.getName()).orElse("Unknown");
        String itemTypeName = auction.item().getType().name();
        if (configManager.notifyHighSales() && auction.currentBid() >= configManager.rareBroadcastThreshold()) {
            discordWebhookService.send(
                    applyWebhookPlaceholders(configManager.highSaleTitle(), sellerName, itemTypeName, auction),
                    applyWebhookPlaceholders(configManager.highSaleDescription(), sellerName, itemTypeName, auction)
            );
        }
        if (configManager.highSaleBroadcastEnabled() && auction.currentBid() >= configManager.rareBroadcastThreshold()) {
            notificationService.broadcast("messages.high-sale-broadcast", Map.of("seller", sellerName, "item", itemTypeName, "amount", economyService.format(auction.currentBid())));
        }
    }

    private Auction applyClaim(Player player, Auction auction) {
        Auction updated = auction;
        if (auction.status() == AuctionStatus.SOLD && player.getUniqueId().equals(auction.sellerId()) && !auction.sellerClaimed()) {
            double sellerCut = auction.currentBid() * Math.max(0.0D, 1.0D - configManager.taxRate(player) - configManager.commissionRate(player));
            economyService.deposit(player, sellerCut);
            updated = updated.markSellerClaimed();
        }
        if (auction.status() == AuctionStatus.SOLD && auction.highestBidderId() != null && player.getUniqueId().equals(auction.highestBidderId()) && !auction.buyerClaimed()) {
            giveItem(player, auction.item(), auction.id(), "won-auction");
            updated = updated.markBuyerClaimed();
        }
        if ((auction.status() == AuctionStatus.EXPIRED || auction.status() == AuctionStatus.CANCELLED) && player.getUniqueId().equals(auction.sellerId()) && !auction.sellerClaimed()) {
            giveItem(player, auction.item(), auction.id(), "returned-auction");
            updated = updated.markSellerClaimed();
        }
        if ((updated.sellerClaimed() && updated.buyerClaimed()) || ((updated.status() == AuctionStatus.EXPIRED || updated.status() == AuctionStatus.CANCELLED) && updated.sellerClaimed())) {
            updated = updated.withStatus(AuctionStatus.CLAIMED);
        }
        cacheUpdated(updated);
        return updated;
    }

    private CompletableFuture<Void> notifyWatchers(Auction auction, UUID actorId) {
        List<CompletableFuture<Void>> notifications = new ArrayList<>();
        return marketRepository.watchSubscriptions(auction.id()).thenCompose(subscriptions -> {
            for (WatchSubscription subscription : subscriptions) {
                if (subscription.playerId().equals(actorId)) {
                    continue;
                }
                if (subscription.targetPrice() != null && auction.displayPrice() >= subscription.targetPrice()) {
                    notificationService.notify(subscription.playerId(), "messages.watch-target-reached", Map.of("item", auction.item().getType().name(), "amount", economyService.format(auction.displayPrice())));
                    notifications.add(marketRepository.clearWatchTarget(subscription.playerId(), auction.id()));
                }
            }
            return CompletableFuture.allOf(notifications.toArray(CompletableFuture[]::new));
        });
    }

    private boolean tryClaimDelivery(Player player, DeliveryBoxEntry entry) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(entry.item().clone());
        return leftovers.isEmpty();
    }

    private void giveItem(Player player, ItemStack itemStack, long auctionId, String reason) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
        if (leftovers.isEmpty()) {
            return;
        }
        leftovers.values().forEach(leftover -> marketRepository.storeDelivery(player.getUniqueId(), leftover, auctionId, reason));
        notificationService.notify(player.getUniqueId(), "messages.delivery-box-stored", Map.of("count", String.valueOf(leftovers.size())));
    }

    private boolean isAllowedItem(ItemStack itemStack) {
        if (configManager.blacklistMaterials().contains(itemStack.getType())) {
            return false;
        }
        if (configManager.whitelistEnabled() && !configManager.whitelistMaterials().contains(itemStack.getType())) {
            return false;
        }
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta == null) {
            return true;
        }
        return itemMeta.getPersistentDataContainer().getKeys().stream()
                .map(NamespacedKey::toString)
                .noneMatch(configManager.blockedNbtKeys()::contains);
    }

    private CompletableFuture<Auction> fetchAuction(long auctionId) {
        Auction cached = auctionCache.getIfPresent(auctionId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return repository.findById(auctionId).thenApply(optional -> optional.orElseThrow(() -> new LocalizedException("messages.auction-not-found"))).thenApply(auction -> {
            auctionCache.put(auction.id(), auction);
            return auction;
        });
    }

    private boolean isCoolingDown(UUID playerId, Map<UUID, Long> cooldowns, long durationMillis) {
        long now = System.currentTimeMillis();
        long lastAction = cooldowns.getOrDefault(playerId, 0L);
        if ((now - lastAction) < durationMillis) {
            return true;
        }
        cooldowns.put(playerId, now);
        return false;
    }

    private void restoreFailedListing(Player seller, ItemStack item, double listingFee) {
        onMainThread(() -> {
            seller.getInventory().addItem(item);
            if (!seller.hasPermission("auctionhousepro.bypass.fees") && listingFee > 0.0D) {
                economyService.deposit(seller, listingFee);
            }
            return null;
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to restore listed item after insert error: " + throwable.getMessage());
            return null;
        });
    }

    private void runCreateAuctionSideEffects(UUID sellerId, String sellerName, String itemTypeName, Auction inserted) {
        try {
            cacheUpdated(inserted);
            auditLogService.append(sellerId, "auction-create", "id=" + inserted.id() + ", price=" + inserted.startingPrice());
            repository.appendLog(sellerId, "auction-create", "id=" + inserted.id());
            if (configManager.notifyRareListings() && inserted.displayPrice() >= configManager.rareBroadcastThreshold()) {
                discordWebhookService.send(
                        applyWebhookPlaceholders(configManager.rareListingTitle(), sellerName, itemTypeName, inserted),
                        applyWebhookPlaceholders(configManager.rareListingDescription(), sellerName, itemTypeName, inserted)
                );
            }
            if (configManager.rareListingBroadcastEnabled() && inserted.displayPrice() >= configManager.rareBroadcastThreshold()) {
                notificationService.broadcast("messages.rare-listing-broadcast", Map.of("seller", sellerName, "item", itemTypeName, "amount", economyService.format(inserted.displayPrice())));
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Auction created but post-create actions failed: " + exception.getMessage());
        }
    }

    private void cacheUpdated(Auction auction) {
        auctionCache.put(auction.id(), auction.refreshFeaturedScore());
    }

    private String applyWebhookPlaceholders(String template, String sellerName, String itemTypeName, Auction auction) {
        return template
                .replace("<seller>", sellerName)
                .replace("<item>", itemTypeName)
                .replace("<price>", String.format(Locale.US, "%.2f", auction.displayPrice()))
                .replace("<amount>", String.format(Locale.US, "%.2f", auction.currentBid()))
                .replace("<auction_id>", String.valueOf(auction.id()));
    }

    private <T> CompletableFuture<T> withAuctionLock(long auctionId, Supplier<CompletableFuture<T>> action) {
        Semaphore semaphore = auctionLocks.computeIfAbsent(auctionId, unused -> new Semaphore(1));
        return CompletableFuture.runAsync(semaphore::acquireUninterruptibly)
                .thenCompose(unused -> action.get())
                .whenComplete((result, throwable) -> semaphore.release());
    }

    private <T> CompletableFuture<T> onMainThread(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private record PendingAuctionInsert(Auction auction, String sellerName, String itemTypeName, double listingFee) {
    }
}
