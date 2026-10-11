package org.wannes.commandpanels;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class PanelTests {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private PanelModel.Panel panel(String command) { return new PanelModel.Panel(UUID.randomUUID().toString(),"Servers",command,"&6Servers",27,true,"",Map.of()); }
    @Test public void validateNamesSizesPermissionsAndIdentity() {
        var p=panel("servers"); p.validate();
        for(int size:List.of(9,18,27,36,45,54)) new PanelModel.Panel(p.id(),p.name(),p.command(),p.title(),size,true,"panels.test",Map.of()).validate();
        for(String command:List.of("/bad","Bad","contains spaces","", "a".repeat(33))) assertThrows(IllegalArgumentException.class,()->panel(command).validate());
        assertThrows(IllegalArgumentException.class,()->new PanelModel.Panel(p.id(),p.name(),p.command(),p.title(),10,true,"",Map.of()).validate());
        assertThrows(IllegalArgumentException.class,()->PanelModel.permission("bad\npermission"));
        assertThrows(IllegalArgumentException.class,()->PanelModel.text("bad\u2028line",20,"Text"));
    }
    @Test public void duplicateCommandsAndUnsupportedSchemaRejected() {
        var first=panel("servers"); var second=panel("servers");
        assertThrows(IllegalArgumentException.class,()->new PanelModel.State(1,0,false,Map.of(first.id(),first,second.id(),second)).validate());
        assertThrows(IllegalArgumentException.class,()->new PanelModel.State(2,0,false,Map.of()).validate());
    }
    @Test public void importedPrimitiveTypesAndFractionsAreNotSilentlyCoerced() {
        String valid=PanelStore.JSON.toJson(panel("servers"));
        assertThrows(IllegalArgumentException.class,()->PanelStore.parse(valid.replace("\"size\": 27","\"size\": 27.5")));
        assertThrows(IllegalArgumentException.class,()->PanelStore.parse(valid.replace("\"enabled\": true","\"enabled\": \"true\"")));
        assertThrows(Exception.class,()->PanelStore.parse("{ malformed }"));
        assertEquals("servers",PanelStore.parse(valid).command());
    }
    @Test public void atomicRoundTripRetainsIdentityFormattingAndRevision() throws Exception {
        var store=new PanelStore(temporary.newFolder().toPath()); var panel=panel("servers");
        var state=new PanelModel.State(1,9,true,Map.of(panel.id(),panel)); store.save(state); assertEquals(state,store.load());
        store.save(new PanelModel.State(1,10,false,state.panels())); assertEquals(state,PanelStore.JSON.fromJson(Files.readString(store.path("config/panels.previous.json")),PanelModel.State.class));
        try(var files=Files.list(store.path("config"))) { assertFalse(files.anyMatch(p->p.toString().endsWith(".tmp"))); }
    }
    @Test public void malformedFileIsNotOverwrittenAndPathsAreConfined() throws Exception {
        var store=new PanelStore(temporary.newFolder().toPath()); var file=store.path("config/panels.json"); Files.writeString(file,"{broken");
        assertThrows(Exception.class,store::load); assertEquals("{broken",Files.readString(file));
        assertThrows(java.io.IOException.class,()->store.path("../other-module.json"));
    }
    @Test public void editingAndImportPreserveOrRegenerateIdentitySafely() {
        var panel=panel("servers"); var old=new PanelModel.State(1,4,false,Map.of(panel.id(),panel));
        var saved=CommandPanelsModule.change(old,"save",Map.of("revision","4","original",panel.id(),"definition",PanelStore.JSON.toJson(panel)));
        assertTrue(saved.panels().containsKey(panel.id())); assertEquals(5,saved.revision());
        var duplicate=CommandPanelsModule.change(old,"duplicate",Map.of("revision","4","id",panel.id()));
        var copy=duplicate.panels().values().stream().filter(p->!p.id().equals(panel.id())).findFirst().orElseThrow();
        assertEquals("servers_2",copy.command()); assertFalse(copy.enabled());
        assertThrows(IllegalArgumentException.class,()->CommandPanelsModule.change(old,"save",Map.of("revision","3","definition",PanelStore.JSON.toJson(panel))));
        assertThrows(IllegalArgumentException.class,()->CommandPanelsModule.change(old,"import",Map.of("revision","4","definition",PanelStore.export(panel)))); // command conflict
        var standalone=CommandPanelsModule.change(PanelModel.State.empty(),"import",Map.of("revision","0","definition",PanelStore.export(panel)));
        assertNotEquals(panel.id(),standalone.panels().values().iterator().next().id());
    }
    @Test public void toggleDeleteAndConfirmation() {
        var panel=panel("servers"); var old=new PanelModel.State(1,0,false,Map.of(panel.id(),panel));
        assertFalse(CommandPanelsModule.change(old,"toggle",Map.of("revision","0","id",panel.id())).panels().get(panel.id()).enabled());
        assertThrows(IllegalArgumentException.class,()->CommandPanelsModule.change(old,"delete",Map.of("revision","0","id",panel.id())));
        assertTrue(CommandPanelsModule.change(old,"delete",Map.of("revision","0","id",panel.id(),"confirmation","DELETE:"+panel.id())).panels().isEmpty());
    }
    @Test public void actionValidationRejectsInjectionAndUnsafeInputs() {
        assertThrows(IllegalArgumentException.class,()->new PanelModel.Action("console","op {player}","ANY","",false).validate());
        assertThrows(IllegalArgumentException.class,()->new PanelModel.Action("player","say hello\nstop","ANY","",false).validate());
        assertThrows(IllegalArgumentException.class,()->new PanelModel.Action("player","{player} args","ANY","",false).validate());
        assertThrows(IllegalArgumentException.class,()->new PanelModel.Action("server","bad server","ANY","",false).validate());
        assertThrows(IllegalArgumentException.class,()->PanelModel.location("world;NaN;2;3;0;0"));
        assertThrows(IllegalArgumentException.class,()->PanelModel.location("world;1;2;3;0;91"));
        new PanelModel.Action("console","say {player}","ANY","",true).validate();
    }
    @Test public void actionOrderClickFilteringFailureAndDisconnect() {
        List<String> calls=new ArrayList<>();
        var actor=new ActionEngine.Actor() {
            public boolean online(){return !calls.contains("disconnect");}
            public boolean permitted(String node){return node.equals("allowed");}
            public void perform(PanelModel.Action action){calls.add(action.value()); if(action.value().equals("fail"))throw new IllegalStateException("fixture");}
            public void error(String error){calls.add("error");}
        };
        var actions=List.of(new PanelModel.Action("message","first","ANY","",false),new PanelModel.Action("message","right","RIGHT","",false),new PanelModel.Action("message","last","LEFT","allowed",false));
        ActionEngine.execute(actions,"LEFT",actor); assertEquals(List.of("first","last"),calls); calls.clear();
        ActionEngine.execute(List.of(new PanelModel.Action("message","fail","ANY","",false),actions.getFirst()),"LEFT",actor); assertEquals(List.of("fail","error"),calls); calls.clear();
        ActionEngine.execute(List.of(new PanelModel.Action("message","denied","ANY","denied",false),actions.getFirst()),"LEFT",actor); assertEquals(List.of("error"),calls); calls.clear();
        ActionEngine.execute(List.of(new PanelModel.Action("message","disconnect","ANY","",false),actions.getFirst()),"LEFT",actor); assertEquals(List.of("disconnect"),calls);
    }
}
