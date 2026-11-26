package dev.jjstools.util;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

/**
 * Keeps Meteor's Freecam working through world changes while it stays on.
 *
 * When a world change leaves the game without a camera entity, the camera is given back to your
 * player (FreecamTickMixin skips Freecam's tick until then, so it cannot crash). And when you
 * arrive in a new world, or the server moves you far away, the free camera is brought back to you: left
 * where it was, it was too far from the blocks around you for any of them to be drawn.
 */
public final class FreecamGuard {
    private FreecamGuard() {}

    private static World lastWorld;
    private static double lastX, lastZ;

    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(FreecamGuard.class);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 100)
    private static void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        Modules modules = Modules.get();
        if (modules == null) return;
        Freecam freecam = modules.get(Freecam.class);
        if (freecam == null || !freecam.isActive()) {
            remember(mc);
            return;
        }
        if (mc.getCameraEntity() == null) mc.setCameraEntity(mc.player);
        if (strayed(freecam)) recentre(freecam);
        remember(mc);
    }

    private static void remember(MinecraftClient mc) {
        lastWorld = mc.world;
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
    }

    /**
     * Whether the free camera was left behind: you are in another world, or the server moved you
     * further than you can see since the last tick. Flying the camera far yourself does not count.
     */
    public static boolean strayed(Freecam freecam) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;
        if (mc.world != lastWorld) return true;
        double reach = (mc.options.getViewDistance().getValue() + 1) * 16.0;
        double dx = mc.player.getX() - lastX, dz = mc.player.getZ() - lastZ;
        return dx * dx + dz * dz > reach * reach;
    }

    /** Puts the free camera at your player's eyes. */
    public static void recentre(Freecam freecam) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        remember(mc);
        double x = mc.player.getX(), y = mc.player.getEyeY(), z = mc.player.getZ();
        freecam.pos.set(x, y, z);
        freecam.prevPos.set(x, y, z);
    }
}
