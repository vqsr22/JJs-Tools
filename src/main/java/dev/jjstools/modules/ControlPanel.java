package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.panel.ControlPanelScreen;
import dev.jjstools.panel.StashStore;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * Opens the Control Panel: chat, stashes, encounters, your blocked list, deaths and session
 * stats in one screen of its own, drawn with your resource pack's textures.
 *
 * Set Open GUI Key and press it to open the panel, and again to close it. The module never has to
 * be switched on; turning it on just opens the panel once. The records behind the panels are kept
 * all the time, whether or not the panel is open.
 */
public class ControlPanel extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChat = settings.createGroup("Chat");

    private final Setting<Keybind> openKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("open-gui-key")
        .description("The key that opens the Control Panel, and closes it again. Works whether or not this module is on.")
        .defaultValue(Keybind.none())
        .build()
    );
    private final SettingGroup sgLook = settings.createGroup("Look");
    private final SettingGroup sgStash = settings.createGroup("Stash Manager");

    private final Setting<Double> guiScale = sgLook.add(new DoubleSetting.Builder()
        .name("gui-scale")
        .description("How big the whole Control Panel is drawn, on top of Minecraft's own GUI scale. Text size inside it is set separately in the panel.")
        .defaultValue(1.0).min(0.5).max(2.0).sliderRange(0.5, 2.0)
        .build()
    );

    private final Setting<SettingColor> accent = sgLook.add(new ColorSetting.Builder()
        .name("accent-colour")
        .description("The colour of selected tabs, column edges, headings and other highlights.")
        .defaultValue(new SettingColor(138, 180, 255))
        .build()
    );

    private final Setting<Boolean> packColour = sgLook.add(new BoolSetting.Builder()
        .name("main-colour-from-pack")
        .description("Take the panel's main colour from your resource pack's inventory screen. Off uses Main Colour instead.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> main = sgLook.add(new ColorSetting.Builder()
        .name("main-colour")
        .description("The panel's main colour, when it is not taken from your resource pack. Lists, slots and buttons are worked out from it.")
        .defaultValue(new SettingColor(198, 198, 198))
        .visible(() -> !packColour.get())
        .build()
    );

    private final Setting<String> whisperCommand = sgChat.add(new StringSetting.Builder()
        .name("whisper-command")
        .description("How a whisper is sent from the chat panel. {player} is who to and {message} what to say. 2b2t takes /msg, /w or /tell.")
        .defaultValue("msg {player} {message}")
        .build()
    );

    private final Setting<String> syncFolder = sgStash.add(new StringSetting.Builder()
        .name("stash-folder")
        .description("Folder the stash files are kept in. Put it in a synced folder, such as Dropbox, Google Drive or OneDrive, and point a friend's Control Panel at the same one to share stashes. Empty keeps them in .minecraft/jjs-tools/stashes.")
        .defaultValue("")
        .onChanged(v -> StashStore.reload())
        .build()
    );

    private final Setting<Integer> walkDistance = sgStash.add(new IntSetting.Builder()
        .name("walk-distance")
        .description("When walking to an item, containers within this many blocks are picked by how much of it they hold; past it, the nearest is picked, so it never sets off on a long trip when a nearer chest has some.")
        .defaultValue(96).min(8).max(2000).sliderRange(16, 500)
        .build()
    );

    private final Setting<Boolean> stashOutlines = sgStash.add(new BoolSetting.Builder()
        .name("stash-outlines")
        .description("Outline the containers holding the item you pick in the stash manager, through walls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> stashRadius = sgStash.add(new IntSetting.Builder()
        .name("new-stash-radius")
        .description("The radius new stashes start with, in blocks. Every container opened within it is recorded.")
        .defaultValue(5000).min(16).max(100_000).sliderRange(100, 10_000)
        .build()
    );

    public ControlPanel() {
        super(JJsTools.CATEGORY, "control-panel", "One screen for whispers, stashes, encounters, blocked players, deaths and session stats. Set Open GUI Key in its settings to open it.");
    }

    @Override
    public void onActivate() {
        mc.setScreen(new ControlPanelScreen());
        toggle();
    }

    private static ControlPanel instance() {
        Modules modules = Modules.get();
        return modules == null ? null : modules.get(ControlPanel.class);
    }

    public static double guiScale() {
        ControlPanel m = instance();
        return m == null ? 1.0 : m.guiScale.get();
    }

    /** The accent colour, as ARGB. */
    public static int accent() {
        ControlPanel m = instance();
        return m == null ? 0xFF8AB4FF : 0xFF000000 | (m.accent.get().getPacked() & 0xFFFFFF);
    }

    /** The main colour chosen in the settings, as ARGB, or null to take it from the resource pack. */
    public static Integer mainColour() {
        ControlPanel m = instance();
        if (m == null || m.packColour.get()) return null;
        return 0xFF000000 | (m.main.get().getPacked() & 0xFFFFFF);
    }

    public static boolean stashOutlines() {
        ControlPanel m = instance();
        return m == null || m.stashOutlines.get();
    }

    public static Keybind openKey() {
        ControlPanel m = instance();
        return m == null ? null : m.openKey.get();
    }

    public static String syncFolder() {
        ControlPanel m = instance();
        return m == null ? "" : m.syncFolder.get();
    }

    public static String whisperCommand() {
        ControlPanel m = instance();
        return m == null ? "msg {player} {message}" : m.whisperCommand.get();
    }

    public static int walkDistance() {
        ControlPanel m = instance();
        return m == null ? 96 : m.walkDistance.get();
    }

    public static int newStashRadius() {
        ControlPanel m = instance();
        return m == null ? 5000 : m.stashRadius.get();
    }
}
