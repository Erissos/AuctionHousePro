package com.auctionhousepro.service;

import com.auctionhousepro.i18n.LocaleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NotificationService {
    private final LocaleManager localeManager;
    private final Map<UUID, Component> pendingClaimNotice;

    public NotificationService(LocaleManager localeManager) {
        this.localeManager = localeManager;
        this.pendingClaimNotice = new ConcurrentHashMap<>();
    }

    public void notify(UUID playerId, String path, Map<String, String> placeholders) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            if ("messages.claim-ready".equals(path)) {
                pendingClaimNotice.put(playerId, localeManager.message(localeManager.playerLocale(playerId), path, Placeholder.component("prefix", Component.empty())));
            }
            return;
        }

        player.sendMessage(localeManager.message(player, path, placeholders.entrySet().stream()
                .map(entry -> Placeholder.parsed(entry.getKey(), entry.getValue()))
                .toArray(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new)));
    }

    public void sendPendingNotices(Player player) {
        Component component = pendingClaimNotice.remove(player.getUniqueId());
        if (component != null) {
            player.sendMessage(component);
        }
    }

    public void broadcast(String path, Map<String, String> placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            notify(player.getUniqueId(), path, placeholders);
        }
    }
}
