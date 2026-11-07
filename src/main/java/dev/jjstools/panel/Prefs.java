package dev.jjstools.panel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How you have the Control Panel set up: which panels are open, where and how big, light or
 * dark, the text size and window layout. Saved whenever the panel closes.
 */
public final class Prefs {
    public static final class Window {
        public boolean open = true;
        /** Position and size as fractions of the screen, so a different window size keeps the layout. */
        public double x, y, w, h;
        public int z;
        /** Whether it fills the screen; x, y, w and h are then where it goes back to. */
        public boolean maximised;
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
        /** Whether the stash manager's grid leaves off how many of each item there are. */
        public boolean stashHideAmounts;
        /** Hides every coordinate in the panel, for screenshots and streams. */
        public boolean hideCoords;
        /** The panel's font: "minecraft", "meteor" for Meteor's custom font, or one of PanelFonts'. */
        public String font = PanelFonts.DEFAULT;
        /** Whether panels have a thin outline, so overlapping ones are told apart. */
        public boolean panelOutlines = true;
        /** Whether the panel buttons along the top show their item. */
        public boolean dockIcons;
        public double textScale = 1.0;
        /** How tall the bar along the top is, dragged by its bottom edge. */
        public int dockHeight = 26;
        /** Which edge of the screen the dock is on: top or bottom. */
        public String dockSide = "top";
        public Map<String, Window> windows = new LinkedHashMap<>();
        public String activeStash;
        /** How wide the people column of the chat panel is, as a fraction of it. */
        public double chatLeft = 0.26;
        /** People pinned to the top of the chat panel's list, lower case. */
        public List<String> chatPinned = new ArrayList<>();
        /** How tall each of Meteor's module windows is set to under JJ's theme, by window id; missing is Meteor's own. */
        public Map<String, Integer> meteorWindowHeights = new java.util.HashMap<>();
        /** Meteor module columns stacked under JJ's theme: each column's window id and the id of the column it sits below. */
        public Map<String, String> meteorStacks = new LinkedHashMap<>();
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
            if (data.chatPinned == null) data.chatPinned = new ArrayList<>();
            if (data.meteorWindowHeights == null) data.meteorWindowHeights = new java.util.HashMap<>();
            if (data.meteorStacks == null) data.meteorStacks = new LinkedHashMap<>();
            if (data.windows == null) data.windows = new LinkedHashMap<>();
        }
        return data;
    }

    public static void save() {
        if (data != null) PanelFiles.write(PanelFiles.file("panel.json"), data);
    }
}
