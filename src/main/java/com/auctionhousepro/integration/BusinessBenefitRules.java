package com.auctionhousepro.integration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

/** Reads only JDK profile data. A company bonus never transfers company money. */
public final class BusinessBenefitRules {
    private static final String PREFIX = "suite-integrations.features.auction-business-";
    private static final Set<String> ROLES = Set.of("owner", "manager", "employee");
    private static final Set<String> TYPES = Set.of("restaurant", "mining", "arcade", "market", "cargo");
    private BusinessBenefitRules() {}

    public static void validate(FileConfiguration config) {
        bool(config, PREFIX + "benefits", false);
        bool(config, "suite-integrations.features.auction-milestones", true);
        ratio(config, PREFIX + "listing-discount-per-level", .02);
        ratio(config, PREFIX + "tax-discount-per-level", .02);
        ratio(config, PREFIX + "maximum-discount", .25);
        values(config, PREFIX + "eligible-roles", ROLES);
        values(config, PREFIX + "eligible-types", TYPES);
    }

    public static double discount(FileConfiguration config, Object profile, String purpose) {
        if (!config.getBoolean(PREFIX + "benefits", false) || !(profile instanceof Map<?, ?> data)) return 0;
        if (!(data.get("role") instanceof String role) || !(data.get("type") instanceof String type)
                || !values(config, PREFIX + "eligible-roles", ROLES).contains(role)
                || !values(config, PREFIX + "eligible-types", TYPES).contains(type)
                || !(data.get("level") instanceof Number number)) return 0;
        double level = number.doubleValue();
        if (!Double.isFinite(level) || level != number.longValue() || level < 1 || level > 100) return 0;
        String key = switch (purpose) {
            case "listing" -> "listing-discount-per-level";
            case "tax" -> "tax-discount-per-level";
            default -> throw new IllegalArgumentException("Unknown benefit purpose");
        };
        return Math.min(ratio(config, PREFIX + "maximum-discount", .25), level * ratio(config, PREFIX + key, .02));
    }

    private static boolean bool(FileConfiguration c, String path, boolean fallback) {
        if (c.contains(path) && !c.isBoolean(path)) throw new IllegalArgumentException(path + " must be boolean");
        return c.getBoolean(path, fallback);
    }
    private static double ratio(FileConfiguration c, String path, double fallback) {
        Object raw = c.get(path);
        if (raw != null && !(raw instanceof Number)) throw new IllegalArgumentException(path + " must be a number");
        double value = c.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException(path + " must be within 0..1");
        return value;
    }
    private static Set<String> values(FileConfiguration c, String path, Set<String> fallback) {
        Object raw = c.get(path);
        if (raw == null) return fallback;
        if (!(raw instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String text) || !fallback.contains(text)))
            throw new IllegalArgumentException(path + " contains unknown role/type");
        return Set.copyOf(c.getStringList(path));
    }
}
