package dev.jjstools.util;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;

/**
 * Keeps Meteor's Freecam from crashing the game while you change worlds.
 *
 * Freecam's tick asks the game's camera entity something on every tick, without checking there
 * is one. While a server moves you to another world (2b2t's queue to the main server, a
 * dimension change) there is a moment with no camera entity, and with Freecam on through it, as
 * Smoother Freecam keeps it, the game crashed. Just before Freecam's tick, the camera is given
 * back to your player when it is missing; if there is no player yet either, Freecam is turned off
 * for that moment and back on once you are in the world.
 */
public final class FreecamGuard {
    private FreecamGuard() {}

    private static boolean turnedOff;

    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(FreecamGuard.class);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 100)
    private static void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Modules modules = Modules.get();
        if (modules == null) return;
        Freecam freecam = modules.get(Freecam.class);
        if (freecam == null) return;

        if (turnedOff) {
            // Back in a world: on again, as it was.
            if (mc.player != null && mc.world != null && mc.getCameraEntity() != null) {
                turnedOff = false;
                if (!freecam.isActive()) freecam.toggle();
            }
            return;
        }

        if (!freecam.isActive() || mc.getCameraEntity() != null) return;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        } else {
            turnedOff = true;
            freecam.toggle();
        }
    }
}
