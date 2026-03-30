package com.auctionhousepro.database;

import com.auctionhousepro.AuctionHouseProPlugin;
import com.auctionhousepro.model.Auction;
import com.auctionhousepro.model.AuctionCategory;
import com.auctionhousepro.model.AuctionFilter;
import com.auctionhousepro.model.AuctionStatus;
import com.auctionhousepro.model.AuctionType;
import com.auctionhousepro.util.ItemSerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqlAuctionRepository implements AuctionRepository {
    private final AuctionHouseProPlugin plugin;
    private final DatabaseManager databaseManager;

    public SqlAuctionRepository(AuctionHouseProPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<Auction> insert(Auction auction) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO auctions (seller_id, highest_bidder_id, item_data, type, status, category, starting_price, current_bid, buy_now_price, bid_increment, created_at, expires_at, seller_claimed, buyer_claimed, searchable_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                populateAuction(statement, auction);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        return auction.withId(keys.getLong(1));
                    }
                }
                return auction;
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to insert auction", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> update(Auction auction) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE auctions SET seller_id = ?, highest_bidder_id = ?, item_data = ?, type = ?, status = ?, category = ?, starting_price = ?, current_bid = ?, buy_now_price = ?, bid_increment = ?, created_at = ?, expires_at = ?, seller_claimed = ?, buyer_claimed = ?, searchable_text = ? WHERE id = ?";
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                populateAuction(statement, auction);
                statement.setLong(16, auction.id());
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to update auction", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Auction>> findById(long id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM auctions WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to find auction by id", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<Auction>> search(AuctionFilter filter, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM auctions WHERE 1=1");
            List<Object> parameters = new ArrayList<>();
            AuctionFilter normalized = filter.withPageDefaults();

            if (normalized.activeOnly()) {
                sql.append(" AND status = ?");
                parameters.add(AuctionStatus.ACTIVE.name());
            }
            if (normalized.claimsOnly()) {
                sql.append(" AND ((seller_id = ? AND status IN ('SOLD','EXPIRED','CANCELLED') AND seller_claimed = 0) OR (highest_bidder_id = ? AND status = 'SOLD' AND buyer_claimed = 0))");
                String uuid = normalized.sellerId() == null ? "" : normalized.sellerId().toString();
                parameters.add(uuid);
                parameters.add(uuid);
            } else if (normalized.sellerId() != null) {
                sql.append(" AND seller_id = ?");
                parameters.add(normalized.sellerId().toString());
            }
            if (normalized.category() != null && normalized.category() != AuctionCategory.ALL) {
                sql.append(" AND category = ?");
                parameters.add(normalized.category().name());
            }
            if (normalized.query() != null && !normalized.query().isBlank()) {
                sql.append(" AND LOWER(searchable_text) LIKE ?");
                parameters.add("%" + normalized.query().toLowerCase() + "%");
            }
            sql.append(" AND current_bid >= ? AND current_bid <= ?");
            parameters.add(normalized.minPrice());
            parameters.add(normalized.maxPrice());
            sql.append(" LIMIT ?");
            parameters.add(limit);

            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int index = 0; index < parameters.size(); index++) {
                    statement.setObject(index + 1, parameters.get(index));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Auction> auctions = new ArrayList<>();
                    while (resultSet.next()) {
                        auctions.add(map(resultSet));
                    }
                    return auctions;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to search auctions", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<Auction>> findBySeller(UUID sellerId) {
        return search(new AuctionFilter("", AuctionCategory.ALL, null, 0.0D, Double.MAX_VALUE, sellerId, false, false), Integer.MAX_VALUE);
    }

    @Override
    public CompletableFuture<List<Auction>> claimable(UUID playerId) {
        return search(new AuctionFilter("", AuctionCategory.ALL, null, 0.0D, Double.MAX_VALUE, playerId, true, false), Integer.MAX_VALUE);
    }

    @Override
    public CompletableFuture<List<Auction>> expiringBefore(long epochMillis) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM auctions WHERE status = 'ACTIVE' AND expires_at <= ?")) {
                statement.setLong(1, epochMillis);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Auction> auctions = new ArrayList<>();
                    while (resultSet.next()) {
                        auctions.add(map(resultSet));
                    }
                    return auctions;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to load expiring auctions", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> appendLog(UUID actorId, String action, String details) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.connection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_logs (actor_id, action, details, created_at) VALUES (?, ?, ?, ?)") ) {
                statement.setString(1, actorId == null ? null : actorId.toString());
                statement.setString(2, action);
                statement.setString(3, details);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to append audit log: " + exception.getMessage());
            }
        });
    }

    private void populateAuction(PreparedStatement statement, Auction auction) throws SQLException {
        statement.setString(1, auction.sellerId().toString());
        statement.setString(2, auction.highestBidderId() == null ? null : auction.highestBidderId().toString());
        statement.setString(3, ItemSerializer.serialize(auction.item()));
        statement.setString(4, auction.type().name());
        statement.setString(5, auction.status().name());
        statement.setString(6, auction.category().name());
        statement.setDouble(7, auction.startingPrice());
        statement.setDouble(8, auction.currentBid());
        statement.setDouble(9, auction.buyNowPrice());
        statement.setDouble(10, auction.bidIncrement());
        statement.setLong(11, auction.createdAt().toEpochMilli());
        statement.setLong(12, auction.expiresAt().toEpochMilli());
        statement.setBoolean(13, auction.sellerClaimed());
        statement.setBoolean(14, auction.buyerClaimed());
        statement.setString(15, auction.searchableText());
    }

    private Auction map(ResultSet resultSet) throws SQLException {
        return new Auction(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("seller_id")),
                resultSet.getString("highest_bidder_id") == null ? null : UUID.fromString(resultSet.getString("highest_bidder_id")),
                ItemSerializer.deserialize(resultSet.getString("item_data")),
                AuctionType.valueOf(resultSet.getString("type")),
                AuctionStatus.valueOf(resultSet.getString("status")),
                AuctionCategory.valueOf(resultSet.getString("category")),
                resultSet.getDouble("starting_price"),
                resultSet.getDouble("current_bid"),
                resultSet.getDouble("buy_now_price"),
                resultSet.getDouble("bid_increment"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                Instant.ofEpochMilli(resultSet.getLong("expires_at")),
                resultSet.getBoolean("seller_claimed"),
                resultSet.getBoolean("buyer_claimed"),
                resultSet.getString("searchable_text")
        );
    }
}
