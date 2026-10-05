package dev.desperis.integration;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional, synchronous territory checks. No provider classes are linked in this plugin's API. */
public final class IntegrationService implements Listener, AutoCloseable {
    public enum Action { INTERACT, BUILD, BREAK, TELEPORT, SPAWN, TRADE, PROGRESS, TRACK, PVP, EVENT }
    private static final Map<String,String> NAMES = Map.of(
        "towny","Towny", "griefprevention","GriefPrevention", "worldguard","WorldGuard",
        "lands","Lands", "bentobox","BentoBox", "superiorskyblock","SuperiorSkyblock2");
    private final Plugin plugin;
    private final Supplier<FileConfiguration> config;
    private final Map<String,Long> warnings = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Snapshot snapshot = new Snapshot(Settings.read(new org.bukkit.configuration.file.YamlConfiguration()),List.of(),Set.of());
    private volatile boolean closed;

    public IntegrationService(JavaPlugin plugin, Supplier<FileConfiguration> config) { this(plugin,config,true); }
    IntegrationService(Plugin plugin, Supplier<FileConfiguration> config, boolean listen) {
        this.plugin=plugin; this.config=config;
        if(listen)plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public static void validateConfig(FileConfiguration config) { Settings.read(config); }
    public void reload() {
        if(closed)return;
        Settings settings=Settings.read(config.get());
        List<Entry> hooks=new ArrayList<>(); Set<String> missing=new java.util.HashSet<>();
        if(settings.enabled())for(String id:NAMES.keySet().stream().sorted().toList()) {
            if(!settings.providers().contains(id)){if(settings.required().contains(id))missing.add(id);continue;}
            Plugin provider=plugin.getServer().getPluginManager().getPlugin(NAMES.get(id));
            if(provider==null||!provider.isEnabled()){if(settings.required().contains(id))missing.add(id);continue;}
            try {hooks.add(new Entry(id,provider,create(id,provider),null));}
            catch(ReflectiveOperationException|RuntimeException|LinkageError failure){
                hooks.add(new Entry(id,provider,null,failure));warn(id,"API could not be bound",failure);
            }
        }
        snapshot=new Snapshot(settings,List.copyOf(hooks),Set.copyOf(missing));
        if(!missing.isEmpty())warn("required","Required providers unavailable: "+missing,null);
        if(settings.debug())plugin.getLogger().info("Territory integration providers: "+activeProviders());
    }
    public List<String> activeProviders() { return snapshot.entries().stream().map(e->NAMES.get(e.id())+(e.error()!=null?" (API error)":"")).toList(); }
    public boolean allows(Player player, Location at, Action action) {return allows(player,at,action==Action.TRADE||action==Action.SPAWN?Material.CHEST:Material.AIR,action);}
    public boolean allows(Player player, Location at, Material material, Action action) {
        Snapshot current=snapshot; Settings s=current.settings();
        if(!s.enabled()||closed)return true;
        if(bypass(player,s))return true;
        if(!validWorld(at,s))return false;
        if(!s.checks().contains(action))return true;
        if(!onThread()||!current.missing().isEmpty())return false;
        for(Entry entry:current.entries()) {
            try {
                if(entry.error()!=null)throw new IllegalStateException("Provider API unavailable",entry.error());
                if(!entry.provider().isEnabled()) {if(s.required().contains(entry.id()))return false;continue;}
                if(!entry.hook().supports(at.getWorld()))continue;
                if(!s.wilderness()&&!entry.hook().claimed(at))return false;
                if(!entry.hook().allows(player,at,material==null?Material.CHEST:material,action))return false;
            } catch(ReflectiveOperationException|RuntimeException|LinkageError failure) {
                warn(entry.id(),"Protection query failed",failure);if(s.failClosed())return false;
            }
        }
        return true;
    }
    public boolean allowsPvP(Player attacker, Player target) {
        if(attacker==null||target==null)return false;
        Snapshot current=snapshot;Settings s=current.settings();
        if(!s.enabled()||closed)return true;
        if(bypass(attacker,s))return true;
        Location a=attacker.getLocation(),b=target.getLocation();
        if(!validWorld(a,s)||!validWorld(b,s))return false;
        if(!s.checks().contains(Action.PVP))return true;
        if(!onThread()||!current.missing().isEmpty()||!a.getWorld().equals(b.getWorld())||!a.getWorld().getPVP())return false;
        for(Entry entry:current.entries())try {
            if(entry.error()!=null)throw new IllegalStateException("Provider API unavailable",entry.error());
            if(!entry.provider().isEnabled()){if(s.required().contains(entry.id()))return false;continue;}
            if(!entry.hook().supportsPvP(a.getWorld()))continue;
            if(!s.wilderness()&&(!entry.hook().claimed(a)||!entry.hook().claimed(b)))return false;
            if(!entry.hook().pvp(attacker,target,a)||!entry.hook().pvp(attacker,target,b))return false;
        } catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(entry.id(),"PvP query failed",failure);if(s.failClosed())return false;}
        return true;
    }
    public boolean isClaimed(Location at) {
        if(at==null||at.getWorld()==null||closed||!snapshot.settings().enabled()||!onThread())return false;
        for(Entry entry:snapshot.entries())try {
            if(entry.hook()!=null&&entry.provider().isEnabled()&&entry.hook().supports(at.getWorld())&&entry.hook().claimed(at))return true;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(entry.id(),"Claim lookup failed",failure);}
        return false;
    }
    public boolean hasTerritorialProvider(World world) {
        if(world==null||closed||!snapshot.settings().enabled())return false;
        if(!onThread()||!snapshot.missing().isEmpty())return true;
        for(Entry entry:snapshot.entries())try {
            if(entry.error()!=null)return true;
            if(entry.provider().isEnabled()&&entry.hook().supports(world))return true;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(entry.id(),"World scope lookup failed",failure);return true;}
        return false;
    }
    private boolean onThread(){if(plugin.getServer().isPrimaryThread())return true;warn("thread","Asynchronous protection query denied",null);return false;}
    private static boolean bypass(Player player,Settings s){return player!=null&&!s.bypass().isEmpty()&&player.hasPermission(s.bypass());}
    private static boolean validWorld(Location at,Settings s){return at!=null&&at.getWorld()!=null&&!s.blocked().contains(at.getWorld().getName())&&(s.allowed().isEmpty()||s.allowed().contains(at.getWorld().getName()));}
    private void warn(String key,String message,Throwable failure){
        long now=System.currentTimeMillis();Long before=warnings.get(key);if(before!=null&&now-before<60000)return;warnings.put(key,now);
        plugin.getLogger().warning("Integration "+key+": "+message+(failure==null?"":" ("+failure.getClass().getSimpleName()+")")+". Check integrations configuration and provider version.");
    }
    @EventHandler public void enabled(PluginEnableEvent e){if(NAMES.containsValue(e.getPlugin().getName()))reload();}
    @EventHandler public void disabled(PluginDisableEvent e){if(NAMES.containsValue(e.getPlugin().getName()))reload();}
    @Override public void close(){closed=true;HandlerList.unregisterAll(this);snapshot=new Snapshot(snapshot.settings(),List.of(),Set.of());warnings.clear();}
    private record Snapshot(Settings settings,List<Entry> entries,Set<String> missing){}
    private record Entry(String id,Plugin provider,Hook hook,Throwable error){}
    record Settings(boolean enabled,boolean failClosed,boolean wilderness,boolean debug,String bypass,Set<String> allowed,Set<String> blocked,Set<String> providers,Set<String> required,Set<Action> checks) {
        static Settings read(FileConfiguration c){
            for(String path:List.of("integrations","integrations.worlds","integrations.providers","integrations.checks"))section(c,path);
            for(String key:NAMES.keySet())section(c,"integrations.providers."+key);
            boolean enabled=bool(c,"integrations.enabled",true),failClosed=bool(c,"integrations.fail-closed",true),debug=bool(c,"integrations.debug",false);
            String wilderness=text(c,"integrations.wilderness","allow").toLowerCase(Locale.ROOT);
            if(!Set.of("allow","deny").contains(wilderness))throw new IllegalArgumentException("integrations.wilderness must be allow or deny");
            Set<String> providers=new java.util.HashSet<>();
            for(String key:NAMES.keySet())if(bool(c,"integrations.providers."+key+".enabled",true)&&bool(c,"claims.providers."+key,true))providers.add(key);
            Set<String> required=new java.util.HashSet<>();for(String key:list(c,"integrations.required-providers")) {
                String normalized=key.toLowerCase(Locale.ROOT);if(normalized.equals("superiorskyblock2"))normalized="superiorskyblock";
                if(!NAMES.containsKey(normalized))throw new IllegalArgumentException("Unknown required integration: "+key);required.add(normalized);
            }
            EnumSet<Action> checks=EnumSet.noneOf(Action.class);for(Action a:Action.values())if(bool(c,"integrations.checks."+a.name().toLowerCase(Locale.ROOT),true))checks.add(a);
            return new Settings(enabled,failClosed,wilderness.equals("allow"),debug,text(c,"integrations.bypass-permission",""),list(c,"integrations.worlds.allowed"),list(c,"integrations.worlds.blocked"),Set.copyOf(providers),Set.copyOf(required),Set.copyOf(checks));
        }
        private static boolean bool(FileConfiguration c,String path,boolean fallback){if(c.isSet(path)&&!(c.get(path)instanceof Boolean))throw new IllegalArgumentException(path+" must be true or false");return c.getBoolean(path,fallback);}
        private static void section(FileConfiguration c,String path){if(c.isSet(path)&&!c.isConfigurationSection(path))throw new IllegalArgumentException(path+" must be a section");}
        private static String text(FileConfiguration c,String path,String fallback){Object value=c.get(path);if(value==null)return fallback;if(!(value instanceof String))throw new IllegalArgumentException(path+" must be text");return ((String)value).trim();}
        private static Set<String> list(FileConfiguration c,String path){Object value=c.get(path);if(value==null)return Set.of();if(!(value instanceof List<?> values))throw new IllegalArgumentException(path+" must be a list");Set<String> result=new java.util.HashSet<>();for(Object item:values){if(!(item instanceof String s)||s.trim().isEmpty())throw new IllegalArgumentException(path+" must contain nonempty text");result.add(s.trim());}return Set.copyOf(result);}
    }

    private interface Hook {
        boolean supports(World world)throws ReflectiveOperationException;
        default boolean supportsPvP(World world)throws ReflectiveOperationException{return supports(world);}
        boolean claimed(Location at)throws ReflectiveOperationException;
        boolean allows(Player player,Location at,Material material,Action action)throws ReflectiveOperationException;
        default boolean pvp(Player attacker,Player target,Location at)throws ReflectiveOperationException{return allows(attacker,at,Material.AIR,Action.PVP);}
    }
    private static boolean container(Material material){return Set.of(Material.CHEST,Material.TRAPPED_CHEST,Material.BARREL,Material.HOPPER,Material.DROPPER,Material.DISPENSER,Material.FURNACE,Material.BLAST_FURNACE,Material.SMOKER,Material.BREWING_STAND).contains(material)||material.name().endsWith("SHULKER_BOX");}
    private Hook create(String id,Plugin provider)throws ReflectiveOperationException {
        return switch(id){case "towny"->new Towny(provider);case "griefprevention"->new GriefPrevention(provider);case "worldguard"->new WorldGuard(provider);case "lands"->new Lands(provider,plugin);case "bentobox"->new BentoBox(provider);case "superiorskyblock"->new Superior(provider);default->throw new IllegalArgumentException(id);};
    }
    private static abstract class ReflectiveHook implements Hook {
        final ClassLoader loader; final Map<String,Method> methods=new HashMap<>();
        ReflectiveHook(Plugin p){loader=p.getClass().getClassLoader();}
        Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,loader);}
        Object field(Class<?> type,String name)throws ReflectiveOperationException{return type.getField(name).get(null);}
        Object invoke(Object target,String name,Object...args)throws ReflectiveOperationException{
            Class<?> owner=target instanceof Class<?> c?c:target.getClass();String key=owner.getName()+":"+name+":"+Arrays.stream(args).map(a->a==null?"null":a.getClass().getName()).toList();
            Method method=methods.get(key);
            if(method==null){
                for(Method candidate:owner.getMethods()){
                    if(!candidate.getName().equals(name)||candidate.getParameterCount()!=args.length||(target instanceof Class<?> && !Modifier.isStatic(candidate.getModifiers())))continue;
                    Class<?>[] types=candidate.getParameterTypes();boolean matches=true;
                    for(int i=0;i<args.length;i++)if(args[i]!=null&&!boxed(types[i]).isInstance(args[i])){matches=false;break;}else if(args[i]==null&&types[i].isPrimitive()){matches=false;break;}
                    if(matches){method=candidate;break;}
                }
                if(method==null)throw new NoSuchMethodException(owner.getName()+"."+name);
                method.trySetAccessible();methods.put(key,method);
            }
            try{return method.invoke(target instanceof Class<?> ?null:target,args);}catch(InvocationTargetException e){throw new ReflectiveOperationException("Provider invocation failed",e.getCause());}
        }
        private static Class<?> boxed(Class<?> type){if(!type.isPrimitive())return type;return Map.of(boolean.class,Boolean.class,int.class,Integer.class,long.class,Long.class,double.class,Double.class,float.class,Float.class,byte.class,Byte.class,short.class,Short.class,char.class,Character.class).get(type);}
        static boolean yes(Object value){if(!(value instanceof Boolean b))throw new IllegalStateException("Provider returned no boolean decision");return b;}
        Object enumValue(Class<?> type,String name){if(!type.isEnum())throw new IllegalStateException("Provider enum missing");for(Object value:type.getEnumConstants())if(((Enum<?>)value).name().equals(name))return value;throw new IllegalStateException("Provider action missing: "+name);}
    }
    private static final class Towny extends ReflectiveHook {
        final Object api; final Class<?> cache,actions,combat; final Plugin provider;
        Towny(Plugin p)throws ReflectiveOperationException{super(p);provider=p;api=invoke(type("com.palmergames.bukkit.towny.TownyAPI"),"getInstance");cache=type("com.palmergames.bukkit.towny.utils.PlayerCacheUtil");actions=type("com.palmergames.bukkit.towny.object.TownyPermission$ActionType");combat=type("com.palmergames.bukkit.towny.utils.CombatUtil");}
        public boolean supports(World world)throws ReflectiveOperationException{return yes(invoke(api,"isTownyWorld",world));}
        public boolean claimed(Location at)throws ReflectiveOperationException{return invoke(api,"getTownBlock",at)!=null;}
        public boolean allows(Player p,Location at,Material m,Action a)throws ReflectiveOperationException{
            if(a==Action.PVP)return yes(invoke(api,"isPVP",at));
            if(p==null)return !claimed(at)&&yes(invoke(api,"areMobsEnabled",at));
            String action=switch(a){case BUILD,SPAWN->"BUILD";case BREAK->"DESTROY";default->"SWITCH";};
            return yes(invoke(cache,"getCachePermission",p,at,m,enumValue(actions,action)));
        }
        public boolean pvp(Player a,Player b,Location at)throws ReflectiveOperationException{
            Object cause=enumValue(type("org.bukkit.event.entity.EntityDamageEvent$DamageCause"),"ENTITY_ATTACK");
            try{return !yes(invoke(combat,"preventDamageCall",a,b,cause));}
            catch(NoSuchMethodException legacy){return !yes(invoke(combat,"preventDamageCall",provider,a,b,cause));}
        }
    }
    private static final class GriefPrevention extends ReflectiveHook {
        final Object api,store;final Plugin provider;
        GriefPrevention(Plugin p)throws ReflectiveOperationException{super(p);provider=p;api=field(type("me.ryanhamshire.GriefPrevention.GriefPrevention"),"instance");store=api.getClass().getField("dataStore").get(api);}
        public boolean supports(World w)throws ReflectiveOperationException{return yes(invoke(api,"claimsEnabledForWorld",w));}
        public boolean supportsPvP(World w)throws ReflectiveOperationException{return supports(w)||yes(invoke(api,"pvpRulesApply",w));}
        private Object claim(Location at)throws ReflectiveOperationException{return invoke(store,"getClaimAt",at,false,null);}
        public boolean claimed(Location at)throws ReflectiveOperationException{return claim(at)!=null;}
        public boolean allows(Player p,Location at,Material m,Action a)throws ReflectiveOperationException{
            Object claim=claim(at);
            if(a==Action.PVP)return !yes(invoke(api,"pvpRulesApply",at.getWorld()))||claim==null||!yes(invoke(api,"claimIsPvPSafeZone",claim));
            if(p==null)return claim==null;
            if(a==Action.BUILD||a==Action.SPAWN)return invoke(api,"allowBuild",p,at,m)==null;
            if(claim==null)return a!=Action.BREAK||invoke(api,"allowBuild",p,at,m)==null;
            return switch(a){case BREAK->invoke(claim,"allowBreak",p,m)==null;case TRADE->invoke(claim,"allowContainers",p)==null;case INTERACT->invoke(claim,container(m)?"allowContainers":"allowAccess",p)==null;default->invoke(claim,"allowAccess",p)==null;};
        }
        public boolean pvp(Player a,Player b,Location at)throws ReflectiveOperationException{
            if(!yes(invoke(api,"pvpRulesApply",at.getWorld())))return true;
            Object attacker=invoke(store,"getPlayerData",a.getUniqueId()),defender=invoke(store,"getPlayerData",b.getUniqueId());
            if(api.getClass().getField("config_pvp_protectFreshSpawns").getBoolean(api)&&(attacker.getClass().getField("pvpImmune").getBoolean(attacker)||defender.getClass().getField("pvpImmune").getBoolean(defender)))return false;
            if(attacker.getClass().getField("ignoreClaims").getBoolean(attacker))return true;
            Object[] data={attacker,defender};Location[] locations={a.getLocation(),b.getLocation()};
            for(int i=0;i<2;i++){
                if(yes(invoke(data[i],"inPvpCombat")))continue;
                Object claim=claim(locations[i]);if(claim==null||!yes(invoke(api,"claimIsPvPSafeZone",claim)))continue;
                Class<?> eventType=type("me.ryanhamshire.GriefPrevention.events.PreventPvPEvent");
                org.bukkit.event.Event event=(org.bukkit.event.Event)eventType.getConstructor(type("me.ryanhamshire.GriefPrevention.Claim"),Player.class,Player.class).newInstance(claim,a,b);
                provider.getServer().getPluginManager().callEvent(event);if(!((org.bukkit.event.Cancellable)event).isCancelled())return false;
            }
            return true;
        }
    }
    private static final class WorldGuard extends ReflectiveHook {
        final Object api,platform,container,query;final Plugin provider;final Class<?> adapter,flags,state;
        WorldGuard(Plugin p)throws ReflectiveOperationException{super(p);provider=p;api=invoke(type("com.sk89q.worldguard.WorldGuard"),"getInstance");platform=invoke(api,"getPlatform");container=invoke(platform,"getRegionContainer");query=invoke(container,"createQuery");adapter=type("com.sk89q.worldedit.bukkit.BukkitAdapter");flags=type("com.sk89q.worldguard.protection.flags.Flags");state=type("com.sk89q.worldguard.protection.flags.StateFlag");}
        public boolean supports(World w)throws ReflectiveOperationException{
            Object settings=invoke(invoke(platform,"getGlobalStateManager"),"get",invoke(adapter,"adapt",w));
            return settings.getClass().getField("useRegions").getBoolean(settings);
        }
        public boolean claimed(Location at)throws ReflectiveOperationException{
            Object regions=invoke(query,"getApplicableRegions",invoke(adapter,"adapt",at));
            if(!(regions instanceof Iterable<?> iterable))throw new IllegalStateException("Region result is not iterable");
            for(Object region:iterable)if(!"__global__".equals(invoke(region,"getId")))return true;return false;
        }
        public boolean allows(Player p,Location at,Material m,Action a)throws ReflectiveOperationException{
            Object local=p==null?null:invoke(provider,"wrapPlayer",p),where=invoke(adapter,"adapt",at);
            if(p!=null&&a!=Action.PVP&&yes(invoke(invoke(platform,"getSessionManager"),"hasBypass",local,invoke(adapter,"adapt",at.getWorld()))))return true;
            String flag=switch(a){case BREAK->"BLOCK_BREAK";case BUILD->"BLOCK_PLACE";case TELEPORT,TRACK->"ENTRY";case SPAWN->"MOB_SPAWNING";case PVP->"PVP";case EVENT->"MOB_DAMAGE";case TRADE->"CHEST_ACCESS";case INTERACT->container(m)?"CHEST_ACCESS":"INTERACT";default->"INTERACT";};
            Object values=Array.newInstance(state,1);Array.set(values,0,field(flags,flag));
            if(a==Action.SPAWN&&p!=null&&!yes(invoke(query,"testBuild",where,local,Array.newInstance(state,0))))return false;
            if(p==null&&(a==Action.BUILD||a==Action.BREAK||a==Action.INTERACT||a==Action.TRADE))return !claimed(at)&&yes(invoke(query,"testState",where,null,values));
            boolean build=Set.of(Action.BUILD,Action.BREAK,Action.INTERACT,Action.TRADE,Action.PROGRESS).contains(a);
            return yes(invoke(query,build?"testBuild":"testState",where,local,values));
        }
    }
    private static final class Lands extends ReflectiveHook {
        final Object api;final Class<?> flags;
        Lands(Plugin p,Plugin host)throws ReflectiveOperationException{super(p);api=invoke(type("me.angeschossen.lands.api.LandsIntegration"),"of",host);flags=type("me.angeschossen.lands.api.flags.type.Flags");}
        public boolean supports(World w)throws ReflectiveOperationException{return invoke(api,"getWorld",w)!=null;}
        private Object area(Location at)throws ReflectiveOperationException{return invoke(api,"getUnloadedArea",at);}
        public boolean claimed(Location at)throws ReflectiveOperationException{return area(at)!=null;}
        public boolean allows(Player p,Location at,Material m,Action a)throws ReflectiveOperationException{
            Object area=area(at);if(p==null)return area==null;
            Object landPlayer=invoke(api,"getLandPlayer",p.getUniqueId());
            if(area!=null&&yes(invoke(area,"isBanned",p.getUniqueId())))return false;
            if(area!=null&&(a==Action.TELEPORT||a==Action.TRACK))return yes(invoke(area,"canEnter",landPlayer,false));
            String key=switch(a){case BREAK->"BLOCK_BREAK";case BUILD,SPAWN->"BLOCK_PLACE";case PVP->"ATTACK_PLAYER";case TELEPORT,TRACK->"LAND_ENTER";case TRADE->"INTERACT_CONTAINER";case INTERACT->container(m)?"INTERACT_CONTAINER":"INTERACT_GENERAL";default->"INTERACT_GENERAL";};
            Object flag=field(flags,key);if(flag==null)throw new IllegalStateException("Lands flag uninitialized");
            return area!=null?yes(invoke(area,"hasRoleFlag",landPlayer,flag,m,false)):yes(invoke(invoke(api,"getWorld",at.getWorld()),"hasWildernessRoleFlag",landPlayer,at,flag,false));
        }
        public boolean pvp(Player a,Player b,Location at)throws ReflectiveOperationException{return yes(invoke(api,"canPvP",a,b,at,false,false));}
    }
    private static final class BentoBox extends ReflectiveHook {
        final Object api,worlds,islands;final Class<?> flags,users;
        BentoBox(Plugin p)throws ReflectiveOperationException{super(p);api=invoke(type("world.bentobox.bentobox.BentoBox"),"getInstance");worlds=invoke(api,"getIWM");islands=invoke(api,"getIslands");flags=type("world.bentobox.bentobox.lists.Flags");users=type("world.bentobox.bentobox.api.user.User");}
        public boolean supports(World w)throws ReflectiveOperationException{return yes(invoke(worlds,"inWorld",w));}
        private Object island(Location at)throws ReflectiveOperationException{Object result=invoke(islands,"getProtectedIslandAt",at);if(!(result instanceof java.util.Optional<?> o))throw new IllegalStateException("Island result is not Optional");return o.orElse(null);}
        public boolean claimed(Location at)throws ReflectiveOperationException{return island(at)!=null;}
        public boolean allows(Player p,Location at,Material m,Action a)throws ReflectiveOperationException{
            Object island=island(at);
            String flag=switch(a){case BREAK->"BREAK_BLOCKS";case BUILD->"PLACE_BLOCKS";case SPAWN->"SPAWN_EGGS";case TELEPORT,TRACK->"LOCK";case EVENT->"MONSTER_SPAWN";case PVP->switch(at.getWorld().getEnvironment()){case NETHER->"PVP_NETHER";case THE_END->"PVP_END";default->"PVP_OVERWORLD";};default->"CONTAINER";};
            Object setting=field(flags,flag);
            if(island==null)return yes(invoke(setting,"isSetForWorld",at.getWorld()))||(p!=null&&a!=Action.PVP&&a!=Action.EVENT&&bypass(p,at,setting,false));
            if(a==Action.PVP)return yes(invoke(island,"isAllowed",setting));
            if(p==null)return false;
            if(yes(invoke(island,"isDeleted"))||yes(invoke(island,"isDeletable")))return bypass(p,at,setting,false);
            if(yes(invoke(island,"isBanned",p.getUniqueId()))&&!p.isOp())return false;
            Object user=invoke(users,"getInstance",p);
            if(a==Action.EVENT)return yes(invoke(island,"isAllowed",setting))&&yes(invoke(island,"isAllowed",user,field(flags,"PLACE_BLOCKS")));
            return yes(invoke(island,"isAllowed",user,setting))||bypass(p,at,setting,true);
        }
        private boolean bypass(Player p,Location at,Object flag,boolean island)throws ReflectiveOperationException{
            Object user=invoke(users,"getInstance",p),switched=invoke(user,"getMetaData",field(type("world.bentobox.bentobox.api.commands.admin.AdminSwitchCommand"),"META_TAG"));
            if(!(switched instanceof java.util.Optional<?> option))throw new IllegalStateException("Admin metadata not Optional");
            if(option.isPresent()&&yes(invoke(option.get(),"asBoolean")))return false;
            String prefix=String.valueOf(invoke(worlds,"getPermissionPrefix",at.getWorld())),id=String.valueOf(invoke(flag,"getID"));
            return p.isOp()||yes(invoke(user,"hasPermission",prefix+"mod.bypassprotect"))||yes(invoke(user,"hasPermission",prefix+"mod.bypass."+id+".everywhere"))||(island&&yes(invoke(user,"hasPermission",prefix+"mod.bypass."+id+".island")));
        }
    }
    private static final class Superior extends ReflectiveHook {
        final Class<?> api,privileges,flags;final Object grid,regions;final Plugin provider;
        Superior(Plugin p)throws ReflectiveOperationException{super(p);provider=p;api=type("com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI");grid=invoke(api,"getGrid");privileges=type("com.bgsoftware.superiorskyblock.api.island.IslandPrivilege");flags=type("com.bgsoftware.superiorskyblock.api.island.IslandFlag");regions=invoke(invoke(p,"getServices"),"getService",type("com.bgsoftware.superiorskyblock.api.service.region.RegionManagerService"));if(regions==null)throw new IllegalStateException("Superior region service not ready");}
        public boolean supports(World w)throws ReflectiveOperationException{
            if(yes(invoke(grid,"isIslandsWorld",w)))return true;
            if(!yes(invoke(invoke(invoke(provider,"getSettings"),"getSpawn"),"isProtected")))return false;
            Object spawn=invoke(grid,"getSpawnIsland");if(spawn==null)return false;
            Method center=type("com.bgsoftware.superiorskyblock.api.island.Island").getMethod("getCenter",type("com.bgsoftware.superiorskyblock.api.world.Dimension"));
            Object at=center.invoke(spawn,new Object[]{null});return at instanceof Location location&&w.equals(location.getWorld());
        }
        private Object island(Location at)throws ReflectiveOperationException{return invoke(api,"getIslandAt",at);}
        public boolean claimed(Location at)throws ReflectiveOperationException{return island(at)!=null;}
        public boolean allows(Player p,Location at,Material m,Action a)throws ReflectiveOperationException{
            Object island=island(at);
            if(a==Action.PVP)return island==null||yes(invoke(island,"hasSettingsEnabled",invoke(flags,"getByName","PVP")));
            if(p==null)return island==null&&a==Action.EVENT;
            Object superior=invoke(api,"getPlayer",p.getUniqueId());
            if(yes(invoke(superior,"hasBypassModeEnabled")))return true;
            if(island!=null&&yes(invoke(island,"isBanned",superior)))return false;
            if(a==Action.TELEPORT||a==Action.TRACK)return island==null||!yes(invoke(island,"isLocked"))||yes(invoke(island,"isMember",superior))||yes(invoke(island,"hasPermission",superior,invoke(privileges,"getByName","CLOSE_BYPASS")));
            String key=switch(a){case BREAK->"BREAK";case TRADE->"VILLAGER_TRADING";default->"BUILD";};
            if(island==null){Object permissions=invoke(invoke(provider,"getSettings"),"getWorldPermissions");if(!(permissions instanceof Iterable<?> values))throw new IllegalStateException("World permissions not iterable");boolean allowed=false;for(Object value:values)allowed|=key.equalsIgnoreCase(String.valueOf(value));if(!allowed)return false;}
            if(island!=null&&Set.of(Action.BREAK,Action.BUILD,Action.SPAWN).contains(a)&&yes(invoke(island,"isBeingRecalculated")))return false;
            Object result=invoke(regions,"handleCustomInteraction",superior,at,invoke(privileges,"getByName",key));
            return result instanceof Enum<?> e&&e.name().equals("SUCCESS");
        }
        public boolean pvp(Player a,Player b,Location at)throws ReflectiveOperationException{Object result=invoke(invoke(api,"getPlayer",a.getUniqueId()),"canHit",invoke(api,"getPlayer",b.getUniqueId()));return result instanceof Enum<?> e&&e.name().equals("SUCCESS");}
    }
}
