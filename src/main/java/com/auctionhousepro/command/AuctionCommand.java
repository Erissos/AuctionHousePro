package com.auctionhousepro.command;

import com.auctionhousepro.config.ConfigManager;
import com.auctionhousepro.gui.GuiManager;
import com.auctionhousepro.i18n.LocaleManager;
import com.auctionhousepro.model.AuctionFilter;
import com.auctionhousepro.model.AuctionSortMode;
import com.auctionhousepro.service.impl.AuctionServiceImpl;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

public final class AuctionCommand implements CommandExecutor, TabCompleter {
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
            case "locale" -> handleLocale(player, args);
            case "listings" -> guiManager.openPlayerListings(player);
            case "claims" -> guiManager.openClaims(player);
            case "search" -> handleSearch(player, args);
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
        Duration duration = args.length >= 4 ? parseDuration(args[3]) : Duration.ofMinutes(configManager.defaultDurationMinutes());
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
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        auctionService.search(new AuctionFilter(query, com.auctionhousepro.model.AuctionCategory.ALL, AuctionSortMode.NEWEST, 0.0D, Double.MAX_VALUE, null, false, true))
            .thenAccept(results -> player.sendMessage(localeManager.message(player, "messages.search-results", Placeholder.parsed("count", String.valueOf(results.size())))));
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

    private Duration parseDuration(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Duration.ofMinutes(Long.parseLong(normalized));
        }

        long totalMinutes = 0L;
        StringBuilder number = new StringBuilder();
        for (char character : normalized.toCharArray()) {
            if (Character.isDigit(character)) {
                number.append(character);
                continue;
            }
            if (number.isEmpty()) {
                return null;
            }
            long value = Long.parseLong(number.toString());
            number.setLength(0);
            switch (character) {
                case 'm' -> totalMinutes += value;
                case 'h' -> totalMinutes += value * 60L;
                case 'd' -> totalMinutes += value * 1440L;
                default -> {
                    return null;
                }
            }
        }
        if (!number.isEmpty()) {
            totalMinutes += Long.parseLong(number.toString());
        }
        return totalMinutes > 0L ? Duration.ofMinutes(totalMinutes) : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("sell", "bid", "buy", "claim", "locale", "listings", "claims", "search", "admin", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return List.of("reload", "remove");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("locale")) {
            return List.copyOf(localeManager.availableLocales());
        }
        return List.of();
    }

    private void runSync(Runnable action) {
        Bukkit.getScheduler().runTask(com.auctionhousepro.AuctionHouseProPlugin.getInstance(), action);
    }
}
