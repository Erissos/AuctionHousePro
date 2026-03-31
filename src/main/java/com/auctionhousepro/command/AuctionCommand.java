package com.auctionhousepro.command;

import com.auctionhousepro.config.ConfigManager;
import com.auctionhousepro.gui.GuiManager;
import com.auctionhousepro.i18n.LocaleManager;
import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionBidRecord;
import com.auctionhousepro.model.AuctionCategory;
import com.auctionhousepro.model.AuctionFilter;
import com.auctionhousepro.model.AuctionOffer;
import com.auctionhousepro.model.AuctionSortMode;
import com.auctionhousepro.model.DeliveryBoxEntry;
import com.auctionhousepro.model.MarketStatsSnapshot;
import com.auctionhousepro.model.SellerProfile;
import com.auctionhousepro.service.impl.AuctionServiceImpl;
import com.auctionhousepro.util.DurationParser;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class AuctionCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final AuctionServiceImpl auctionService;
    private final GuiManager guiManager;
    private final LocaleManager localeManager;
    private final ConfigManager configManager;

    public AuctionCommand(AuctionServiceImpl auctionService, GuiManager guiManager, LocaleManager localeManager, ConfigManager configManager) {
        this.auctionService = auctionService;
        this.guiManager = guiManager;
        this.localeManager = localeManager;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage(localeManager.string(configManager.defaultLocale(), "messages.player-only"));
            return true;
        }
        if (!player.hasPermission("auctionhousepro.use")) {
            player.sendMessage(localeManager.message(player, "messages.no-permission"));
            return true;
        }
        if (args.length == 0) {
            guiManager.openBrowser(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> handleSell(player, args);
            case "bid" -> handleBid(player, args);
            case "buy" -> handleBuy(player, args);
            case "claim" -> handleClaim(player, args);
            case "delivery" -> handleDelivery(player, args);
            case "locale" -> handleLocale(player, args);
            case "listings" -> guiManager.openPlayerListings(player);
            case "claims" -> guiManager.openClaims(player);
            case "search" -> handleSearch(player, args);
            case "watch" -> handleWatch(player, args);
            case "watched" -> guiManager.openWatched(player);
            case "detail" -> handleDetail(player, args);
            case "profile" -> handleProfile(player, args);
            case "history" -> handleHistory(player, args);
            case "offer" -> handleOffer(player, args);
            case "offers" -> handleOffers(player, args);
            case "admin" -> handleAdmin(player, args);
            case "help" -> localeManager.messageList(player, "messages.help").forEach(player::sendMessage);
            default -> player.sendMessage(localeManager.message(player, "messages.unknown-subcommand"));
        }
        return true;
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(localeManager.message(player, "messages.usage-sell"));
            return;
        }
        double startPrice = parseDouble(args[1]);
        if (startPrice < 0.0D) {
            player.sendMessage(localeManager.message(player, "messages.invalid-number"));
            return;
        }
        double buyNow = args.length >= 3 ? parseDouble(args[2]) : 0.0D;
        Duration duration = args.length >= 4 ? DurationParser.parse(args[3]) : Duration.ofMinutes(configManager.defaultDurationMinutes());
        if (duration == null) {
            player.sendMessage(localeManager.message(player, "messages.invalid-duration"));
            return;
        }
        double increment = args.length >= 5 ? parseDouble(args[4]) : Math.max(1.0D, Math.floor(startPrice * 0.05D));
        if (buyNow < 0.0D || increment <= 0.0D) {
            player.sendMessage(localeManager.message(player, "messages.invalid-number"));
            return;
        }

        ItemStack itemStack = player.getInventory().getItemInMainHand();
        auctionService.createAuction(player, itemStack, duration, startPrice, buyNow, increment)
            .thenAccept(auction -> runSync(() -> player.sendMessage(localeManager.message(player, "messages.auction-created",
                Placeholder.parsed("item", auction.item().getType().name()),
                Placeholder.parsed("price", String.format("%.2f", auction.displayPrice()))))))
                .exceptionally(throwable -> {
                runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                    return null;
                });
    }

    private void handleBid(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(localeManager.message(player, "messages.usage-bid"));
            return;
        }
        long auctionId = (long) parseDouble(args[1]);
        double amount = parseDouble(args[2]);
        auctionService.placeBid(player, auctionId, amount)
            .thenAccept(auction -> runSync(() -> player.sendMessage(localeManager.message(player, "messages.bid-success",
                Placeholder.parsed("amount", String.format("%.2f", auction.currentBid()))))))
                .exceptionally(throwable -> {
                runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                    return null;
                });
    }

    private void handleBuy(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(localeManager.message(player, "messages.usage-buy"));
            return;
        }
        long auctionId = (long) parseDouble(args[1]);
        auctionService.buyNow(player, auctionId)
            .thenAccept(auction -> runSync(() -> player.sendMessage(localeManager.message(player, "messages.buy-now-success",
                Placeholder.parsed("item", auction.item().getType().name()),
                Placeholder.parsed("amount", String.format("%.2f", auction.currentBid()))))))
                .exceptionally(throwable -> {
                runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                    return null;
                });
    }

    private void handleClaim(Player player, String[] args) {
        Long targetId = args.length >= 2 ? (long) parseDouble(args[1]) : null;
        auctionService.claim(player, targetId).thenAccept(success -> {
            if (!success) {
                runSync(() -> player.sendMessage(localeManager.message(player, "messages.no-claimable-auctions")));
                return;
            }
            runSync(() -> player.sendMessage(localeManager.message(player, "messages.claim-completed")));
        }).exceptionally(throwable -> {
            runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
            return null;
        });
    }

    private void handleDelivery(Player player, String[] args) {
        if (args.length >= 2) {
            Long deliveryId = (long) parseDouble(args[1]);
            auctionService.claimDelivery(player, deliveryId).thenAccept(success -> runSync(() -> player.sendMessage(localeManager.message(player, success ? "messages.delivery-claimed" : "messages.delivery-empty")))).exceptionally(throwable -> {
                runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                return null;
            });
            return;
        }

        auctionService.deliveryBox(player.getUniqueId()).thenAccept(entries -> runSync(() -> sendDeliverySummary(player, entries))).exceptionally(throwable -> {
            runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
            return null;
        });
    }

    private void handleLocale(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(localeManager.message(player, "messages.available-locales", Placeholder.parsed("locales", String.join(", ", localeManager.availableLocales()))));
            return;
        }
        localeManager.setPlayerLocale(player.getUniqueId(), args[1]);
        player.sendMessage(localeManager.message(player, "messages.locale-changed", Placeholder.parsed("locale", args[1])));
    }

    private void handleSearch(Player player, String[] args) {
        if (args.length < 2) {
            guiManager.openBrowser(player);
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        guiManager.openBrowser(player, AuctionFilter.defaultFilter().withQuery(query));
    }

    private void handleWatch(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(localeManager.message(player, "messages.usage-watch"));
            return;
        }
        long auctionId = (long) parseDouble(args[1]);
        Double targetPrice = args.length >= 3 ? parsePositive(args[2]) : null;
        auctionService.toggleWatch(player.getUniqueId(), auctionId, targetPrice).thenAccept(watching -> runSync(() -> player.sendMessage(localeManager.message(player, watching ? "messages.watch-added" : "messages.watch-removed",
                Placeholder.parsed("id", String.valueOf(auctionId)),
                Placeholder.parsed("amount", targetPrice == null ? "-" : String.format(Locale.US, "%.2f", targetPrice)))))).exceptionally(throwable -> {
            runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
            return null;
        });
    }

    private void handleDetail(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(localeManager.message(player, "messages.usage-detail"));
            return;
        }
        long auctionId = (long) parseDouble(args[1]);
        auctionService.findAuction(auctionId).thenCompose(optional -> optional.map(auction -> auctionService.bidHistory(auctionId, 5).thenApply(history -> new AuctionDetailPayload(auction, history))).orElseGet(() -> java.util.concurrent.CompletableFuture.failedFuture(new com.auctionhousepro.exception.LocalizedException("messages.auction-not-found")))).thenAccept(payload -> runSync(() -> sendAuctionDetail(player, payload.auction(), payload.history()))).exceptionally(throwable -> {
            runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
            return null;
        });
    }

    private void handleProfile(Player player, String[] args) {
        UUID targetId = args.length >= 2 ? resolvePlayerId(args[1]).orElse(player.getUniqueId()) : player.getUniqueId();
        auctionService.sellerProfile(targetId).thenCompose(profile -> auctionService.recentSales(targetId, 5).thenApply(sales -> new SellerProfilePayload(profile, sales))).thenAccept(payload -> runSync(() -> sendSellerProfile(player, payload.profile(), payload.recentSales()))).exceptionally(throwable -> {
            runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
            return null;
        });
    }

    private void handleHistory(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(localeManager.message(player, "messages.usage-history"));
            return;
        }
        long auctionId = (long) parseDouble(args[1]);
        auctionService.bidHistory(auctionId, 10).thenAccept(history -> runSync(() -> sendBidHistory(player, auctionId, history))).exceptionally(throwable -> {
            runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
            return null;
        });
    }

    private void handleOffer(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(localeManager.message(player, "messages.usage-offer"));
            return;
        }
        long auctionId = (long) parseDouble(args[1]);
        double amount = parsePositive(args[2]);
        if (amount < 0.0D) {
            player.sendMessage(localeManager.message(player, "messages.invalid-number"));
            return;
        }
        auctionService.createOffer(player, auctionId, amount).thenAccept(offer -> runSync(() -> player.sendMessage(localeManager.message(player, "messages.offer-created",
                Placeholder.parsed("id", String.valueOf(offer.id())),
                Placeholder.parsed("amount", String.format(Locale.US, "%.2f", offer.amount())))))).exceptionally(throwable -> {
            runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
            return null;
        });
    }

    private void handleOffers(Player player, String[] args) {
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "incoming";
        switch (mode) {
            case "incoming" -> auctionService.offersForSeller(player.getUniqueId()).thenAccept(offers -> runSync(() -> sendOfferList(player, offers, true))).exceptionally(throwable -> {
                runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                return null;
            });
            case "outgoing" -> auctionService.offersForBuyer(player.getUniqueId()).thenAccept(offers -> runSync(() -> sendOfferList(player, offers, false))).exceptionally(throwable -> {
                runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                return null;
            });
            case "accept", "reject", "cancel" -> {
                if (args.length < 3) {
                    player.sendMessage(localeManager.message(player, "messages.usage-offers-manage"));
                    return;
                }
                long offerId = (long) parseDouble(args[2]);
                boolean accept = mode.equals("accept");
                auctionService.respondToOffer(player, offerId, accept).thenAccept(success -> runSync(() -> player.sendMessage(localeManager.message(player, success ? (accept ? "messages.offer-accepted" : "messages.offer-updated") : "messages.offer-not-found",
                        Placeholder.parsed("id", String.valueOf(offerId)))))).exceptionally(throwable -> {
                    runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                    return null;
                });
            }
            default -> player.sendMessage(localeManager.message(player, "messages.usage-offers"));
        }
    }

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("auctionhousepro.admin")) {
            player.sendMessage(localeManager.message(player, "messages.no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(localeManager.message(player, "messages.usage-admin"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                configManager.reload();
                localeManager.reload();
                player.sendMessage(localeManager.message(player, "messages.reload-complete"));
            }
            case "menu" -> guiManager.openAdmin(player);
            case "remove" -> {
                if (args.length < 3) {
                    player.sendMessage(localeManager.message(player, "messages.usage-admin-remove"));
                    return;
                }
                long auctionId = (long) parseDouble(args[2]);
                auctionService.cancelAuction(player, auctionId).thenAccept(success -> runSync(() -> player.sendMessage(localeManager.message(player, "messages.auction-cancelled", Placeholder.parsed("id", String.valueOf(auctionId)))))).exceptionally(throwable -> {
                    runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                    return null;
                });
            }
            case "expire" -> {
                if (args.length < 3) {
                    player.sendMessage(localeManager.message(player, "messages.usage-admin-expire"));
                    return;
                }
                long auctionId = (long) parseDouble(args[2]);
                auctionService.forceExpire(player, auctionId).thenAccept(success -> runSync(() -> player.sendMessage(localeManager.message(player, success ? "messages.admin-expired" : "messages.auction-not-found", Placeholder.parsed("id", String.valueOf(auctionId)))))).exceptionally(throwable -> {
                    runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                    return null;
                });
            }
            case "return" -> {
                if (args.length < 3) {
                    player.sendMessage(localeManager.message(player, "messages.usage-admin-return"));
                    return;
                }
                long auctionId = (long) parseDouble(args[2]);
                auctionService.returnListing(player, auctionId).thenAccept(success -> runSync(() -> player.sendMessage(localeManager.message(player, success ? "messages.admin-returned" : "messages.auction-not-found", Placeholder.parsed("id", String.valueOf(auctionId)))))).exceptionally(throwable -> {
                    runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                    return null;
                });
            }
            case "stats" -> auctionService.marketStats().thenAccept(stats -> runSync(() -> sendMarketStats(player, stats))).exceptionally(throwable -> {
                runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                return null;
            });
            case "audit" -> {
                String query = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
                auctionService.recentAuditLines(query, 10).thenAccept(lines -> runSync(() -> sendAuditLines(player, query, lines))).exceptionally(throwable -> {
                    runSync(() -> player.sendMessage(localeManager.exception(player, throwable)));
                    return null;
                });
            }
            default -> player.sendMessage(localeManager.message(player, "messages.unknown-admin-subcommand"));
        }
    }

    private double parseDouble(String input) {
        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException exception) {
            return -1.0D;
        }
    }

    private double parsePositive(String input) {
        double value = parseDouble(input);
        return value <= 0.0D ? -1.0D : value;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("sell", "bid", "buy", "claim", "delivery", "locale", "listings", "claims", "search", "watch", "watched", "detail", "profile", "history", "offer", "offers", "admin", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return List.of("reload", "menu", "remove", "expire", "return", "stats", "audit");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("locale")) {
            return List.copyOf(localeManager.availableLocales());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("offers")) {
            return List.of("incoming", "outgoing", "accept", "reject", "cancel");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    private void sendAuctionDetail(Player player, Auction auction, List<AuctionBidRecord> history) {
        player.sendMessage(localeManager.message(player, "messages.detail-header", Placeholder.parsed("id", String.valueOf(auction.id())), Placeholder.parsed("item", auction.item().getType().name())));
        player.sendMessage(localeManager.message(player, "messages.detail-line", Placeholder.parsed("label", localized(player, "labels.seller")), Placeholder.parsed("value", nameOf(auction.sellerId()))));
        player.sendMessage(localeManager.message(player, "messages.detail-line", Placeholder.parsed("label", localized(player, "labels.price")), Placeholder.parsed("value", String.format(Locale.US, "%.2f", auction.displayPrice()))));
        player.sendMessage(localeManager.message(player, "messages.detail-line", Placeholder.parsed("label", localized(player, "labels.buy-now")), Placeholder.parsed("value", auction.hasBuyNow() ? String.format(Locale.US, "%.2f", auction.buyNowPrice()) : localized(player, "values.not-available"))));
        player.sendMessage(localeManager.message(player, "messages.detail-line", Placeholder.parsed("label", localized(player, "labels.category")), Placeholder.parsed("value", localizedCategory(player, auction.category()))));
        player.sendMessage(localeManager.message(player, "messages.detail-line", Placeholder.parsed("label", localized(player, "labels.ends-at")), Placeholder.parsed("value", TIMESTAMP_FORMAT.format(auction.expiresAt()))));
        player.sendMessage(localeManager.message(player, "messages.detail-line", Placeholder.parsed("label", localized(player, "labels.watchers")), Placeholder.parsed("value", String.valueOf(auction.watchCount()))));
        sendBidHistory(player, auction.id(), history);
    }

    private void sendSellerProfile(Player player, SellerProfile profile, List<Auction> recentSales) {
        player.sendMessage(localeManager.message(player, "messages.profile-header", Placeholder.parsed("seller", nameOf(profile.sellerId()))));
        player.sendMessage(localeManager.message(player, "messages.profile-line", Placeholder.parsed("label", localized(player, "labels.active-listings")), Placeholder.parsed("value", String.valueOf(profile.activeListings()))));
        player.sendMessage(localeManager.message(player, "messages.profile-line", Placeholder.parsed("label", localized(player, "labels.completed-sales")), Placeholder.parsed("value", String.valueOf(profile.completedSales()))));
        player.sendMessage(localeManager.message(player, "messages.profile-line", Placeholder.parsed("label", localized(player, "labels.gross-volume")), Placeholder.parsed("value", String.format(Locale.US, "%.2f", profile.grossSales()))));
        player.sendMessage(localeManager.message(player, "messages.profile-line", Placeholder.parsed("label", localized(player, "labels.average-sale")), Placeholder.parsed("value", String.format(Locale.US, "%.2f", profile.averageSale()))));
        player.sendMessage(localeManager.message(player, "messages.profile-line", Placeholder.parsed("label", localized(player, "labels.pending-offers")), Placeholder.parsed("value", String.valueOf(profile.pendingOffers()))));
        player.sendMessage(localeManager.message(player, "messages.profile-line", Placeholder.parsed("label", localized(player, "labels.watchers")), Placeholder.parsed("value", String.valueOf(profile.watchers()))));
        if (recentSales.isEmpty()) {
            player.sendMessage(localeManager.message(player, "messages.profile-no-sales"));
            return;
        }
        player.sendMessage(localeManager.message(player, "messages.profile-sales-header"));
        recentSales.forEach(auction -> player.sendMessage(localeManager.message(player, "messages.profile-sales-line", Placeholder.parsed("id", String.valueOf(auction.id())), Placeholder.parsed("item", auction.item().getType().name()), Placeholder.parsed("amount", String.format(Locale.US, "%.2f", auction.currentBid())))));
    }

    private void sendBidHistory(Player player, long auctionId, List<AuctionBidRecord> history) {
        player.sendMessage(localeManager.message(player, "messages.history-header", Placeholder.parsed("id", String.valueOf(auctionId))));
        if (history.isEmpty()) {
            player.sendMessage(localeManager.message(player, "messages.history-empty"));
            return;
        }
        history.forEach(entry -> player.sendMessage(localeManager.message(player, "messages.history-line", Placeholder.parsed("bidder", nameOf(entry.bidderId())), Placeholder.parsed("amount", String.format(Locale.US, "%.2f", entry.amount())), Placeholder.parsed("time", TIMESTAMP_FORMAT.format(entry.createdAt())))));
    }

    private void sendOfferList(Player player, List<AuctionOffer> offers, boolean incoming) {
        player.sendMessage(localeManager.message(player, incoming ? "messages.offers-incoming-header" : "messages.offers-outgoing-header"));
        if (offers.isEmpty()) {
            player.sendMessage(localeManager.message(player, "messages.offers-empty"));
            return;
        }
        offers.forEach(offer -> player.sendMessage(localeManager.message(player, "messages.offers-line",
                Placeholder.parsed("id", String.valueOf(offer.id())),
                Placeholder.parsed("auction", String.valueOf(offer.auctionId())),
                Placeholder.parsed("player", nameOf(incoming ? offer.buyerId() : offer.sellerId())),
                Placeholder.parsed("amount", String.format(Locale.US, "%.2f", offer.amount())),
                Placeholder.parsed("status", localizedStatus(player, offer.status().name())))));
    }

    private void sendDeliverySummary(Player player, List<DeliveryBoxEntry> entries) {
        player.sendMessage(localeManager.message(player, "messages.delivery-header"));
        if (entries.isEmpty()) {
            player.sendMessage(localeManager.message(player, "messages.delivery-empty"));
            return;
        }
        entries.forEach(entry -> player.sendMessage(localeManager.message(player, "messages.delivery-line",
                Placeholder.parsed("id", String.valueOf(entry.id())),
                Placeholder.parsed("item", entry.item().getType().name()),
                Placeholder.parsed("reason", entry.reason()),
                Placeholder.parsed("time", TIMESTAMP_FORMAT.format(entry.createdAt())))));
    }

    private void sendMarketStats(Player player, MarketStatsSnapshot stats) {
        player.sendMessage(localeManager.message(player, "messages.admin-stats-header"));
        player.sendMessage(localeManager.message(player, "messages.admin-stats-line", Placeholder.parsed("label", localized(player, "labels.active-listings")), Placeholder.parsed("value", String.valueOf(stats.activeAuctions()))));
        player.sendMessage(localeManager.message(player, "messages.admin-stats-line", Placeholder.parsed("label", localized(player, "labels.sold-listings")), Placeholder.parsed("value", String.valueOf(stats.soldAuctions()))));
        player.sendMessage(localeManager.message(player, "messages.admin-stats-line", Placeholder.parsed("label", localized(player, "labels.watch-records")), Placeholder.parsed("value", String.valueOf(stats.totalWatchlistEntries()))));
        player.sendMessage(localeManager.message(player, "messages.admin-stats-line", Placeholder.parsed("label", localized(player, "labels.pending-offers")), Placeholder.parsed("value", String.valueOf(stats.pendingOffers()))));
        player.sendMessage(localeManager.message(player, "messages.admin-stats-line", Placeholder.parsed("label", localized(player, "labels.total-bids")), Placeholder.parsed("value", String.valueOf(stats.totalBids()))));
        player.sendMessage(localeManager.message(player, "messages.admin-stats-line", Placeholder.parsed("label", localized(player, "labels.delivery-box")), Placeholder.parsed("value", String.valueOf(stats.deliveriesWaiting()))));
        player.sendMessage(localeManager.message(player, "messages.admin-stats-line", Placeholder.parsed("label", localized(player, "labels.gross-volume")), Placeholder.parsed("value", String.format(Locale.US, "%.2f", stats.grossVolume()))));
        player.sendMessage(localeManager.message(player, "messages.admin-stats-line", Placeholder.parsed("label", localized(player, "labels.average-sale")), Placeholder.parsed("value", String.format(Locale.US, "%.2f", stats.averageSale()))));
    }

    private void sendAuditLines(Player player, String query, List<String> lines) {
        player.sendMessage(localeManager.message(player, "messages.admin-audit-header", Placeholder.parsed("query", query.isBlank() ? "*" : query)));
        if (lines.isEmpty()) {
            player.sendMessage(localeManager.message(player, "messages.admin-audit-empty"));
            return;
        }
        lines.forEach(line -> player.sendMessage(localeManager.message(player, "messages.admin-audit-line", Placeholder.parsed("line", line))));
    }

    private Optional<UUID> resolvePlayerId(String input) {
        if (input.equalsIgnoreCase("me")) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        try {
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(input);
        return offline.getName() == null && !offline.hasPlayedBefore() ? Optional.empty() : Optional.of(offline.getUniqueId());
    }

    private String nameOf(UUID playerId) {
        if (playerId == null) {
            return localizedRaw(configManager.defaultLocale(), "values.none");
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() == null ? playerId.toString() : player.getName();
    }

    private String localized(Player player, String path) {
        return localeManager.string(localeManager.playerLocale(player), path);
    }

    private String localizedRaw(String locale, String path) {
        return localeManager.string(locale, path);
    }

    private String localizedCategory(Player player, AuctionCategory category) {
        return localized(player, "categories." + category.name().toLowerCase(Locale.ROOT));
    }

    private String localizedStatus(Player player, String status) {
        return localized(player, "statuses." + status.toLowerCase(Locale.ROOT));
    }

    private void runSync(Runnable action) {
        Bukkit.getScheduler().runTask(com.auctionhousepro.AuctionHouseProPlugin.getInstance(), action);
    }

    private record AuctionDetailPayload(Auction auction, List<AuctionBidRecord> history) {
    }

    private record SellerProfilePayload(SellerProfile profile, List<Auction> recentSales) {
    }
}
