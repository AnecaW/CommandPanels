package org.wannes.commandpanels;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Versioned snapshots; no persistence occurs in inventory click handlers. */
public final class PanelStore {
    public static final Gson JSON=new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();
    public static final int MAX_BYTES=2_000_000;
    private final Path root;
    public PanelStore(Path root) throws IOException {
        Files.createDirectories(root);
        if(Files.isSymbolicLink(root)) throw new IOException("Module directory cannot be a symbolic link");
        this.root=root.toRealPath(); Files.createDirectories(path("config"));
    }
    public Path path(String relative) throws IOException {
        Path result=root.resolve(relative).normalize();
        if(!result.startsWith(root)||relative.contains("..")||relative.contains(":")) throw new IOException("Invalid module storage path");
        Path cursor=root;
        for(Path part:root.relativize(result)) { cursor=cursor.resolve(part); if(Files.isSymbolicLink(cursor)) throw new IOException("Storage symlink forbidden"); }
        return result;
    }
    public PanelModel.State load() throws IOException {
        Path file=path("config/panels.json");
        if(!Files.exists(file)) return PanelModel.State.empty();
        if(Files.size(file)>MAX_BYTES) throw new IOException("Panel configuration exceeds size limit");
        try(var input=Files.newInputStream(file)) {
            byte[] bytes=input.readNBytes(MAX_BYTES+1); if(bytes.length>MAX_BYTES) throw new IOException("Panel configuration exceeds size limit");
            var object=JSON.fromJson(new String(bytes,StandardCharsets.UTF_8),JsonObject.class);
            if(object==null) throw new IOException("Panel configuration is empty; existing file retained");
            integer(object,"schemaVersion",false); integer(object,"revision",false); bool(object,"proxyEnabled");
            if(!object.has("panels")||!object.get("panels").isJsonObject()) throw new IllegalArgumentException("Panels must be an object");
            for(var entry:object.getAsJsonObject("panels").entrySet()) validatePanelJson(entry.getValue().getAsJsonObject());
            var state=JSON.fromJson(object,PanelModel.State.class);
            if(state==null) throw new IOException("Panel configuration is empty; existing file retained");
            return state.validate();
        }
    }
    public void save(PanelModel.State state) throws IOException {
        state.validate(); byte[] bytes=JSON.toJson(state).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) throw new IOException("Panel configuration exceeds size limit");
        Path target=path("config/panels.json"), temp=Files.createTempFile(path("config"),"panels-",".tmp");
        try {
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)) { var buffer=java.nio.ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true); }
            if(Files.exists(target)) Files.copy(target,path("config/panels.previous.json"),StandardCopyOption.REPLACE_EXISTING);
            path("config/panels.json"); Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    public static PanelModel.Panel parse(String json) {
        if(json==null || json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new IllegalArgumentException("Missing or oversized panel");
        JsonObject object=JSON.fromJson(json,JsonObject.class);
        if(object==null) throw new IllegalArgumentException("Panel definition required");
        if(object.has("schemaVersion")) {
            integer(object,"schemaVersion",false);
            if(object.get("schemaVersion").getAsInt()!=1) throw new IllegalArgumentException("Unsupported export version");
            object=object.getAsJsonObject("panel");
        }
        validatePanelJson(object);
        var panel=JSON.fromJson(object,PanelModel.Panel.class);
        if(panel==null) throw new IllegalArgumentException("Panel definition required"); return panel.validate();
    }
    public static String export(PanelModel.Panel panel) { return JSON.toJson(Map.of("schemaVersion",1,"panel",panel)); }
    private static void validatePanelJson(JsonObject panel) {
        if(panel==null) throw new IllegalArgumentException("Panel object is required");
        for(String key:List.of("id","name","command","title","permission")) string(panel,key);
        integer(panel,"size",false); bool(panel,"enabled");
        if(!panel.has("items")||!panel.get("items").isJsonObject()) throw new IllegalArgumentException("Items must be an object keyed by slot");
        for(var entry:panel.getAsJsonObject("items").entrySet()) {
            if(!entry.getKey().matches("0|[1-9]|[1-4][0-9]|5[0-3]")) throw new IllegalArgumentException("Invalid item slot: "+entry.getKey());
            var item=entry.getValue().getAsJsonObject();
            for(String key:List.of("material","name","itemModel")) string(item,key);
            integer(item,"amount",false); integer(item,"customModelData",true); bool(item,"glint");
            for(String key:List.of("lore","flags")) { if(!item.has(key)||!item.get(key).isJsonArray()) throw new IllegalArgumentException(key+" must be an array"); for(var value:item.getAsJsonArray(key)) if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key+" must contain strings"); }
            if(!item.has("actions")||!item.get("actions").isJsonArray()) throw new IllegalArgumentException("Actions must be an array");
            for(var value:item.getAsJsonArray("actions")) { var action=value.getAsJsonObject(); for(String key:List.of("type","value","click","permission")) string(action,key); bool(action,"authorized"); }
        }
    }
    private static void string(JsonObject object,String key) { if(!object.has(key)||!object.get(key).isJsonPrimitive()||!object.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key+" must be a string"); }
    private static void bool(JsonObject object,String key) { if(!object.has(key)||!object.get(key).isJsonPrimitive()||!object.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException(key+" must be a boolean"); }
    private static void integer(JsonObject object,String key,boolean nullable) {
        if(nullable&&(!object.has(key)||object.get(key).isJsonNull())) return;
        if(!object.has(key)||!object.get(key).isJsonPrimitive()||!object.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException(key+" must be an integer");
        try { if(key.equals("revision")) object.getAsJsonPrimitive(key).getAsBigDecimal().longValueExact(); else object.getAsJsonPrimitive(key).getAsBigDecimal().intValueExact(); }
        catch(ArithmeticException failure) { throw new IllegalArgumentException(key+" must be an integer in range"); }
    }
}
