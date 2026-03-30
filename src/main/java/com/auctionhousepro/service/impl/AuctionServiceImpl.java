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
import com.auctionhousepro.discord.DiscordWebhookService;
import com.auctionhousepro.economy.EconomyService;
import com.auctionhousepro.exception.LocalizedException;
import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionCategory;
import com.auctionhousepro.model.AuctionFilter;
import com.auctionhousepro.model.AuctionStatus;
import com.auctionhousepro.model.AuctionType;
import com.auctionhousepro.service.AuditLogService;
import com.auctionhousepro.service.NotificationService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class AuctionServiceImpl implements AuctionService {
    private final AuctionHouseProPlugin plugin;
    private final ConfigManager configManager;
    private final AuctionRepository repository;
    private final EconomyService economyService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final DiscordWebhookService discordWebhookService;
    private final Cache<Long, Auction> auctionCache;
    private final Map<UUID, Long> bidCooldowns;
    private final Map<UUID, Long> listingCooldowns;
    private int expireTaskId = -1;

    public AuctionServiceImpl(AuctionHouseProPlugin plugin,
                              ConfigManager configManager,
                              AuctionRepository repository,
                              EconomyService economyService,
                              NotificationService notificationService,
                              AuditLogService auditLogService,
                              DiscordWebhookService discordWebhookService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.repository = repository;
        this.economyService = economyService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.discordWebhookService = discordWebhookService;
        this.auctionCache = Caffeine.newBuilder().maximumSize(10000).build();
        this.bidCooldowns = new ConcurrentHashMap<>();
        this.listingCooldowns = new ConcurrentHashMap<>();
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
        if (!seller.hasPermission("auctionhousepro.bypass.fees") && configManager.listingFee() > 0.0D) {
            if (!economyService.has(seller, configManager.listingFee()) || !economyService.withdraw(seller, configManager.listingFee())) {
                return CompletableFuture.failedFuture(new LocalizedException("messages.not-enough-money"));
            }
        }

        return repository.findBySeller(seller.getUniqueId()).thenCompose(existing -> onMainThread(() -> {
            long activeCount = existing.stream().filter(auction -> auction.status() == AuctionStatus.ACTIVE).count();
            if (activeCount >= configManager.maxActiveListings()) {
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
                    searchableText(item)
            );

            AuctionCreateEvent event = new AuctionCreateEvent(seller, auction);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                throw new LocalizedException("messages.action-cancelled");
            }

            seller.getInventory().setItemInMainHand(null);
            String sellerName = seller.getName();
            String itemTypeName = item.getType().name();
            return new PendingAuctionInsert(auction, sellerName, itemTypeName);
        })).thenCompose(pending -> repository.insert(pending.auction()).handle((inserted, throwable) -> {
            if (throwable != null) {
                restoreFailedListing(seller, item);
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

        return fetchAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
            if (auction.status() != AuctionStatus.ACTIVE || auction.isExpired()) {
                throw new LocalizedException("messages.auction-inactive");
            }
            if (auction.sellerId().equals(bidder.getUniqueId())) {
                throw new LocalizedException("messages.cannot-bid-own");
            }
            if (amount < auction.minimumNextBid()) {
                throw new LocalizedException("messages.bid-too-low", Map.of("amount", String.format("%.2f", auction.minimumNextBid())));
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
                Player previousBidder = Bukkit.getOfflinePlayer(auction.highestBidderId()).getPlayer();
                economyService.deposit(Bukkit.getOfflinePlayer(auction.highestBidderId()), auction.currentBid());
                if (previousBidder != null) {
                    notificationService.notify(previousBidder.getUniqueId(), "messages.outbid", Map.of("item", auction.item().getType().name()));
                }
            }

            Instant expiry = auction.expiresAt();
            long remainingSeconds = Duration.between(Instant.now(), auction.expiresAt()).toSeconds();
            if (remainingSeconds <= configManager.antiSnipeWindowSeconds()) {
                expiry = expiry.plusSeconds(configManager.antiSnipeExtensionSeconds());
            }
            return auction.withBid(bidder.getUniqueId(), amount, expiry);
        })).thenCompose(updated -> repository.update(updated).thenApply(unused -> updated)).thenApply(updated -> {
            auctionCache.put(updated.id(), updated);
            auditLogService.append(bidder.getUniqueId(), "auction-bid", "id=" + updated.id() + ", bid=" + amount);
            repository.appendLog(bidder.getUniqueId(), "auction-bid", "id=" + updated.id() + ", bid=" + amount);
            return updated;
        });
    }

    @Override
    public CompletableFuture<Auction> buyNow(Player buyer, long auctionId) {
        return fetchAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
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
            return auction.soldTo(buyer.getUniqueId(), auction.buyNowPrice());
        })).thenCompose(updated -> repository.update(updated).thenApply(unused -> updated)).thenApply(updated -> {
            auctionCache.put(updated.id(), updated);
            processSale(updated);
            return updated;
        });
    }

    @Override
    public CompletableFuture<Boolean> cancelAuction(CommandSender actor, long auctionId) {
        return fetchAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
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
        })).thenCompose(updated -> repository.update(updated).thenApply(unused -> true)).thenApply(success -> {
            auctionCache.invalidate(auctionId);
            return success;
        });
    }

    public CompletableFuture<List<Auction>> claimable(UUID playerId) {
        return repository.claimable(playerId).thenApply(auctions -> auctions.stream()
                .sorted(Comparator.comparing(Auction::createdAt).reversed())
                .toList());
    }

    public CompletableFuture<Boolean> claim(Player player, Long targetAuctionId) {
        return claimable(player.getUniqueId()).thenCompose(auctions -> {
            List<Auction> targets = auctions;
            if (targetAuctionId != null) {
                targets = auctions.stream().filter(auction -> auction.id() == targetAuctionId).toList();
            }
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            List<CompletableFuture<Void>> updates = new ArrayList<>();
            for (Auction auction : targets) {
                updates.add(onMainThread(() -> applyClaim(player, auction)).thenCompose(updated -> repository.update(updated)));
            }
            return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new)).thenApply(unused -> true);
        });
    }

    @Override
    public CompletableFuture<List<Auction>> search(AuctionFilter filter) {
        return repository.search(filter, configManager.maxSearchResults()).thenApply(results -> results.stream()
                .sorted(filter.withPageDefaults().sortMode().comparator())
                .toList());
    }

    @Override
    public CompletableFuture<List<Auction>> playerListings(UUID playerId) {
        return repository.findBySeller(playerId).thenApply(results -> results.stream()
                .sorted(Comparator.comparing(Auction::createdAt).reversed())
                .toList());
    }

    @Override
    public Optional<Auction> cachedAuction(long auctionId) {
        return Optional.ofNullable(auctionCache.getIfPresent(auctionId));
    }

    @Override
    public void openBrowser(Player player) {
    }

    private void tickExpirations() {
        repository.expiringBefore(System.currentTimeMillis()).thenAccept(auctions -> auctions.forEach(auction -> onMainThread(() -> {
            if (auction.status() != AuctionStatus.ACTIVE) {
                return null;
            }
            Auction finalState = auction.highestBidderId() == null ? auction.withStatus(AuctionStatus.EXPIRED) : auction.withStatus(AuctionStatus.SOLD);
            repository.update(finalState);
            auctionCache.put(finalState.id(), finalState);
            if (finalState.status() == AuctionStatus.SOLD) {
                processSale(finalState);
                Bukkit.getPluginManager().callEvent(new AuctionWinEvent(finalState));
            } else {
                notificationService.notify(finalState.sellerId(), "messages.expired-auction", Map.of("item", finalState.item().getType().name()));
                Bukkit.getPluginManager().callEvent(new AuctionExpireEvent(finalState));
            }
            return null;
        })));
    }

    private void processSale(Auction auction) {
        double sellerCut = auction.currentBid() * Math.max(0.0D, 1.0D - configManager.taxRate() - configManager.commissionRate());
        notificationService.notify(auction.sellerId(), "messages.sold-auction", Map.of("item", auction.item().getType().name(), "amount", economyService.format(auction.currentBid())));
        if (auction.highestBidderId() != null) {
            notificationService.notify(auction.highestBidderId(), "messages.won-auction", Map.of("item", auction.item().getType().name()));
        }
        auditLogService.append(auction.sellerId(), "auction-sold", "id=" + auction.id() + ", sellerCut=" + sellerCut);
        repository.appendLog(auction.sellerId(), "auction-sold", "id=" + auction.id() + ", gross=" + auction.currentBid());
        if (configManager.notifyHighSales() && auction.currentBid() >= configManager.rareBroadcastThreshold()) {
            String sellerName = Optional.ofNullable(Bukkit.getOfflinePlayer(auction.sellerId()).getName()).orElse("Unknown");
            String itemTypeName = auction.item().getType().name();
            discordWebhookService.send(
                    applyWebhookPlaceholders(configManager.highSaleTitle(), sellerName, itemTypeName, auction),
                    applyWebhookPlaceholders(configManager.highSaleDescription(), sellerName, itemTypeName, auction)
            );
        }
    }

    private Auction applyClaim(Player player, Auction auction) {
        Auction updated = auction;
        if (auction.status() == AuctionStatus.SOLD && player.getUniqueId().equals(auction.sellerId()) && !auction.sellerClaimed()) {
            double sellerCut = auction.currentBid() * Math.max(0.0D, 1.0D - configManager.taxRate() - configManager.commissionRate());
            economyService.deposit(player, sellerCut);
            updated = updated.markSellerClaimed();
        }
        if (auction.status() == AuctionStatus.SOLD && auction.highestBidderId() != null && player.getUniqueId().equals(auction.highestBidderId()) && !auction.buyerClaimed()) {
            giveItem(player, auction.item());
            updated = updated.markBuyerClaimed();
        }
        if ((auction.status() == AuctionStatus.EXPIRED || auction.status() == AuctionStatus.CANCELLED) && player.getUniqueId().equals(auction.sellerId()) && !auction.sellerClaimed()) {
            giveItem(player, auction.item());
            updated = updated.markSellerClaimed();
        }
        if ((updated.sellerClaimed() && updated.buyerClaimed()) || ((updated.status() == AuctionStatus.EXPIRED || updated.status() == AuctionStatus.CANCELLED) && updated.sellerClaimed())) {
            updated = updated.withStatus(AuctionStatus.CLAIMED);
        }
        auctionCache.put(updated.id(), updated);
        return updated;
    }

    private void giveItem(Player player, ItemStack itemStack) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack.clone());
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
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
        return repository.findById(auctionId).thenApply(optional -> optional.orElseThrow(() -> new LocalizedException("messages.auction-not-found")));
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

    private String searchableText(ItemStack itemStack) {
        List<String> parts = new ArrayList<>();
        parts.add(itemStack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            if (meta.displayName() != null) {
                parts.add(PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT));
            }
            if (meta.hasLore() && meta.lore() != null) {
                parts.addAll(meta.lore().stream().map(component -> component.toString().toLowerCase(Locale.ROOT)).collect(Collectors.toList()));
            }
            meta.getEnchants().forEach((enchantment, level) -> parts.add(enchantment.getKey().getKey() + " " + level));
        }
        return String.join(" ", parts);
    }

    private void restoreFailedListing(Player seller, ItemStack item) {
        onMainThread(() -> {
            seller.getInventory().addItem(item);
            if (!seller.hasPermission("auctionhousepro.bypass.fees") && configManager.listingFee() > 0.0D) {
                economyService.deposit(seller, configManager.listingFee());
            }
            return null;
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to restore listed item after insert error: " + throwable.getMessage());
            return null;
        });
    }

    private void runCreateAuctionSideEffects(UUID sellerId, String sellerName, String itemTypeName, Auction inserted) {
        try {
            auctionCache.put(inserted.id(), inserted);
            auditLogService.append(sellerId, "auction-create", "id=" + inserted.id() + ", price=" + inserted.startingPrice());
            repository.appendLog(sellerId, "auction-create", "id=" + inserted.id());
            if (configManager.notifyRareListings() && inserted.displayPrice() >= configManager.rareBroadcastThreshold()) {
                discordWebhookService.send(
                        applyWebhookPlaceholders(configManager.rareListingTitle(), sellerName, itemTypeName, inserted),
                        applyWebhookPlaceholders(configManager.rareListingDescription(), sellerName, itemTypeName, inserted)
                );
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Auction created but post-create actions failed: " + exception.getMessage());
        }
    }

    private String applyWebhookPlaceholders(String template, String sellerName, String itemTypeName, Auction auction) {
        return template
                .replace("<seller>", sellerName)
                .replace("<item>", itemTypeName)
                .replace("<price>", String.format("%.2f", auction.displayPrice()))
                .replace("<amount>", String.format("%.2f", auction.currentBid()))
                .replace("<auction_id>", String.valueOf(auction.id()));
    }

    private record PendingAuctionInsert(Auction auction, String sellerName, String itemTypeName) {
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
}
