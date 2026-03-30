package com.auctionhousepro.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyService {
    private final JavaPlugin plugin;
    private final Economy economy;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.economy = resolveEconomy();
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        return economy != null && economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        return economy != null && economy.depositPlayer(player, amount).transactionSuccess();
    }

    public String format(double amount) {
        return economy == null ? String.format(java.util.Locale.US, "%.2f", amount) : economy.format(amount);
    }

    private Economy resolveEconomy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            plugin.getLogger().warning("Vault economy provider was not found. Economy-backed actions will be disabled.");
            return null;
        }
        return provider.getProvider();
    }
}
