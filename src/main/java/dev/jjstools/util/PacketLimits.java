package dev.jjstools.util;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;

import java.util.ArrayDeque;

/**
 * Counts the packets you send, the way the server's packet limits count them: every packet over
 * the last four seconds, inventory packets (slot clicks, creative inventory actions and container
 * buttons) over the last four seconds, and block interactions over the last 310 milliseconds.
 * Always counting, so the HUD shows the numbers whether or not No Packet Kicks is on.
 *
 * With the server lagging, its windows may cover more real time than these (if it measures them
 * in ticks), so it can count packets that have already dropped out here. When No Packet Kicks
 * says to, the windows are stretched by how far the server is behind 20 ticks a second.
 */
public final class PacketLimits {
    private PacketLimits() {}

    public static final long GLOBAL_WINDOW_MS = 4000;
    public static final long INVENTORY_WINDOW_MS = 4000;
    public static final long INTERACT_WINDOW_MS = 310;

    public static final int DEFAULT_GLOBAL = 1249;
    public static final int DEFAULT_INVENTORY = 79;
    public static final int DEFAULT_INTERACT = 9;

    private static final ArrayDeque<Long> global = new ArrayDeque<>();
    private static final ArrayDeque<Long> inventory = new ArrayDeque<>();
    private static final ArrayDeque<Long> interact = new ArrayDeque<>();

    public static void init() {
        meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.subscribe(PacketLimits.class);
    }

    public static boolean isInventory(Packet<?> packet) {
        return packet instanceof ClickSlotC2SPacket || packet instanceof CreativeInventoryActionC2SPacket
            || packet instanceof ButtonClickC2SPacket;
    }

    public static boolean isInteract(Packet<?> packet) {
        return packet instanceof PlayerInteractBlockC2SPacket;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private static void onSent(PacketEvent.Sent event) {
        long now = System.currentTimeMillis();
        synchronized (PacketLimits.class) {
            global.addLast(now);
            if (isInventory(event.packet)) inventory.addLast(now);
            if (isInteract(event.packet)) interact.addLast(now);
            // Old ones go here too, so nothing piles up while no one is reading the counts.
            trim(global, now - (long) (GLOBAL_WINDOW_MS * MAX_STRETCH));
            trim(inventory, now - (long) (INVENTORY_WINDOW_MS * MAX_STRETCH));
            trim(interact, now - (long) (INTERACT_WINDOW_MS * MAX_STRETCH));
        }
    }

    @EventHandler
    private static void onLeft(GameLeftEvent event) {
        synchronized (PacketLimits.class) {
            global.clear();
            inventory.clear();
            interact.clear();
        }
    }

    /** The most a window is ever stretched, so a frozen server does not lock you out for long. */
    public static final double MAX_STRETCH = 4;

    /** How much the windows are stretched right now: 1, or more while the server lags. */
    public static double stretch() {
        if (!dev.jjstools.modules.NoPacketKicks.tpsAware()) return 1;
        float tps = meteordevelopment.meteorclient.utils.world.TickRate.INSTANCE.getTickRate();
        if (!(tps > 0) || tps >= 19.5f) return 1;
        return Math.min(MAX_STRETCH, 20.0 / tps);
    }

    private static int count(ArrayDeque<Long> sent, long window) {
        long now = System.currentTimeMillis();
        long cutoff = now - (long) (window * stretch());
        synchronized (PacketLimits.class) {
            // Kept for the longest the window can be stretched to; counted from the newest back.
            trim(sent, now - (long) (window * MAX_STRETCH));
            int n = 0;
            for (var it = sent.descendingIterator(); it.hasNext(); ) {
                if (it.next() < cutoff) break;
                n++;
            }
            return n;
        }
    }

    private static void trim(ArrayDeque<Long> sent, long keep) {
        while (!sent.isEmpty() && sent.peekFirst() < keep) sent.pollFirst();
    }

    public static int global() {
        return count(global, GLOBAL_WINDOW_MS);
    }

    public static int inventory() {
        return count(inventory, INVENTORY_WINDOW_MS);
    }

    public static int interact() {
        return count(interact, INTERACT_WINDOW_MS);
    }
}
