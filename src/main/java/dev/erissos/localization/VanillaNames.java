package dev.erissos.localization;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import com.google.gson.JsonParser;

/** Plain standard names in the plugin's selected language, independent of the client locale. */
public final class VanillaNames {
 private VanillaNames(){}
 private static final List<String> CODES=List.of("en","tr","de","es","fr","it","pt","ru","zh","ja","cs","sk","ro","ar","bg","el","nl","sv");
 private static final Map<String,Map<String,String>> WORDS=load();
 private static Map<String,Map<String,String>> load(){
  Map<String,Map<String,String>> languages=new HashMap<>();
  for(String code:CODES)try(InputStream stream=VanillaNames.class.getResourceAsStream("/vanilla-names/"+code+".json")){
   if(stream==null){if(CODES.indexOf(code)>=10)continue;throw new IllegalStateException("Missing standard names: "+code);}
   var data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
   Map<String,String> words=new HashMap<>();for(var entry:data.entrySet())if(entry.getValue().isJsonPrimitive()&&entry.getValue().getAsJsonPrimitive().isString())words.put(entry.getKey(),entry.getValue().getAsString());
   languages.put(code,Map.copyOf(words));
  }catch(Exception e){throw new IllegalStateException("Cannot load standard names: "+code,e);}
  return Map.copyOf(languages);
 }
 public static String translate(String language,String key,String fallback){
  String code=language==null?"en":language.toLowerCase(Locale.ROOT).replace('-','_').split("_")[0];
  return WORDS.getOrDefault(code,WORDS.get("en")).getOrDefault(key,WORDS.get("en").getOrDefault(key,fallback));
 }
 public static String item(Material material,String language){
  String key=material.getKey().getKey();String fallback=humanize(key);
  String block=translate(language,"block.minecraft."+key,fallback);
  return translate(language,"item.minecraft."+key,block);
 }
 public static String entity(EntityType entity,String language){
  var key=entity.getKey();return key==null?humanize(entity.name()):translate(language,"entity."+key.getNamespace()+"."+key.getKey(),humanize(key.getKey()));
 }
 private static String humanize(String key){String text=key.toLowerCase(Locale.ROOT).replace('_',' ');return text.isEmpty()?text:text.substring(0,1).toUpperCase(Locale.ROOT)+text.substring(1);}
 /** Accept natural names, ASCII Turkish typing and internal IDs in search without changing IDs. */
 public static String searchKey(String input){String normalized=Normalizer.normalize(input.replace('ı','i'),Normalizer.Form.NFD);StringBuilder out=new StringBuilder();normalized.codePoints().filter(c->Character.getType(c)!=Character.NON_SPACING_MARK).forEach(out::appendCodePoint);return out.toString().toLowerCase(Locale.ROOT).replace('_',' ').strip();}
}
