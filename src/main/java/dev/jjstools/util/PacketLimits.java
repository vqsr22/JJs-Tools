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

    private static int count(ArrayDeque<Long> sent, long window) {
        long cutoff = System.currentTimeMillis() - window;
        synchronized (PacketLimits.class) {
            while (!sent.isEmpty() && sent.peekFirst() < cutoff) sent.pollFirst();
            return sent.size();
        }
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
