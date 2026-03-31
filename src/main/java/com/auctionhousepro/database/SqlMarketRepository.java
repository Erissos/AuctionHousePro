package com.auctionhousepro.database;

import com.auctionhousepro.model.AuctionBidRecord;
import com.auctionhousepro.model.AuctionOffer;
import com.auctionhousepro.model.AuctionOfferStatus;
import com.auctionhousepro.model.DeliveryBoxEntry;
import com.auctionhousepro.model.MarketStatsSnapshot;
import com.auctionhousepro.model.WatchSubscription;
import com.auctionhousepro.util.ItemSerializer;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqlMarketRepository implements MarketRepository {
    private final DatabaseManager databaseManager;

    public SqlMarketRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<Void> recordBid(long auctionId, UUID bidderId, double amount) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO auction_bids (auction_id, bidder_id, amount, created_at) VALUES (?, ?, ?, ?)")) {
                statement.setLong(1, auctionId);
                statement.setString(2, bidderId.toString());
                statement.setDouble(3, amount);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to record bid history", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<AuctionBidRecord>> bidHistory(long auctionId, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM auction_bids WHERE auction_id = ? ORDER BY created_at DESC LIMIT ?")) {
                statement.setLong(1, auctionId);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<AuctionBidRecord> bids = new ArrayList<>();
                    while (resultSet.next()) {
                        bids.add(new AuctionBidRecord(resultSet.getLong("id"), resultSet.getLong("auction_id"), UUID.fromString(resultSet.getString("bidder_id")), resultSet.getDouble("amount"), Instant.ofEpochMilli(resultSet.getLong("created_at"))));
                    }
                    return bids;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load bid history", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> watchAuction(UUID playerId, long auctionId, Double targetPrice) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.connection()) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM auction_watchlist WHERE player_id = ? AND auction_id = ?")) {
                    delete.setString(1, playerId.toString());
                    delete.setLong(2, auctionId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO auction_watchlist (player_id, auction_id, target_price, created_at) VALUES (?, ?, ?, ?)")) {
                    insert.setString(1, playerId.toString());
                    insert.setLong(2, auctionId);
                    if (targetPrice == null) {
                        insert.setNull(3, java.sql.Types.DOUBLE);
                    } else {
                        insert.setDouble(3, targetPrice);
                    }
                    insert.setLong(4, System.currentTimeMillis());
                    insert.executeUpdate();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to add watch", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> unwatchAuction(UUID playerId, long auctionId) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM auction_watchlist WHERE player_id = ? AND auction_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, auctionId);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to remove watch", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Set<Long>> watchedAuctionIds(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT auction_id FROM auction_watchlist WHERE player_id = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    Set<Long> ids = new HashSet<>();
                    while (resultSet.next()) {
                        ids.add(resultSet.getLong("auction_id"));
                    }
                    return ids;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load watchlist", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Double>> watchTarget(UUID playerId, long auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT target_price FROM auction_watchlist WHERE player_id = ? AND auction_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, auctionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    double value = resultSet.getDouble("target_price");
                    return resultSet.wasNull() ? Optional.empty() : Optional.of(value);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load watch target", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<WatchSubscription>> watchSubscriptions(long auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM auction_watchlist WHERE auction_id = ?")) {
                statement.setLong(1, auctionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<WatchSubscription> subscriptions = new ArrayList<>();
                    while (resultSet.next()) {
                        Double targetPrice = resultSet.getDouble("target_price");
                        if (resultSet.wasNull()) {
                            targetPrice = null;
                        }
                        subscriptions.add(new WatchSubscription(UUID.fromString(resultSet.getString("player_id")), resultSet.getLong("auction_id"), targetPrice, Instant.ofEpochMilli(resultSet.getLong("created_at"))));
                    }
                    return subscriptions;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load watch subscriptions", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> clearWatchTarget(UUID playerId, long auctionId) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("UPDATE auction_watchlist SET target_price = NULL WHERE player_id = ? AND auction_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, auctionId);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to clear watch target", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> storeDelivery(UUID playerId, ItemStack itemStack, Long sourceAuctionId, String reason) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO delivery_box (player_id, item_data, source_auction_id, reason, created_at) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, ItemSerializer.serialize(itemStack));
                if (sourceAuctionId == null) {
                    statement.setNull(3, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(3, sourceAuctionId);
                }
                statement.setString(4, reason);
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to store delivery", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<DeliveryBoxEntry>> deliveries(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM delivery_box WHERE player_id = ? ORDER BY created_at ASC")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<DeliveryBoxEntry> entries = new ArrayList<>();
                    while (resultSet.next()) {
                        long sourceAuctionId = resultSet.getLong("source_auction_id");
                        entries.add(new DeliveryBoxEntry(resultSet.getLong("id"), UUID.fromString(resultSet.getString("player_id")), ItemSerializer.deserialize(resultSet.getString("item_data")), resultSet.wasNull() ? null : sourceAuctionId, resultSet.getString("reason"), Instant.ofEpochMilli(resultSet.getLong("created_at"))));
                    }
                    return entries;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load delivery box", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> removeDelivery(long deliveryId) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM delivery_box WHERE id = ?")) {
                statement.setLong(1, deliveryId);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to remove delivery", exception);
            }
        });
    }

    @Override
    public CompletableFuture<AuctionOffer> createOffer(long auctionId, UUID sellerId, UUID buyerId, double amount) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO auction_offers (auction_id, seller_id, buyer_id, amount, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                long now = System.currentTimeMillis();
                statement.setLong(1, auctionId);
                statement.setString(2, sellerId.toString());
                statement.setString(3, buyerId.toString());
                statement.setDouble(4, amount);
                statement.setString(5, AuctionOfferStatus.PENDING.name());
                statement.setLong(6, now);
                statement.setLong(7, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0L;
                    Instant timestamp = Instant.ofEpochMilli(now);
                    return new AuctionOffer(id, auctionId, sellerId, buyerId, amount, AuctionOfferStatus.PENDING, timestamp, timestamp);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to create offer", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<AuctionOffer>> offersForSeller(UUID sellerId) {
        return loadOffers("SELECT * FROM auction_offers WHERE seller_id = ? ORDER BY created_at DESC", sellerId.toString());
    }

    @Override
    public CompletableFuture<List<AuctionOffer>> offersForBuyer(UUID buyerId) {
        return loadOffers("SELECT * FROM auction_offers WHERE buyer_id = ? ORDER BY created_at DESC", buyerId.toString());
    }

    @Override
    public CompletableFuture<List<AuctionOffer>> offersForAuction(long auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM auction_offers WHERE auction_id = ? ORDER BY created_at DESC")) {
                statement.setLong(1, auctionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<AuctionOffer> offers = new ArrayList<>();
                    while (resultSet.next()) {
                        offers.add(mapOffer(resultSet));
                    }
                    return offers;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load auction offers", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<AuctionOffer>> findOffer(long offerId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM auction_offers WHERE id = ?")) {
                statement.setLong(1, offerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapOffer(resultSet)) : Optional.empty();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to find offer", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> updateOfferStatus(long offerId, AuctionOfferStatus status) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("UPDATE auction_offers SET status = ?, updated_at = ? WHERE id = ?")) {
                statement.setString(1, status.name());
                statement.setLong(2, System.currentTimeMillis());
                statement.setLong(3, offerId);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to update offer status", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> recentAuditLines(String query, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT action, details, created_at FROM audit_logs WHERE (? = '' OR LOWER(details) LIKE ? OR LOWER(action) LIKE ?) ORDER BY created_at DESC LIMIT ?";
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                String normalized = query == null ? "" : query.toLowerCase();
                String like = "%" + normalized + "%";
                statement.setString(1, normalized);
                statement.setString(2, like);
                statement.setString(3, like);
                statement.setInt(4, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<String> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        rows.add(Instant.ofEpochMilli(resultSet.getLong("created_at")) + " | " + resultSet.getString("action") + " | " + resultSet.getString("details"));
                    }
                    return rows;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load audit lines", exception);
            }
        });
    }

    @Override
    public CompletableFuture<MarketStatsSnapshot> marketStats() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection()) {
                long activeAuctions = scalarLong(connection, "SELECT COUNT(*) FROM auctions WHERE status = 'ACTIVE'");
                long soldAuctions = scalarLong(connection, "SELECT COUNT(*) FROM auctions WHERE status = 'SOLD' OR status = 'CLAIMED'");
                long watchlistEntries = scalarLong(connection, "SELECT COUNT(*) FROM auction_watchlist");
                long pendingOffers = scalarLong(connection, "SELECT COUNT(*) FROM auction_offers WHERE status = 'PENDING'");
                long totalBids = scalarLong(connection, "SELECT COUNT(*) FROM auction_bids");
                long deliveries = scalarLong(connection, "SELECT COUNT(*) FROM delivery_box");
                double grossVolume = scalarDouble(connection, "SELECT COALESCE(SUM(current_bid), 0) FROM auctions WHERE status = 'SOLD' OR status = 'CLAIMED'");
                double averageSale = scalarDouble(connection, "SELECT COALESCE(AVG(current_bid), 0) FROM auctions WHERE status = 'SOLD' OR status = 'CLAIMED'");
                return new MarketStatsSnapshot(activeAuctions, soldAuctions, watchlistEntries, pendingOffers, grossVolume, averageSale, totalBids, deliveries);
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to build market stats", exception);
            }
        });
    }

    private CompletableFuture<List<AuctionOffer>> loadOffers(String sql, String id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<AuctionOffer> offers = new ArrayList<>();
                    while (resultSet.next()) {
                        offers.add(mapOffer(resultSet));
                    }
                    return offers;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load offers", exception);
            }
        });
    }

    private AuctionOffer mapOffer(ResultSet resultSet) throws SQLException {
        return new AuctionOffer(resultSet.getLong("id"), resultSet.getLong("auction_id"), UUID.fromString(resultSet.getString("seller_id")), UUID.fromString(resultSet.getString("buyer_id")), resultSet.getDouble("amount"), AuctionOfferStatus.valueOf(resultSet.getString("status")), Instant.ofEpochMilli(resultSet.getLong("created_at")), Instant.ofEpochMilli(resultSet.getLong("updated_at")));
    }

    private long scalarLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private double scalarDouble(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getDouble(1) : 0.0D;
        }
    }
}