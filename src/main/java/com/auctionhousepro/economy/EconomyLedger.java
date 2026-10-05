package com.auctionhousepro.economy;

import com.auctionhousepro.database.DatabaseManager;
import com.auctionhousepro.exception.LocalizedException;
import com.auctionhousepro.model.EconomyCredit;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** SQL outbox for credits and receipts for debits. Ambiguous external-provider outcomes are never replayed. */
public final class EconomyLedger {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final EconomyService economy;
    private final Map<String,CompletableFuture<Void>> inFlight=new HashMap<>();
    public EconomyLedger(JavaPlugin plugin, DatabaseManager database, EconomyService economy) {
        this.plugin=plugin; this.database=database; this.economy=economy;
    }
    public static void insertCredit(Connection connection, boolean mysql, EconomyCredit credit) throws SQLException {
        if (!valid(credit.amount())) throw new IllegalArgumentException("Invalid credit amount");
        String sql=(mysql ? "INSERT IGNORE" : "INSERT OR IGNORE")+" INTO economy_operations (operation_id, player_id, amount, kind, state, created_at) VALUES (?, ?, ?, 'CREDIT', 'QUEUED', ?)";
        try (PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,credit.operationId()); statement.setString(2,credit.playerId().toString());
            statement.setDouble(3,credit.amount()); statement.setLong(4,System.currentTimeMillis()); statement.executeUpdate();
        }
    }
    public static void commitDebit(Connection connection, String operationId) throws SQLException {
        if (operationId == null) return;
        try (PreparedStatement statement=connection.prepareStatement("UPDATE economy_operations SET state='COMMITTED' WHERE operation_id=? AND kind='DEBIT' AND state='APPLIED'")) {
            statement.setString(1,operationId);
            if (statement.executeUpdate()!=1) throw new SQLException("Debit receipt is missing: "+operationId);
        }
    }
    public CompletableFuture<String> debit(UUID player, double amount) {
        return debit(player, amount, () -> {});
    }

    /** A guard runs on the server thread immediately before withdrawal, after queued SQL work. */
    public CompletableFuture<String> debit(UUID player, double amount, Runnable guard) {
        Objects.requireNonNull(guard, "guard");
        if (!valid(amount)) return CompletableFuture.failedFuture(new LocalizedException("messages.invalid-number"));
        String id="debit:"+UUID.randomUUID();
        return CompletableFuture.runAsync(() -> {
            try (Connection connection=database.connection(); PreparedStatement statement=connection.prepareStatement(
                "INSERT INTO economy_operations (operation_id, player_id, amount, kind, state, created_at) VALUES (?, ?, ?, 'DEBIT', 'QUEUED', ?)")) {
                try (PreparedStatement unresolved=connection.prepareStatement("SELECT 1 FROM economy_operations WHERE player_id=? AND state IN ('REVIEW','APPLYING') LIMIT 1")) {
                    unresolved.setString(1,player.toString());
                    try (ResultSet result=unresolved.executeQuery()) { if (result.next()) throw new LocalizedException("messages.transaction-pending"); }
                }
                statement.setString(1,id); statement.setString(2,player.toString()); statement.setDouble(3,amount);
                statement.setLong(4,System.currentTimeMillis()); statement.executeUpdate();
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }).thenCompose(unused -> apply(id, guard)).thenApply(unused -> id);
    }
    public CompletableFuture<Void> refund(String debitId) {
        if (debitId == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try (Connection connection=database.connection()) {
                connection.setAutoCommit(false);
                try {
                    Operation debit=read(connection,debitId);
                    if (debit!=null && (debit.state.equals("APPLIED") || debit.state.equals("REFUND_QUEUED"))) {
                        insertCredit(connection,database.isMysql(),new EconomyCredit("refund:"+debitId,debit.player,debit.amount));
                        update(connection,debitId,"REFUND_QUEUED");
                    }
                    connection.commit();
                } catch (Throwable failure) { connection.rollback(); throw failure; }
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }).thenCompose(unused -> apply("refund:"+debitId));
    }
    public CompletableFuture<Void> processQueued() {
        return list("QUEUED", "CREDIT").thenCompose(ids -> {
            Set<String> pending=new LinkedHashSet<>(ids);
            synchronized (this) { pending.addAll(inFlight.keySet()); }
            List<CompletableFuture<Void>> tasks=new ArrayList<>();
            for (String id:pending) tasks.add(apply(id).exceptionally(failure -> { plugin.getLogger().warning("Payment remains pending: "+id+": "+failure.getMessage()); return null; }));
            return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
        });
    }
    public CompletableFuture<Void> recover() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection=database.connection(); Statement statement=connection.createStatement()) {
                int count=statement.executeUpdate("UPDATE economy_operations SET state='REVIEW' WHERE state='APPLYING'");
                if (count>0) plugin.getLogger().severe(count+" external payments require reconciliation: /ah admin ledger");
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }).thenCompose(unused -> list("APPLIED","DEBIT")).thenCompose(ids -> {
            List<CompletableFuture<Void>> refunds=new ArrayList<>();
            for (String id:ids) refunds.add(refund(id).exceptionally(failure -> { plugin.getLogger().warning("Refund retained: "+id+": "+failure.getMessage()); return null; }));
            return CompletableFuture.allOf(refunds.toArray(CompletableFuture[]::new));
        }).thenCompose(unused -> processQueued());
    }
    public CompletableFuture<List<String>> review() { return list("REVIEW",null); }
    public CompletableFuture<Void> reconcile(String id, boolean applied) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection=database.connection()) {
                Operation operation=read(connection,id);
                if (operation==null || !operation.state.equals("REVIEW")) throw new IllegalArgumentException("Operation is not awaiting review");
                try (PreparedStatement statement=connection.prepareStatement("UPDATE economy_operations SET state=? WHERE operation_id=? AND state='REVIEW'")) {
                    statement.setString(1,operation.kind.equals("DEBIT") ? (applied ? "APPLIED" : "REJECTED") : (applied ? "DONE" : "QUEUED"));
                    statement.setString(2,id);
                    if (statement.executeUpdate()!=1) throw new IllegalArgumentException("Operation was already reconciled");
                }
                return operation.kind.equals("DEBIT") && applied;
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }).thenCompose(refund -> refund ? refund(id) : processQueued());
    }

    public CompletableFuture<String> state(String id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection=database.connection()) { Operation operation=read(connection,id); return operation==null ? "MISSING" : operation.state; }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
        });
    }
    private CompletableFuture<Void> apply(String id) { return apply(id, () -> {}); }

    private synchronized CompletableFuture<Void> apply(String id, Runnable guard) {
        CompletableFuture<Void> existing=inFlight.get(id);
        if (existing!=null) return existing;
        CompletableFuture<Void> result=new CompletableFuture<>();
        inFlight.put(id,result);
        applyOnce(id, guard).whenComplete((unused,failure) -> {
            synchronized (this) { inFlight.remove(id,result); }
            if (failure==null) result.complete(null); else result.completeExceptionally(failure);
        });
        return result;
    }
    private CompletableFuture<Void> applyOnce(String id, Runnable guard) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection=database.connection()) {
                connection.setAutoCommit(false);
                try {
                    Operation operation=read(connection,id);
                    if (operation==null || !operation.state.equals("QUEUED")) { connection.commit(); return null; }
                    try (PreparedStatement reserve=connection.prepareStatement("UPDATE economy_operations SET state='APPLYING' WHERE operation_id=? AND state='QUEUED'")) {
                        reserve.setString(1,id); if (reserve.executeUpdate()!=1) { connection.commit(); return null; }
                    }
                    connection.commit(); return operation;
                } catch (Throwable failure) { connection.rollback(); throw failure; }
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        }).thenCompose(operation -> {
            if (operation==null) return CompletableFuture.completedFuture(null);
            CompletableFuture<Boolean> transfer=new CompletableFuture<>();
            java.util.concurrent.atomic.AtomicReference<LocalizedException> denied = new java.util.concurrent.atomic.AtomicReference<>();
            Runnable action=() -> {
                try {
                    if (operation.kind.equals("DEBIT")) {
                        try { guard.run(); }
                        catch (LocalizedException rejection) { denied.set(rejection); transfer.complete(false); return; }
                    }
                    var player=Bukkit.getOfflinePlayer(operation.player);
                    boolean success=operation.amount==0 || (operation.kind.equals("DEBIT")
                        ? economy.has(player,operation.amount) && economy.withdraw(player,operation.amount)
                        : economy.deposit(player,operation.amount));
                    transfer.complete(success);
                } catch (Throwable failure) { transfer.completeExceptionally(failure); }
            };
            if (!plugin.isEnabled()) return CompletableFuture.failedFuture(new IllegalStateException("Plugin stopped; payment retained for reconciliation"));
            if (Bukkit.isPrimaryThread()) action.run(); else Bukkit.getScheduler().runTask(plugin,action);
            return transfer.handle((success,failure) -> {
                String state=failure!=null ? "REVIEW" : Boolean.TRUE.equals(success)
                    ? (operation.kind.equals("DEBIT") ? "APPLIED" : "DONE") : (operation.kind.equals("DEBIT") ? "REJECTED" : "QUEUED");
                return CompletableFuture.runAsync(() -> {
                    try (Connection connection=database.connection()) { update(connection,id,state); }
                    catch (SQLException problem) { throw new IllegalStateException("Payment outcome is uncertain: "+id,problem); }
                    if (failure!=null) throw new IllegalStateException("Payment requires review: "+id,failure);
                    if (denied.get()!=null) throw denied.get();
                    if (!Boolean.TRUE.equals(success)) throw new LocalizedException("messages.not-enough-money");
                });
            }).thenCompose(future -> future);
        });
    }
    private CompletableFuture<List<String>> list(String state, String kind) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> ids=new ArrayList<>();
            try (Connection connection=database.connection(); PreparedStatement statement=connection.prepareStatement(
                "SELECT operation_id FROM economy_operations WHERE state=?"+(kind==null ? "" : " AND kind=?")+" ORDER BY created_at")) {
                statement.setString(1,state); if (kind!=null) statement.setString(2,kind);
                try (ResultSet result=statement.executeQuery()) { while (result.next()) ids.add(result.getString(1)); }
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
            return ids;
        });
    }
    private static Operation read(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement("SELECT player_id,amount,kind,state FROM economy_operations WHERE operation_id=?")) {
            statement.setString(1,id);
            try (ResultSet result=statement.executeQuery()) { return result.next() ? new Operation(UUID.fromString(result.getString(1)),result.getDouble(2),result.getString(3),result.getString(4)) : null; }
        }
    }
    private static void update(Connection connection,String id,String state) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement("UPDATE economy_operations SET state=? WHERE operation_id=?")) {
            statement.setString(1,state); statement.setString(2,id); statement.executeUpdate();
        }
    }
    private record Operation(UUID player,double amount,String kind,String state) { }
    public static boolean valid(double value) { return Double.isFinite(value) && value>=0 && value<=1.0e12; }
}
