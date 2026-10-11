package org.wannes.commandpanels;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.wannes.plugModCore.api.PlaceholderService;
import java.util.*;

final class InventoryRuntime implements Listener, AutoCloseable {
    private final CommandPanelsModule module;
    final Map<UUID,Menu> sessions=new HashMap<>();
    final Set<UUID> pending=new HashSet<>();
    private final Set<String> warned=new HashSet<>();
    static final class Menu implements InventoryHolder {
        final UUID player; final PanelModel.Panel panel; final long revision; Inventory inventory;
        Menu(UUID player,PanelModel.Panel panel,long revision) { this.player=player; this.panel=panel; this.revision=revision; }
        public Inventory getInventory() { return inventory; }
    }
    InventoryRuntime(CommandPanelsModule module) { this.module=module; }
    String text(Player player,String value,boolean command) {
        String parsed=value.replace("{player}",player.getName()).replace("{uuid}",player.getUniqueId().toString()).replace("{world}",player.getWorld().getName());
        if(parsed.matches(".*%[^%]+%.*")) {
            try { var service=module.context.getOptionalService(PlaceholderService.class);
                if(service!=null) parsed=service.parse(player,parsed);
                else if(warned.add("placeholder")) module.context.getLogger().warning("CommandPanels placeholder provider is unavailable; unknown display placeholders remain unchanged and commands with unresolved placeholders are rejected");
            } catch(RuntimeException | LinkageError failure) { if(command) throw new IllegalStateException("Placeholder provider unavailable: "+failure.getMessage()); if(warned.add("placeholder-failure")) module.context.getLogger().warning("Placeholder rendering failed: "+failure); }
        }
        if(command) { PanelModel.text(parsed,1024,"Resolved command"); if(parsed.matches(".*%[^%]+%.*") || parsed.contains("{player}") || parsed.contains("{uuid}") || parsed.contains("{world}")) throw new IllegalStateException("Command contains unresolved placeholders"); }
        return parsed;
    }
    Component component(Player player,String value) {
        return TextFormat.parse(text(player,value,false));
    }
    boolean open(Player player,PanelModel.Panel panel) {
        if(!player.isOnline()||module.stopped||module.editing||!panel.enabled()) { player.sendMessage("This panel is currently unavailable."); return false; }
        if(!panel.permission().isEmpty()&&!player.hasPermission(panel.permission())) { player.sendMessage("You do not have permission to open this panel."); return false; }
        Menu holder=new Menu(player.getUniqueId(),panel,module.state.revision());
        holder.inventory=Bukkit.createInventory(holder,panel.size(),component(player,panel.title()));
        for(var entry:panel.items().entrySet()) holder.inventory.setItem(entry.getKey(),item(player,entry.getValue()));
        player.openInventory(holder.inventory); sessions.put(player.getUniqueId(),holder); module.proxy.discover(player); return true;
    }
    ItemStack item(Player player,PanelModel.Item definition) {
        ItemStack item=new ItemStack(Objects.requireNonNull(Material.matchMaterial(definition.material())),definition.amount());
        ItemMeta meta=item.getItemMeta();
        if(!definition.name().isEmpty()) meta.displayName(component(player,definition.name()));
        meta.lore(definition.lore().stream().map(line->component(player,line)).toList());
        if(definition.customModelData()!=null) meta.setCustomModelData(definition.customModelData());
        if(!definition.itemModel().isEmpty()) meta.setItemModel(NamespacedKey.fromString(definition.itemModel()));
        meta.setEnchantmentGlintOverride(definition.glint());
        for(String flag:definition.flags()) meta.addItemFlags(ItemFlag.valueOf(flag));
        item.setItemMeta(meta); return item;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true); // Includes bottom shift-clicks, hotbar swaps, collect-to-cursor etc.
        if(!(event.getWhoClicked() instanceof Player player)||!menu.player.equals(player.getUniqueId())||event.getRawSlot()<0||event.getRawSlot()>=menu.panel.size()) return;
        if(module.stopped||module.editing||menu.revision!=module.state.revision()||sessions.get(player.getUniqueId())!=menu) return;
        if(!menu.panel.permission().isEmpty()&&!player.hasPermission(menu.panel.permission())) { player.closeInventory(); player.sendMessage("Panel access is no longer permitted."); return; }
        String click=switch(event.getClick()) { case LEFT->"LEFT"; case RIGHT->"RIGHT"; case SHIFT_LEFT->"SHIFT_LEFT"; case SHIFT_RIGHT->"SHIFT_RIGHT"; default->null; };
        var item=menu.panel.items().get(event.getRawSlot());
        if(click==null||item==null||item.actions().isEmpty()||!pending.add(player.getUniqueId())) return;
        module.later(()->{
            if(!pending.remove(player.getUniqueId())||!player.isOnline()||sessions.get(player.getUniqueId())!=menu||module.editing||menu.revision!=module.state.revision()) return;
            ActionEngine.execute(item.actions(),click,new ActionEngine.Actor() {
                public boolean online() { return player.isOnline()&&!module.stopped&&(menu.panel.permission().isEmpty()||player.hasPermission(menu.panel.permission())); }
                public boolean permitted(String permission) { return player.hasPermission(permission); }
                public void error(String message) { player.sendMessage(message); module.context.getLogger().fine("Panel "+menu.panel.id()+": "+message); }
                public void perform(PanelModel.Action action) { performAction(player,action); }
            });
        });
    }
    void performAction(Player player,PanelModel.Action action) {
        switch(action.type()) {
            case "message" -> player.sendMessage(component(player,action.value()));
            case "player","console" -> {
                String command=text(player,action.value(),true);
                if(!command.split(" +",2)[0].equals(action.value().split(" +",2)[0])) throw new IllegalStateException("Placeholders cannot change the command being executed");
                if(!Bukkit.dispatchCommand(action.type().equals("console")?Bukkit.getConsoleSender():player,command)) throw new IllegalStateException("Command was not handled: "+command.split(" +",2)[0]);
            }
            case "open" -> { if(!module.open(player,action.value())) throw new IllegalStateException("Target panel could not be opened"); }
            case "close" -> player.closeInventory();
            case "sound" -> { NamespacedKey key=NamespacedKey.fromString(action.value()); Sound sound=Registry.SOUNDS.get(key); if(sound==null) throw new IllegalStateException("Unknown sound: "+key); player.playSound(player.getLocation(),sound,1,1); }
            case "teleport" -> { String[] fields=action.value().split(";"); double[] coordinates=PanelModel.location(action.value()); World world=Bukkit.getWorld(fields[0]); if(world==null) throw new IllegalStateException("Teleport world is not loaded: "+fields[0]); Location location=new Location(world,coordinates[0],coordinates[1],coordinates[2],(float)coordinates[3],(float)coordinates[4]); if(!world.isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4)) throw new IllegalStateException("Teleport destination chunk must already be loaded"); if(!player.teleport(location)) throw new IllegalStateException("Teleport was denied by the server"); }
            case "server" -> module.proxy.transfer(player,action.value());
            default -> throw new IllegalArgumentException("Unsupported action");
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event) { if(event.getView().getTopInventory().getHolder() instanceof Menu menu && event.getRawSlots().stream().anyMatch(slot->slot<menu.panel.size())) event.setCancelled(true); }
    @EventHandler public void closed(InventoryCloseEvent event) { if(event.getInventory().getHolder() instanceof Menu menu) { sessions.remove(event.getPlayer().getUniqueId(),menu); pending.remove(event.getPlayer().getUniqueId()); } }
    @EventHandler public void quit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); pending.remove(event.getPlayer().getUniqueId()); module.proxy.quit(event.getPlayer()); }
    public void close() {
        for(Menu menu:List.copyOf(sessions.values())) { Player player=Bukkit.getPlayer(menu.player); if(player!=null && player.getOpenInventory().getTopInventory()==menu.inventory) player.closeInventory(); }
        sessions.clear(); pending.clear();
    }
}
