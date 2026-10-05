package com.auctionhousepro.service;

import com.auctionhousepro.i18n.LocaleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NotificationService {
    private final LocaleManager localeManager;
    private final Map<UUID, Notice> pendingClaimNotice;
    private record Notice(String path, Map<String,String> values) {}

    public NotificationService(LocaleManager localeManager) {
        this.localeManager = localeManager;
        this.pendingClaimNotice = new ConcurrentHashMap<>();
    }

    public void notify(UUID playerId, String path, Map<String, String> placeholders) {
        if (!Bukkit.isPrimaryThread()) {
            var plugin=com.auctionhousepro.AuctionHouseProPlugin.getInstance();
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> notify(playerId,path,Map.copyOf(placeholders)));
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            if ("messages.claim-ready".equals(path)) {
                pendingClaimNotice.put(playerId, new Notice(path,Map.copyOf(placeholders)));
            }
            return;
        }

        player.sendMessage(localeManager.message(player, path, placeholders.entrySet().stream()
                .map(entry -> entry.getKey().equals("item") ? Placeholder.unparsed(entry.getKey(), entry.getValue()) : Placeholder.parsed(entry.getKey(), entry.getValue()))
                .toArray(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new)));
    }

    public void sendPendingNotices(Player player) {
        Notice notice = pendingClaimNotice.remove(player.getUniqueId());
        if (notice != null) notify(player.getUniqueId(),notice.path(),notice.values());
    }

    /** Native standard names follow each recipient's selected plugin language; custom item names stay literal. */
    public void notifyItem(UUID playerId, String path, ItemStack item, Map<String, String> placeholders) {
        if (!Bukkit.isPrimaryThread()) {
            var plugin = com.auctionhousepro.AuctionHouseProPlugin.getInstance();
            ItemStack snapshot = item.clone(); Map<String, String> values = Map.copyOf(placeholders);
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> notifyItem(playerId, path, snapshot, values));
            return;
        }
        var values = new java.util.HashMap<>(placeholders);
        values.put("item", localeManager.itemName(playerId, item));
        notify(playerId, path, values);
    }

    public void broadcastItem(String path, ItemStack item, Map<String, String> placeholders) {
        if (!Bukkit.isPrimaryThread()) {
            var plugin = com.auctionhousepro.AuctionHouseProPlugin.getInstance();
            ItemStack snapshot = item.clone(); Map<String, String> values = Map.copyOf(placeholders);
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> broadcastItem(path, snapshot, values));
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) notifyItem(player.getUniqueId(), path, item, placeholders);
    }

    public void broadcast(String path, Map<String, String> placeholders) {
        if (!Bukkit.isPrimaryThread()) {
            var plugin=com.auctionhousepro.AuctionHouseProPlugin.getInstance();
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> broadcast(path,Map.copyOf(placeholders)));
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            notify(player.getUniqueId(), path, placeholders);
        }
    }
}
