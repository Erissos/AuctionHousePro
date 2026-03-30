package com.auctionhousepro.model;

import org.bukkit.Material;

public enum AuctionCategory {
    ALL,
    WEAPONS,
    ARMOR,
    TOOLS,
    BLOCKS,
    REDSTONE,
    FOOD,
    POTIONS,
    MISC;

    public static AuctionCategory fromMaterial(Material material) {
        String name = material.name();
        if (name.endsWith("SWORD") || name.endsWith("AXE") || name.endsWith("BOW") || name.endsWith("CROSSBOW") || name.endsWith("TRIDENT")) {
            return WEAPONS;
        }
        if (name.endsWith("HELMET") || name.endsWith("CHESTPLATE") || name.endsWith("LEGGINGS") || name.endsWith("BOOTS") || name.endsWith("ELYTRA") || name.endsWith("SHIELD")) {
            return ARMOR;
        }
        if (name.endsWith("PICKAXE") || name.endsWith("SHOVEL") || name.endsWith("HOE") || name.endsWith("FLINT_AND_STEEL") || name.endsWith("SHEARS")) {
            return TOOLS;
        }
        if (name.contains("POTION")) {
            return POTIONS;
        }
        if (name.contains("REDSTONE") || name.contains("REPEATER") || name.contains("COMPARATOR") || name.contains("OBSERVER")) {
            return REDSTONE;
        }
        if (material.isBlock()) {
            return BLOCKS;
        }
        if (material.isEdible()) {
            return FOOD;
        }
        return MISC;
    }
}
