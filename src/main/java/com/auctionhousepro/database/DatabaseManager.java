package com.auctionhousepro.database;

import com.auctionhousepro.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private HikariDataSource dataSource;

    public DatabaseManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void initialize() {
        HikariConfig hikari = new HikariConfig();
        hikari.setMaximumPoolSize(configManager.maxPoolSize());
        hikari.setMinimumIdle(configManager.minIdle());
        hikari.setPoolName("AuctionHouseProPool");

        if (configManager.databaseType().equals("mysql")) {
            hikari.setJdbcUrl(configManager.mysqlJdbcUrl());
            hikari.setUsername(configManager.mysqlUsername());
            hikari.setPassword(configManager.mysqlPassword());
        } else {
            File databaseFile = new File(plugin.getDataFolder(), configManager.sqliteFile());
            hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            hikari.setConnectionTestQuery("SELECT 1");
        }

        this.dataSource = new HikariDataSource(hikari);
        createSchema();
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private void createSchema() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS auctions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        seller_id VARCHAR(36) NOT NULL,
                        highest_bidder_id VARCHAR(36),
                        item_data TEXT NOT NULL,
                        type VARCHAR(24) NOT NULL,
                        status VARCHAR(24) NOT NULL,
                        category VARCHAR(24) NOT NULL,
                        starting_price DOUBLE NOT NULL,
                        current_bid DOUBLE NOT NULL,
                        buy_now_price DOUBLE NOT NULL,
                        bid_increment DOUBLE NOT NULL,
                        created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL,
                        seller_claimed BOOLEAN NOT NULL,
                        buyer_claimed BOOLEAN NOT NULL,
                        searchable_text TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        actor_id VARCHAR(36),
                        action VARCHAR(64) NOT NULL,
                        details TEXT NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create database schema", exception);
        }
    }
}
