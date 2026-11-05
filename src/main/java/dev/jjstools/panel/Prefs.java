package dev.jjstools.panel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How you have the Control Panel set up: which panels are open, where and how big, light or
 * dark, the text size, and your quick message presets. Saved whenever the panel closes.
 */
public final class Prefs {
    public static final class Window {
        public boolean open = true;
        /** Position and size as fractions of the screen, so a different window size keeps the layout. */
        public double x, y, w, h;
        public int z;
    }

    public static final class Preset {
        public String label;
        public String message;

        public Preset() {}

        public Preset(String label, String message) {
            this.label = label;
            this.message = message;
        }
    }

    public static final class Data {
        public boolean dark = true;
        /** Light, Dark or one of the named palettes; null on files from before there were palettes. */
        public String theme;
        /** Whether the stash manager counts what is inside shulker boxes, rather than the boxes. */
        public boolean stashContents = true;
        /** Whether the stash manager counts renamed items with the rest, and enchanted items together whatever their enchantments. */
        public boolean stashNamesIgnored = true;
        public boolean stashEnchantsIgnored = true;
        public boolean stashTrimsIgnored = true;
        /** Hides every coordinate in the panel, for screenshots and streams. */
        public boolean hideCoords;
        public double textScale = 1.0;
        public Map<String, Window> windows = new LinkedHashMap<>();
        public List<Preset> presets = new ArrayList<>(List.of(
            new Preset("Warp", "warp"),
            new Preset("Load base", "load base"),
            new Preset("Load stash", "load stash"),
            new Preset("Here?", "you on?")
        ));
        public String activeStash;
        /** How wide the people and quick message columns of the chat panel are, as fractions of it. */
        public double chatLeft = 0.26;
        public double chatRight = 0.13;
        public String chatSort = "Recent";
        /** Whether the chat panel's list leaves out people who are not on, apart from whoever is open. */
        public boolean chatHideOffline = true;
        public boolean chatSortReverse;
        public String openChat;
        public String stashSort = "Count";
        public boolean stashSortReverse;
        public String encountersSort = "Last";
        public boolean encountersSortReverse = true;
    }

    private static Data data;
    private static Path loadedFrom;

    private Prefs() {}

    public static Data get() {
        Path path = PanelFiles.file("panel.json");
        if (data == null || !path.equals(loadedFrom)) {
            loadedFrom = path;
            data = PanelFiles.read(path, Data.class, new Data());
            if (data.windows == null) data.windows = new LinkedHashMap<>();
            if (data.presets == null) data.presets = new ArrayList<>();
        }
        return data;
    }

    public static void save() {
        if (data != null) PanelFiles.write(PanelFiles.file("panel.json"), data);
    }
}
