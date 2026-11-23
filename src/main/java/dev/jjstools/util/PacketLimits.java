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
 * Always counting, for the Packet Limits and Packet Graph HUD elements.
 *
 * With the server lagging, its windows may cover more real time than these (if it measures them
 * in ticks), so it can count packets that have already dropped out here. When tps-aware is on,
 * the windows are stretched by how far the server is behind 20 ticks a second.
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

    private static meteordevelopment.meteorclient.settings.Setting<Integer> globalLimit, inventoryLimit, interactLimit;
    private static meteordevelopment.meteorclient.settings.Setting<Boolean> tpsAware;

    /**
     * The limits, kept with Meteor's HUD settings (the HUD tab, "Packet Limits") so the Packet
     * Limits and Packet Graph elements share them.
     */
    public static void init() {
        var group = meteordevelopment.meteorclient.systems.hud.Hud.get().settings.createGroup("Packet Limits");
        globalLimit = group.add(new meteordevelopment.meteorclient.settings.IntSetting.Builder()
            .name("global-limit").description("Packets of any kind the server allows in four seconds.")
            .defaultValue(DEFAULT_GLOBAL).min(1).sliderRange(100, 2000).build());
        inventoryLimit = group.add(new meteordevelopment.meteorclient.settings.IntSetting.Builder()
            .name("inventory-limit").description("Inventory packets (slot clicks, creative inventory, container buttons) the server allows in four seconds.")
            .defaultValue(DEFAULT_INVENTORY).min(1).sliderRange(10, 200).build());
        interactLimit = group.add(new meteordevelopment.meteorclient.settings.IntSetting.Builder()
            .name("interact-limit").description("Block interactions the server allows in 310 milliseconds.")
            .defaultValue(DEFAULT_INTERACT).min(1).sliderRange(1, 30).build());
        tpsAware = group.add(new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
            .name("tps-aware").description("While the server lags, count over a longer window to match it (up to four times), since a lagging server counts over more real time.")
            .defaultValue(true).build());
        meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.subscribe(PacketLimits.class);
    }

    public static int globalLimit() {
        return globalLimit != null ? globalLimit.get() : DEFAULT_GLOBAL;
    }

    public static int inventoryLimit() {
        return inventoryLimit != null ? inventoryLimit.get() : DEFAULT_INVENTORY;
    }

    public static int interactLimit() {
        return interactLimit != null ? interactLimit.get() : DEFAULT_INTERACT;
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
        if (tpsAware != null && !tpsAware.get()) return 1;
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
