package com.auctionhousepro.integration;

import com.auctionhousepro.AuctionHouseProPlugin;
import dev.desperis.suite.SuiteIntegrationService;
import org.bukkit.configuration.file.FileConfiguration;

/** Domain-only registration; plugin lifecycle and common bridge belong to the host. */
public final class SuiteHooks {
    private SuiteHooks() {}
    public static void validateConfig(FileConfiguration candidate) { BusinessBenefitRules.validate(candidate); }
    public static void install(AuctionHouseProPlugin plugin, SuiteIntegrationService service) {
        plugin.getConfigManager().setSuiteBusinessProfileLookup(player ->
                service.linkEnabled("PlayerBusiness") ? service.callPeer("PlayerBusiness", "suiteBusiness", player) : null);
    }
}
