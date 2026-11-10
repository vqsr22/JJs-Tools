package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.panel.ControlPanelScreen;
import dev.jjstools.panel.StashStore;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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

    private final Setting<Double> animationSpeed = sgLook.add(new DoubleSetting.Builder()
        .name("animation-speed")
        .description("How fast windows, buttons, dropdowns, module lists and the module wheel animate, in JJ's theme and the Control Panel. 1 is normal, 2 twice as fast, 0 no animation at all.")
        .defaultValue(1.0).min(0).max(3).sliderRange(0, 3)
        .build()
    );

    private final Setting<Integer> fadeTime = sgLook.add(new IntSetting.Builder()
        .name("button-fade-time")
        .description("How long the close and maximise buttons take to fade in and out when you hover over them, in milliseconds. 0 shows them straight away.")
        .defaultValue(110).min(0).max(1000).sliderRange(0, 500)
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

    public enum SyncMode { Off, Folder, Server }

    private final Setting<SyncMode> syncMode = sgStash.add(new EnumSetting.Builder<SyncMode>()
        .name("sync-mode")
        .description("How stashes are shared. Off keeps them on this computer only. Folder keeps them in the stash folder, for Dropbox, Google Drive or OneDrive to sync. Server syncs them through a JJ's Tools sync server, such as one on a VPS.")
        .defaultValue(SyncMode.Folder)
        .onChanged(v -> dev.jjstools.panel.StashStore.reload())
        .build()
    );

    private final Setting<String> syncServer = sgStash.add(new StringSetting.Builder()
        .name("sync-server")
        .description("The sync server's address, such as http://203.0.113.5:8787, or https://stash.yourdomain.com behind Caddy.")
        .defaultValue("")
        .visible(() -> syncMode.get() == SyncMode.Server)
        .build()
    );

    private final Setting<String> syncToken = sgStash.add(new StringSetting.Builder()
        .name("sync-token")
        .description("The secret token the server was started with. Everyone with the same token sees the same stashes, so only give it to people you trust.")
        .defaultValue("")
        .visible(() -> syncMode.get() == SyncMode.Server)
        .build()
    );

    private final Setting<Integer> syncInterval = sgStash.add(new IntSetting.Builder()
        .name("sync-interval")
        .description("How often to check the server for changes, in seconds.")
        .defaultValue(5).min(2).max(120).sliderRange(2, 60)
        .visible(() -> syncMode.get() == SyncMode.Server)
        .build()
    );

    private final Setting<String> syncFolder = sgStash.add(new StringSetting.Builder()
        .name("stash-folder")
        .visible(() -> syncMode.get() == SyncMode.Folder)
        .description("Folder the stash files are kept in. Put it in a synced folder, such as Dropbox, Google Drive or OneDrive, and point a friend's Control Panel at the same one to share stashes. Empty keeps them in .minecraft/jjs-tools/stashes.")
        .defaultValue("")
        .onChanged(v -> StashStore.reload())
        .build()
    );

    private final Setting<Boolean> stashAnyServer = sgStash.add(new BoolSetting.Builder()
        .name("stash-any-address")
        .description("Records containers into a stash whatever address you joined by, so friends joining through ZenithProxy or a different address all fill the same stash. Turn it off if you play on several servers with stashes at the same coordinates.")
        .defaultValue(true)
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

    private final SettingGroup sgEncounters = settings.createGroup("Encounters");

    private final Setting<Boolean> encountersChat = sgEncounters.add(new BoolSetting.Builder()
        .name("encounters-chat-message")
        .description("Say something in chat the first time you ever see a player.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> encountersHideCoords = sgEncounters.add(new BoolSetting.Builder()
        .name("encounters-hide-coords")
        .description("Leave coordinates out of that chat message, for streaming or screenshots. Hide coords in the panel's settings does this too.")
        .defaultValue(false)
        .visible(encountersChat::get)
        .build()
    );

    private final Setting<Boolean> encountersSkipFriends = sgEncounters.add(new BoolSetting.Builder()
        .name("encounters-skip-friends")
        .description("Don't record players on your Meteor friends list.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> encountersSaveInterval = sgEncounters.add(new IntSetting.Builder()
        .name("encounters-save-interval")
        .description("Seconds between writes to disk. The list is also written when you leave a world.")
        .defaultValue(30).min(5).sliderRange(5, 300)
        .build()
    );

    private final SettingGroup sgPearls = settings.createGroup("Pearls");

    private final Setting<Double> pearlWaitTime = sgPearls.add(new DoubleSetting.Builder()
        .name("pearl-wait-time")
        .description("Seconds to wait once you land at the base, for the pearlbot to hand you a pearl, before checking whether you have one.")
        .defaultValue(2.1).min(0).sliderMax(10)
        .build()
    );

    private final Setting<Integer> pearlArriveTimeout = sgPearls.add(new IntSetting.Builder()
        .name("pearl-arrive-timeout")
        .description("Seconds to wait to be pulled after sending the message before giving up.")
        .defaultValue(15).min(5).sliderMax(180)
        .build()
    );

    private final Setting<Boolean> pearlTrapdoorOpen = sgPearls.add(new BoolSetting.Builder()
        .name("pearl-trapdoor-open")
        .description("Leaves the chamber's trapdoor open (flipped up) after resetting it. Turn off if your chambers hold a pearl with the trapdoor closed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pearlFreeLook = sgPearls.add(new BoolSetting.Builder()
        .name("pearl-free-look")
        .description("Lets you look around freely while pulling and resetting, using Meteor's Free Look in camera mode, put back as it was afterwards.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pearlSwapBack = sgPearls.add(new BoolSetting.Builder()
        .name("pearl-swap-back")
        .description("Goes back to the hotbar slot you had after throwing.")
        .defaultValue(true)
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

    /** How long the title bar buttons fade for, in milliseconds. */
    public static int fadeTime() {
        ControlPanel m = instance();
        return m == null ? 110 : m.fadeTime.get();
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

    public static boolean stashAnyServer() {
        ControlPanel m = instance();
        return m == null || m.stashAnyServer.get();
    }

    public static SyncMode syncMode() {
        ControlPanel m = instance();
        return m == null ? SyncMode.Folder : m.syncMode.get();
    }

    public static String syncServer() {
        ControlPanel m = instance();
        return m == null ? "" : m.syncServer.get().trim();
    }

    public static String syncToken() {
        ControlPanel m = instance();
        return m == null ? "" : m.syncToken.get().trim();
    }

    public static int syncInterval() {
        ControlPanel m = instance();
        return m == null ? 5 : m.syncInterval.get();
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

    public static boolean encountersChat() {
        ControlPanel m = instance();
        return m == null || m.encountersChat.get();
    }

    public static boolean encountersHideCoords() {
        ControlPanel m = instance();
        return m != null && m.encountersHideCoords.get();
    }

    public static boolean encountersSkipFriends() {
        ControlPanel m = instance();
        return m != null && m.encountersSkipFriends.get();
    }

    public static int encountersSaveInterval() {
        ControlPanel m = instance();
        return m == null ? 30 : m.encountersSaveInterval.get();
    }

    public static double animationSpeed() {
        ControlPanel m = instance();
        return m == null ? 1 : m.animationSpeed.get();
    }

    public static double pearlWaitTime() {
        ControlPanel m = instance();
        return m == null ? 2.1 : m.pearlWaitTime.get();
    }

    public static int pearlArriveTimeout() {
        ControlPanel m = instance();
        return m == null ? 15 : m.pearlArriveTimeout.get();
    }

    public static boolean pearlTrapdoorOpen() {
        ControlPanel m = instance();
        return m == null || m.pearlTrapdoorOpen.get();
    }

    public static boolean pearlFreeLook() {
        ControlPanel m = instance();
        return m == null || m.pearlFreeLook.get();
    }

    public static boolean pearlSwapBack() {
        ControlPanel m = instance();
        return m == null || m.pearlSwapBack.get();
    }
}
