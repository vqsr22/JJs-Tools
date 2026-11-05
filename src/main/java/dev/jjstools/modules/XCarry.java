package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;

/**
 * Lets you keep items in your inventory's 2x2 crafting grid, as 4 extra slots.
 *
 * Normally closing your inventory tells the server, and the server moves anything in the crafting
 * grid back into your inventory. This module stops that one "closed my inventory" packet (only for
 * your own inventory, never chests or other containers), so the items stay in the grid.
 *
 * Turning the module off sends the close packet, and the server hands the items back as normal.
 * If you disconnect or die with items in the grid, the server returns or drops them like vanilla.
 */
public class XCarry extends Module {
    private boolean holding;

    public XCarry() {
        super(JJsTools.CATEGORY, "XCarry", "Keep items in your inventory's crafting grid as 4 extra slots. Warning: items in the grid can be dropped if you disconnect or turn XCarry off.");
    }

    @Override
    public void onActivate() {
        holding = false;
    }

    @Override
    public void onDeactivate() {
        if (holding && mc.player != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.playerScreenHandler.syncId));
        }
        holding = false;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        holding = false;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!(event.packet instanceof CloseHandledScreenC2SPacket packet) || mc.player == null) return;
        if (packet.getSyncId() != mc.player.playerScreenHandler.syncId) return; // other containers close normally

        event.cancel();
        holding = true;
    }
}
