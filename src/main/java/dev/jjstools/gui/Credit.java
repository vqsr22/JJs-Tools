package dev.jjstools.gui;

import meteordevelopment.meteorclient.utils.render.color.Color;

/** Shared look for the tabs this add-on adds to the Meteor GUI. */
public final class Credit {
    /**
     * The add-on's own purple.
     *
     * The same 120, 60, 200 given to Meteor in fabric.mod.json, so the tabs match the colour the
     * modules already carry rather than picking a second purple that is nearly but not quite it.
     */
    public static final Color PURPLE = new Color(120, 60, 200);

    private Credit() {}
}
