package com.auctionhousepro.config;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration menus;
    private FileConfiguration webhook;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        File menusFile = new File(plugin.getDataFolder(), "menus.yml");
        this.menus = YamlConfiguration.loadConfiguration(menusFile);
        File webhookFile = new File(plugin.getDataFolder(), "webhook.yml");
        this.webhook = YamlConfiguration.loadConfiguration(webhookFile);
    }

    public FileConfiguration config() {
        return config;
    }

    public FileConfiguration menus() {
        return menus;
    }

    public FileConfiguration webhook() {
        return webhook;
    }

    public String databaseType() {
        return config.getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
    }

    public String sqliteFile() {
        return config.getString("database.sqlite-file", "auctions.db");
    }

    public String mysqlJdbcUrl() {
        String host = config.getString("database.mysql.host", "localhost");
        int port = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "auctionhouse");
        String parameters = config.getString("database.mysql.parameters", "");
        return "jdbc:mysql://" + host + ":" + port + "/" + database + parameters;
    }

    public String mysqlUsername() {
        return config.getString("database.mysql.username", "root");
    }

    public String mysqlPassword() {
        return config.getString("database.mysql.password", "");
    }

    public int maxPoolSize() {
        return config.getInt("database.pool.maximum-pool-size", 10);
    }

    public int minIdle() {
        return config.getInt("database.pool.minimum-idle", 2);
    }

    public double listingFee() {
        return config.getDouble("auction.listing-fee", 25.0D);
    }

    public double listingFee(OfflinePlayer player) {
        return listingFee() * segmentMultiplier(player, "listing-fee-multiplier", 1.0D);
    }

    public double taxRate() {
        return config.getDouble("auction.tax-rate", 0.02D);
    }

    public double taxRate(OfflinePlayer player) {
        return taxRate() * segmentMultiplier(player, "tax-rate-multiplier", 1.0D);
    }

    public double commissionRate() {
        return config.getDouble("auction.commission-rate", 0.05D);
    }

    public double commissionRate(OfflinePlayer player) {
        return commissionRate() * segmentMultiplier(player, "commission-rate-multiplier", 1.0D);
    }

    public int defaultDurationMinutes() {
        return config.getInt("auction.default-duration-minutes", 1440);
    }

    public List<Integer> allowedDurations() {
        return config.getIntegerList("auction.allowed-durations-minutes");
    }

    public int minDurationMinutes() {
        return config.getInt("auction.min-duration-minutes", 15);
    }

    public int maxDurationMinutes() {
        return config.getInt("auction.max-duration-minutes", 10080);
    }

    public int maxActiveListings() {
        return config.getInt("auction.max-active-listings", 30);
    }

    public int maxActiveListings(OfflinePlayer player) {
        return segmentInt(player, "max-active-listings", maxActiveListings());
    }

    public long antiSnipeWindowSeconds() {
        return config.getLong("auction.anti-snipe-window-seconds", 45L);
    }

    public long antiSnipeExtensionSeconds() {
        return config.getLong("auction.anti-snipe-extension-seconds", 60L);
    }

    public long bidCooldownMillis() {
        return config.getLong("auction.bid-cooldown-millis", 750L);
    }

    public long listCooldownMillis() {
        return config.getLong("auction.list-cooldown-millis", 1250L);
    }

    public int maxSearchResults() {
        return config.getInt("auction.max-search-results", 500);
    }

    public double rareBroadcastThreshold() {
        return config.getDouble("auction.broadcast-rare-threshold", 50000.0D);
    }

    public boolean rareListingBroadcastEnabled() {
        return config.getBoolean("auction.broadcast-rare-listings", true);
    }

    public boolean highSaleBroadcastEnabled() {
        return config.getBoolean("auction.broadcast-high-sales", true);
    }

    public String activeSegmentName(OfflinePlayer player) {
        ConfigurationSection section = matchingSegment(player);
        if (section == null) {
            return "default";
        }
        return section.getName();
    }

    public Set<Material> blacklistMaterials() {
        return config.getStringList("restrictions.blacklist-materials").stream()
                .map(String::toUpperCase)
                .map(Material::matchMaterial)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public boolean whitelistEnabled() {
        return config.getBoolean("restrictions.whitelist-enabled", false);
    }

    public Set<Material> whitelistMaterials() {
        return config.getStringList("restrictions.whitelist-materials").stream()
                .map(String::toUpperCase)
                .map(Material::matchMaterial)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public List<String> blockedNbtKeys() {
        return config.getStringList("restrictions.nbt-blocked-keys");
    }

    public String defaultLocale() {
        return config.getString("localization.default-locale", "en_US");
    }

    public String fallbackLocale() {
        return config.getString("localization.fallback-locale", defaultLocale());
    }

    public boolean discordEnabled() {
        return webhook.getBoolean("enabled", config.getBoolean("discord.enabled", false));
    }

    public String discordWebhookUrl() {
        return webhook.getString("webhook-url", config.getString("discord.webhook-url", "")).trim();
    }

    public boolean notifyRareListings() {
        return webhook.getBoolean("notify-rare-listings", config.getBoolean("discord.notify-rare-listings", true));
    }

    public boolean notifyHighSales() {
        return webhook.getBoolean("notify-high-sales", config.getBoolean("discord.notify-high-sales", true));
    }

    public String rareListingTitle() {
        return webhook.getString("messages.rare-listing.title", "Rare listing");
    }

    public String rareListingDescription() {
        return webhook.getString("messages.rare-listing.description", "<seller> listed <item> for <price>");
    }

    public String highSaleTitle() {
        return webhook.getString("messages.high-sale.title", "High value sale");
    }

    public String highSaleDescription() {
        return webhook.getString("messages.high-sale.description", "Auction #<auction_id> sold for <amount>");
    }

    public long refreshTicks() {
        return config.getLong("performance.refresh-ticks", 20L);
    }

    public long expireCheckTicks() {
        return config.getLong("performance.expire-check-ticks", 40L);
    }

    public Sound menuOpenSound() {
        return sound("sounds.menu-open", Sound.BLOCK_AMETHYST_BLOCK_CHIME);
    }

    public Sound menuClickSound() {
        return sound("sounds.menu-click", Sound.UI_BUTTON_CLICK);
    }

    public Sound menuPageSound() {
        return sound("sounds.menu-page", Sound.ITEM_BOOK_PAGE_TURN);
    }

    public Sound menuActionSound() {
        return sound("sounds.menu-action", Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    private Sound sound(String path, Sound fallback) {
        String raw = config.getString(path, fallback.name());
        try {
            return Sound.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private ConfigurationSection matchingSegment(OfflinePlayer player) {
        if (!(player instanceof org.bukkit.entity.Player onlinePlayer)) {
            return null;
        }
        ConfigurationSection section = config.getConfigurationSection("auction.permission-segments");
        if (section == null) {
            return null;
        }

        ConfigurationSection best = null;
        int priority = Integer.MIN_VALUE;
        for (String key : section.getKeys(false)) {
            ConfigurationSection candidate = section.getConfigurationSection(key);
            if (candidate == null) {
                continue;
            }
            String permission = candidate.getString("permission", "");
            if (!permission.isBlank() && onlinePlayer.hasPermission(permission) && candidate.getInt("priority", 0) >= priority) {
                best = candidate;
                priority = candidate.getInt("priority", 0);
            }
        }
        return best;
    }

    private double segmentMultiplier(OfflinePlayer player, String key, double fallback) {
        ConfigurationSection section = matchingSegment(player);
        return section == null ? fallback : section.getDouble(key, fallback);
    }

    private int segmentInt(OfflinePlayer player, String key, int fallback) {
        ConfigurationSection section = matchingSegment(player);
        return section == null ? fallback : section.getInt(key, fallback);
    }
}
