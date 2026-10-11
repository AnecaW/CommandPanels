package org.wannes.commandpanels;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.wannes.plugModCore.api.*;
import java.io.*;
import java.util.*;

/** BungeeCord-compatible channel also supported by explicitly configured Velocity proxies. */
final class ProxyBridge implements PluginMessageListener {
    private final ModuleContext context;
    private record Discovery(Set<String> servers,long expires) {}
    private final Map<UUID,Discovery> discoveries=new HashMap<>();
    private final Map<UUID,Long> requests=new HashMap<>();
    boolean enabled;
    ProxyBridge(ModuleContext context) {
        this.context=context;
        context.registerOutgoingChannel("BungeeCord"); context.registerIncomingChannel("BungeeCord",this);
    }
    void configure(boolean enabled) { this.enabled=enabled; discoveries.clear(); requests.clear(); }
    Set<String> servers() {
        var bridge=context.getOptionalService(ServerTransferService.class);
        if(bridge!=null) return Set.copyOf(bridge.servers());
        if(!enabled) return Set.of();
        Set<String> names=new TreeSet<>();long now=System.currentTimeMillis();
        for(var discovery:discoveries.values()) if(now<discovery.expires()) names.addAll(discovery.servers());
        return Set.copyOf(names);
    }
    void discover(Player player) {
        var discovery=discoveries.get(player.getUniqueId());
        if(!enabled || !player.isOnline() || (discovery!=null&&System.currentTimeMillis()<discovery.expires())) return;
        long now=System.currentTimeMillis(); if(now-requests.getOrDefault(player.getUniqueId(),0L)<5000) return;
        requests.put(player.getUniqueId(),now);
        send(player,"GetServers",null);
    }
    private void send(Player player,String type,String value) {
        try { var bytes=new ByteArrayOutputStream(); var output=new DataOutputStream(bytes); output.writeUTF(type); if(value!=null) output.writeUTF(value); player.sendPluginMessage(context.getHostPlugin(),"BungeeCord",bytes.toByteArray()); }
        catch(IOException failure) { throw new IllegalStateException("Cannot encode proxy request",failure); }
    }
    public void onPluginMessageReceived(String channel,Player player,byte[] bytes) {
        if(!enabled || !channel.equals("BungeeCord") || bytes.length>32767) return;
        Long requested=requests.remove(player.getUniqueId()); if(requested==null||System.currentTimeMillis()-requested>10000) return;
        try { var input=new DataInputStream(new ByteArrayInputStream(bytes)); if(!input.readUTF().equals("GetServers")) return;
            Set<String> found=new TreeSet<>(); for(String name:input.readUTF().split(",\\s*")) if(name.matches("[A-Za-z0-9_.-]{1,64}")) found.add(name);
            discoveries.put(player.getUniqueId(),new Discovery(Set.copyOf(found),System.currentTimeMillis()+30000));
        } catch(IOException ignored) { context.getLogger().fine("Ignored malformed proxy discovery response"); }
    }
    void transfer(Player player,String server) {
        var bridge=context.getOptionalService(ServerTransferService.class);
        if(bridge!=null) { if(!bridge.servers().contains(server)||!bridge.requestTransfer(player,server)) throw new IllegalStateException("Proxy bridge did not accept transfer to "+server); }
        else {
            if(!enabled) throw new IllegalStateException("Server switching requires a configured compatible proxy; enable its integration in CommandPanels settings");
            var discovery=discoveries.get(player.getUniqueId());
            if(discovery==null||System.currentTimeMillis()>=discovery.expires()) { discover(player); throw new IllegalStateException("No recent proxy response for this player's connection. Check BungeeCord/Velocity channel configuration, or reopen the menu after discovery"); }
            if(!discovery.servers().contains(server)) throw new IllegalStateException("Proxy did not advertise server '"+server+"' for this player's connection");
            send(player,"Connect",server);
        }
        player.sendMessage("Server transfer requested. Arrival is confirmed by the proxy, not this menu.");
    }
    void quit(Player player) { requests.remove(player.getUniqueId()); discoveries.remove(player.getUniqueId()); }
}
