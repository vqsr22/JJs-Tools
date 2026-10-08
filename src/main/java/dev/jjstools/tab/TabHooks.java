package dev.jjstools.tab;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;

/** Lets LiteTab call PlayerListHud's private latency icon method through the mixin. */
public interface TabHooks {
    void jjstools$renderLatency(DrawContext ctx, int width, int x, int y, PlayerListEntry entry);

    static void latency(PlayerListHud hud, DrawContext ctx, int width, int x, int y, PlayerListEntry entry) {
        ((TabHooks) hud).jjstools$renderLatency(ctx, width, x, y, entry);
    }
}
