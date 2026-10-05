package com.auctionhousepro.util;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
/** Fail closed on corrupt user data; never replace it with an empty configuration. */
public final class StrictYaml {
    private StrictYaml() {}
    public static YamlConfiguration load(File file) {
        YamlConfiguration data=new YamlConfiguration();
        if (file==null || !file.exists()) return data;
        try { data.load(file); return data; }
        catch (Exception invalid) { throw new IllegalStateException("Invalid YAML; original preserved: "+file,invalid); }
    }
}
