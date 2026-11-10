package dev.jjstools.compat;

/**
 * A mouse press or release: where it was and which button. Minecraft 1.21.9 and newer hand
 * screens a Click for this and older versions three plain values, so code written against this
 * works on both.
 */
public record Mouse(double x, double y, int button) {
    //#if MC >= 1.21.9
    public static Mouse of(net.minecraft.client.gui.Click click) {
        return new Mouse(click.x(), click.y(), click.button());
    }
    //#endif
}
