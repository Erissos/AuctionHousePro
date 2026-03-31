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
            if (configManager.databaseType().equals("mysql")) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS auctions (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            seller_id VARCHAR(36) NOT NULL,
                            highest_bidder_id VARCHAR(36),
                            item_data LONGTEXT NOT NULL,
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
                            searchable_text LONGTEXT NOT NULL,
                            watch_count INT NOT NULL DEFAULT 0,
                            view_count INT NOT NULL DEFAULT 0,
                            bid_count INT NOT NULL DEFAULT 0,
                            featured_score DOUBLE NOT NULL DEFAULT 0
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS audit_logs (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            actor_id VARCHAR(36),
                            action VARCHAR(64) NOT NULL,
                            details LONGTEXT NOT NULL,
                            created_at BIGINT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS auction_bids (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            auction_id BIGINT NOT NULL,
                            bidder_id VARCHAR(36) NOT NULL,
                            amount DOUBLE NOT NULL,
                            created_at BIGINT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS auction_watchlist (
                            player_id VARCHAR(36) NOT NULL,
                            auction_id BIGINT NOT NULL,
                            target_price DOUBLE,
                            created_at BIGINT NOT NULL,
                            PRIMARY KEY (player_id, auction_id)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS delivery_box (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            player_id VARCHAR(36) NOT NULL,
                            item_data LONGTEXT NOT NULL,
                            source_auction_id BIGINT,
                            reason VARCHAR(64) NOT NULL,
                            created_at BIGINT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS auction_offers (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            auction_id BIGINT NOT NULL,
                            seller_id VARCHAR(36) NOT NULL,
                            buyer_id VARCHAR(36) NOT NULL,
                            amount DOUBLE NOT NULL,
                            status VARCHAR(24) NOT NULL,
                            created_at BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL
                        )
                        """);
            } else {
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
                            searchable_text TEXT NOT NULL,
                            watch_count INTEGER NOT NULL DEFAULT 0,
                            view_count INTEGER NOT NULL DEFAULT 0,
                            bid_count INTEGER NOT NULL DEFAULT 0,
                            featured_score DOUBLE NOT NULL DEFAULT 0
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
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS auction_bids (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            auction_id BIGINT NOT NULL,
                            bidder_id VARCHAR(36) NOT NULL,
                            amount DOUBLE NOT NULL,
                            created_at BIGINT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS auction_watchlist (
                            player_id VARCHAR(36) NOT NULL,
                            auction_id BIGINT NOT NULL,
                            target_price DOUBLE,
                            created_at BIGINT NOT NULL,
                            PRIMARY KEY (player_id, auction_id)
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS delivery_box (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            player_id VARCHAR(36) NOT NULL,
                            item_data TEXT NOT NULL,
                            source_auction_id BIGINT,
                            reason VARCHAR(64) NOT NULL,
                            created_at BIGINT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS auction_offers (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            auction_id BIGINT NOT NULL,
                            seller_id VARCHAR(36) NOT NULL,
                            buyer_id VARCHAR(36) NOT NULL,
                            amount DOUBLE NOT NULL,
                            status VARCHAR(24) NOT NULL,
                            created_at BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL
                        )
                        """);
            }

            ensureAuctionColumn(statement, "watch_count", configManager.databaseType().equals("mysql") ? "INT NOT NULL DEFAULT 0" : "INTEGER NOT NULL DEFAULT 0");
            ensureAuctionColumn(statement, "view_count", configManager.databaseType().equals("mysql") ? "INT NOT NULL DEFAULT 0" : "INTEGER NOT NULL DEFAULT 0");
            ensureAuctionColumn(statement, "bid_count", configManager.databaseType().equals("mysql") ? "INT NOT NULL DEFAULT 0" : "INTEGER NOT NULL DEFAULT 0");
            ensureAuctionColumn(statement, "featured_score", "DOUBLE NOT NULL DEFAULT 0");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create database schema", exception);
        }
    }

    private void ensureAuctionColumn(Statement statement, String columnName, String definition) {
        try {
            statement.executeUpdate("ALTER TABLE auctions ADD COLUMN " + columnName + " " + definition);
        } catch (SQLException ignored) {
        }
    }
}
