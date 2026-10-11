package org.wannes.integration;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.wannes.plugModCore.api.*;
import java.nio.file.*;
import java.util.*;

/** Native Paper fixture. Never packaged in CommandPanels. */
public final class PanelProbe extends JavaPlugin implements org.bukkit.event.Listener {
    final com.google.gson.Gson json=new com.google.gson.Gson();
    final List<String> executed=new ArrayList<>();
    boolean cancelled; int events; int observedClicks; String error=""; org.bukkit.permissions.PermissionAttachment grant;
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this,this);
        getCommand("fixtureaction").setExecutor((sender,command,label,args)->{executed.add(sender.getName()+":"+String.join(" ",args));return true;});
        getCommand("panelprobe").setExecutor((sender,command,label,args)->{
            try { var player=Bukkit.getPlayerExact("PanelTest"); if(player==null) throw new IllegalStateException("No client");
                switch(args[0]) {
                    case "command" -> Bukkit.dispatchCommand(player,args[1]);
                    case "open" -> getServer().getServicesManager().load(PanelService.class).open(player,args[1]);
                    case "grant" -> { if(grant==null)grant=player.addAttachment(this); grant.setPermission("fixture.panel",Boolean.parseBoolean(args[1])); grant.setPermission("fixture.action",Boolean.parseBoolean(args[1])); player.recalculatePermissions(); }
                    case "click","double" -> { click(player,args[1],Integer.parseInt(args[2])); if(args[0].equals("double"))click(player,args[1],Integer.parseInt(args[2])); }
                    case "drag" -> { var view=player.getOpenInventory(); var event=new InventoryDragEvent(view,new ItemStack(Material.STONE),new ItemStack(Material.STONE,2),false,Map.of(0,new ItemStack(Material.STONE)));getServer().getPluginManager().callEvent(event);cancelled=event.isCancelled();events++; }
                    case "close" -> player.closeInventory();
                    case "placeholder" -> { getServer().getServicesManager().unregisterAll(this); if(Boolean.parseBoolean(args[1]))getServer().getServicesManager().register(PlaceholderService.class,new PlaceholderService(){public String parse(OfflinePlayer p,String value){return value.replace("%fixture_name%",p.getName());}public String parseBrackets(OfflinePlayer p,String value){return parse(p,value);}public Set<String> identifiers(){return Set.of("fixture");}},this,org.bukkit.plugin.ServicePriority.Normal); }
                    default -> throw new IllegalArgumentException("Unknown fixture action");
                }
                error="";
            } catch(Exception failure) { error=failure.toString(); getLogger().warning(error); }
            return true;
        });
        getServer().getScheduler().runTaskTimer(this,()->{
            try { Files.createDirectories(getDataFolder().toPath()); Map<String,Object> result=new LinkedHashMap<>();var player=Bukkit.getPlayerExact("PanelTest");result.put("online",player!=null);result.put("events",events);result.put("observedClicks",observedClicks);result.put("cancelled",cancelled);result.put("executed",executed);result.put("error",error);
                var service=getServer().getServicesManager().load(PanelService.class); result.put("service",service!=null); result.put("registered",org.wannes.plugModCore.api.CommandRegistry.snapshot(getServer()).keySet().stream().filter(n->n.startsWith("commandpanels:")).sorted().toList()); result.put("commandPermissions",org.wannes.plugModCore.api.CommandRegistry.snapshot(getServer()).entrySet().stream().filter(e->e.getKey().startsWith("commandpanels:")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->Objects.toString(e.getValue().getPermission(),""))));result.put("permissionNodes",getServer().getPluginManager().getPermissions().stream().map(org.bukkit.permissions.Permission::getName).filter(n->n.startsWith("commandpanels.")).sorted().toList());
                result.put("listenerCount",org.bukkit.event.HandlerList.getRegisteredListeners(getServer().getPluginManager().getPlugin("PlugModCore")).stream().filter(l->l.getListener().getClass().getName().equals("org.wannes.commandpanels.InventoryRuntime")).count());
                if(player!=null) { var view=player.getOpenInventory();result.put("title",net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(view.title()));result.put("size",view.getTopInventory().getSize());result.put("holder",Objects.toString(view.getTopInventory().getHolder()==null?null:view.getTopInventory().getHolder().getClass().getName(),"none"));result.put("permission",player.hasPermission("fixture.panel"));var location=player.getLocation();result.put("location",List.of(location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch()));List<Object> items=new ArrayList<>();for(var item:view.getTopInventory().getContents()){if(item==null||item.getType().isAir()){items.add(null);continue;}var meta=item.getItemMeta();var record=new LinkedHashMap<String,Object>();record.put("material",item.getType().name());record.put("amount",item.getAmount());record.put("name",meta.hasDisplayName()?net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(meta.displayName()):"");record.put("lore",meta.hasLore()?meta.lore().stream().map(line->net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(line)).toList():List.of());record.put("glint",meta.getEnchantmentGlintOverride());record.put("customModelData",meta.hasCustomModelData()?meta.getCustomModelData():null);record.put("itemModel",meta.hasItemModel()?meta.getItemModel().toString():"");record.put("flags",meta.getItemFlags().stream().map(Enum::name).toList());items.add(record);}result.put("items",items);result.put("menuItemsInPlayerInventory",Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull).filter(i->i.getType()==Material.DIAMOND).count()); }
                Path temporary=getDataFolder().toPath().resolve("status.tmp");Files.writeString(temporary,json.toJson(result));Files.move(temporary,getDataFolder().toPath().resolve("status.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } catch(Exception failure){getLogger().warning(failure.toString());}
        },1,2);
    }
    private void click(Player player,String name,int slot) {
        ClickType type=ClickType.valueOf(name);InventoryAction action=type==ClickType.NUMBER_KEY?InventoryAction.HOTBAR_SWAP:type.isShiftClick()?InventoryAction.MOVE_TO_OTHER_INVENTORY:InventoryAction.PICKUP_ALL;
        var event=type==ClickType.NUMBER_KEY?new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,type,action,0):new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,slot,type,action);
        getServer().getPluginManager().callEvent(event);cancelled=event.isCancelled();events++;
    }
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
    public void observed(InventoryClickEvent event) {
        var holder=event.getView().getTopInventory().getHolder();
        if(holder!=null&&holder.getClass().getName().equals("org.wannes.commandpanels.InventoryRuntime$Menu")) { observedClicks++;cancelled=event.isCancelled(); }
    }
}
