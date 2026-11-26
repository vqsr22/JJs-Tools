package dev.jjstools.util;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;

/**
 * Gives the camera back to your player when a world change leaves the game without one while
 * Meteor's Freecam is on. FreecamTickMixin skips Freecam's tick until then, so it cannot crash.
 */
public final class FreecamGuard {
    private FreecamGuard() {}

    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(FreecamGuard.class);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 100)
    private static void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getCameraEntity() != null || mc.player == null) return;
        Modules modules = Modules.get();
        if (modules == null) return;
        Freecam freecam = modules.get(Freecam.class);
        if (freecam != null && freecam.isActive()) mc.setCameraEntity(mc.player);
    }
}
