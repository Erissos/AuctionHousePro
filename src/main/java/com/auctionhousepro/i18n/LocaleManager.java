package com.auctionhousepro.i18n;

import com.auctionhousepro.config.ConfigManager;
import com.auctionhousepro.exception.LocalizedException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class LocaleManager {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage;
    private final Map<String, YamlConfiguration> locales;
    private final Map<UUID, String> playerLocales;
    private final File playerLocaleFile;
    private YamlConfiguration playerLocaleConfig;

    public LocaleManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.miniMessage = MiniMessage.miniMessage();
        this.locales = new ConcurrentHashMap<>();
        this.playerLocales = new ConcurrentHashMap<>();
        this.playerLocaleFile = new File(plugin.getDataFolder(), "player-locales.yml");
        loadLocales();
        loadPlayerLocales();
    }

    public void reload() {
        com.auctionhousepro.util.StrictYaml.load(playerLocaleFile);
        File[] files=new File(plugin.getDataFolder(),"lang").listFiles((directory,name)->name.endsWith(".yml"));
        if (files!=null) for (File file:files) com.auctionhousepro.util.StrictYaml.load(file);
        locales.clear();
        playerLocales.clear();
        loadLocales();
        loadPlayerLocales();
    }

    public Component message(Player player, String path, TagResolver... resolvers) {
        return message(playerLocale(player), path, TagResolver.resolver(resolvers));
    }

    public Component message(String locale, String path, TagResolver resolver) {
        String raw = string(locale, path);
        return normalize(miniMessage.deserialize(raw, resolver));
    }

    public List<Component> messageList(Player player, String path, TagResolver... resolvers) {
        return messageList(playerLocale(player), path, TagResolver.resolver(resolvers));
    }

    public List<Component> messageList(String locale, String path, TagResolver resolver) {
        List<String> raw = stringList(locale, path);
        List<Component> components = new ArrayList<>(raw.size());
        for (String line : raw) {
            components.add(normalize(miniMessage.deserialize(line, resolver)));
        }
        return components;
    }

    public String string(String locale, String path) {
        YamlConfiguration selected = locales.getOrDefault(normalize(locale), locales.get(normalize(configManager.fallbackLocale())));
        if (selected != null && selected.contains(path)) {
            return selected.getString(path);
        }
        YamlConfiguration fallback = locales.get(normalize(configManager.fallbackLocale()));
        String value = fallback == null ? null : fallback.getString(path);
        return value == null ? "<red>Missing message: " + path + "</red>" : value;
    }

    public List<String> stringList(String locale, String path) {
        YamlConfiguration selected = locales.getOrDefault(normalize(locale), locales.get(normalize(configManager.fallbackLocale())));
        if (selected != null && selected.contains(path)) {
            return selected.getStringList(path);
        }
        YamlConfiguration fallback = locales.get(normalize(configManager.fallbackLocale()));
        return fallback == null ? List.of("<red>Missing locale</red>") : fallback.getStringList(path);
    }

    public String plain(Player player, String path, TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(message(player, path, resolvers));
    }

    public Component exception(Player player, Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof LocalizedException localizedException) {
            TagResolver[] resolvers = localizedException.placeholders().entrySet().stream()
                    .map(entry -> Placeholder.parsed(entry.getKey(), entry.getValue()))
                    .toArray(TagResolver[]::new);
            return message(player, localizedException.messageKey(), resolvers);
        }
        plugin.getLogger().log(Level.SEVERE, "Unexpected plugin error", root);
        return message(player, "messages.generic-error");
    }

    public String prefix(Player player) {
        return string(playerLocale(player), "prefix");
    }

    public String playerLocale(Player player) {
        return playerLocale(player.getUniqueId());
    }

    public String playerLocale(UUID playerId) {
        String selected = resolveLocale(playerLocales.get(playerId));
        if (selected != null) return selected;
        String fallback = resolveLocale(configManager.defaultLocale());
        return fallback == null ? normalize(configManager.fallbackLocale()) : fallback;
    }

    public void setPlayerLocale(UUID playerId, String locale) {
        String normalized = resolveLocale(locale);
        if (normalized == null) throw new IllegalArgumentException("Unknown language: " + locale);
        String previous=playerLocales.put(playerId,normalized);
        playerLocaleConfig.set(playerId.toString(),normalized);
        try { savePlayerLocales(); }
        catch (RuntimeException failure) {
            if (previous==null) playerLocales.remove(playerId); else playerLocales.put(playerId,previous);
            playerLocaleConfig.set(playerId.toString(),previous); throw failure;
        }
    }

    public Collection<String> availableLocales() {
        return locales.keySet().stream().sorted().toList();
    }

    public String resolveLocale(String code) {
        if (code == null || code.isBlank()) return null;
        String normalized = normalize(code);
        if (locales.containsKey(normalized)) return normalized;
        List<String> matches = locales.keySet().stream().filter(locale -> locale.startsWith(normalized + "_")).sorted().toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    public List<String> availableLanguages() {
        return locales.keySet().stream().map(locale -> {
            String base = locale.split("_", 2)[0];
            return resolveLocale(base) == null ? locale : base;
        }).distinct().sorted().toList();
    }

    private void loadLocales() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        File[] files = langFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            String locale = file.getName().replace(".yml", "");
            YamlConfiguration language = com.auctionhousepro.util.StrictYaml.load(file);
            var resource = plugin.getResource("lang/" + file.getName());
            if (resource != null) {
                try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                    language.setDefaults(YamlConfiguration.loadConfiguration(reader));
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot load language defaults", exception);
                }
            }
            locales.put(normalize(locale), language);
        }
    }

    private void loadPlayerLocales() {
        if (!playerLocaleFile.exists()) {
            try {
                playerLocaleFile.createNewFile();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to create player-locales.yml", exception);
            }
        }

        this.playerLocaleConfig = com.auctionhousepro.util.StrictYaml.load(playerLocaleFile);
        ConfigurationSection section = playerLocaleConfig.getConfigurationSection("");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            try { playerLocales.put(UUID.fromString(key), normalize(playerLocaleConfig.getString(key, configManager.defaultLocale()))); }
            catch (IllegalArgumentException invalid) { plugin.getLogger().warning("Ignoring invalid player locale key: "+key); }
        }
    }

    private void savePlayerLocales() {
        try {
            com.auctionhousepro.util.AtomicFiles.write(playerLocaleFile.toPath(),playerLocaleConfig.saveToString());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save player locales",exception);
        }
    }

    private String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return configManager.defaultLocale().trim().replace('-', '_').toLowerCase(Locale.ROOT);
        }
        return locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private Component normalize(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
