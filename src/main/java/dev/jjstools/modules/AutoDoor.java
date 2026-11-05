package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
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

import java.util.ArrayList;
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

    private final Setting<Double> closeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("close-range")
        .description("How far you have to get from a door you opened before it is shut behind you, in blocks. Lower shuts sooner but risks shutting one you are still stood in the way of.")
        .defaultValue(2.0)
        .min(1.2).max(5.0)
        .sliderRange(1.5, 4.0)
        .visible(autoClose::get)
        .build()
    );

    private final Setting<Boolean> ignorePlated = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-plated")
        .description("Leave a door alone entirely when there is a pressure plate at it. The plate already works it, and clicking as well fights the plate instead of helping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> plateRange = sgGeneral.add(new IntSetting.Builder()
        .name("plate-range")
        .description("How far from a door to look for a plate, in blocks. Raise it for a plate set back from the door and wired to it.")
        .defaultValue(1)
        .min(1).max(4)
        .sliderRange(1, 3)
        .visible(ignorePlated::get)
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

        if (mode.get() == Mode.Classic) closeBehind();

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
            if (plated(p)) continue;

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

        if (autoOpen.get()) openAhead(feet, dir, event.movement, onPlate);
        if (autoClose.get()) handle(feet.offset(dir.getOpposite()), dir, false, onPlate, 0);
    }

    /** Never looks further ahead than this, however fast you are going. */
    private static final int MAX_LOOKAHEAD = 4;

    /**
     * Opens what is in your path, as far ahead as you will travel this tick and one block more.
     *
     * Only ever checking the very next block works at walking pace, where you spend several ticks
     * beside a door before reaching it. Coming in fast, off an elytra or with a running jump, you
     * can cover more than a block in one tick: the tick before you hit the door you are still
     * two blocks short of it, so the block next to you is air, and the tick you hit it the
     * collision stops you dead, so there is no movement left the tick after to set anything off.
     * The door was never once the block next to you while you were moving toward it.
     *
     * So the path ahead is followed out to where this tick's movement will take you, stopping at
     * the first thing that would block it, so a door on the far side of a wall is never opened.
     * Coming down as well as along, the block below is checked too, since you can be a block
     * above a door's top half the moment you are about to land in front of it.
     */
    private void openAhead(BlockPos feet, Direction dir, Vec3d movement, boolean onPlate) {
        double speed = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        int reach = Math.min(MAX_LOOKAHEAD, 1 + (int) Math.ceil(speed));
        int lowest = movement.y < -0.08 && !mc.player.isOnGround() ? -1 : 0;

        for (int k = 1; k <= reach; k++) {
            BlockPos base = feet.offset(dir, k);
            if (handle(base, dir, true, onPlate, lowest)) return;
            if (blocksPath(base)) return;
        }
    }

    /** Something solid at foot or head height that is not a door, gate or trapdoor. */
    private boolean blocksPath(BlockPos base) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = base.up(dy);
            BlockState state = mc.world.getBlockState(p);
            if (openable(state.getBlock())) continue;
            if (!state.getCollisionShape(mc.world, p).isEmpty()) return true;
        }
        return false;
    }

    private static boolean openable(Block block) {
        return block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapdoorBlock;
    }

    /**
     * Checks foot and head height at base, and the block below that too when lowest is -1, and
     * moves each one toward the wanted open state. Returns true if anything there was a door,
     * gate or trapdoor, whatever was done about it, so a search along a path stops at it.
     */
    private boolean handle(BlockPos base, Direction dir, boolean open, boolean onPlate, int lowest) {
        boolean found = false;

        for (int dy = lowest; dy <= 1; dy++) {
            BlockPos p = base.up(dy);
            BlockState state = mc.world.getBlockState(p);
            Block block = state.getBlock();

            // Below foot height only a door counts. A trapdoor or gate down there is the floor
            // you are about to land on, and opening that would drop you through it.
            if (dy < 0 && !(block instanceof DoorBlock)) continue;

            if (openable(block)) found = true;

            if (block instanceof DoorBlock) {
                // Already in the wanted state (for example someone else opened it): nothing to do.
                if (state.get(DoorBlock.OPEN) == open) return true;
                if (plated(p)) return true;
                if (!open && (!clearOf(p) || !mayClose(p, state))) return true;

                if (state.isOf(Blocks.IRON_DOOR)) {
                    if (ironDoors.get() && !onPlate && useSwitch(p, open)) mark(p, state, open);
                    return true;
                }

                if (toggle(p, true)) mark(p, state, open);
                if (doubleDoors.get()) partner(p, dir, open);
                return true;
            }

            if (fenceGates.get() && block instanceof FenceGateBlock) {
                if (plated(p)) continue;
                if (state.get(FenceGateBlock.OPEN) != open && (open || (clearOf(p) && mayClose(p, state)))) {
                    if (toggle(p, true)) mark(p, state, open);
                }
                continue;
            }

            if (trapdoors.get() && block instanceof TrapdoorBlock && !state.isOf(Blocks.IRON_TRAPDOOR)) {
                if (plated(p)) continue;
                if (state.get(TrapdoorBlock.OPEN) != open && (open || (clearOf(p) && mayClose(p, state)))) {
                    if (toggle(p, true)) mark(p, state, open);
                }
            }
        }

        return found;
    }

    /**
     * Whether a pressure plate is sitting at this door.
     *
     * A plate opens the door the moment you step on it and shuts it again the moment you step
     * off, on the server's own schedule. Clicking the same door on top of that does not help: it
     * fights the plate, so the door flickers, and every one of those clicks is a packet the
     * server did not need. So a door with a plate at it is left alone completely, neither opened
     * nor closed.
     *
     * The search covers a block up and down as well as out, which catches a plate on a step or
     * on the floor below a door at the top of stairs.
     */
    private boolean plated(BlockPos pos) {
        if (!ignorePlated.get() || mc.world == null) return false;

        int r = plateRange.get();
        for (BlockPos p : BlockPos.iterate(pos.add(-r, -1, -r), pos.add(r, 1, r))) {
            if (mc.world.getBlockState(p).getBlock() instanceof AbstractPressurePlateBlock) return true;
        }
        return false;
    }

    /**
     * Shuts anything we opened, once you are properly past it.
     *
     * The close used to work off the block directly behind the way you were moving, which only
     * lands on the door if you walked straight through it. An open door folds into one half of
     * its own block, so going through on the far side from the hinge means curving around the
     * panel, and by the time you are out the way you are moving no longer points back at the
     * door. That is the whole bug: on the hinge side you walk straight and it closed, on the
     * other side you did not and it did not.
     *
     * So the direction is not used at all here. Every door we opened is already written down,
     * so each one is simply checked against where you now are, and shut once you are clear of
     * it however you got there.
     */
    private void closeBehind() {
        if (!autoClose.get() || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (openedByUs.isEmpty() || mc.currentScreen != null || mc.player.isSneaking()) return;

        double range = closeRange.get();
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        for (long key : new ArrayList<>(openedByUs.keySet())) {
            BlockPos pos = BlockPos.fromLong(key);

            // A door on another floor is not one you just walked through.
            if (Math.abs(py - pos.getY()) > 3.0) continue;

            double dx = px - (pos.getX() + 0.5);
            double dz = pz - (pos.getZ() + 0.5);
            if (dx * dx + dz * dz < range * range) continue;

            // Still on its way to you: opened ahead of a fast approach and not reached yet.
            if (headingFor(dx, dz)) continue;

            BlockState state = mc.world.getBlockState(pos);
            Block block = state.getBlock();

            if (!clearOf(pos) || plated(pos)) continue;

            if (block instanceof DoorBlock) {
                if (!state.get(DoorBlock.OPEN)) {
                    openedByUs.remove(key);
                    continue;
                }

                if (state.isOf(Blocks.IRON_DOOR)) {
                    if (ironDoors.get() && useSwitch(pos, false)) mark(pos, state, false);
                    continue;
                }

                if (toggle(pos, true)) mark(pos, state, false);
                continue;
            }

            if (block instanceof FenceGateBlock) {
                if (!state.get(FenceGateBlock.OPEN)) {
                    openedByUs.remove(key);
                    continue;
                }
                if (fenceGates.get() && toggle(pos, true)) mark(pos, state, false);
                continue;
            }

            if (block instanceof TrapdoorBlock) {
                if (!state.get(TrapdoorBlock.OPEN)) {
                    openedByUs.remove(key);
                    continue;
                }
                if (trapdoors.get() && !state.isOf(Blocks.IRON_TRAPDOOR) && toggle(pos, true)) {
                    mark(pos, state, false);
                }
                continue;
            }

            // Whatever we opened is not there any more.
            openedByUs.remove(key);
        }
    }

    private void partner(BlockPos door, Direction dir, boolean open) {
        for (Direction side : new Direction[]{dir.rotateYClockwise(), dir.rotateYCounterclockwise()}) {
            BlockPos p = door.offset(side);
            BlockState state = mc.world.getBlockState(p);
            if (!(state.getBlock() instanceof DoorBlock) || state.isOf(Blocks.IRON_DOOR)) continue;
            if (plated(p)) return;

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

    /**
     * Whether you are moving toward a door rather than away from it or past it.
     *
     * Takes the offset from the door to you. Only counts when you are actually moving and
     * pointed within sixty degrees of it, so stopping beside a door or walking off at an angle
     * after going through still lets it close as before.
     */
    private boolean headingFor(double dx, double dz) {
        Vec3d v = mc.player.getVelocity();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (speed < 0.05 || dist < 1.0E-4) return false;

        // The door is at minus the offset from it to you.
        double cos = (-dx * v.x + -dz * v.z) / (speed * dist);
        return cos > 0.5;
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
