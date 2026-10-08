package dev.jjstools.hud;

import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;

/** Groups JJ's Tools HUD elements under their own heading in the HUD editor. */
public class JJsHud {
    public static final HudGroup GROUP = new HudGroup("JJ's Tools");

    private JJsHud() {}

    public static void init() {
        Hud.get().register(CustomTextHud.INFO);
    }
}
