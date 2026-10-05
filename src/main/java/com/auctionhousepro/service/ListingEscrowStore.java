package com.auctionhousepro.service;

import com.auctionhousepro.util.AtomicFiles;
import com.auctionhousepro.util.ItemSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.util.*;

/** Retains a listing item until its SQL insert or compensation is durably recorded. */
public final class ListingEscrowStore {
    private final JavaPlugin plugin;
    public ListingEscrowStore(JavaPlugin plugin) { this.plugin=plugin; }
    public synchronized UUID prepare(UUID seller,ItemStack item,String debitId) {
        UUID id=UUID.randomUUID();
        YamlConfiguration data=new YamlConfiguration();
        data.set("seller",seller.toString()); data.set("item",ItemSerializer.serialize(item)); data.set("debit",debitId); data.set("state","RESERVED");
        save(id,data); return id;
    }
    public synchronized void mark(UUID id,String state) {
        YamlConfiguration data=new YamlConfiguration();
        try { data.load(file(id)); } catch (Exception invalid) { throw new IllegalStateException("Invalid escrow "+id,invalid); }
        data.set("state",state); save(id,data);
    }
    public synchronized void recoverReservations() {
        for (Entry entry:pending()) if ("RESERVED".equals(entry.state())) mark(entry.id(),"REVIEW");
    }
    public synchronized List<Entry> pending() {
        List<Entry> entries=new ArrayList<>();
        File[] files=new File(plugin.getDataFolder(),"listing-escrow").listFiles((d,n)->n.endsWith(".yml"));
        if (files==null) return entries;
        for (File file:files) {
            try {
                YamlConfiguration data=new YamlConfiguration(); data.load(file);
                String state=data.getString("state");
                if ("COMMITTED".equals(state) || "RESTORED".equals(state)) continue;
                entries.add(new Entry(UUID.fromString(file.getName().replace(".yml","")),UUID.fromString(data.getString("seller")),ItemSerializer.deserialize(data.getString("item")),data.getString("debit"),state));
            } catch (Exception invalid) { plugin.getLogger().severe("Invalid listing escrow "+file+": "+invalid.getMessage()); }
        }
        return entries;
    }
    private File file(UUID id) { return new File(plugin.getDataFolder(),"listing-escrow/"+id+".yml"); }
    private void save(UUID id,YamlConfiguration data) {
        try { AtomicFiles.write(file(id).toPath(),data.saveToString()); }
        catch (IOException failure) { throw new IllegalStateException("Could not write listing escrow "+id,failure); }
    }
    public record Entry(UUID id,UUID seller,ItemStack item,String debitId,String state) { }
}
