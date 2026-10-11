package org.wannes.commandpanels;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import java.util.*;

public final class PanelModel {
    private PanelModel() {}
    public record Action(String type, String value, String click, String permission, boolean authorized) {
        public Action validate() {
            if (!Set.of("message","player","console","open","close","sound","teleport","server").contains(type)) invalid("Unsupported action type");
            if (!Set.of("ANY","LEFT","RIGHT","SHIFT_LEFT","SHIFT_RIGHT").contains(click)) invalid("Unsupported click type");
            text(value, 512, "Action value"); PanelModel.permission(permission);
            if (!type.equals("close") && value.isBlank()) invalid("Action value is required");
            if (Set.of("player","console").contains(type)) {
                if (!value.split(" +",2)[0].matches("[a-zA-Z0-9_:-]{1,100}")) invalid("Command must start with a literal command name, without / or placeholders");
                if (type.equals("console") && !authorized) invalid("Console actions must be explicitly authorized by an administrator");
            }
            if (type.equals("server") && !value.matches("[A-Za-z0-9_.-]{1,64}")) invalid("Invalid proxy server name");
            if (type.equals("sound") && NamespacedKey.fromString(value) == null) invalid("Invalid sound key");
            if (type.equals("teleport")) location(value);
            return this;
        }
    }
    public record Item(String material, String name, List<String> lore, int amount, Integer customModelData, String itemModel, boolean glint, List<String> flags, List<Action> actions) {
        public Item validate() {
            Material type = Material.matchMaterial(Objects.toString(material,""));
            if (type == null || type.isLegacy() || !type.isItem() || type.isAir()) invalid("Choose a valid Minecraft item material");
            if (amount < 1 || amount > type.getMaxStackSize()) invalid("Amount must be 1–"+type.getMaxStackSize()+" for "+material);
            text(name,256,"Item name"); if (lore == null || lore.size()>20) invalid("Maximum 20 lore lines");
            for (String line:lore) text(line,256,"Lore line");
            if (customModelData != null && customModelData < 0) invalid("Custom model data must be non-negative");
            if (itemModel == null || (!itemModel.isEmpty() && NamespacedKey.fromString(itemModel)==null)) invalid("Invalid item model key");
            if (flags == null || flags.size()>ItemFlag.values().length) invalid("Invalid item flags");
            for (String flag:flags) try { ItemFlag.valueOf(flag); } catch (RuntimeException failure) { invalid("Unsupported item flag: "+flag); }
            if (actions == null || actions.size()>16) invalid("Maximum 16 actions per item");
            for (Action action:actions) { if(action==null) invalid("Null action"); action.validate(); }
            return this;
        }
    }
    public record Panel(String id, String name, String command, String title, int size, boolean enabled, String permission, Map<Integer,Item> items) {
        public Panel validate() {
            try { UUID.fromString(id); } catch(RuntimeException failure) { invalid("Invalid panel identity"); }
            text(name,80,"Panel name"); if(name.isBlank()) invalid("Panel name is required");
            if(command==null || !command.matches("[a-z][a-z0-9_-]{0,31}")) invalid("Opening command must be lowercase, without /, and contain letters, digits, _ or - (maximum 32 characters)");
            text(title,128,"Inventory title"); if(title.isBlank()) invalid("Inventory title is required");
            if(size<9 || size>54 || size%9!=0) invalid("Inventory size must be 9, 18, 27, 36, 45 or 54");
            PanelModel.permission(permission);
            if(items==null || items.size()>size) invalid("Invalid inventory items");
            for(var entry:items.entrySet()) { if(entry.getKey()==null || entry.getKey()<0 || entry.getKey()>=size || entry.getValue()==null) invalid("Item slot is outside this inventory"); entry.getValue().validate(); }
            return this;
        }
        public boolean consoleActions() { return items.values().stream().flatMap(i->i.actions().stream()).anyMatch(a->a.type().equals("console")); }
    }
    public record State(int schemaVersion, long revision, boolean proxyEnabled, Map<String,Panel> panels) {
        public static State empty() { return new State(1,0,false,Map.of()); }
        public State validate() {
            if(schemaVersion!=1 || revision<0) invalid("Unsupported or malformed panel configuration version; existing file retained");
            if(panels==null || panels.size()>100) invalid("Maximum 100 panels");
            Set<String> commands=new HashSet<>();
            for(var entry:panels.entrySet()) { var panel=entry.getValue(); if(panel==null || !entry.getKey().equals(panel.id())) invalid("Panel identity mismatch"); panel.validate(); if(!commands.add(panel.command())) invalid("Duplicate opening command: "+panel.command()); }
            return this;
        }
    }
    public static double[] location(String value) {
        String[] parts=value.split(";",-1);
        if(parts.length!=6 || !parts[0].matches("[A-Za-z0-9_./-]{1,128}")) invalid("Teleport requires world, x, y, z, yaw and pitch");
        double[] result=new double[5];
        try { for(int i=0;i<5;i++) { result[i]=Double.parseDouble(parts[i+1]); if(!Double.isFinite(result[i]) || Math.abs(result[i])>30_000_000) invalid("Invalid teleport coordinate"); } }
        catch(NumberFormatException failure) { invalid("Teleport coordinates must be numbers"); }
        if(Math.abs(result[4])>90) invalid("Teleport pitch must be between -90 and 90");
        return result;
    }
    public static void permission(String value) { if(value==null || (!value.isEmpty() && !value.matches("[a-zA-Z0-9_.:-]{1,160}"))) invalid("Invalid permission node"); }
    public static void text(String value,int maximum,String field) { if(value==null || value.length()>maximum || value.chars().anyMatch(c->c<32||c==127||c==0x2028||c==0x2029)) invalid(field+" must be one line, maximum "+maximum+" characters"); }
    static void invalid(String message) { throw new IllegalArgumentException(message); }
}
