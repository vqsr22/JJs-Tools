package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.PacketLimits;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

/**
 * Keeps you under the server's packet limits so they never kick you.
 *
 * Inventory packets are the strict one: at the limit, items cannot be moved at all. The click is
 * stopped before the game moves anything on your screen, so nothing is left out of step with the
 * server; it simply does nothing until the count has dropped. Block interactions are held back at
 * their limit too, and both stop while the overall count is at its limit, since they add to it.
 * The counts are the ones the Packet Limits HUD element shows.
 */
public class NoPacketKicks extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLimits = settings.createGroup("Limits");

    private final Setting<Integer> margin = sgGeneral.add(new IntSetting.Builder()
        .name("safety-margin")
        .description("Stop this many percent short of each limit (at least one packet). Packets reach the server bunched up by ping jitter and lag, so it can count more in its window than were sent in any one here.")
        .defaultValue(10).range(0, 50).sliderRange(0, 30)
        .build());

    private final Setting<Boolean> tpsAware = sgGeneral.add(new BoolSetting.Builder()
        .name("tps-aware")
        .description("While the server lags, count over a longer window to match it (up to four times), since a lagging server counts over more real time.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> blockInteract = sgGeneral.add(new BoolSetting.Builder()
        .name("limit-interactions")
        .description("Also hold back block interactions (placing, opening, using) at their limit.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> warn = sgGeneral.add(new BoolSetting.Builder()
        .name("warn")
        .description("Say in chat when something was held back, or when the overall count is near its limit.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> globalLimit = sgLimits.add(new IntSetting.Builder()
        .name("global-limit")
        .description("Packets of any kind allowed in four seconds.")
        .defaultValue(PacketLimits.DEFAULT_GLOBAL).min(1).sliderRange(100, 2000)
        .build());

    private final Setting<Integer> inventoryLimit = sgLimits.add(new IntSetting.Builder()
        .name("inventory-limit")
        .description("Inventory packets (slot clicks, creative inventory, container buttons) allowed in four seconds.")
        .defaultValue(PacketLimits.DEFAULT_INVENTORY).min(1).sliderRange(10, 200)
        .build());

    private final Setting<Integer> interactLimit = sgLimits.add(new IntSetting.Builder()
        .name("interact-limit")
        .description("Block interactions allowed in 310 milliseconds.")
        .defaultValue(PacketLimits.DEFAULT_INTERACT).min(1).sliderRange(1, 30)
        .build());

    private long lastWarn;

    public NoPacketKicks() {
        super(JJsTools.CATEGORY, "no-packet-kicks", "Keeps your global, inventory and interaction packets under the server's limits: at the inventory limit items cannot be moved at all until it drops. Pair with the Packet Limits HUD element.");
    }

    private static NoPacketKicks active() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        NoPacketKicks m = modules.get(NoPacketKicks.class);
        return m != null && m.isActive() ? m : null;
    }

    /** The limits the HUD shows: this module's, or the defaults. */
    public static int globalLimitShown() {
        NoPacketKicks m = Modules.get() == null ? null : Modules.get().get(NoPacketKicks.class);
        return m != null ? m.globalLimit.get() : PacketLimits.DEFAULT_GLOBAL;
    }

    public static int inventoryLimitShown() {
        NoPacketKicks m = Modules.get() == null ? null : Modules.get().get(NoPacketKicks.class);
        return m != null ? m.inventoryLimit.get() : PacketLimits.DEFAULT_INVENTORY;
    }

    public static int interactLimitShown() {
        NoPacketKicks m = Modules.get() == null ? null : Modules.get().get(NoPacketKicks.class);
        return m != null ? m.interactLimit.get() : PacketLimits.DEFAULT_INTERACT;
    }

    /** Whether the windows follow the server's tick rate: on unless this module says otherwise. */
    public static boolean tpsAware() {
        NoPacketKicks m = Modules.get() == null ? null : Modules.get().get(NoPacketKicks.class);
        return m == null || m.tpsAware.get();
    }

    /** Where to stop for a limit: the margin short of it, and always at least one short. */
    private int stopAt(int limit) {
        int under = Math.max(1, (int) Math.ceil(limit * margin.get() / 100.0));
        return Math.max(1, limit - under);
    }

    private boolean globalFull() {
        return PacketLimits.global() >= stopAt(globalLimit.get());
    }

    private boolean inventoryFull() {
        return PacketLimits.inventory() >= stopAt(inventoryLimit.get()) || globalFull();
    }

    /**
     * Whether an inventory click may go through now. Asked by the click itself
     * (ClientPlayerInteractionManagerMixin) before anything moves, so a refused click changes
     * nothing on either side.
     */
    public static boolean allowInventory() {
        NoPacketKicks m = active();
        if (m == null) return true;
        boolean full = m.inventoryFull();
        if (full) m.tellOnce("Inventory packet limit reached: items cannot be moved for a moment.");
        return !full;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onInteract(InteractBlockEvent event) {
        if (!blockInteract.get()) return;
        if (PacketLimits.interact() >= stopAt(interactLimit.get()) || globalFull()) {
            event.cancel();
            tellOnce("Interaction packet limit reached: held back for a moment.");
        }
    }

    /**
     * The last line: an inventory packet that did not come through a normal click (another mod
     * sending it straight out) is dropped at the limit rather than sent.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (event.isCancelled()) return;
        if (PacketLimits.isInventory(event.packet)) {
            if (inventoryFull()) {
                event.cancel();
                tellOnce("Inventory packet limit reached: a packet was held back.");
            }
        } else if (globalFull()) {
            tellOnce("Close to the overall packet limit (" + PacketLimits.global() + "/" + globalLimit.get() + ").");
        }
    }

    private void tellOnce(String message) {
        if (!warn.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastWarn < 2000) return;
        lastWarn = now;
        warning(message);
    }
}
