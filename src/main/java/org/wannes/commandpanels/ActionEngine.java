package org.wannes.commandpanels;

import java.util.List;

/** Ordered dispatch separated from Paper's event lifecycle for deterministic tests. */
public final class ActionEngine {
    public interface Actor {
        boolean online();
        boolean permitted(String permission);
        void perform(PanelModel.Action action) throws Exception;
        void error(String message);
    }
    public static void execute(List<PanelModel.Action> actions, String click, Actor actor) {
        for(var action:actions) {
            if(!actor.online()) return;
            if(!action.click().equals("ANY")&&!action.click().equals(click)) continue;
            if(!action.permission().isEmpty()&&!actor.permitted(action.permission())) { actor.error("You do not have permission for this action."); return; }
            try { action.validate(); actor.perform(action); }
            catch(Exception | LinkageError failure) { actor.error("Menu action failed: "+java.util.Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())); return; }
        }
    }
}
