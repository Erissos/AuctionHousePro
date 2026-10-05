package dev.desperis.suite;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional suite protocol. Foreign plugin classes are never linked or cast across class loaders. */
public final class SuiteIntegrationService implements Listener, AutoCloseable {
    public static final List<String> PRODUCTS=List.of("StableMan","BetterAdvancements","AuctionHousePro",
        "PlayerBusiness","DynamicBounty","SmartNPCWorkers","ServerEventsEngine","DynamicDungeonGenerator","AdaptiveBosses");
    private static final String ROOT="suite-integrations";
    private static final int RECEIPT_LIMIT=4096;
    private static final List<NamespacedKey> MANAGED=List.of(new NamespacedKey("stableman","stable-animal-id"),
        new NamespacedKey("smartnpcworkers","worker"),new NamespacedKey("adaptivebosses","encounter"),
        new NamespacedKey("dynamicdungeongenerator","session"));
    private final Plugin plugin;
    private final Supplier<FileConfiguration> config;
    private final Map<String,Long> warnings=new ConcurrentHashMap<>();
    private final Map<MethodKey,Method> methods=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,Boolean> receipts=new LinkedHashMap<>();
    private volatile Settings settings=new Settings(true,false,true,Set.copyOf(PRODUCTS));
    private volatile boolean closed;
    private boolean applyingLanguage;
    private BiPredicate<UUID,String> languageReceiver;
    private BiConsumer<Player,Map<String,String>> milestoneReceiver;

    public SuiteIntegrationService(JavaPlugin plugin,Supplier<FileConfiguration> config){this(plugin,config,true);}
    SuiteIntegrationService(Plugin plugin,Supplier<FileConfiguration> config,boolean listen){
        this.plugin=Objects.requireNonNull(plugin);this.config=Objects.requireNonNull(config);
        if(listen)plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public int protocolVersion(){return 1;}
    public void reload(){if(closed)return;Settings candidate=Settings.read(config.get());settings=candidate;}
    public static void validateConfig(FileConfiguration config){Settings.read(config);}
    public void onLanguage(BiPredicate<UUID,String> receiver){languageReceiver=Objects.requireNonNull(receiver);}
    public void onMilestone(BiConsumer<Player,Map<String,String>> receiver){milestoneReceiver=Objects.requireNonNull(receiver);}
    public boolean acceptsLinkFrom(Plugin source){return !closed&&settings.enabled&&source!=null&&source!=plugin
        &&PRODUCTS.contains(source.getName())&&settings.products.contains(source.getName())
        &&source.isEnabled()&&plugin.getServer().getPluginManager().getPlugin(source.getName())==source;}
    public boolean linkEnabled(String product){return peer(product)!=null;}
    public List<String> connectedProducts(){return PRODUCTS.stream().filter(this::linkEnabled).toList();}
    public Object callPeer(String product,String method,Object... arguments){
        if(!onThread())return null;
        Plugin target=peer(product);if(target==null)return null;
        try{return invoke(target,method,arguments);}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(product+"/"+method,failure);return null;}
    }
    /** Only exact receipt-owned encounter completion/cleanup may outlive configuration or enable-state changes. */
    public Object callPeerCleanup(String product,String method,Object... arguments){
        if(!onThread()||!PRODUCTS.contains(product)||!Set.of("suiteRemoveDungeonBoss","suiteLinkedBossAborted","suiteLinkedBossDefeated").contains(method))return null;
        Plugin target=plugin.getServer().getPluginManager().getPlugin(product);
        if(target==null||!target.isEnabled()||target==plugin)return null;
        try{if(!Integer.valueOf(1).equals(invoke(bridge(target),"protocolVersion")))return null;return invoke(target,method,arguments);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(product+"/owned-"+method,failure);return null;}
    }
    public void publishLanguage(UUID player,String code){
        if(player==null||code==null||applyingLanguage||!onThread()||!settings.enabled||!settings.language||closed)return;
        String normalized=normalizeLanguage(code);if(normalized==null)return;
        for(String name:PRODUCTS){Plugin target=peer(name);if(target==null)continue;
            try{Object bridge=bridge(target);invoke(bridge,"acceptLanguage",plugin,player,normalized);}
            catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(name+"/language",failure);}
        }
    }
    public boolean acceptLanguage(Plugin source,UUID player,String code){
        if(!onThread()||!acceptsLinkFrom(source)||!settings.language||languageReceiver==null||player==null)return false;
        String normalized=normalizeLanguage(code);if(normalized==null)return false;
        applyingLanguage=true;
        try{return languageReceiver.test(player,normalized);}
        catch(RuntimeException|LinkageError failure){warn(source.getName()+"/language",failure);return false;}
        finally{applyingLanguage=false;}
    }
    private static String normalizeLanguage(String code){
        if(code==null)return null;String value=code.toLowerCase(Locale.ROOT).replace('-','_');
        String normalized=value.split("_",2)[0];
        return normalized.matches("[a-z]{2,3}")&&!normalized.equals("auto")?normalized:null;
    }
    /** Live observers only: source emits after its authoritative success; no historic replay or cross-wallet transfer. */
    public void milestone(UUID player,String event,String receipt,long amount,Map<String,String> details){
        if(!onThread()||player==null||closed||!settings.enabled||!settings.milestones)return;
        Plugin target=peer("BetterAdvancements");if(target==null)return;
        try{invoke(bridge(target),"acceptMilestone",plugin,player,event,receipt,amount,details==null?Map.of():details);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn("BetterAdvancements/milestone",failure);}
    }
    public boolean acceptMilestone(Plugin source,UUID player,String event,String receipt,long amount,Map<String,String> details){
        if(!onThread()||!acceptsLinkFrom(source)||!settings.milestones||milestoneReceiver==null||player==null)return false;
        if(event==null||!event.matches("[a-z][a-z0-9_]{0,63}")||receipt==null||receipt.isBlank()||receipt.length()>256
            ||receipt.codePoints().anyMatch(Character::isISOControl)||amount<=0||amount>1_000_000)return false;
        Player actor=plugin.getServer().getPlayer(player);if(actor==null||!actor.isOnline()||actor.hasMetadata("NPC"))return false;
        Map<String,String> context=new LinkedHashMap<>();
        if(details!=null){if(details.size()>24)return false;for(var detail:details.entrySet()){
            String key=detail.getKey(),value=detail.getValue();
            if(key==null||!key.matches("[a-z][a-z0-9_-]{0,31}")||value==null||value.length()>256
                ||value.codePoints().anyMatch(Character::isISOControl))return false;
            if(!List.of("source","event","amount").contains(key))context.put(key,value);
        }}
        context.put("source",source.getName());context.put("event",event);context.put("amount",Long.toString(amount));
        String id=source.getName()+"/"+event+"/"+player+"/"+receipt;
        if(receipts.containsKey(id))return false;
        // Mark first to isolate partially failing observers. Observations are not retried as money operations.
        receipts.put(id,true);if(receipts.size()>RECEIPT_LIMIT)receipts.remove(receipts.keySet().iterator().next());
        try{milestoneReceiver.accept(actor,Map.copyOf(context));return true;}
        catch(RuntimeException|LinkageError failure){warn(source.getName()+"/milestone",failure);return false;}
    }
    /** Baseline event safety remains available even when optional commercial links are disabled. */
    public boolean isSynthetic(Event event){
        if(event==null||!onThread())return false;
        Plugin workers=plugin.getServer().getPluginManager().getPlugin("SmartNPCWorkers");
        if(workers==null||!workers.isEnabled())return false;
        try{return Boolean.TRUE.equals(invoke(workers,"suiteIsProtectionProbe",event));}
        catch(NoSuchMethodException unsupported){return false;}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn("SmartNPCWorkers/probe",failure);return true;}
    }
    public boolean isManaged(Entity entity){
        if(entity==null||!onThread())return false;if(entity.hasMetadata("NPC"))return true;
        for(NamespacedKey key:MANAGED)if(entity.getPersistentDataContainer().getKeys().contains(key))return true;
        return false;
    }
    public boolean isDungeonParticipant(UUID player){return Boolean.TRUE.equals(callPeer("DynamicDungeonGenerator","suiteIsParticipant",player));}
    public boolean isDungeonLocation(Location location){return Boolean.TRUE.equals(callPeer("DynamicDungeonGenerator","suiteIsLocation",location));}
    private Plugin peer(String name){
        if(closed||!settings.enabled||!onThread()||name==null||name.equals(plugin.getName())||!settings.products.contains(name))return null;
        Plugin target=plugin.getServer().getPluginManager().getPlugin(name);if(target==null||!target.isEnabled())return null;
        try{Object bridge=bridge(target);if(!Integer.valueOf(1).equals(invoke(bridge,"protocolVersion")))return null;
            return Boolean.TRUE.equals(invoke(bridge,"acceptsLinkFrom",plugin))?target:null;
        }catch(NoSuchMethodException oldProduct){return null;}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(name+"/protocol",failure);return null;}
    }
    private Object bridge(Plugin target)throws ReflectiveOperationException{
        try{return invoke(target,"getSuiteIntegrations");}catch(NoSuchMethodException legacy){return invoke(target,"suite");}
    }
    private boolean onThread(){return plugin.getServer().isPrimaryThread();}
    private record MethodKey(Class<?> owner,String name,List<Class<?>> arguments){}
    private Object invoke(Object target,String name,Object... args)throws ReflectiveOperationException{
        if(target==null)throw new NoSuchMethodException(name+" unavailable");
        List<Class<?>> types=java.util.Arrays.stream(args).<Class<?>>map(a->a==null?Void.class:a.getClass()).toList();
        MethodKey key=new MethodKey(target.getClass(),name,types);Method method=methods.get(key);
        if(method==null){for(Method candidate:target.getClass().getMethods()){
            if(!candidate.getName().equals(name)||candidate.getParameterCount()!=args.length)continue;
            Class<?>[] parameters=candidate.getParameterTypes();boolean matches=true;
            for(int i=0;i<args.length;i++)if(args[i]==null?parameters[i].isPrimitive():!boxed(parameters[i]).isInstance(args[i])){matches=false;break;}
            if(matches){candidate.trySetAccessible();method=candidate;methods.put(key,method);break;}
        }}
        if(method==null)throw new NoSuchMethodException(target.getClass().getName()+"."+name);
        try{return method.invoke(target,args);}catch(InvocationTargetException ex){throw new ReflectiveOperationException("Suite peer rejected "+name,ex.getCause());}
    }
    private static Class<?> boxed(Class<?> type){if(!type.isPrimitive())return type;return switch(type.getName()){
        case "int"->Integer.class;case "long"->Long.class;case "boolean"->Boolean.class;case "double"->Double.class;
        case "float"->Float.class;case "byte"->Byte.class;case "short"->Short.class;case "char"->Character.class;default->type;};}
    private void warn(String key,Throwable failure){long now=System.currentTimeMillis();Long last=warnings.get(key);
        if(last==null||now-last>=60_000){warnings.put(key,now);plugin.getLogger().warning("Optional suite link "+key+" unavailable: "+failure.getClass().getSimpleName()+"; primary operation retained.");}}
    @EventHandler public void disabled(PluginDisableEvent event){ClassLoader loader=event.getPlugin().getClass().getClassLoader();methods.keySet().removeIf(key->key.owner().getClassLoader()==loader);}
    @Override public void close(){closed=true;languageReceiver=null;milestoneReceiver=null;methods.clear();receipts.clear();HandlerList.unregisterAll(this);}
    private record Settings(boolean enabled,boolean language,boolean milestones,Set<String> products){
        static Settings read(FileConfiguration config){
            section(config,ROOT);section(config,ROOT+".language-sync");section(config,ROOT+".milestones");section(config,ROOT+".providers");section(config,ROOT+".features");
            java.util.HashSet<String> products=new java.util.HashSet<>();
            for(String name:PRODUCTS){String path=ROOT+".providers."+name.toLowerCase(Locale.ROOT);section(config,path);
                if(bool(config,path+".enabled",true))products.add(name);}
            ConfigurationSection providers=config.getConfigurationSection(ROOT+".providers");
            if(providers!=null)for(String id:providers.getKeys(false))if(PRODUCTS.stream().noneMatch(name->name.toLowerCase(Locale.ROOT).equals(id)))throw new IllegalArgumentException("Unknown suite product: "+id);
            return new Settings(bool(config,ROOT+".enabled",true),bool(config,ROOT+".language-sync.enabled",false),
                bool(config,ROOT+".milestones.enabled",true),Set.copyOf(products));
        }
        private static void section(FileConfiguration config,String path){if(config.contains(path)&&!config.isConfigurationSection(path))throw new IllegalArgumentException(path+" must be a YAML section");}
        private static boolean bool(FileConfiguration config,String path,boolean fallback){if(!config.contains(path))return fallback;
            if(!config.isBoolean(path))throw new IllegalArgumentException(path+" must be true/false");return config.getBoolean(path);}
    }
}
