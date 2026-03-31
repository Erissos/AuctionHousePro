package com.auctionhousepro.gui;

import com.auctionhousepro.AuctionHouseProPlugin;
import com.auctionhousepro.config.ConfigManager;
import com.auctionhousepro.i18n.LocaleManager;
import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionBidRecord;
import com.auctionhousepro.model.AuctionCategory;
import com.auctionhousepro.model.AuctionFilter;
import com.auctionhousepro.model.AuctionSortMode;
import com.auctionhousepro.model.SellerProfile;
import com.auctionhousepro.service.impl.AuctionServiceImpl;
import com.auctionhousepro.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class GuiManager implements Listener {
    private final AuctionHouseProPlugin plugin;
    private final ConfigManager configManager;
    private final LocaleManager localeManager;
    private final AuctionServiceImpl auctionService;
    private final MiniMessage miniMessage;
    private final Map<UUID, MenuSession> sessions;

    public GuiManager(AuctionHouseProPlugin plugin, ConfigManager configManager, LocaleManager localeManager, AuctionServiceImpl auctionService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.localeManager = localeManager;
        this.auctionService = auctionService;
        this.miniMessage = MiniMessage.miniMessage();
        this.sessions = new ConcurrentHashMap<>();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, configManager.refreshTicks(), configManager.refreshTicks());
    }

    public void openBrowser(Player player) {
        openBrowser(player, AuctionFilter.defaultFilter());
    }

    public void openBrowser(Player player, AuctionFilter filter) {
        MenuSession session = sessions.computeIfAbsent(player.getUniqueId(), uuid -> new MenuSession(MenuType.BROWSER, AuctionFilter.defaultFilter(), 0));
        session.type = MenuType.BROWSER;
        session.filter = filter.withPageDefaults();
        session.page = 0;
        render(player, session);
    }

    public void openClaims(Player player) {
        MenuSession session = sessions.computeIfAbsent(player.getUniqueId(), uuid -> new MenuSession(MenuType.CLAIMS, AuctionFilter.defaultFilter(), 0));
        session.type = MenuType.CLAIMS;
        session.page = 0;
        render(player, session);
    }

    public void openPlayerListings(Player player) {
        MenuSession session = sessions.computeIfAbsent(player.getUniqueId(), uuid -> new MenuSession(MenuType.LISTINGS, AuctionFilter.defaultFilter(), 0));
        session.type = MenuType.LISTINGS;
        session.page = 0;
        render(player, session);
    }

    public void openWatched(Player player) {
        openBrowser(player, AuctionFilter.defaultFilter().toggleFavorites(player.getUniqueId()));
    }

    public void openAdmin(Player player) {
        MenuSession session = sessions.computeIfAbsent(player.getUniqueId(), uuid -> new MenuSession(MenuType.ADMIN, AuctionFilter.defaultFilter(), 0));
        session.type = MenuType.ADMIN;
        session.page = 0;
        render(player, session);
    }

    private void render(Player player, MenuSession session) {
        switch (session.type) {
            case BROWSER -> auctionService.search(session.filter).thenAccept(auctions -> Bukkit.getScheduler().runTask(plugin, () -> draw(player, session, auctions, "browser")));
            case CLAIMS -> auctionService.claimable(player.getUniqueId()).thenAccept(auctions -> Bukkit.getScheduler().runTask(plugin, () -> draw(player, session, auctions, "claims")));
            case LISTINGS -> auctionService.playerListings(player.getUniqueId()).thenAccept(auctions -> Bukkit.getScheduler().runTask(plugin, () -> draw(player, session, auctions, "browser")));
            case ADMIN -> auctionService.search(AuctionFilter.defaultFilter()).thenAccept(auctions -> Bukkit.getScheduler().runTask(plugin, () -> draw(player, session, auctions, "admin")));
        }
    }

    private void draw(Player player, MenuSession session, List<Auction> auctions, String menuKey) {
        FileConfiguration menus = configManager.menus();
        int size = menus.getInt(menuKey + ".size", 54);
        Component title = miniMessage.deserialize(menus.getString(menuKey + ".title", "<#62d2ff><bold>AuctionHousePro</bold>"))
            .decoration(TextDecoration.ITALIC, false);
        Inventory inventory = session.inventory;
        boolean requiresNewInventory = inventory == null || inventory.getSize() != size || !menuKey.equals(session.menuKey);
        if (requiresNewInventory) {
            MenuHolder holder = new MenuHolder(player.getUniqueId());
            inventory = Bukkit.createInventory(holder, size, title);
            session.inventory = inventory;
            session.menuKey = menuKey;
        } else if (inventory != null) {
            inventory.clear();
        }
        if (inventory == null) {
            return;
        }
        fillBackground(inventory, menuKey);

        List<Integer> contentSlots = parseSlots(menus.getString(menuKey + ".content-slots", "10-16,19-25,28-34"));
        session.slotToAuction.clear();

        List<Auction> pageItems = paginate(auctions, session.page, contentSlots.size());
        for (int index = 0; index < pageItems.size(); index++) {
            Auction auction = pageItems.get(index);
            int slot = contentSlots.get(index);
            inventory.setItem(slot, toDisplayItem(player, auction));
            session.slotToAuction.put(slot, auction.id());
        }

        placeButton(inventory, menus.getInt(menuKey + ".previous-page-slot", -1), Material.ARROW, localeManager.string(localeManager.playerLocale(player), "gui.button-previous"));
        placeButton(inventory, menus.getInt(menuKey + ".next-page-slot", -1), Material.ARROW, localeManager.string(localeManager.playerLocale(player), "gui.button-next"));
        placeButton(inventory, menus.getInt(menuKey + ".refresh-slot", -1), Material.SUNFLOWER, localeManager.string(localeManager.playerLocale(player), "gui.button-refresh"));
        placeButton(inventory, menus.getInt("browser.claims-slot", -1), Material.CHEST, localeManager.string(localeManager.playerLocale(player), "gui.button-claims"));
        placeButton(inventory, menus.getInt("browser.player-listings-slot", -1), Material.BOOK, localeManager.string(localeManager.playerLocale(player), "gui.button-player-listings"));
        placeButton(inventory, menus.getInt("browser.sort-slot", -1), Material.HOPPER, localeManager.string(localeManager.playerLocale(player), "gui.button-sort").replace("<mode>", prettySort(player, session.filter.sortMode())));
        placeButton(inventory, menus.getInt("browser.watchlist-slot", 37), Material.HEART_OF_THE_SEA, localeManager.string(localeManager.playerLocale(player), "gui.button-watchlist"));
        placeButton(inventory, menus.getInt("browser.category-slot", 38), Material.COMPASS, localeManager.string(localeManager.playerLocale(player), "gui.button-category").replace("<category>", localizedCategory(player, session.filter.category())));
        placeButton(inventory, menus.getInt("browser.featured-slot", 41), Material.NETHER_STAR, localeManager.string(localeManager.playerLocale(player), "gui.button-featured").replace("<state>", onOff(player, session.filter.featuredOnly())));
        placeButton(inventory, menus.getInt("browser.buy-now-slot", 43), Material.GOLD_INGOT, localeManager.string(localeManager.playerLocale(player), "gui.button-buy-now-filter").replace("<state>", onOff(player, session.filter.buyNowOnly())));

        if (requiresNewInventory || player.getOpenInventory().getTopInventory() != inventory) {
            player.openInventory(inventory);
            playSound(player, configManager.menuOpenSound(), 0.7F, 1.2F);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
        MenuSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        int slot = event.getRawSlot();
        String menuKey = session.type == MenuType.CLAIMS ? "claims" : session.type == MenuType.ADMIN ? "admin" : "browser";
        FileConfiguration menus = configManager.menus();

        if (slot == menus.getInt(menuKey + ".previous-page-slot", -1)) {
            session.page = Math.max(0, session.page - 1);
            playSound(player, configManager.menuPageSound(), 0.7F, 0.95F);
            render(player, session);
            return;
        }
        if (slot == menus.getInt(menuKey + ".next-page-slot", -1)) {
            session.page += 1;
            playSound(player, configManager.menuPageSound(), 0.7F, 1.05F);
            render(player, session);
            return;
        }
        if (slot == menus.getInt(menuKey + ".refresh-slot", -1)) {
            playSound(player, configManager.menuClickSound(), 0.7F, 1.1F);
            render(player, session);
            return;
        }
        if (slot == menus.getInt("browser.claims-slot", -1)) {
            playSound(player, configManager.menuClickSound(), 0.75F, 1.25F);
            openClaims(player);
            return;
        }
        if (slot == menus.getInt("browser.player-listings-slot", -1)) {
            playSound(player, configManager.menuClickSound(), 0.75F, 1.0F);
            openPlayerListings(player);
            return;
        }
        if (slot == menus.getInt("browser.sort-slot", -1)) {
            session.filter = session.filter.withSort(nextSort(session.filter.sortMode()));
            playSound(player, configManager.menuClickSound(), 0.7F, 1.3F);
            render(player, session);
            return;
        }
        if (slot == menus.getInt("browser.watchlist-slot", 37)) {
            playSound(player, configManager.menuClickSound(), 0.75F, 1.2F);
            openWatched(player);
            return;
        }
        if (slot == menus.getInt("browser.category-slot", 38)) {
            session.filter = session.filter.withCategory(nextCategory(session.filter.category()));
            playSound(player, configManager.menuClickSound(), 0.75F, 1.1F);
            render(player, session);
            return;
        }
        if (slot == menus.getInt("browser.featured-slot", 41)) {
            session.filter = session.filter.toggleFeatured();
            playSound(player, configManager.menuClickSound(), 0.75F, 1.1F);
            render(player, session);
            return;
        }
        if (slot == menus.getInt("browser.buy-now-slot", 43)) {
            session.filter = session.filter.toggleBuyNowOnly();
            playSound(player, configManager.menuClickSound(), 0.75F, 1.1F);
            render(player, session);
            return;
        }

        Long auctionId = session.slotToAuction.get(slot);
        if (auctionId == null) {
            return;
        }

        auctionService.recordView(player, auctionId);

        if (session.type == MenuType.CLAIMS) {
            playSound(player, configManager.menuActionSound(), 0.8F, 1.15F);
            auctionService.claim(player, auctionId).thenAccept(success -> {
                if (success) {
                    render(player, session);
                }
            });
            return;
        }

        if (session.type == MenuType.ADMIN) {
            if (event.getClick() == ClickType.RIGHT) {
                playSound(player, configManager.menuActionSound(), 0.8F, 1.2F);
                auctionService.returnListing(player, auctionId).thenAccept(success -> render(player, session)).exceptionally(throwable -> {
                    player.sendMessage(localeManager.exception(player, throwable));
                    return null;
                });
            } else {
                playSound(player, configManager.menuActionSound(), 0.8F, 1.0F);
                auctionService.forceExpire(player, auctionId).thenAccept(success -> render(player, session)).exceptionally(throwable -> {
                    player.sendMessage(localeManager.exception(player, throwable));
                    return null;
                });
            }
            return;
        }

        if (event.getClick() == ClickType.SHIFT_RIGHT) {
            playSound(player, configManager.menuActionSound(), 0.8F, 1.25F);
            auctionService.toggleWatch(player.getUniqueId(), auctionId, null).thenAccept(watching -> render(player, session)).exceptionally(throwable -> {
                player.sendMessage(localeManager.exception(player, throwable));
                return null;
            });
            return;
        }

        if (event.getClick() == ClickType.MIDDLE) {
            playSound(player, configManager.menuActionSound(), 0.8F, 1.35F);
            auctionService.findAuction(auctionId).thenCompose(optional -> optional.map(auction -> auctionService.bidHistory(auctionId, 5).thenApply(history -> new AuctionInspection(auction, history))).orElseGet(() -> java.util.concurrent.CompletableFuture.completedFuture(null))).thenAccept(payload -> {
                if (payload == null) {
                    return;
                }
                player.sendMessage(localeManager.message(player, "messages.gui-detail-header", Placeholder.parsed("id", String.valueOf(payload.auction().id())), Placeholder.parsed("item", payload.auction().item().getType().name())));
                payload.history().stream().limit(3).forEach(entry -> player.sendMessage(localeManager.message(player, "messages.gui-detail-line", Placeholder.parsed("bidder", nameOf(entry.bidderId())), Placeholder.parsed("amount", String.format(java.util.Locale.US, "%.2f", entry.amount())))));
            }).exceptionally(throwable -> {
                player.sendMessage(localeManager.exception(player, throwable));
                return null;
            });
            return;
        }

        if (event.getClick() == ClickType.SHIFT_LEFT) {
            playSound(player, configManager.menuActionSound(), 0.8F, 1.2F);
            auctionService.findAuction(auctionId).thenCompose(optional -> optional.map(auction -> auctionService.sellerProfile(auction.sellerId()).thenApply(profile -> new SellerInspection(auction, profile))).orElseGet(() -> java.util.concurrent.CompletableFuture.completedFuture(null))).thenAccept(payload -> {
                if (payload == null) {
                    return;
                }
                SellerProfile profile = payload.profile();
                player.sendMessage(localeManager.message(player, "messages.gui-profile-header", Placeholder.parsed("seller", nameOf(payload.auction().sellerId()))));
                player.sendMessage(localeManager.message(player, "messages.gui-profile-line", Placeholder.parsed("active", String.valueOf(profile.activeListings())), Placeholder.parsed("sales", String.valueOf(profile.completedSales())), Placeholder.parsed("watchers", String.valueOf(profile.watchers()))));
            }).exceptionally(throwable -> {
                player.sendMessage(localeManager.exception(player, throwable));
                return null;
            });
            return;
        }

        if (event.getClick() == ClickType.RIGHT) {
            playSound(player, configManager.menuActionSound(), 0.8F, 1.25F);
            auctionService.buyNow(player, auctionId).thenAccept(auction -> render(player, session)).exceptionally(throwable -> {
                player.sendMessage(localeManager.exception(player, throwable));
                return null;
            });
        } else {
            playSound(player, configManager.menuActionSound(), 0.8F, 1.0F);
            auctionService.cachedAuction(auctionId).ifPresentOrElse(cached -> auctionService.placeBid(player, auctionId, cached.minimumNextBid()).thenAccept(auction -> render(player, session)).exceptionally(throwable -> {
                player.sendMessage(localeManager.exception(player, throwable));
                return null;
            }), () -> render(player, session));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        MenuSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.inventory = null;
        }
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            MenuSession session = sessions.get(player.getUniqueId());
            if (session == null || session.inventory == null) {
                continue;
            }
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)) {
                continue;
            }
            render(player, session);
        }
    }

    private ItemStack toDisplayItem(Player viewer, Auction auction) {
        ItemStack itemStack = auction.item().clone();
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }

        List<Component> lore = new ArrayList<>(localeManager.messageList(viewer, "gui.browse-lore",
                Placeholder.parsed("seller", nameOf(auction.sellerId())),
                Placeholder.parsed("current_bid", String.format("%.2f", auction.displayPrice())),
            Placeholder.parsed("buy_now", auction.hasBuyNow() ? String.format("%.2f", auction.buyNowPrice()) : localized(viewer, "values.not-available")),
                Placeholder.parsed("time_left", TimeUtil.format(auction.timeLeft())),
            Placeholder.parsed("category", localizedCategory(viewer, auction.category())),
                Placeholder.parsed("id", String.valueOf(auction.id())),
                Placeholder.parsed("watchers", String.valueOf(auction.watchCount())),
                Placeholder.parsed("bids", String.valueOf(auction.bidCount()))));
        if (auction.featuredScore() >= 10.0D) {
            lore.addAll(localeManager.messageList(viewer, "gui.featured-lore"));
        }
        if (auction.timeLeft().toSeconds() <= configManager.antiSnipeWindowSeconds()) {
            lore.addAll(localeManager.messageList(viewer, "gui.expiring-lore"));
        }
        if (viewer.hasPermission("auctionhousepro.admin")) {
            lore.addAll(localeManager.messageList(viewer, "gui.admin-lore"));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private void fillBackground(Inventory inventory, String menuKey) {
        Material material = Material.matchMaterial(configManager.menus().getString(menuKey + ".background-item", "GRAY_STAINED_GLASS_PANE"));
        ItemStack filler = new ItemStack(material == null ? Material.GRAY_STAINED_GLASS_PANE : material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            filler.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private void placeButton(Inventory inventory, int slot, Material material, String title) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(title).decoration(TextDecoration.ITALIC, false));
            itemStack.setItemMeta(meta);
        }
        inventory.setItem(slot, itemStack);
    }

    private List<Integer> parseSlots(String input) {
        List<Integer> slots = new ArrayList<>();
        for (String part : input.split(",")) {
            String trimmed = part.trim();
            if (trimmed.contains("-")) {
                String[] range = trimmed.split("-");
                int from = Integer.parseInt(range[0]);
                int to = Integer.parseInt(range[1]);
                for (int slot = from; slot <= to; slot++) {
                    slots.add(slot);
                }
            } else {
                slots.add(Integer.parseInt(trimmed));
            }
        }
        return slots;
    }

    private List<Auction> paginate(List<Auction> auctions, int page, int pageSize) {
        int start = page * pageSize;
        if (start >= auctions.size()) {
            return List.of();
        }
        int end = Math.min(auctions.size(), start + pageSize);
        return auctions.subList(start, end);
    }

    private AuctionSortMode nextSort(AuctionSortMode current) {
        List<AuctionSortMode> modes = Arrays.asList(AuctionSortMode.values());
        return modes.get((modes.indexOf(current) + 1) % modes.size());
    }

    private AuctionCategory nextCategory(AuctionCategory current) {
        List<AuctionCategory> categories = Arrays.asList(AuctionCategory.values());
        return categories.get((categories.indexOf(current) + 1) % categories.size());
    }

    private String nameOf(UUID playerId) {
        if (playerId == null) {
            return localizedRaw(configManager.defaultLocale(), "values.none");
        }
        if (Bukkit.getOfflinePlayer(playerId).getName() != null) {
            return Bukkit.getOfflinePlayer(playerId).getName();
        }
        return playerId.toString();
    }

    private String prettySort(Player player, AuctionSortMode mode) {
        return localized(player, "sort-modes." + mode.name().toLowerCase(Locale.ROOT));
    }

    private String onOff(Player player, boolean enabled) {
        return localized(player, enabled ? "values.enabled" : "values.disabled");
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

    private void playSound(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private enum MenuType {
        BROWSER,
        CLAIMS,
        LISTINGS,
        ADMIN
    }

    private static final class MenuSession {
        private MenuType type;
        private AuctionFilter filter;
        private int page;
        private Inventory inventory;
        private String menuKey;
        private final Map<Integer, Long> slotToAuction;

        private MenuSession(MenuType type, AuctionFilter filter, int page) {
            this.type = type;
            this.filter = filter;
            this.page = page;
            this.menuKey = "browser";
            this.slotToAuction = new HashMap<>();
        }
    }

    private record MenuHolder(UUID viewerId) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record AuctionInspection(Auction auction, List<AuctionBidRecord> history) {
    }

    private record SellerInspection(Auction auction, SellerProfile profile) {
    }
}
