package com.auctionhousepro.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SearchTextUtil {
    private SearchTextUtil() {
    }

    public static String build(ItemStack itemStack) {
        List<String> parts = new ArrayList<>();
        parts.add(itemStack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return String.join(" ", parts);
        }
        if (meta.displayName() != null) {
            parts.add(PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT));
        }
        if (meta.hasLore() && meta.lore() != null) {
            meta.lore().stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                    .map(text -> text.toLowerCase(Locale.ROOT))
                    .forEach(parts::add);
        }
        meta.getEnchants().forEach((enchantment, level) -> parts.add(enchantment.getKey().getKey() + " " + level));
        return String.join(" ", parts);
    }
}