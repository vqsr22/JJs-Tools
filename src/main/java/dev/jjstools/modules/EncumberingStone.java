package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.entity.DropItemsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps items out of your inventory and in it.
 *
 * Items you are not to carry are still picked up, as the server decides that, but straight back
 * out again: thrown in front of you or behind you, and only as many as just came in, so nothing
 * you were already carrying goes with them. Items you are not to lose cannot be thrown, whether
 * by the drop key, from the inventory screen, or by clicking outside it.
 *
 * Each side has its own list: All for every item, Blacklist for only the items listed, or
 * Whitelist for every item except those listed.
 */
public class EncumberingStone extends Module {
    public enum Mode { All, Blacklist, Whitelist }
    public enum Direction { Front, Behind }

    private final SettingGroup sgPickup = settings.createGroup("Pickup");
    private final SettingGroup sgThrow = settings.createGroup("Throwing");

    private final Setting<Boolean> refusePickup = sgPickup.add(new BoolSetting.Builder()
        .name("refuse-pickup")
        .description("Throw back out items you pick up.")
        .defaultValue(true)
        .build());

    private final Setting<Mode> pickupMode = sgPickup.add(new EnumSetting.Builder<Mode>()
        .name("pickup-mode")
        .description("Which items are thrown back out. All: every item. Blacklist: only the listed items. Whitelist: every item except the listed ones.")
        .defaultValue(Mode.All)
        .visible(refusePickup::get)
        .build());

    private final Setting<List<Item>> pickupItems = sgPickup.add(new ItemListSetting.Builder()
        .name("pickup-items")
        .description("The items for the blacklist or whitelist.")
        .visible(() -> refusePickup.get() && pickupMode.get() != Mode.All)
        .build());

    private final Setting<Direction> direction = sgPickup.add(new EnumSetting.Builder<Direction>()
        .name("throw-direction")
        .description("Which way refused items are thrown: in front of you or behind you.")
        .defaultValue(Direction.Behind)
        .visible(refusePickup::get)
        .build());

    private final Setting<Boolean> blockThrowing = sgThrow.add(new BoolSetting.Builder()
        .name("block-throwing")
        .description("Stop items being thrown out of your inventory.")
        .defaultValue(true)
        .build());

    private final Setting<Mode> throwMode = sgThrow.add(new EnumSetting.Builder<Mode>()
        .name("throw-mode")
        .description("Which items cannot be thrown. All: every item. Blacklist: only the listed items. Whitelist: every item except the listed ones.")
        .defaultValue(Mode.All)
        .visible(blockThrowing::get)
        .build());

    private final Setting<List<Item>> throwItems = sgThrow.add(new ItemListSetting.Builder()
        .name("throw-items")
        .description("The items for the blacklist or whitelist.")
        .visible(() -> blockThrowing.get() && throwMode.get() != Mode.All)
        .build());

    /**
     * What the inventory held the moment before the latest pickup, or null when nothing has been
     * picked up lately. Items that come in after it, of a kind that is refused, are thrown out.
     */
    private ItemStack[] before;
    /** Ticks since the latest pickup; the watch ends a couple of seconds after it. */
    private int sincePickup;
    /** The inventory as it was at the end of the last tick, before anything this tick arrived. */
    private ItemStack[] lastTick;
    /** Set while this module throws, so its own throws are not blocked. */
    private boolean throwing;

    public EncumberingStone() {
        super(JJsTools.CATEGORY, "encumbering-stone", "Throws items you pick up straight back out, in front of or behind you, and stops you throwing items away. Each has its own all, blacklist or whitelist.");
    }

    @Override
    public void onDeactivate() {
        before = null;
        lastTick = null;
    }

    private static boolean matches(Mode mode, List<Item> list, Item item) {
        return switch (mode) {
            case All -> true;
            case Blacklist -> list.contains(item);
            case Whitelist -> !list.contains(item);
        };
    }

    // Throwing out

    @EventHandler(priority = EventPriority.HIGH)
    private void onDrop(DropItemsEvent event) {
        if (throwing || !blockThrowing.get() || event.itemStack == null || event.itemStack.isEmpty()) return;
        if (matches(throwMode.get(), throwItems.get(), event.itemStack.getItem())) event.cancel();
    }

    // Picking up

    /**
     * The server says you picked something up. The item itself is not looked at: an item that
     * flies straight into you can be picked up before the game has even shown it, so there is no
     * entity to ask. And the inventory update for it arrives ahead of this message, so the
     * inventory now already holds it. What it held at the end of the last tick is the baseline
     * instead, and whatever has grown since is the pickup.
     */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof ItemPickupAnimationS2CPacket packet) || !refusePickup.get()) return;
        mc.execute(() -> {
            if (mc.player == null || packet.getCollectorEntityId() != mc.player.getId()) return;
            if (before == null) before = lastTick != null ? lastTick : snapshot();
            sincePickup = 0;
        });
    }

    private ItemStack[] snapshot() {
        ItemStack[] stacks = new ItemStack[36];
        for (int i = 0; i < 36; i++) stacks[i] = mc.player.getInventory().getStack(i).copy();
        return stacks;
    }

    /** Throws out whatever refused items came in since the pickup, as soon as they arrive. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.interactionManager == null) {
            before = null;
            lastTick = null;
            return;
        }
        if (before == null) {
            if (refusePickup.get()) lastTick = snapshot();
            return;
        }

        List<int[]> throwsToDo = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack now = mc.player.getInventory().getStack(i);
            if (now.isEmpty() || !matches(pickupMode.get(), pickupItems.get(), now.getItem())) continue;
            ItemStack was = before[i];
            boolean sameKind = !was.isEmpty() && ItemStack.areItemsAndComponentsEqual(was, now);
            if (!sameKind) {
                // Nothing there before, or something else: all of it came in.
                throwsToDo.add(new int[] { i, -1 });
            } else if (now.getCount() > was.getCount()) {
                throwsToDo.add(new int[] { i, now.getCount() - was.getCount() });
            }
        }
        if (!throwsToDo.isEmpty()) throwOut(throwsToDo);

        // The throws are taken out of the inventory straight away, so this is the new baseline.
        before = snapshot();
        lastTick = before;
        if (++sincePickup > 40) before = null;
    }

    /** Throws each slot's new items, facing the way the setting says for the length of the throw. */
    private void throwOut(List<int[]> slots) {
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        float throwYaw = direction.get() == Direction.Behind ? yaw + 180f : yaw;
        // A little upward, so the items carry rather than land at your feet.
        boolean onGround = mc.player.isOnGround();
        boolean collision = mc.player.horizontalCollision;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(throwYaw, -20f, onGround, collision));
        throwing = true;
        try {
            int syncId = mc.player.currentScreenHandler.syncId;
            for (int[] s : slots) {
                int id = SlotUtils.indexToId(s[0]);
                if (id < 0) continue;
                if (s[1] < 0) {
                    mc.interactionManager.clickSlot(syncId, id, 1, SlotActionType.THROW, mc.player);
                } else {
                    for (int n = 0; n < s[1]; n++) mc.interactionManager.clickSlot(syncId, id, 0, SlotActionType.THROW, mc.player);
                }
            }
        } finally {
            throwing = false;
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, collision));
        }
    }
}
