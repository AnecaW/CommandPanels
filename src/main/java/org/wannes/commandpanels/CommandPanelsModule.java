package org.wannes.commandpanels;

import org.wannes.plugModCore.api.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public final class CommandPanelsModule implements org.wannes.plugModCore.api.Module, ModuleWebApi, PanelService {
    ModuleContext context; PanelStore store; InventoryRuntime runtime; ProxyBridge proxy;
    volatile PanelModel.State state=PanelModel.State.empty(); volatile boolean stopped=true; volatile boolean editing;
    private String capability; private ExecutorService worker;
    private final Map<String,AutoCloseable> commands=new HashMap<>();
    private final Map<String,org.bukkit.permissions.Permission> permissions=new HashMap<>();
    private final Map<String,Map<String,Object>> jobs=new ConcurrentHashMap<>();
    private final Set<org.bukkit.scheduler.BukkitTask> tasks=ConcurrentHashMap.newKeySet();
    public void onEnable(ModuleContext context) {
        this.context=context; stopped=false;
        try {
            store=new PanelStore(context.getDataFolder().toPath()); state=store.load();
            // Core serves the packaged website directly; no second asset deployment is needed.
            worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var thread=new Thread(r,"CommandPanels-storage");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
            context.manage(this::onDisable);
            runtime=new InventoryRuntime(this); context.registerListener(runtime);
            proxy=new ProxyBridge(context); proxy.configure(state.proxyEnabled());
            reserve(state); refreshPermissions(state); context.registerService(PanelService.class,this);
            byte[] token=new byte[32]; new SecureRandom().nextBytes(token); capability=HexFormat.of().formatHex(token);
            context.getLogger().info("CommandPanels ready: "+state.panels().size()+" panels, revision "+state.revision());
        } catch(Exception failure) { onDisable(); throw new IllegalStateException("CommandPanels initialization failed; panel data retained: "+failure.getMessage(),failure); }
    }
    public void onDisable() {
        stopped=true; capability=null;
        if(runtime!=null) runtime.close();
        for(var task:List.copyOf(tasks)) task.cancel(); tasks.clear();
        for(var handle:List.copyOf(commands.values())) close(handle); commands.clear();
        for(var permission:permissions.values()) if(Bukkit.getPluginManager().getPermission(permission.getName())==permission) Bukkit.getPluginManager().removePermission(permission); permissions.clear();
        if(worker!=null) { worker.shutdown(); try { if(!worker.awaitTermination(5,TimeUnit.SECONDS)) worker.shutdownNow(); } catch(InterruptedException failure) { worker.shutdownNow(); Thread.currentThread().interrupt(); } }
        jobs.clear(); editing=false;
    }
    private static void close(AutoCloseable handle) { try { handle.close(); } catch(Exception failure) { throw new IllegalStateException("Cannot unregister panel command",failure); } }
    void later(Runnable action) {
        if(stopped) return;
        class Pending implements Runnable {
            volatile org.bukkit.scheduler.BukkitTask task; volatile boolean finished;
            public void run() { finished=true; if(task!=null) tasks.remove(task); if(!stopped) action.run(); }
        }
        var pending=new Pending();
        pending.task=context.getHostPlugin().getServer().getScheduler().runTask(context.getHostPlugin(),pending);
        tasks.add(pending.task);
        if(pending.finished||stopped) { tasks.remove(pending.task); if(stopped) pending.task.cancel(); }
    }
    private void mainThread() { if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Panel service calls require the server thread"); }
    public boolean open(Player player,String idOrCommand) {
        mainThread();
        var panel=state.panels().get(idOrCommand);
        if(panel==null) panel=state.panels().values().stream().filter(p->p.command().equalsIgnoreCase(idOrCommand)).findFirst().orElse(null);
        if(panel==null) { player.sendMessage("Panel does not exist: "+idOrCommand); return false; }
        return runtime.open(player,panel);
    }
    public List<PanelService.Panel> panels() { return state.panels().values().stream().map(p->new PanelService.Panel(p.id(),p.name(),p.command(),p.enabled(),p.permission())).toList(); }
    private final class Opener implements CommandExecutor,TabCompleter {
        final String name; Opener(String name) { this.name=name; }
        public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
            if(!(sender instanceof Player player)) { sender.sendMessage("Only players can open inventory panels."); return true; }
            if(args.length!=0) { sender.sendMessage("Usage: /"+label); return true; }
            try { open(player,name); } catch(RuntimeException | LinkageError failure) { sender.sendMessage("Panel could not open: "+failure.getMessage()); context.getLogger().warning("Panel render failed: "+failure); }
            return true;
        }
        public List<String> onTabComplete(CommandSender sender,Command command,String label,String[] args) { return List.of(); }
    }
    private Set<String> desired(PanelModel.State candidate) { Set<String> desired=new HashSet<>(); for(var panel:candidate.panels().values()) if(panel.enabled()) desired.add(panel.command()); return desired; }
    private void preflight(PanelModel.State candidate) {
        candidate.validate(); var map=context.getHostPlugin().getServer().getCommandMap();
        for(String name:desired(candidate)) {
            var command=map.getCommand(name);
            if(command!=null && !(command instanceof ModuleCommandMetadata metadata && metadata.moduleId().equals(context.getModuleId()) && commands.containsKey(name))) throw new IllegalArgumentException("Opening command /"+name+" is already registered by another plugin or module");
            var qualified=map.getCommand(context.getModuleId()+":"+name);
            if(qualified!=null && !(qualified instanceof ModuleCommandMetadata metadata && metadata.moduleId().equals(context.getModuleId()) && commands.containsKey(name))) throw new IllegalArgumentException("Namespaced command conflict: "+context.getModuleId()+":"+name);
        }
        for(var panel:candidate.panels().values()) for(var item:panel.items().values()) for(var action:item.actions()) {
            if(action.type().equals("open") && !candidate.panels().containsKey(action.value()) && candidate.panels().values().stream().noneMatch(p->p.command().equals(action.value()))) throw new IllegalArgumentException("Open-panel action references unknown panel: "+action.value());
            if(action.type().equals("sound") && Registry.SOUNDS.get(NamespacedKey.fromString(action.value()))==null) throw new IllegalArgumentException("Unknown sound: "+action.value());
            if(action.type().equals("teleport") && Bukkit.getWorld(action.value().split(";")[0])==null) throw new IllegalArgumentException("Teleport world is not loaded");
        }
    }
    private void reserve(PanelModel.State candidate) {
        preflight(candidate);
        List<String> added=new ArrayList<>();
        try { for(String name:desired(candidate)) if(!commands.containsKey(name)) { commands.put(name,context.registerManagedCommand(name,new Opener(name),"Open a CommandPanels menu","/"+name)); added.add(name); } }
        catch(RuntimeException failure) { for(String name:added) close(commands.remove(name)); throw failure; }
    }
    private void reconcile(PanelModel.State active) {
        var wanted=desired(active);
        for(String name:List.copyOf(commands.keySet())) if(!wanted.contains(name)) close(commands.remove(name));
    }
    private void refreshPermissions(PanelModel.State active) {
        Set<String> wanted=new HashSet<>();
        var registered=context.getRegisteredCommands();
        for(var panel:active.panels().values()) if(panel.enabled()) {
            if(!panel.permission().isEmpty()) wanted.add(panel.permission());
            for(var item:panel.items().values()) for(var action:item.actions()) if(!action.permission().isEmpty()) wanted.add(action.permission());
            var command=registered.get(panel.command());
            if(command!=null) command.setPermission(panel.permission().isEmpty()?null:panel.permission());
        }
        var manager=Bukkit.getPluginManager();
        for(String node:List.copyOf(permissions.keySet())) if(!wanted.contains(node)) { var owned=permissions.remove(node); if(manager.getPermission(node)==owned) manager.removePermission(owned); }
        for(String node:wanted) if(manager.getPermission(node)==null) { var permission=new org.bukkit.permissions.Permission(node,"CommandPanels configured access",org.bukkit.permissions.PermissionDefault.OP);manager.addPermission(permission);permissions.put(node,permission); }
        for(Player player:Bukkit.getOnlinePlayers()) player.updateCommands();
    }
    static PanelModel.State change(PanelModel.State old,String operation,Map<String,String> params) {
        if(!Long.toString(old.revision()).equals(params.get("revision"))) throw new IllegalArgumentException("Panel configuration changed; refresh before saving. Your draft has been retained.");
        Map<String,PanelModel.Panel> next=new LinkedHashMap<>(old.panels()); boolean proxy=old.proxyEnabled();
        switch(operation) {
            case "save","import" -> {
                var panel=PanelStore.parse(params.get("definition")); String original=params.getOrDefault("original","");
                if(operation.equals("import")) original="";
                if(!original.isEmpty()&&!next.containsKey(original)) throw new IllegalArgumentException("Panel no longer exists; refresh before saving");
                if(!original.isEmpty()&&!panel.id().equals(original)) throw new IllegalArgumentException("Panel identity cannot change");
                if(panel.consoleActions()&&!"true".equals(params.get("consoleAuthorized"))) throw new IllegalArgumentException("Explicit administrator authorization is required to save or import console actions");
                String id=original.isEmpty()?UUID.randomUUID().toString():original;
                Map<Integer,PanelModel.Item> items=panel.items();
                if(operation.equals("import")) {
                    String importedId=panel.id(); Map<Integer,PanelModel.Item> remapped=new HashMap<>();
                    for(var entry:items.entrySet()) { var item=entry.getValue();var actions=item.actions().stream().map(a->a.type().equals("open")&&a.value().equals(importedId)?new PanelModel.Action(a.type(),id,a.click(),a.permission(),a.authorized()):a).toList();remapped.put(entry.getKey(),new PanelModel.Item(item.material(),item.name(),item.lore(),item.amount(),item.customModelData(),item.itemModel(),item.glint(),item.flags(),actions)); }
                    items=Map.copyOf(remapped);
                }
                panel=new PanelModel.Panel(id,panel.name(),panel.command(),panel.title(),panel.size(),panel.enabled(),panel.permission(),items).validate();
                next.put(id,panel);
            }
            case "delete","toggle","duplicate" -> {
                var panel=next.get(params.get("id")); if(panel==null) throw new IllegalArgumentException("Unknown panel");
                if(operation.equals("delete")) {
                    if(!("DELETE:"+panel.id()).equals(params.get("confirmation"))) throw new IllegalArgumentException("Deletion confirmation required");
                    next.remove(panel.id());
                } else if(operation.equals("toggle")) next.put(panel.id(),new PanelModel.Panel(panel.id(),panel.name(),panel.command(),panel.title(),panel.size(),!panel.enabled(),panel.permission(),panel.items()));
                else {
                    String command=panel.command(); int number=2;
                    do { String suffix="_"+number++; command=panel.command().substring(0,Math.min(panel.command().length(),32-suffix.length()))+suffix; } while(containsCommand(next,command));
                    String id=UUID.randomUUID().toString(); next.put(id,new PanelModel.Panel(id,panel.name().substring(0,Math.min(70,panel.name().length()))+" copy",command,panel.title(),panel.size(),false,panel.permission(),panel.items()));
                }
            }
            case "settings" -> { if(!Set.of("true","false").contains(params.get("proxyEnabled"))) throw new IllegalArgumentException("Proxy setting must be true or false"); proxy=Boolean.parseBoolean(params.get("proxyEnabled")); }
            default -> throw new IllegalArgumentException("Unknown panel operation");
        }
        return new PanelModel.State(1,old.revision()+1,proxy,Map.copyOf(next)).validate();
    }
    private static boolean containsCommand(Map<String,PanelModel.Panel> panels,String command) { return panels.values().stream().anyMatch(p->p.command().equals(command)); }
    private String edit(String operation,Map<String,String> params) {
        if(editing) throw new IllegalStateException("Another panel save is in progress; retry shortly");
        var candidate=change(state,operation,params); var previous=state; reserve(candidate);
        if(jobs.size()>64) jobs.entrySet().removeIf(e->!"running".equals(e.getValue().get("status")));
        String job=UUID.randomUUID().toString(); jobs.put(job,Map.of("status","running")); editing=true;
        try { worker.execute(()->{
            try {
                if(stopped) throw new IllegalStateException("Module stopped before save");
                store.save(candidate);
                later(()->{
                    try { runtime.close(); state=candidate; if(candidate.proxyEnabled()!=previous.proxyEnabled()) proxy.configure(candidate.proxyEnabled()); reconcile(candidate); refreshPermissions(candidate); jobs.put(job,Map.of("status","done","revision",candidate.revision())); }
                    catch(RuntimeException failure) { state=candidate; context.getLogger().log(java.util.logging.Level.SEVERE,"Panel configuration saved but live application failed",failure); jobs.put(job,Map.of("status","failed","error","Configuration was saved, but live application failed: "+failure.getMessage()+". Reload CommandPanels to restore the committed configuration.")); }
                    finally { editing=false; }
                });
            } catch(Exception failure) {
                context.getLogger().warning("Panel save failed; previous snapshot retained: "+failure);
                later(()->{ reconcile(previous); editing=false; jobs.put(job,Map.of("status","failed","error",Objects.toString(failure.getMessage(),"Cannot save panel"))); });
            }
        }); } catch(RejectedExecutionException failure) { reconcile(previous); editing=false; jobs.remove(job); throw new IllegalStateException("Storage queue unavailable; previous configuration retained",failure); }
        return job;
    }
    private boolean authorized(String supplied) { return capability!=null&&supplied!=null&&MessageDigest.isEqual(capability.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)); }
    public ApiResponse handleApiRequest(String method,String path,Map<String,String> params) {
        if(stopped) return ApiResponse.badRequest("CommandPanels is disabled");
        // Core authenticates this request and executes module APIs on the main thread.
        if(method.equals("GET")&&path.equals("/session")) return ApiResponse.okJson(PanelStore.JSON.toJson(Map.of("capability",capability)));
        if(!authorized(params.get("capability"))) return new ApiResponse(403,"text/plain","Authorized administrator session required");
        try {
            Object result;
            if(method.equals("GET")) result=switch(path) {
                case "/panels" -> Map.of("state",state,"proxyServers",proxy.servers(),"proxyWarning",proxy.servers().isEmpty()?"Server switching requires a compatible configured proxy and a recent discovery response (an online player is needed). Velocity must enable its BungeeCord plugin-message channel.":"Proxy accepted server discovery; transfer requests do not confirm arrival.","placeholders",context.getOptionalService(PlaceholderService.class)!=null);
                case "/catalogue" -> Map.of("materials",Arrays.stream(Material.values()).filter(m->!m.isLegacy()&&m.isItem()&&!m.isAir()).map(m->Map.of("name",m.name(),"maxStack",m.getMaxStackSize())).toList(),"flags",Arrays.stream(org.bukkit.inventory.ItemFlag.values()).map(Enum::name).toList(),"sounds",Registry.SOUNDS.stream().map(s->s.getKey().toString()).sorted().toList(),"worlds",Bukkit.getWorlds().stream().map(World::getName).toList());
                case "/jobs" -> { var value=jobs.get(params.get("job")); if(value==null) throw new IllegalArgumentException("Unknown save operation; reload the panel list to verify persistence"); yield value; }
                case "/export" -> { var panel=state.panels().get(params.get("id")); if(panel==null) throw new IllegalArgumentException("Unknown panel"); yield Map.of("schemaVersion",1,"panel",panel); }
                default -> null;
            };
            else if(method.equals("POST")&&path.equals("/validate")) result=Map.of("panel",PanelStore.parse(params.get("definition")));
            else if(method.equals("POST")&&Set.of("/save","/import","/delete","/toggle","/duplicate","/settings").contains(path)) result=Map.of("job",edit(path.substring(1),Map.copyOf(params)));
            else result=null;
            return result==null?ApiResponse.notFound("Unknown CommandPanels operation"):ApiResponse.okJson(PanelStore.JSON.toJson(result));
        } catch(Exception failure) { return ApiResponse.badRequest(failure instanceof com.google.gson.JsonParseException?"Malformed panel configuration; previous data retained":Objects.toString(failure.getMessage(),"Invalid panel request")); }
    }
}
