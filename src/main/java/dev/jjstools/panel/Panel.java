package dev.jjstools.panel;

import net.minecraft.item.ItemStack;

/**
 * One window of the Control Panel.
 *
 * The screen draws the frame, title bar, close button and resize grip, and moves and sizes the
 * window; the panel only fills in the inside, at whatever size it has been given. Resizing a
 * panel changes how much fits, never the size of its text, which has a setting of its own.
 */
public abstract class Panel {
    public final String id;
    public final String title;
    public final ItemStack icon;
    public final int minW;
    public final int minH;

    public int x, y, w, h;
    public boolean open = true;
    public int z;
    /** When the window was opened and how long it waited to come in, and when it was closed, for its animation. */
    public double shownAt = -1e9, delay, hiddenAt = -1e9;
    /** The middle of its button on the dock, which it grows out of and shrinks back into. */
    public float tabX, tabY;
    /** Where the window was before it was made to fill the screen, or null if it does not. */
    public int[] restore;

    protected Panel(String id, String title, ItemStack icon, int minW, int minH) {
        this.id = id;
        this.title = title;
        this.icon = icon;
        this.minW = minW;
        this.minH = minH;
    }

    /** Draws the inside of the panel, from x, y, w by h on screen. */
    public abstract void draw(Ui ui, int x, int y, int w, int h);

    /** A short note after the title, such as how many there are. */
    public String badge() {
        return null;
    }

    /** Called when the panel is shown, or the Control Panel opened with it showing. */
    public void shown() {}
}
