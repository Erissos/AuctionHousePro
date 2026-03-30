package com.auctionhousepro.discord;

import com.auctionhousepro.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public final class DiscordWebhookService {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final HttpClient httpClient;
    private volatile boolean invalidWebhookUrlLogged;

    public DiscordWebhookService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.httpClient = HttpClient.newHttpClient();
    }

    public void send(String title, String description) {
        try {
            if (!configManager.discordEnabled()) {
                return;
            }

            URI webhookUri = resolveWebhookUri();
            if (webhookUri == null) {
                return;
            }

            String payload = "{\"content\":null,\"embeds\":[{\"title\":\"" + escape(title) + "\",\"description\":\"" + escape(description) + "\"}]}";
            HttpRequest request = HttpRequest.newBuilder(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            future.thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    plugin.getLogger().warning("Discord webhook request returned status " + response.statusCode() + ": " + response.body());
                }
            }).exceptionally(throwable -> {
                plugin.getLogger().warning("Discord webhook request failed: " + throwable.getMessage());
                return null;
            });
        } catch (Exception exception) {
            plugin.getLogger().warning("Discord webhook request could not be created: " + exception.getMessage());
        }
    }

    private URI resolveWebhookUri() {
        String webhookUrl = configManager.discordWebhookUrl();
        if (webhookUrl.isBlank()) {
            return null;
        }
        try {
            invalidWebhookUrlLogged = false;
            return URI.create(webhookUrl);
        } catch (IllegalArgumentException exception) {
            if (!invalidWebhookUrlLogged) {
                plugin.getLogger().warning("Discord webhook URL is invalid. Check discord.webhook-url in config.yml.");
                invalidWebhookUrlLogged = true;
            }
            return null;
        }
    }

    private String escape(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
