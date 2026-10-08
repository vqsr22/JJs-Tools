package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.MovementType;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Opens doors, trapdoors and fence gates in front of you and closes them behind
 * you. Feature set follows Stardust's AutoDoors by Tas (GPL-3.0), rewritten for
 * 1.21.11 with these changes:
 *  - Movement direction comes from the requested move, before collision, so a
 *    closed door you are walking into still registers.
 *  - Nothing is closed while your hitbox is still inside it.
 *  - Fence gates only react when fence-gates is on.
 *  - Iron doors are never clicked directly (that would place your held block);
 *    the nearest button or lever in reach is used instead.
 *  - Sneaking pauses the module, so you can walk through without toggling.
 *  - Doors that were already open when you reached them are left open: only
 *    doors auto-door opened itself get closed behind you (close-only-own).
 */
public class AutoDoor extends Module {
    public enum Mode { Classic, Spammer }

    public enum Mute { Never, Always, Spammer }

    public enum Swing { Both, Client, Packet, None }

    private static AutoDoor INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBlocks = settings.createGroup("Blocks");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Classic opens what is ahead and closes what is behind. Spammer toggles every door in range.")
        .defaultValue(Mode.Classic)
        .build()
    );

    private final Setting<Mute> mute = sgGeneral.add(new EnumSetting.Builder<Mute>()
        .name("mute-doors")
        .description("Mute door sounds while the module is on.")
        .defaultValue(Mute.Never)
        .build()
    );

    private final Setting<Swing> swing = sgGeneral.add(new EnumSetting.Builder<Swing>()
        .name("swing")
        .description("Both: normal swing. Client: animation only, no packet. Packet: packet only, no animation. None: no swing.")
        .defaultValue(Swing.Both)
        .build()
    );

    private final Setting<Boolean> autoOpen = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-open")
        .description("Open doors as you walk into them.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Classic)
        .build()
    );

    private final Setting<Boolean> autoClose = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-close")
        .description("Close doors behind you once you are fully through.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Classic)
        .build()
    );

    private final Setting<Boolean> closeOnlyOwn = sgGeneral.add(new BoolSetting.Builder()
        .name("close-only-own")
        .description("Only close doors that auto-door opened. Doors that were already open when you got there stay open.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Classic && autoClose.get())
        .build()
    );

    private final Setting<Boolean> doubleDoors = sgGeneral.add(new BoolSetting.Builder()
        .name("double-doors")
        .description("Also toggle the door beside it, so double doors open and close together.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Classic)
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("Minimum ticks before the same block is toggled again. Stops flicker on laggy servers.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 40)
        .visible(() -> mode.get() == Mode.Classic)
        .build()
    );

    private final Setting<Integer> spamRange = sgGeneral.add(new IntSetting.Builder()
        .name("spam-range")
        .description("Range in blocks to look for doors. Doors out of reach are skipped.")
        .defaultValue(4)
        .range(1, 5)
        .sliderRange(1, 5)
        .visible(() -> mode.get() == Mode.Spammer)
        .build()
    );

    private final Setting<Integer> spamDelay = sgGeneral.add(new IntSetting.Builder()
        .name("spam-delay")
        .description("Ticks between each round of toggles.")
        .defaultValue(2)
        .range(2, 20)
        .sliderRange(2, 20)
        .visible(() -> mode.get() == Mode.Spammer)
        .build()
    );

    private final Setting<Boolean> ironDoors = sgBlocks.add(new BoolSetting.Builder()
        .name("iron-doors")
        .description("Open iron doors with the nearest button or lever. Only levers are used to close them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> leverDelay = sgBlocks.add(new IntSetting.Builder()
        .name("lever-delay")
        .description("Ticks between button or lever presses. Raise this if iron doors act up.")
        .defaultValue(5)
        .range(0, 100)
        .sliderRange(2, 60)
        .visible(ironDoors::get)
        .build()
    );

    private final Setting<Boolean> trapdoors = sgBlocks.add(new BoolSetting.Builder()
        .name("trapdoors")
        .description("Open trapdoors at foot or head height ahead of you. Paused while climbing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fenceGates = sgBlocks.add(new BoolSetting.Builder()
        .name("fence-gates")
        .description("Open fence gates, including gates stacked two high.")
        .defaultValue(false)
        .build()
    );

    private final Map<Long, Integer> recent = new HashMap<>();
    /** Blocks auto-door opened itself (doors keyed by their lower half), with the tick they were opened. */
    private final Map<Long, Integer> openedByUs = new HashMap<>();
    private int tick;
    private int spamTimer;
    private int lastSwitchTick = -1000;

    public AutoDoor() {
        super(JJsTools.CATEGORY, "auto-door", "Opens doors ahead of you and closes them behind you.");
        INSTANCE = this;
    }

    public static AutoDoor get() {
        return INSTANCE;
    }

    /** Used by DoorBlockMixin. */
    public boolean shouldMute() {
        return isActive() && (mute.get() == Mute.Always || (mute.get() == Mute.Spammer && mode.get() == Mode.Spammer));
    }

    @Override
    public void onActivate() {
        recent.clear();
        openedByUs.clear();
        spamTimer = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        recent.clear();
        openedByUs.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tick++;
        if ((tick & 255) == 0) recent.values().removeIf(t -> tick - t > 200);
        if ((tick & 31) == 0) pruneOpened();

        if (mode.get() != Mode.Spammer) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null || mc.player.isSneaking()) return;

        if (++spamTimer < spamDelay.get()) return;
        spamTimer = 0;

        int r = spamRange.get();
        BlockPos centre = mc.player.getBlockPos();
        for (BlockPos p : BlockPos.iterate(centre.add(-r, -r, -r), centre.add(r, r, r))) {
            BlockState state = mc.world.getBlockState(p);
            if (!(state.getBlock() instanceof DoorBlock) || state.isOf(Blocks.IRON_DOOR)) continue;

            // Only click the lower half, so each door toggles once per round.
            if (mc.world.getBlockState(p.down()).getBlock() instanceof DoorBlock) continue;

            toggle(p.toImmutable(), false);
        }
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        if (mode.get() != Mode.Classic || event.type != MovementType.SELF) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null || mc.player.isSneaking() || mc.player.isClimbing()) return;

        double mx = event.movement.x;
        double mz = event.movement.z;
        if (mx * mx + mz * mz < 1.0E-6) return;

        Direction dir = Direction.getFacing(mx, 0.0, mz);
        BlockPos feet = mc.player.getBlockPos();
        boolean onPlate = mc.world.getBlockState(feet).getBlock() instanceof AbstractPressurePlateBlock;

        if (autoOpen.get()) handle(feet.offset(dir), dir, true, onPlate);
        if (autoClose.get()) handle(feet.offset(dir.getOpposite()), dir, false, onPlate);
    }

    /** Checks foot and head height at base and moves each block toward the wanted open state. */
    private void handle(BlockPos base, Direction dir, boolean open, boolean onPlate) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = dy == 0 ? base : base.up();
            BlockState state = mc.world.getBlockState(p);
            Block block = state.getBlock();

            if (block instanceof DoorBlock) {
                // Already in the wanted state (for example someone else opened it): nothing to do.
                if (state.get(DoorBlock.OPEN) == open) return;
                if (!open && (!clearOf(p) || !mayClose(p, state))) return;

                if (state.isOf(Blocks.IRON_DOOR)) {
                    if (ironDoors.get() && !onPlate && useSwitch(p, open)) mark(p, state, open);
                    return;
                }

                if (toggle(p, true)) mark(p, state, open);
                if (doubleDoors.get()) partner(p, dir, open);
                return;
            }

            if (fenceGates.get() && block instanceof FenceGateBlock) {
                if (state.get(FenceGateBlock.OPEN) != open && (open || (clearOf(p) && mayClose(p, state)))) {
                    if (toggle(p, true)) mark(p, state, open);
                }
                continue;
            }

            if (trapdoors.get() && block instanceof TrapdoorBlock && !state.isOf(Blocks.IRON_TRAPDOOR)) {
                if (state.get(TrapdoorBlock.OPEN) != open && (open || (clearOf(p) && mayClose(p, state)))) {
                    if (toggle(p, true)) mark(p, state, open);
                }
            }
        }
    }

    private void partner(BlockPos door, Direction dir, boolean open) {
        for (Direction side : new Direction[]{dir.rotateYClockwise(), dir.rotateYCounterclockwise()}) {
            BlockPos p = door.offset(side);
            BlockState state = mc.world.getBlockState(p);
            if (!(state.getBlock() instanceof DoorBlock) || state.isOf(Blocks.IRON_DOOR)) continue;

            if (state.get(DoorBlock.OPEN) != open && (open || (clearOf(p) && mayClose(p, state)))) {
                if (toggle(p, true)) mark(p, state, open);
            }
            return;
        }
    }

    /** Presses the nearest reachable button or lever that moves an iron door the right way. */
    private boolean useSwitch(BlockPos door, boolean open) {
        if (tick - lastSwitchTick < leverDelay.get()) return false;

        Vec3d eye = mc.player.getEyePos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos p : BlockPos.iterate(door.add(-2, -2, -2), door.add(2, 3, 2))) {
            BlockState state = mc.world.getBlockState(p);
            Block block = state.getBlock();

            boolean wanted;
            if (block instanceof LeverBlock) wanted = state.get(LeverBlock.POWERED) != open;
            else if (block instanceof ButtonBlock) wanted = open && !state.get(ButtonBlock.POWERED);
            else continue;

            if (!wanted || !mc.player.canInteractWithBlockAt(p, 0.0)) continue;

            double dist = eye.squaredDistanceTo(p.toCenterPos());
            if (dist < bestDist) {
                bestDist = dist;
                best = p.toImmutable();
            }
        }

        if (best == null) return false;
        lastSwitchTick = tick;
        return toggle(best, true);
    }

    /** True once your hitbox has fully left the block, so a door never closes on you. */
    private boolean clearOf(BlockPos pos) {
        return !mc.player.getBoundingBox().intersects(new Box(pos));
    }

    /** Returns true if the block was actually clicked. */
    private boolean toggle(BlockPos pos, boolean useCooldown) {
        long key = pos.asLong();
        if (useCooldown) {
            Integer last = recent.get(key);
            if (last != null && tick - last < cooldown.get()) return false;
        }

        if (!mc.player.canInteractWithBlockAt(pos, 0.0)) return false;
        recent.put(key, tick);

        Vec3d hit = pos.toCenterPos();
        Vec3d eye = mc.player.getEyePos();
        Direction side = Direction.getFacing(eye.x - hit.x, eye.y - hit.y, eye.z - hit.z);

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
        swingHand();
        return true;
    }

    /** Doors are tracked by their lower half, so either half counts as the same door. */
    private static long keyOf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) return pos.down().asLong();
        return pos.asLong();
    }

    /** With close-only-own on, only blocks auto-door opened itself may be closed. */
    private boolean mayClose(BlockPos pos, BlockState state) {
        return !closeOnlyOwn.get() || openedByUs.containsKey(keyOf(pos, state));
    }

    private void mark(BlockPos pos, BlockState state, boolean opened) {
        long key = keyOf(pos, state);
        if (opened) openedByUs.put(key, tick);
        else openedByUs.remove(key);
    }

    /** Forget blocks that are closed again (by anyone) or far away. */
    private void pruneOpened() {
        if (mc.player == null || mc.world == null) {
            openedByUs.clear();
            return;
        }
        BlockPos me = mc.player.getBlockPos();
        openedByUs.entrySet().removeIf(entry -> {
            // Give the server time to confirm the open before judging it.
            if (tick - entry.getValue() < 40) return false;
            BlockPos p = BlockPos.fromLong(entry.getKey());
            if (!p.isWithinDistance(me, 16)) return true;
            BlockState s = mc.world.getBlockState(p);
            if (s.getBlock() instanceof DoorBlock) return !s.get(DoorBlock.OPEN);
            if (s.getBlock() instanceof FenceGateBlock) return !s.get(FenceGateBlock.OPEN);
            if (s.getBlock() instanceof TrapdoorBlock) return !s.get(TrapdoorBlock.OPEN);
            return true;
        });
    }

    private void swingHand() {
        switch (swing.get()) {
            case Both -> mc.player.swingHand(Hand.MAIN_HAND);
            case Client -> mc.player.swingHand(Hand.MAIN_HAND, false);
            case Packet -> {
                if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            }
            case None -> {
            }
        }
    }
}
