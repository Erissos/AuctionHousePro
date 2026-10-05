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
        if (!java.util.Set.of("sqlite","mysql").contains(configManager.databaseType())) throw new IllegalArgumentException("database.type must be sqlite or mysql");
        HikariConfig hikari = new HikariConfig();
        // The shaded JAR contains two JDBC providers; choose explicitly instead of relying on one service file.
        hikari.setDriverClassName(isMysql() ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        hikari.setMaximumPoolSize(isMysql() ? Math.max(1,configManager.maxPoolSize()) : 1);
        hikari.setMinimumIdle(isMysql() ? Math.max(0,Math.min(configManager.minIdle(),configManager.maxPoolSize())) : 1);
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
        try (Connection connection=connection(); Statement statement=connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS economy_operations (operation_id VARCHAR(200) PRIMARY KEY, player_id VARCHAR(36) NOT NULL, amount DOUBLE NOT NULL, kind VARCHAR(16) NOT NULL, state VARCHAR(24) NOT NULL, created_at BIGINT NOT NULL)");
            if (!hasColumn(connection,"delivery_box","state")) statement.executeUpdate("ALTER TABLE delivery_box ADD COLUMN state VARCHAR(24) NOT NULL DEFAULT 'PENDING'");
            if (!hasColumn(connection,"delivery_box","delivery_key")) statement.executeUpdate("ALTER TABLE delivery_box ADD COLUMN delivery_key VARCHAR(200)");
            boolean deliveryIndex=false;
            try (var indexes=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,"delivery_box",true,false)) {
                while (indexes.next()) if ("delivery_key_unique".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) deliveryIndex=true;
            }
            if (!deliveryIndex) statement.executeUpdate("CREATE UNIQUE INDEX delivery_key_unique ON delivery_box(delivery_key)");
            if (!isMysql()) {
                statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA busy_timeout=10000");
            }
        } catch (SQLException failure) { throw new IllegalStateException("Could not migrate payment schema",failure); }
    }

    public void markUncertainDeliveries() {
        try (Connection connection=connection(); Statement statement=connection.createStatement()) {
            statement.executeUpdate("UPDATE delivery_box SET state='REVIEW' WHERE state='DELIVERING'");
        } catch (SQLException failure) { throw new IllegalStateException("Cannot recover deliveries",failure); }
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean isMysql() { return configManager.databaseType().equals("mysql"); }

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

    private boolean hasColumn(Connection connection,String table,String column) throws SQLException {
        try (var columns=connection.getMetaData().getColumns(connection.getCatalog(),null,table,column)) {
            while (columns.next()) if (table.equalsIgnoreCase(columns.getString("TABLE_NAME")) && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
        }
        return false;
    }
    private void ensureAuctionColumn(Statement statement, String columnName, String definition) throws SQLException {
        if (!hasColumn(statement.getConnection(),"auctions",columnName)) statement.executeUpdate("ALTER TABLE auctions ADD COLUMN " + columnName + " " + definition);
    }
}
