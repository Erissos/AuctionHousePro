package com.auctionhousepro;

import com.auctionhousepro.api.AuctionHouseProApi;
import com.auctionhousepro.command.AuctionCommand;
import com.auctionhousepro.config.ConfigManager;
import com.auctionhousepro.database.AuctionRepository;
import com.auctionhousepro.database.DatabaseManager;
import com.auctionhousepro.database.SqlAuctionRepository;
import com.auctionhousepro.discord.DiscordWebhookService;
import com.auctionhousepro.economy.EconomyService;
import com.auctionhousepro.gui.GuiManager;
import com.auctionhousepro.i18n.LocaleManager;
import com.auctionhousepro.listener.PlayerConnectionListener;
import com.auctionhousepro.service.AuditLogService;
import com.auctionhousepro.service.NotificationService;
import com.auctionhousepro.service.impl.AuctionServiceImpl;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AuctionHouseProPlugin extends JavaPlugin {
    private static AuctionHouseProPlugin instance;

    private ConfigManager configManager;
    private LocaleManager localeManager;
    private DatabaseManager databaseManager;
    private AuctionRepository auctionRepository;
    private EconomyService economyService;
    private AuditLogService auditLogService;
    private DiscordWebhookService discordWebhookService;
    private NotificationService notificationService;
    private AuctionServiceImpl auctionService;
    private GuiManager guiManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResourceIfAbsent("menus.yml");
        saveResourceIfAbsent("webhook.yml");
        saveBundledLanguageResources();

        this.configManager = new ConfigManager(this);
        this.localeManager = new LocaleManager(this, configManager);
        this.databaseManager = new DatabaseManager(this, configManager);
        this.databaseManager.initialize();
        this.auctionRepository = new SqlAuctionRepository(this, databaseManager);
        this.economyService = new EconomyService(this);
        this.auditLogService = new AuditLogService(this, databaseManager);
        this.discordWebhookService = new DiscordWebhookService(this, configManager);
        this.notificationService = new NotificationService(localeManager);
        this.auctionService = new AuctionServiceImpl(this, configManager, auctionRepository, economyService, notificationService, auditLogService, discordWebhookService);
        this.guiManager = new GuiManager(this, configManager, localeManager, auctionService);

        AuctionHouseProApi.setProvider(auctionService);
        registerCommands();
        getServer().getPluginManager().registerEvents(guiManager, this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(localeManager, notificationService), this);

        auctionService.startSchedulers();
    }

    @Override
    public void onDisable() {
        if (auctionService != null) {
            auctionService.shutdown();
        }
        if (auditLogService != null) {
            auditLogService.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("auctionhouse");
        if (command == null) {
            throw new IllegalStateException("auctionhouse command is missing from plugin.yml");
        }

        AuctionCommand executor = new AuctionCommand(auctionService, guiManager, localeManager, configManager);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void saveResourceIfAbsent(String resourcePath) {
        if (getResource(resourcePath) != null && !new java.io.File(getDataFolder(), resourcePath).exists()) {
            saveResource(resourcePath, false);
        }
    }

    private void saveBundledLanguageResources() {
        saveResourceIfAbsent("lang/en_US.yml");
        saveResourceIfAbsent("lang/tr_TR.yml");
        saveResourceIfAbsent("lang/es_ES.yml");
        saveResourceIfAbsent("lang/zh_CN.yml");
        saveResourceIfAbsent("lang/ru_RU.yml");
        saveResourceIfAbsent("lang/de_DE.yml");
        saveResourceIfAbsent("lang/fr_FR.yml");
        saveResourceIfAbsent("lang/ar_SA.yml");
        saveResourceIfAbsent("lang/cs_CZ.yml");
        saveResourceIfAbsent("lang/el_GR.yml");
        saveResourceIfAbsent("lang/bg_BG.yml");
        saveResourceIfAbsent("lang/nl_NL.yml");
        saveResourceIfAbsent("lang/sv_SE.yml");
    }

    public static AuctionHouseProPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LocaleManager getLocaleManager() {
        return localeManager;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }
}
