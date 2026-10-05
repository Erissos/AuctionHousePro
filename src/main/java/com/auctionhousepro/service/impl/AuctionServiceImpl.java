package com.auctionhousepro.service.impl;

import dev.desperis.integration.IntegrationService.Action;
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
import com.auctionhousepro.model.EconomyCredit;
import com.auctionhousepro.model.DeliveryPayload;
import com.auctionhousepro.economy.EconomyLedger;
import com.auctionhousepro.service.ListingEscrowStore;
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
    private final Map<String, CompletableFuture<?>> operationTails = new java.util.HashMap<>();
    private final EconomyLedger ledger;
    private final ListingEscrowStore escrow;
    private volatile boolean ready;
    private int paymentTaskId = -1;
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
        this.ledger = plugin.getEconomyLedger();
        this.escrow = new ListingEscrowStore(plugin);
    }

    public void startSchedulers() {
        shutdown();
        ledger.recover().thenCompose(unused -> onMainThread(() -> { escrow.recoverReservations(); return null; })).thenCompose(unused -> recoverEscrow()).whenComplete((unused,failure) -> {
            ready=failure==null;
            if (failure!=null) plugin.getLogger().severe("Payment recovery failed; monetary actions disabled: "+failure.getMessage());
        });
        expireTaskId=Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin,this::tickExpirations,Math.max(1,configManager.expireCheckTicks()),Math.max(1,configManager.expireCheckTicks()));
        paymentTaskId=Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin,() -> {
            if (ready) ledger.processQueued().exceptionally(failure -> { plugin.getLogger().warning("Pending payment processing failed: "+failure.getMessage()); return null; });
        },100,100);
    }

    public void rescheduleTimers() {
        if (expireTaskId!=-1) Bukkit.getScheduler().cancelTask(expireTaskId);
        expireTaskId=Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin,this::tickExpirations,Math.max(1,configManager.expireCheckTicks()),Math.max(1,configManager.expireCheckTicks()));
    }

    public void releasePlayer(UUID player) { bidCooldowns.remove(player); listingCooldowns.remove(player); }

    public void shutdown() {
        ready=false;
        if (expireTaskId!=-1) Bukkit.getScheduler().cancelTask(expireTaskId);
        if (paymentTaskId!=-1) Bukkit.getScheduler().cancelTask(paymentTaskId);
        expireTaskId=-1; paymentTaskId=-1;
    }

    @Override
    public void warmupCache() {
        repository.activeAuctions().thenAccept(auctions -> auctions.forEach(auction -> auctionCache.put(auction.id(), auction.refreshFeaturedScore()))).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to preload auction cache: " + throwable.getMessage());
            return null;
        });
    }

    @Override
    public CompletableFuture<Auction> createAuction(Player seller,ItemStack item,Duration duration,double startPrice,double buyNowPrice,double bidIncrement) {
        if (item==null || item.getType().isAir()) return CompletableFuture.failedFuture(new LocalizedException("messages.hold-item"));
        if (startPrice<=0 || !EconomyLedger.valid(startPrice) || !EconomyLedger.valid(buyNowPrice) || !EconomyLedger.valid(bidIncrement) || bidIncrement<=0 || (buyNowPrice>0 && buyNowPrice<startPrice)) return CompletableFuture.failedFuture(new LocalizedException("messages.invalid-number"));
        if (duration==null || duration.toMinutes()<configManager.minDurationMinutes() || duration.toMinutes()>configManager.maxDurationMinutes()) return CompletableFuture.failedFuture(new LocalizedException("messages.invalid-duration"));
        ItemStack snapshot=item.clone();
        int slot=seller.getInventory().getHeldItemSlot();
        UUID sellerId=seller.getUniqueId();
        return serial("player:"+sellerId,() -> {
            requireReady();
            if (isCoolingDown(sellerId,listingCooldowns,configManager.listCooldownMillis())) return CompletableFuture.failedFuture(new LocalizedException("messages.listing-cooldown"));
            return repository.findBySeller(sellerId).thenCompose(existing -> onMainThread(() -> {
                requireTrade(seller);
                if (!seller.isOnline() || seller.getInventory().getHeldItemSlot()!=slot || !snapshot.equals(seller.getInventory().getItemInMainHand())) throw new LocalizedException("messages.hold-item");
                if (!isAllowedItem(snapshot)) throw new LocalizedException("messages.item-blocked");
                if (existing.stream().filter(a -> a.status()==AuctionStatus.ACTIVE).count()>=configManager.maxActiveListings(seller)) throw new LocalizedException("messages.max-active-listings");
                Auction auction=new Auction(0,sellerId,null,snapshot,buyNowPrice>0 ? AuctionType.HYBRID : AuctionType.BID,AuctionStatus.ACTIVE,AuctionCategory.fromMaterial(snapshot.getType()),startPrice,0,buyNowPrice,bidIncrement,Instant.now(),Instant.now().plus(duration),false,false,SearchTextUtil.build(snapshot),0,0,0,0).refreshFeaturedScore();
                AuctionCreateEvent event=new AuctionCreateEvent(seller,auction); Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) throw new LocalizedException("messages.action-cancelled");
                double fee=seller.hasPermission("auctionhousepro.bypass.fees") ? 0 : configManager.listingFee(seller);
                if (!EconomyLedger.valid(fee)) throw new LocalizedException("messages.invalid-number");
                return new PendingAuctionInsert(auction,seller.getName(),snapshot.getType().name(),fee,slot,snapshot);
            })).thenCompose(pending -> withDebit(seller,pending.listingFee(),debit -> onMainThread(() -> {
                requireTrade(seller);
                if (!seller.isOnline() || seller.getInventory().getHeldItemSlot()!=slot || !snapshot.equals(seller.getInventory().getItemInMainHand())) throw new LocalizedException("messages.hold-item");
                if (escrow.pending().stream().anyMatch(entry -> entry.seller().equals(sellerId) && "REVIEW".equals(entry.state()))) throw new LocalizedException("messages.transaction-pending");
                UUID escrowId=escrow.prepare(sellerId,snapshot,debit);
                seller.getInventory().setItemInMainHand(null);
                try { escrow.mark(escrowId,"REMOVED"); }
                catch (RuntimeException failure) {
                    // Removal and this compensation are both on the server thread; the hand cannot change in between.
                    seller.getInventory().setItemInMainHand(snapshot.clone());
                    try { escrow.mark(escrowId,"RESTORED"); } catch (RuntimeException markFailure) { plugin.getLogger().severe("Escrow requires review but hand was restored: "+escrowId); }
                    throw failure;
                }
                return escrowId;
            }).thenCompose(escrowId -> repository.insertWithReceipt(pending.auction(),debit).handle((inserted,failure) -> {
                if (failure!=null) return restoreEscrow(escrowId,sellerId,snapshot,debit).handle((unused,restoreFailure) -> {
                    if (restoreFailure!=null) plugin.getLogger().severe("Listing compensation pending in escrow "+escrowId+": "+restoreFailure.getMessage());
                    throw new CompletionException(failure);
                }).thenApply(unused -> inserted);
                cacheUpdated(inserted);
                return onMainThread(() -> {
                    try { escrow.mark(escrowId,"COMMITTED"); } catch (RuntimeException journalFailure) { plugin.getLogger().warning("Listing receipt committed; escrow will reconcile on startup: "+escrowId); }
                    runCreateAuctionSideEffects(sellerId,pending.sellerName(),pending.itemTypeName(),inserted); return inserted;
                });
            }).thenCompose(future -> future))));
        });
    }

    @Override
    public CompletableFuture<Auction> placeBid(Player bidder,long auctionId,double amount) {
        long started=System.currentTimeMillis();
        if (auctionId<=0 || !EconomyLedger.valid(amount) || amount<=0) return CompletableFuture.failedFuture(new LocalizedException("messages.invalid-number"));
        UUID playerId=bidder.getUniqueId();
        return serial("player:"+playerId,() -> withAuctionLock(auctionId,() -> {
            requireReady();
            if (isCoolingDown(playerId,bidCooldowns,configManager.bidCooldownMillis())) return CompletableFuture.failedFuture(new LocalizedException("messages.bid-cooldown"));
            return freshAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
                requireActive(auction);
                if (auction.sellerId().equals(playerId)) throw new LocalizedException("messages.cannot-bid-own");
                if (amount<auction.minimumNextBid()) throw new LocalizedException("messages.bid-too-low",Map.of("amount",String.format(Locale.US,"%.2f",auction.minimumNextBid())));
                requireTrade(bidder);
                AuctionBidEvent event=new AuctionBidEvent(bidder,auction,amount); Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) throw new LocalizedException("messages.action-cancelled");
                return auction;
            })).thenCompose(auction -> withDebit(bidder,amount,debit -> {
                Instant expiry=auction.expiresAt();
                if (Duration.between(Instant.now(),expiry).toSeconds()<=configManager.antiSnipeWindowSeconds()) expiry=expiry.plusSeconds(configManager.antiSnipeExtensionSeconds());
                Auction updated=auction.withBid(playerId,amount,expiry).incrementBidCount().refreshFeaturedScore();
                return transition(auction,updated,bidRefund(auction,"bid:"+debit),null,null,debit).thenApply(result -> {
                    telemetryService.markBid(System.currentTimeMillis()-started);
                    marketRepository.recordBid(auctionId,playerId,amount).exceptionally(failure -> { plugin.getLogger().warning("Bid history save failed: "+failure.getMessage()); return null; });
                    auditLogService.append(playerId,"auction-bid","id="+auctionId+", bid="+amount);
                    notifyWatchers(result,playerId).exceptionally(failure -> null);
                    ledger.processQueued().exceptionally(failure -> null);
                    return result;
                });
            }));
        }));
    }

    @Override
    public CompletableFuture<Auction> buyNow(Player buyer,long auctionId) {
        UUID playerId=buyer.getUniqueId();
        return serial("player:"+playerId,() -> withAuctionLock(auctionId,() -> {
            requireReady();
            return freshAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
                requireActive(auction);
                if (!auction.hasBuyNow() || !EconomyLedger.valid(auction.buyNowPrice())) throw new LocalizedException("messages.buy-now-unavailable");
                if (auction.sellerId().equals(playerId)) throw new LocalizedException("messages.cannot-buy-own");
                requireTrade(buyer);
                return auction;
            })).thenCompose(auction -> withDebit(buyer,auction.buyNowPrice(),debit -> transition(auction,auction.soldTo(playerId,auction.buyNowPrice()),bidRefund(auction,"buy:"+debit),null,null,debit)))
                .thenApply(updated -> { ledger.processQueued().exceptionally(failure -> null); processSale(updated); return updated; });
        }));
    }

    @Override
    public CompletableFuture<Boolean> cancelAuction(CommandSender actor,long auctionId) {
        return withAuctionLock(auctionId,() -> {
            requireReady();
            return freshAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
                requireActive(auction);
                if (!actor.hasPermission("auctionhousepro.admin") && (!(actor instanceof Player p) || !auction.sellerId().equals(p.getUniqueId()))) throw new LocalizedException("messages.cannot-cancel-auction");
                AuctionCancelEvent event=new AuctionCancelEvent(actor,auction); Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) throw new LocalizedException("messages.action-cancelled");
                return auction;
            })).thenCompose(auction -> transition(auction,auction.withStatus(AuctionStatus.CANCELLED),bidRefund(auction,"cancel:"+auctionId),null,null,null))
                .thenApply(updated -> { ledger.processQueued().exceptionally(failure -> null); return true; });
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
    public CompletableFuture<Boolean> claim(Player player,Long targetAuctionId) {
        long started=System.currentTimeMillis();
        UUID id=player.getUniqueId();
        return serial("player:"+id,() -> {
            requireReady();
            return claimable(id).thenCompose(auctions -> {
                CompletableFuture<Boolean> chain=CompletableFuture.completedFuture(false);
                for (Auction auction:auctions) if (targetAuctionId==null || auction.id()==targetAuctionId) {
                    chain=chain.thenCompose(changed -> claimSingle(player,auction.id()).thenApply(next -> changed||next));
                }
                return chain;
            }).thenCompose(changed -> claimDeliveryUnlocked(player,null).thenApply(delivered -> changed||delivered))
                .thenApply(success -> { if (success) telemetryService.markClaim(System.currentTimeMillis()-started); ledger.processQueued().exceptionally(failure -> null); return success; });
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
    public CompletableFuture<Boolean> claimDelivery(Player player,Long deliveryId) {
        return serial("player:"+player.getUniqueId(),() -> claimDeliveryUnlocked(player,deliveryId));
    }

    @Override
    public CompletableFuture<AuctionOffer> createOffer(Player buyer,long auctionId,double amount) {
        if (!EconomyLedger.valid(amount) || amount<=0) return CompletableFuture.failedFuture(new LocalizedException("messages.invalid-number"));
        UUID id=buyer.getUniqueId();
        return serial("player:"+id,() -> withAuctionLock(auctionId,() -> {
            requireReady();
            return freshAuction(auctionId).thenCompose(auction -> onMainThread(() -> {
                requireActive(auction);
                if (auction.sellerId().equals(id)) throw new LocalizedException("messages.cannot-buy-own");
                requireTrade(buyer);
                if (amount<auction.displayPrice()) throw new LocalizedException("messages.offer-too-low",Map.of("amount",String.format(Locale.US,"%.2f",auction.displayPrice())));
                return auction;
            })).thenCompose(auction -> withDebit(buyer,amount,debit -> marketRepository.createOfferWithReceipt(auctionId,auction.sellerId(),id,amount,debit)));
        }));
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
    public CompletableFuture<Boolean> respondToOffer(CommandSender actor,long offerId,boolean accept) {
        return marketRepository.findOffer(offerId).thenCompose(optional -> {
            AuctionOffer original=optional.orElseThrow(() -> new LocalizedException("messages.offer-not-found"));
            return withAuctionLock(original.auctionId(),() -> marketRepository.findOffer(offerId).thenCompose(current ->
                handleOfferResponse(actor,current.orElseThrow(() -> new LocalizedException("messages.offer-not-found")),accept)));
        });
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
    public CompletableFuture<Boolean> forceExpire(CommandSender actor,long auctionId) {
        return withAuctionLock(auctionId,() -> onMainThread(() -> {
            if (!actor.hasPermission("auctionhousepro.admin")) throw new LocalizedException("messages.no-permission"); requireReady(); return true;
        }).thenCompose(unused -> freshAuction(auctionId)).thenCompose(auction -> {
            if (auction.status()!=AuctionStatus.ACTIVE) throw new LocalizedException("messages.auction-inactive");
            Auction updated=auction.withStatus(auction.highestBidderId()==null ? AuctionStatus.EXPIRED : AuctionStatus.SOLD);
            return transition(auction,updated,List.of(),null,null,null);
        }).thenApply(updated -> { ledger.processQueued().exceptionally(failure -> null); if (updated.status()==AuctionStatus.SOLD) processSale(updated); return true; }));
    }

    @Override
    public CompletableFuture<Boolean> returnListing(CommandSender actor,long auctionId) {
        return withAuctionLock(auctionId,() -> onMainThread(() -> {
            if (!actor.hasPermission("auctionhousepro.admin")) throw new LocalizedException("messages.no-permission"); requireReady(); return true;
        }).thenCompose(unused -> freshAuction(auctionId)).thenCompose(auction -> {
            if (auction.status()!=AuctionStatus.ACTIVE) throw new LocalizedException("messages.auction-inactive");
            Auction updated=auction.markSellerClaimed().withStatus(AuctionStatus.CLAIMED);
            return transition(auction,updated,bidRefund(auction,"return:"+auctionId),null,new DeliveryPayload(auction.sellerId(),auction.item(),auction.id(),"admin-return"),null);
        }).thenApply(updated -> { ledger.processQueued().exceptionally(failure -> null); return true; }));
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

    private CompletableFuture<Boolean> handleOfferResponse(CommandSender actor,AuctionOffer offer,boolean accept) {
        requireReady();
        return onMainThread(() -> {
            UUID id=actor instanceof Player player ? player.getUniqueId() : null;
            boolean admin=actor.hasPermission("auctionhousepro.admin"), seller=offer.sellerId().equals(id), buyer=offer.buyerId().equals(id);
            if (!admin && !seller && !buyer || accept && !admin && !seller) throw new LocalizedException("messages.no-permission");
            if (accept && actor instanceof Player player) requireTrade(player);
            if (offer.status()!=AuctionOfferStatus.PENDING) throw new LocalizedException("messages.offer-no-longer-pending");
            return buyer&&!seller&&!admin ? AuctionOfferStatus.CANCELLED : AuctionOfferStatus.REJECTED;
        }).thenCompose(rejection -> {
            if (!accept) return marketRepository.rejectOfferWithRefund(offer.id(),rejection).thenApply(changed -> { ledger.processQueued().exceptionally(failure -> null); return changed; });
            return freshAuction(offer.auctionId()).thenCompose(auction -> onMainThread(() -> {
                requireActive(auction);
                if (actor instanceof Player player) requireTrade(player);
                return auction;
            })).thenCompose(auction -> {
                return transition(auction,auction.soldTo(offer.buyerId(),offer.amount()),bidRefund(auction,"offer:"+offer.id()),offer.id(),null,null);
            }).thenApply(updated -> { ledger.processQueued().exceptionally(failure -> null); processSale(updated); return true; });
        });
    }



    private void tickExpirations() {
        if (!ready) return;
        repository.expiringBefore(System.currentTimeMillis()).thenAccept(auctions -> {
            for (Auction original:auctions) withAuctionLock(original.id(),() -> freshAuction(original.id()).thenCompose(current -> {
                if (current.status()!=AuctionStatus.ACTIVE || !current.isExpired()) return CompletableFuture.completedFuture(null);
                Auction updated=current.withStatus(current.highestBidderId()==null ? AuctionStatus.EXPIRED : AuctionStatus.SOLD);
                return transition(current,updated,List.of(),null,null,null);
            }).thenCompose(updated -> {
                if (updated==null) return CompletableFuture.completedFuture(null);
                ledger.processQueued().exceptionally(failure -> null);
                return onMainThread(() -> {
                    if (updated.status()==AuctionStatus.SOLD) { processSale(updated); Bukkit.getPluginManager().callEvent(new AuctionWinEvent(updated)); }
                    else { notificationService.notifyItem(updated.sellerId(),"messages.expired-auction",updated.item(),Map.of()); Bukkit.getPluginManager().callEvent(new AuctionExpireEvent(updated)); }
                    return null;
                });
            })).exceptionally(failure -> { plugin.getLogger().warning("Expiry transition failed: "+failure.getMessage()); return null; });
        }).exceptionally(failure -> { plugin.getLogger().warning("Expiry query failed: "+failure.getMessage()); return null; });
    }

    private void processSale(Auction auction) {
        if (!Bukkit.isPrimaryThread()) {
            onMainThread(() -> { processSale(auction); return null; }).exceptionally(failure -> { plugin.getLogger().warning("Sale notification failed: " + failure.getMessage()); return null; }); return;
        }
        OfflinePlayer seller = Bukkit.getOfflinePlayer(auction.sellerId());
        telemetryService.markSale(0);
        double sellerCut = auction.currentBid() * Math.max(0.0D, 1.0D - configManager.taxRate(seller) - configManager.commissionRate(seller));
        notificationService.notifyItem(auction.sellerId(), "messages.sold-auction", auction.item(), Map.of("amount", economyService.format(auction.currentBid())));
        if (auction.highestBidderId() != null) {
            notificationService.notifyItem(auction.highestBidderId(), "messages.won-auction", auction.item(), Map.of());
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
            notificationService.broadcastItem("messages.high-sale-broadcast", auction.item(), Map.of("seller", sellerName, "amount", economyService.format(auction.currentBid())));
        }
    }



    private CompletableFuture<Void> notifyWatchers(Auction auction, UUID actorId) {
        List<CompletableFuture<Void>> notifications = new ArrayList<>();
        return marketRepository.watchSubscriptions(auction.id()).thenCompose(subscriptions -> onMainThread(() -> {
            for (WatchSubscription subscription : subscriptions) {
                if (subscription.playerId().equals(actorId)) {
                    continue;
                }
                if (subscription.targetPrice() != null && auction.displayPrice() >= subscription.targetPrice()) {
                    notificationService.notifyItem(subscription.playerId(), "messages.watch-target-reached", auction.item(), Map.of("amount", economyService.format(auction.displayPrice())));
                    notifications.add(marketRepository.clearWatchTarget(subscription.playerId(), auction.id()));
                }
            }
            return CompletableFuture.allOf(notifications.toArray(CompletableFuture[]::new));
        })).thenCompose(future -> future);
    }

    private boolean tryClaimDelivery(Player player,DeliveryBoxEntry entry) {
        if (!player.isOnline() || !fits(player,entry.item())) return false;
        Map<Integer,ItemStack> leftovers=player.getInventory().addItem(entry.item().clone());
        if (!leftovers.isEmpty()) throw new IllegalStateException("Inventory changed during reserved delivery "+entry.id());
        return true;
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
                notificationService.broadcastItem("messages.rare-listing-broadcast", inserted.item(), Map.of("seller", sellerName, "amount", economyService.format(inserted.displayPrice())));
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

    private <T> CompletableFuture<T> withAuctionLock(long auctionId,Supplier<CompletableFuture<T>> action) {
        return serial("auction:"+auctionId,action);
    }

    private <T> CompletableFuture<T> onMainThread(Supplier<T> supplier) {
        CompletableFuture<T> future=new CompletableFuture<>();
        if (!plugin.isEnabled()) { future.completeExceptionally(new IllegalStateException("Plugin stopped")); return future; }
        Runnable action=() -> { try { future.complete(supplier.get()); } catch (Throwable failure) { future.completeExceptionally(failure); } };
        if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(plugin,action);
        return future;
    }

    private record PendingAuctionInsert(Auction auction, String sellerName, String itemTypeName, double listingFee, int slot, ItemStack item) {
    }

    public CompletableFuture<List<String>> reviewDeliveries() { return marketRepository.reviewDeliveries(); }
    public CompletableFuture<Void> reconcileDelivery(long id,boolean delivered) { return marketRepository.reconcileDelivery(id,delivered); }
    public List<String> reviewEscrow() { return escrow.pending().stream().filter(entry -> "REVIEW".equals(entry.state())).map(entry -> entry.id()+" "+entry.seller()).toList(); }
    public CompletableFuture<Void> reconcileEscrow(UUID id, boolean itemRemoved) {
        ListingEscrowStore.Entry entry=escrow.pending().stream().filter(value -> value.id().equals(id) && "REVIEW".equals(value.state())).findFirst().orElseThrow();
        return serial("player:"+entry.seller(), () -> itemRemoved ? restoreEscrow(entry.id(),entry.seller(),entry.item(),entry.debitId()) : onMainThread(() -> { escrow.mark(id,"RESTORED"); return null; }));
    }

    private void requireReady() { if (!ready) throw new LocalizedException("messages.transaction-pending"); }
    private void requireActive(Auction auction) {
        if (auction.status()!=AuctionStatus.ACTIVE || auction.isExpired()) throw new LocalizedException("messages.auction-inactive");
    }
    private CompletableFuture<Auction> freshAuction(long id) {
        if (id<=0) return CompletableFuture.failedFuture(new LocalizedException("messages.invalid-number"));
        return repository.findById(id).thenApply(found -> found.orElseThrow(() -> new LocalizedException("messages.auction-not-found")));
    }
    private <T> CompletableFuture<T> serial(String key,Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> result;
        synchronized (operationTails) {
            CompletableFuture<?> previous=operationTails.getOrDefault(key,CompletableFuture.completedFuture(null));
            result=previous.handle((unused,failure) -> null).thenComposeAsync(unused -> action.get());
            operationTails.put(key,result);
        }
        result.whenComplete((unused,failure) -> { synchronized (operationTails) { operationTails.remove(key,result); } });
        return result;
    }
    private CompletableFuture<Auction> transition(Auction before,Auction after,List<EconomyCredit> credits,Long acceptedOffer,DeliveryPayload delivery,String debit) {
        return repository.transition(before,after,credits,acceptedOffer,delivery,debit).thenApply(changed -> {
            if (!changed) { auctionCache.invalidate(before.id()); throw new LocalizedException("messages.auction-inactive"); }
            cacheUpdated(after); return after;
        });
    }
    private List<EconomyCredit> bidRefund(Auction auction,String suffix) {
        return auction.highestBidderId()==null || auction.currentBid()<=0 ? List.of()
            : List.of(new EconomyCredit("bid-refund:"+auction.id()+":"+suffix,auction.highestBidderId(),auction.currentBid()));
    }
    private void requireTrade(Player player) {
        if (!player.isOnline() || !plugin.getIntegrations().allows(player, player.getLocation(), Action.TRADE))
            throw new LocalizedException("messages.integration-denied");
    }

    private <T> CompletableFuture<T> withDebit(Player player,double amount,java.util.function.Function<String,CompletableFuture<T>> work) {
        return ledger.debit(player.getUniqueId(),amount, () -> requireTrade(player)).thenCompose(id -> {
            CompletableFuture<T> task;
            try { task=onMainThread(() -> { requireTrade(player); return null; }).thenCompose(unused -> work.apply(id)); } catch (Throwable failure) { task=CompletableFuture.failedFuture(failure); }
            return task.handle((result,failure) -> {
                if (failure==null) return CompletableFuture.completedFuture(result);
                return ledger.refund(id).handle((unused,refundFailure) -> {
                    if (refundFailure!=null) plugin.getLogger().severe("Refund retained in ledger "+id+": "+refundFailure.getMessage());
                    throw new CompletionException(failure);
                }).thenApply(unused -> result);
            }).thenCompose(future -> future);
        });
    }
    private CompletableFuture<Boolean> claimSingle(Player player,long id) {
        return withAuctionLock(id,() -> freshAuction(id).thenCompose(auction -> onMainThread(() -> {
            UUID actor=player.getUniqueId(); Auction updated=auction; DeliveryPayload delivery=null; List<EconomyCredit> credits=List.of();
            if (auction.status()==AuctionStatus.SOLD && actor.equals(auction.sellerId()) && !auction.sellerClaimed()) {
                double cut=auction.currentBid()*Math.max(0,1-configManager.taxRate(player)-configManager.commissionRate(player));
                credits=List.of(new EconomyCredit("seller-claim:"+id,actor,cut)); updated=updated.markSellerClaimed();
            } else if (auction.status()==AuctionStatus.SOLD && actor.equals(auction.highestBidderId()) && !auction.buyerClaimed()) {
                delivery=new DeliveryPayload(actor,auction.item(),id,"won-auction"); updated=updated.markBuyerClaimed();
            } else if ((auction.status()==AuctionStatus.EXPIRED || auction.status()==AuctionStatus.CANCELLED) && actor.equals(auction.sellerId()) && !auction.sellerClaimed()) {
                delivery=new DeliveryPayload(actor,auction.item(),id,"returned-auction"); updated=updated.markSellerClaimed();
            } else return null;
            if (updated.sellerClaimed() && (updated.buyerClaimed() || updated.status()!=AuctionStatus.SOLD)) updated=updated.withStatus(AuctionStatus.CLAIMED);
            return new ClaimMutation(auction,updated,credits,delivery);
        })).thenCompose(mutation -> mutation==null ? CompletableFuture.completedFuture(false)
            : repository.transition(mutation.before(),mutation.after(),mutation.credits(),null,mutation.delivery(),null)
                .thenApply(changed -> { if (changed) cacheUpdated(mutation.after()); else auctionCache.invalidate(id); return changed; })));
    }
    private CompletableFuture<Boolean> claimDeliveryUnlocked(Player player,Long deliveryId) {
        UUID actor=player.getUniqueId();
        return marketRepository.deliveries(actor).thenCompose(entries -> {
            CompletableFuture<Boolean> chain=CompletableFuture.completedFuture(false);
            for (DeliveryBoxEntry entry:entries) if (deliveryId==null || entry.id()==deliveryId) {
                chain=chain.thenCompose(changed -> onMainThread(() -> player.isOnline() && fits(player,entry.item()))
                    .thenCompose(capacity -> !capacity ? CompletableFuture.completedFuture(false) : marketRepository.reserveDelivery(entry.id(),actor)
                        .thenCompose(reserved -> !reserved ? CompletableFuture.completedFuture(false) : onMainThread(() -> tryClaimDelivery(player,entry))
                            .thenCompose(delivered -> (delivered ? marketRepository.removeDelivery(entry.id()) : marketRepository.releaseDelivery(entry.id())).thenApply(unused -> delivered))))
                    .thenApply(delivered -> changed||delivered));
            }
            return chain;
        });
    }
    private boolean fits(Player player,ItemStack item) {
        int capacity=0;
        for (ItemStack slot:player.getInventory().getStorageContents()) {
            if (slot==null || slot.getType().isAir()) capacity+=Math.min(item.getMaxStackSize(),player.getInventory().getMaxStackSize());
            else if (slot.isSimilar(item)) capacity+=Math.max(0,Math.min(slot.getMaxStackSize(),player.getInventory().getMaxStackSize())-slot.getAmount());
            if (capacity>=item.getAmount()) return true;
        }
        return false;
    }
    private CompletableFuture<Void> restoreEscrow(UUID id,UUID seller,ItemStack item,String debit) {
        return ledger.state(debit).thenCompose(state -> {
            if ("COMMITTED".equals(state)) return onMainThread(() -> { escrow.mark(id,"COMMITTED"); return null; });
            return marketRepository.storeDeliveryOnce(seller,item,"listing-escrow:"+id,"failed-listing")
                .thenCompose(unused -> onMainThread(() -> { escrow.mark(id,"RESTORED"); notificationService.notify(seller,"messages.delivery-box-stored",Map.of("count","1")); return null; }));
        });
    }
    private CompletableFuture<Void> recoverEscrow() {
        return onMainThread(escrow::pending).thenCompose(entries -> {
            CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
            for (var entry:entries) {
                if ("REMOVED".equals(entry.state())) chain=chain.thenCompose(unused -> restoreEscrow(entry.id(),entry.seller(),entry.item(),entry.debitId()));
                else plugin.getLogger().severe("Listing inventory outcome requires review: listing-escrow/"+entry.id()+".yml");
            }
            return chain;
        });
    }
    private record ClaimMutation(Auction before,Auction after,List<EconomyCredit> credits,DeliveryPayload delivery) { }

}
