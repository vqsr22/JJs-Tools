package dev.jjstools.gui.jjtheme;

import dev.jjstools.panel.Prefs;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Module columns stacked under one another under JJ's theme, set in the GUI tab beside auto fit:
 * each column can sit below any other, so World can go under Render, Misc under World, and so on
 * to any depth. A stack takes one place in the row, as wide as its widest column.
 *
 * Saved as each column's window id and the id of the column it sits below. A column whose
 * column above is not on screen goes back into the row on its own.
 */
public final class ColumnStacks {
    private ColumnStacks() {}

    /** Each stacked window and the window right above it in its stack, set as the columns are laid out. */
    private static final Map<WWindow, WWindow> ABOVE = new WeakHashMap<>();
    /** The gap under each window in a stack: the one between columns. */
    public static double gap = 4;

    private static Map<String, String> saved() {
        return Prefs.get().meteorStacks;
    }

    public static boolean any() {
        return !saved().isEmpty();
    }

    /** The id of the column this one is set to sit below, or null for its own place in the row. */
    public static String below(String id) {
        return id == null ? null : saved().get(id);
    }

    /** Whether putting a column below another would make a loop, the other being in its own stack under it. */
    public static boolean wouldLoop(String id, String under) {
        String at = under;
        for (int i = 0; at != null && i < 256; i++) {
            if (at.equals(id)) return true;
            at = below(at);
        }
        return false;
    }

    /** Puts a column below another, or back into the row when under is null. Loops are refused. */
    public static boolean set(String id, String under) {
        if (under != null && (under.equals(id) || wouldLoop(id, under))) return false;
        if (under == null) saved().remove(id);
        else saved().put(id, under);
        Prefs.save();
        return true;
    }

    /** The window right above this one in its stack, or null when it starts a stack or stands alone. */
    public static WWindow above(WWindow w) {
        return ABOVE.get(w);
    }

    /**
     * The windows grouped into stacks, in the row's order: each stack starts with a column that
     * sits below nothing on screen, and goes down through what is below it, a column's own
     * stack before the next column set below the same one. Also notes which window is above which.
     */
    public static List<List<WWindow>> group(Collection<WWindow> windows) {
        ABOVE.clear();
        Map<String, WWindow> byId = new LinkedHashMap<>();
        for (WWindow w : windows) if (w.id != null) byId.put(w.id, w);

        List<List<WWindow>> stacks = new ArrayList<>();
        for (WWindow w : byId.values()) {
            String under = below(w.id);
            if (under != null && byId.containsKey(under) && !wouldLoop(w.id, under)) continue;
            List<WWindow> stack = new ArrayList<>();
            add(stack, w, byId, 0);
            stacks.add(stack);
        }
        for (List<WWindow> stack : stacks) {
            for (int i = 1; i < stack.size(); i++) ABOVE.put(stack.get(i), stack.get(i - 1));
        }
        return stacks;
    }

    private static void add(List<WWindow> stack, WWindow w, Map<String, WWindow> byId, int depth) {
        if (stack.contains(w) || depth > 256) return;
        stack.add(w);
        for (WWindow child : byId.values()) {
            if (w.id.equals(below(child.id))) add(stack, child, byId, depth + 1);
        }
    }

    /** Forgets which window is above which, for when nothing is stacked. */
    public static void clear() {
        ABOVE.clear();
    }
}
