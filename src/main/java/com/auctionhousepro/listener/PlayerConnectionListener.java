package com.auctionhousepro.listener;

import com.auctionhousepro.i18n.LocaleManager;
import com.auctionhousepro.service.NotificationService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerConnectionListener implements Listener {
    private final LocaleManager localeManager;
    private final NotificationService notificationService;

    public PlayerConnectionListener(LocaleManager localeManager, NotificationService notificationService) {
        this.localeManager = localeManager;
        this.notificationService = notificationService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        localeManager.playerLocale(event.getPlayer());
        notificationService.sendPendingNotices(event.getPlayer());
    }
}
