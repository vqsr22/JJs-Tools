package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Flies a clogged highway on an elytra at rocket speed, steering through the gaps.
 *
 * Turned on facing along the highway: it puts an elytra on if you are not wearing one, takes
 * off, and from then on looks for a way through what is ahead every tick. The way is found in a
 * box of blocks in front of you, as long as the lookahead and a few blocks either side, above and
 * below where you started: one step forward at a time, sliding at most one block sideways or up
 * or down per step, so the route is one the elytra can actually follow. Of the routes that get
 * furthest, the one that keeps nearest the middle and your starting height, and changes course
 * least, is flown.
 *
 * Rockets keep you at the top speed while the route ahead is clear for the lookahead. When it
 * runs out sooner, for a dead end or a part not loaded yet, no more rockets are fired, so you slow
 * down instead of flying into it.
 */
public class RocketObstaclePasser extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPath = settings.createGroup("Path");

    private final Setting<Double> maxSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-speed")
        .description("Speed to keep with rockets, in blocks a second. Rockets top out a little over 33.")
        .defaultValue(33).min(5).sliderRange(10, 40)
        .build());

    private final Setting<Integer> rocketBoostTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rocket-interval")
        .description("Ticks to wait after a rocket before another can be fired, roughly how long one keeps pushing. 20 for flight duration 1 rockets, 40 for 2, 60 for 3.")
        .defaultValue(20).min(4).sliderRange(5, 60)
        .build());

    private final Setting<Boolean> autoEquip = sgGeneral.add(new BoolSetting.Builder()
        .name("equip-elytra")
        .description("Put an elytra on from your inventory if you are not wearing one.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> disableOnLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-landing")
        .description("Turn off once you are back on the ground after flying.")
        .defaultValue(true)
        .build());

    private final Setting<Double> turnSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("Most the view may turn in a tick, in degrees. Lower is smoother, higher reacts faster.")
        .defaultValue(25).min(2).sliderRange(5, 90)
        .build());

    private final Setting<Integer> lookahead = sgPath.add(new IntSetting.Builder()
        .name("lookahead")
        .description("How many blocks ahead to plan the route. Further sees dead ends sooner but costs more each tick.")
        .defaultValue(48).min(12).sliderRange(16, 96)
        .build());

    private final Setting<Integer> width = sgPath.add(new IntSetting.Builder()
        .name("width")
        .description("How far either side of the line you started on the route may go, in blocks.")
        .defaultValue(5).min(1).sliderRange(1, 16)
        .build());

    private final Setting<Integer> heightBelow = sgPath.add(new IntSetting.Builder()
        .name("height-below")
        .description("How far below your starting height the route may dip.")
        .defaultValue(3).min(0).sliderRange(0, 10)
        .build());

    private final Setting<Integer> heightAbove = sgPath.add(new IntSetting.Builder()
        .name("height-above")
        .description("How far above your starting height the route may climb.")
        .defaultValue(4).min(0).sliderRange(0, 10)
        .build());

    // Where the highway runs: a step along it and a step across it, in whole blocks.
    private int fx, fz, px, pz;
    // A point on the line you started on, and the height you started at.
    private int refX, refZ, refY;

    private int ticksSinceRocket = 1000;
    private boolean flewYet;
    private int takeoffTicks;
    private int equipCooldown;

    public RocketObstaclePasser() {
        super(JJsTools.CATEGORY, "rocket-obstacle-passer", "Puts on an elytra and flies along a highway at rocket speed, steering through the gaps in what is clogging it. Turn it on facing the way the highway runs.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) {
            toggle();
            return;
        }
        // The highway runs whichever of the eight compass and diagonal directions you face most.
        int octant = Math.floorMod(Math.round(MathHelper.wrapDegrees(mc.player.getYaw()) / 45f), 8);
        int[][] dirs = { {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1} };
        fx = dirs[octant][0];
        fz = dirs[octant][1];
        px = -fz;
        pz = fx;
        BlockPos at = mc.player.getBlockPos();
        refX = at.getX();
        refZ = at.getZ();
        refY = at.getY();
        flewYet = mc.player.isGliding();
        takeoffTicks = 0;
        ticksSinceRocket = 1000;
        equipCooldown = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) return;
        ticksSinceRocket++;
        if (equipCooldown > 0) equipCooldown--;

        if (!hasGlider()) {
            if (!autoEquip.get()) {
                error("Not wearing an elytra.");
                toggle();
                return;
            }
            if (equipCooldown == 0) {
                int slot = findGlider();
                if (slot == -1) {
                    error("No usable elytra in your inventory.");
                    toggle();
                    return;
                }
                InvUtils.move().from(slot).toArmor(2);
                equipCooldown = 10;
            }
            return;
        }

        if (!mc.player.isGliding()) {
            if (flewYet && mc.player.isOnGround() && disableOnLanding.get()) {
                info("Landed.");
                toggle();
                return;
            }
            takeOff();
            return;
        }
        flewYet = true;

        Plan plan = plan();
        if (plan == null) return;

        // Aim at a point on the route further ahead the faster you are going, so the turn into a
        // gap starts in time.
        double speed = mc.player.getVelocity().length() * 20;
        int ahead = MathHelper.clamp((int) Math.round(3 + speed * 0.22), 3, plan.length);
        int[] cell = plan.cells[ahead - 1];
        Vec3d target = new Vec3d(cell[0] + 0.5, cell[1] + 0.3, cell[2] + 0.5);
        aimAt(target);

        // Rocket only while the way is clear for the whole lookahead.
        boolean clearAhead = plan.length >= lookahead.get();
        if (clearAhead && speed < maxSpeed.get() && ticksSinceRocket >= rocketBoostTicks.get()) {
            if (useFirework()) ticksSinceRocket = 0;
        }
    }

    // Takeoff

    private void takeOff() {
        if (mc.player.isTouchingWater() || mc.player.hasVehicle() || mc.player.hasStatusEffect(StatusEffects.LEVITATION)) return;
        if (mc.player.isOnGround()) {
            mc.player.jump();
            takeoffTicks = 0;
            return;
        }
        // Opened on the way down from the jump, the same as a double tap of jump.
        if (++takeoffTicks >= 2 && mc.player.checkGliding()) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
    }

    // Planning

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A route: the cells to fly through, from the one in front of you, and how many there are. */
    public record Plan(int[][] cells, int length) {}

    /** Whether the block at a position can be flown through. */
    public interface Open {
        boolean test(int x, int y, int z);
    }

    private boolean free(BlockPos.Mutable pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        if (state.isAir()) return true;
        return state.getCollisionShape(mc.world, pos).isEmpty();
    }

    /**
     * The route through the blocks ahead, cell by cell from the one in front of you, or null if
     * even the next step is blocked.
     *
     * A grid of forward steps, sideways offsets and heights; each step forward may move one cell
     * sideways and one up or down, through cells that are open, and not cut a corner through a
     * block. Every cell reached keeps the cheapest way to it, where moving away from the middle
     * or your starting height, and changing course, all cost a little.
     */
    private Plan plan() {
        Vec3d pos = dev.jjstools.compat.Compat.pos(mc.player);
        int topY = mc.world.getRegistryKey() == net.minecraft.world.World.NETHER ? 126 : mc.world.getTopYInclusive();
        BlockPos.Mutable probe = new BlockPos.Mutable();
        return plan((x, y, z) -> free(probe.set(x, y, z)),
            MathHelper.floor(pos.x), MathHelper.floor(pos.y + 0.3), MathHelper.floor(pos.z),
            refX, refY, refZ, fx, fz, lookahead.get(), width.get(), heightBelow.get(), heightAbove.get(), topY);
    }

    /** The planner itself, on its own so it can be tested without a world. See plan(). */
    public static Plan plan(Open openAt, int bx, int by, int bz, int refX, int refY, int refZ, int fx, int fz,
                            int len, int w, int below, int above, int topY) {
        int px = -fz, pz = fx;
        int lanes = w * 2 + 1;
        int levels = below + above + 1;

        // Where you are now, across the highway, measured from the line you started on.
        int across = Math.round((float) ((bx - refX) * px + (bz - refZ) * pz) / (px * px + pz * pz));
        // Back onto the line: the cell on your starting line level with you.
        int lineX = bx - across * px, lineZ = bz - across * pz;
        int startLane = clamp(across, -w, w) + w;
        int startLevel = clamp(by - refY, -below, above) + below;

        float[][][] cost = new float[len + 1][lanes][levels];
        byte[][][] from = new byte[len + 1][lanes][levels];
        boolean[][][] open = new boolean[len + 1][lanes][levels];
        for (float[][] a : cost) for (float[] b : a) java.util.Arrays.fill(b, Float.MAX_VALUE);
        cost[0][startLane][startLevel] = 0;

        // Which cells are open, each looked up once.
        for (int k = 0; k <= len; k++) {
            for (int l = 0; l < lanes; l++) {
                for (int v = 0; v < levels; v++) {
                    int y = refY - below + v;
                    if (y > topY) continue;
                    int lane = l - w;
                    open[k][l][v] = openAt.test(lineX + k * fx + lane * px, y, lineZ + k * fz + lane * pz);
                }
            }
        }
        open[0][startLane][startLevel] = true;

        int reached = 0;
        for (int k = 1; k <= len; k++) {
            boolean any = false;
            for (int l = 0; l < lanes; l++) {
                for (int v = 0; v < levels; v++) {
                    if (!open[k][l][v]) continue;
                    float best = Float.MAX_VALUE;
                    byte bestFrom = 0;
                    for (int dl = -1; dl <= 1; dl++) {
                        for (int dv = -1; dv <= 1; dv++) {
                            int pl = l - dl, pv = v - dv;
                            if (pl < 0 || pl >= lanes || pv < 0 || pv >= levels) continue;
                            float c = cost[k - 1][pl][pv];
                            if (c == Float.MAX_VALUE) continue;
                            // No corner cutting: either slide first then go forward, or go
                            // forward then slide, must be open.
                            if ((dl != 0 || dv != 0) && !open[k - 1][l][v] && !open[k][pl][pv]) continue;
                            float step = c + (dl != 0 ? 1.5f : 0) + (dv != 0 ? 1.5f : 0);
                            if (step < best) {
                                best = step;
                                bestFrom = (byte) ((dl + 1) * 3 + (dv + 1));
                            }
                        }
                    }
                    if (best == Float.MAX_VALUE) continue;
                    // Drift toward the middle of the highway and your starting height.
                    best += Math.abs(l - w) * 0.35f + Math.abs(v - below) * 0.35f;
                    cost[k][l][v] = best;
                    from[k][l][v] = bestFrom;
                    any = true;
                }
            }
            if (!any) break;
            reached = k;
        }
        if (reached == 0) return null;

        // The cheapest cell at the furthest step reached, then back along the way to it.
        int bl = -1, bv = -1;
        float bc = Float.MAX_VALUE;
        for (int l = 0; l < lanes; l++) {
            for (int v = 0; v < levels; v++) {
                if (cost[reached][l][v] < bc) {
                    bc = cost[reached][l][v];
                    bl = l;
                    bv = v;
                }
            }
        }
        int[][] cells = new int[reached][];
        int l = bl, v = bv;
        for (int k = reached; k >= 1; k--) {
            int lane = l - w;
            cells[k - 1] = new int[] { lineX + k * fx + lane * px, refY - below + v, lineZ + k * fz + lane * pz };
            int f = from[k][l][v];
            l -= f / 3 - 1;
            v -= f % 3 - 1;
        }
        return new Plan(cells, reached);
    }

    // Steering

    private void aimAt(Vec3d target) {
        Vec3d pos = dev.jjstools.compat.Compat.pos(mc.player).add(0, 0.3, 0);
        double dx = target.x - pos.x, dy = target.y - pos.y, dz = target.z - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));

        float max = turnSpeed.get().floatValue();
        float curYaw = mc.player.getYaw(), curPitch = mc.player.getPitch();
        float dYaw = MathHelper.clamp(MathHelper.wrapDegrees(yaw - curYaw), -max, max);
        float dPitch = MathHelper.clamp(pitch - curPitch, -max, max);
        mc.player.setYaw(curYaw + dYaw);
        mc.player.setPitch(MathHelper.clamp(curPitch + dPitch, -90f, 90f));
    }

    // Rockets

    /** Fires a rocket from the offhand or hotbar. False if there are none. */
    private boolean useFirework() {
        if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            use(Hand.OFF_HAND);
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) {
                InvUtils.swap(i, true);
                use(Hand.MAIN_HAND);
                InvUtils.swapBack();
                return true;
            }
        }
        if (ticksSinceRocket > 200) {
            warning("No rockets in your hotbar or offhand.");
            ticksSinceRocket = 0;
        }
        return false;
    }

    private void use(Hand hand) {
        mc.interactionManager.interactItem(mc.player, hand);
        mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
    }

    // Elytra

    private boolean hasGlider() {
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.contains(DataComponentTypes.GLIDER)) return false;
        return !chest.isDamageable() || chest.getDamage() < chest.getMaxDamage() - 1;
    }

    private int findGlider() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.contains(DataComponentTypes.GLIDER)) continue;
            if (stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1) continue;
            return i;
        }
        return -1;
    }
}
