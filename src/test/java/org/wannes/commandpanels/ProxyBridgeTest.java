package org.wannes.commandpanels;

import org.bukkit.entity.Player;
import org.wannes.plugModCore.api.ModuleContext;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;

public class ProxyBridgeTest {
    @Test public void requiresExplicitConfigurationAndVerifiedProxyResponse() throws Exception {
        List<byte[]> sent=new ArrayList<>();List<String> messages=new ArrayList<>();UUID id=UUID.randomUUID();
        var context=(ModuleContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ModuleContext.class},(p,m,a)->m.getName().equals("getLogger")?java.util.logging.Logger.getAnonymousLogger():null);
        var player=(Player)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->switch(m.getName()){case "getUniqueId"->id;case "isOnline"->true;case "sendPluginMessage"->{sent.add((byte[])a[2]);yield null;}case "sendMessage"->{messages.add((String)a[0]);yield null;}default->null;});
        var bridge=new ProxyBridge(context);
        assertThrows(IllegalStateException.class,()->bridge.transfer(player,"survival")); assertTrue(sent.isEmpty());
        bridge.configure(true);assertThrows(IllegalStateException.class,()->bridge.transfer(player,"survival"));assertEquals("GetServers",decode(sent.getFirst()).readUTF());
        var response=new ByteArrayOutputStream();var writer=new DataOutputStream(response);writer.writeUTF("GetServers");writer.writeUTF("lobby, survival");
        bridge.onPluginMessageReceived("BungeeCord",player,response.toByteArray());assertEquals(Set.of("lobby","survival"),bridge.servers());
        bridge.transfer(player,"survival");var connect=decode(sent.getLast());assertEquals("Connect",connect.readUTF());assertEquals("survival",connect.readUTF());assertTrue(messages.getFirst().contains("requested"));
        UUID otherId=UUID.randomUUID();
        var other=(Player)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->switch(m.getName()){case "getUniqueId"->otherId;case "isOnline"->true;case "sendPluginMessage"->{sent.add((byte[])a[2]);yield null;}default->null;});
        assertThrows(IllegalStateException.class,()->bridge.transfer(other,"survival"));assertEquals("GetServers",decode(sent.getLast()).readUTF());
        assertThrows(IllegalStateException.class,()->bridge.transfer(player,"unknown"));
        bridge.quit(player);assertTrue(bridge.servers().isEmpty());
        bridge.configure(false);assertTrue(bridge.servers().isEmpty());bridge.onPluginMessageReceived("BungeeCord",player,response.toByteArray());assertTrue(bridge.servers().isEmpty());
    }
    private static DataInputStream decode(byte[] value){return new DataInputStream(new ByteArrayInputStream(value));}
}
