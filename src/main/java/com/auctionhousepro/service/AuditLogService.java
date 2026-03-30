package com.auctionhousepro.service;

import com.auctionhousepro.database.DatabaseManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AuditLogService {
    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final ExecutorService executorService;
    private final Path logFile;

    public AuditLogService(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.executorService = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "AuctionHousePro-AuditLog"));
        this.logFile = plugin.getDataFolder().toPath().resolve("logs").resolve("auction-events.log");
        try {
            Files.createDirectories(logFile.getParent());
            if (Files.notExists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize audit log file", exception);
        }
    }

    public void append(UUID actorId, String action, String details) {
        String line = Instant.now() + " | actor=" + actorId + " | action=" + action + " | details=" + details + System.lineSeparator();
        executorService.execute(() -> {
            try {
                Files.writeString(logFile, line, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                plugin.getLogger().warning("Failed to write audit file log: " + exception.getMessage());
            }
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_logs (actor_id, action, details, created_at) VALUES (?, ?, ?, ?)") ) {
                statement.setString(1, actorId == null ? null : actorId.toString());
                statement.setString(2, action);
                statement.setString(3, details);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to write audit database log: " + exception.getMessage());
            }
        });
    }

    public void shutdown() {
        executorService.shutdownNow();
    }
}
