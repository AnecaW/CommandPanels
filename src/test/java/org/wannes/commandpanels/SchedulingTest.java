package org.wannes.commandpanels;

import org.junit.Test;
import org.wannes.plugModCore.api.ModuleContext;
import org.bukkit.plugin.Plugin;
import org.bukkit.Server;
import org.bukkit.scheduler.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SchedulingTest {
    @Test public void callbackCanRunBeforeSchedulerReturnsWithoutRetainingTask() throws Exception {
        var module=new CommandPanelsModule();var count=new AtomicInteger();setup(module,true,new ArrayList<>());
        module.later(count::incrementAndGet);assertEquals(1,count.get());assertTrue(tasks(module).isEmpty());
    }
    @Test public void disableCancelsPendingWorkAndLateCallbacksCannotExecute() throws Exception {
        var module=new CommandPanelsModule();var count=new AtomicInteger();List<Runnable> queued=new ArrayList<>();setup(module,false,queued);
        module.later(count::incrementAndGet);assertEquals(1,tasks(module).size());module.onDisable();assertTrue(tasks(module).isEmpty());queued.getFirst().run();assertEquals(0,count.get());
    }
    private static Set<?> tasks(CommandPanelsModule module) throws Exception { var field=CommandPanelsModule.class.getDeclaredField("tasks");field.setAccessible(true);return (Set<?>)field.get(module); }
    private void setup(CommandPanelsModule module,boolean inline,List<Runnable> queued) {
        var scheduler=(BukkitScheduler)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{BukkitScheduler.class},(p,m,a)->{
            if(m.getName().equals("runTask")) { var action=(Runnable)a[1];if(inline)action.run();else queued.add(action);return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{BukkitTask.class},(q,n,b)->switch(n.getName()){case "hashCode"->System.identityHashCode(q);case "equals"->q==b[0];case "getTaskId"->1;case "isSync","isCancelled"->false;default->null;}); }return null;
        });
        var server=(Server)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Server.class},(p,m,a)->m.getName().equals("getScheduler")?scheduler:null);
        var plugin=(Plugin)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Plugin.class},(p,m,a)->m.getName().equals("getServer")?server:null);
        module.context=(ModuleContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ModuleContext.class},(p,m,a)->m.getName().equals("getHostPlugin")?plugin:null);module.stopped=false;
    }
}
