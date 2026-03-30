package com.auctionhousepro.gui;

import com.auctionhousepro.AuctionHouseProPlugin;
import com.auctionhousepro.config.ConfigManager;
import com.auctionhousepro.i18n.LocaleManager;
import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionFilter;
import com.auctionhousepro.model.AuctionSortMode;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
        MenuSession session = sessions.computeIfAbsent(player.getUniqueId(), uuid -> new MenuSession(MenuType.BROWSER, AuctionFilter.defaultFilter(), 0));
        session.type = MenuType.BROWSER;
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
        placeButton(inventory, menus.getInt("browser.sort-slot", -1), Material.HOPPER, localeManager.string(localeManager.playerLocale(player), "gui.button-sort").replace("<mode>", prettySort(session.filter.sortMode())));

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
            session.filter = new AuctionFilter(session.filter.query(), session.filter.category(), nextSort(session.filter.sortMode()), session.filter.minPrice(), session.filter.maxPrice(), session.filter.sellerId(), session.filter.claimsOnly(), session.filter.activeOnly());
            playSound(player, configManager.menuClickSound(), 0.7F, 1.3F);
            render(player, session);
            return;
        }

        Long auctionId = session.slotToAuction.get(slot);
        if (auctionId == null) {
            return;
        }

        if (session.type == MenuType.CLAIMS) {
            playSound(player, configManager.menuActionSound(), 0.8F, 1.15F);
            auctionService.claim(player, auctionId).thenAccept(success -> {
                if (success) {
                    render(player, session);
                }
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
                Placeholder.parsed("buy_now", auction.hasBuyNow() ? String.format("%.2f", auction.buyNowPrice()) : "N/A"),
                Placeholder.parsed("time_left", TimeUtil.format(auction.timeLeft())),
                Placeholder.parsed("category", auction.category().name()),
                Placeholder.parsed("id", String.valueOf(auction.id()))));
        if (auction.timeLeft().toSeconds() <= configManager.antiSnipeWindowSeconds()) {
            lore.addAll(localeManager.messageList(viewer, "gui.expiring-lore"));
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

    private String nameOf(UUID playerId) {
        if (playerId == null) {
            return "None";
        }
        if (Bukkit.getOfflinePlayer(playerId).getName() != null) {
            return Bukkit.getOfflinePlayer(playerId).getName();
        }
        return playerId.toString();
    }

    private String prettySort(AuctionSortMode mode) {
        return switch (mode) {
            case NEWEST -> "Newest";
            case PRICE_ASC -> "Lowest Price";
            case PRICE_DESC -> "Highest Price";
            case ENDING_SOON -> "Ending Soon";
            case RARITY -> "Rarity";
        };
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
}
