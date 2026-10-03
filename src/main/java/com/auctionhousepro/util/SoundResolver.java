package com.auctionhousepro.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

/** Accepts legacy enum names and namespaced keys without depending on Sound being an enum. */
public final class SoundResolver {
    private static final Map<String, Sound> LEGACY_NAMES = legacyNames();

    private SoundResolver() {
    }

    public static Sound parse(String value, Sound fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String name = value.trim();
        Sound sound = LEGACY_NAMES.get(name.toUpperCase(Locale.ROOT));
        if (sound == null) {
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            if (key != null) {
                sound = Registry.SOUNDS.get(key);
            }
        }
        return sound == null ? fallback : sound;
    }

    private static Map<String, Sound> legacyNames() {
        Map<String, Sound> names = new HashMap<>();
        for (Sound sound : Registry.SOUNDS) {
            NamespacedKey key = ((Keyed) sound).getKey();
            if (key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
                names.put(key.getKey().replace('.', '_').toUpperCase(Locale.ROOT), sound);
            }
        }
        return Map.copyOf(names);
    }
}
