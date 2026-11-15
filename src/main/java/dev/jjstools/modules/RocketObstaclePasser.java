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
 * off, and keeps going. Two things happen every tick.
 *
 * A route is planned through the blocks ahead: forward one block at a time, moving one lane
 * sideways or one level up or down only over several blocks, the way an elytra turns at speed,
 * through blocks that are open the whole way. It keeps to the middle of the highway and off the
 * floor, sticks with the route it had unless something is clearly better, and searches a wider
 * box when the usual one has no way through.
 *
 * Then the look is chosen by trying it out: a spread of looks either side of straight at the
 * route is each flown forward half a second with the game's own elytra and rocket sums and its
 * own order of moving, checking the 0.6 hitbox against the blocks, and the one that stays clear
 * and closest to the route is used. Rockets are fired back to back, as a rocket pushing you is
 * what lets an elytra steer hard, so you go at full speed the whole way and never land.
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
        .description("Fewest ticks between rockets. A new one is fired as soon as the last one stops pushing, so you can steer the whole time; this only stops two going off at once.")
        .defaultValue(6).min(2).sliderRange(2, 40)
        .build());

    private final Setting<Boolean> autoEquip = sgGeneral.add(new BoolSetting.Builder()
        .name("equip-elytra")
        .description("Put an elytra on from your inventory if you are not wearing one.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> disableOnLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-landing")
        .description("Turn off if you end up back on the ground. Off: take off again and carry on.")
        .defaultValue(false)
        .build());

    private final Setting<Double> turnSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("Most the view may turn in a tick, in degrees. Lower is smoother, higher reacts faster.")
        .defaultValue(45).min(2).sliderRange(5, 90)
        .build());

    private final Setting<Integer> lookahead = sgPath.add(new IntSetting.Builder()
        .name("lookahead")
        .description("How many blocks ahead to plan the route. Further sees dead ends sooner but costs more each tick.")
        .defaultValue(48).min(12).sliderRange(16, 96)
        .build());

    private final Setting<Integer> width = sgPath.add(new IntSetting.Builder()
        .name("width")
        .description("How far either side of the line you started on the route may go, in blocks. Keep it to the highway's width so it never leaves it; a wider search is only tried when no way through is found.")
        .defaultValue(3).min(1).sliderRange(1, 16)
        .build());

    private final Setting<Integer> sidestep = sgPath.add(new IntSetting.Builder()
        .name("sidestep-length")
        .description("Blocks forward it takes to move one block sideways or up or down, at 30 blocks a second; scaled to the speed you are going. Lower weaves tighter but may clip edges at speed.")
        .defaultValue(4).min(1).sliderRange(1, 10)
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
    /** The height to fly at: a block above where you stood, or where you were if already flying. */
    private int flyY;

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
        flyY = mc.player.isOnGround() ? refY + 1 : refY;
        flewYet = mc.player.isGliding();
        takeoffTicks = 0;
        ticksSinceRocket = 1000;
        equipCooldown = 0;
        lastPlan = null;
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
        Vec3d vel = mc.player.getVelocity();
        boolean boosted = boosting();
        double speed = vel.length() * 20;
        boolean willFire = speed < maxSpeed.get() + 3 && !boosted && ticksSinceRocket >= rocketBoostTicks.get();
        steer(plan, boosted || willFire);

        // A rocket pushing you is what makes the elytra steer: it pulls your speed halfway to
        // where you look every tick. Without one you coast and barely turn. So a new one goes off
        // the moment the last stops.
        if (willFire && useFirework()) ticksSinceRocket = 0;
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

    /**
     * A route: where the middle of you should be at each block forward, from the one in front of
     * you, as a smooth line through the gaps, and how many points there are.
     */
    public record Plan(double[][] points, int length, java.util.Map<Integer, double[]> lanes) {}

    /** Blocks past the end of a turn the lane it left must stay open, for the trail through it. */
    private static final int SETTLE = 2;

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
     * The route at the turn length this speed allows; if that cannot get through the whole
     * lookahead, tighter turns, down to one block, and the first that gets through is flown.
     * An elytra cannot brake, so tight spots are taken at speed with sharper steering.
     */
    /** The route flown last tick, kept so the next one sticks to it unless something is better. */
    private Plan lastPlan;

    private Plan plan() {
        int natural = sidestepSteps();
        Plan best = null;
        for (int steps = natural; steps >= 1; steps--) {
            Plan p = plan(steps);
            if (p != null && (best == null || p.length > best.length)) best = p;
            if (p != null && p.length >= lookahead.get()) break;
        }
        lastPlan = best;
        return best;
    }

    /**
     * The route from the cell the middle of you is in; and when that finds no way through, from
     * each other cell your hitbox reaches into too, since straddling two lanes you can go on from
     * either.
     */
    private Plan plan(int steps) {
        Vec3d pos = dev.jjstools.compat.Compat.pos(mc.player);
        int topY = mc.world.getRegistryKey() == net.minecraft.world.World.NETHER ? 126 : mc.world.getTopYInclusive();
        BlockPos.Mutable probe = new BlockPos.Mutable();
        Open open = (x, y, z) -> free(probe.set(x, y, z));
        java.util.Map<Integer, double[]> prefer = lastPlan == null ? null : lastPlan.lanes();
        double cy = pos.y + 0.3;
        int len = lookahead.get();
        Plan best = planWidening(open, MathHelper.floor(pos.x), MathHelper.floor(cy), MathHelper.floor(pos.z), refX, refY, refZ, flyY,
            fx, fz, len, width.get(), heightBelow.get(), heightAbove.get(), topY, steps, prefer);
        if (best != null && best.length >= len) return best;
        java.util.Set<Long> tried = new java.util.HashSet<>();
        for (double ox : new double[] { -0.29, 0.29 })
            for (double oy : new double[] { -0.29, 0.29 })
                for (double oz : new double[] { -0.29, 0.29 }) {
                    int x = MathHelper.floor(pos.x + ox), y = MathHelper.floor(cy + oy), z = MathHelper.floor(pos.z + oz);
                    if (!tried.add(BlockPos.asLong(x, y, z))) continue;
                    if (x == MathHelper.floor(pos.x) && y == MathHelper.floor(cy) && z == MathHelper.floor(pos.z)) continue;
                    Plan p = planWidening(open, x, y, z, refX, refY, refZ, flyY, fx, fz, len, width.get(), heightBelow.get(), heightAbove.get(), topY, steps, prefer);
                    if (p != null && (best == null || p.length > best.length)) best = p;
                }
        return best;
    }

    /**
     * How many blocks forward a one-block step sideways or up or down takes at the speed you are
     * going: an elytra cannot slide sideways, it turns, and the faster it goes the further it
     * travels while it does.
     */
    private int sidestepSteps() {
        double speed = mc.player.getVelocity().length() * 20;
        return clamp((int) Math.round(sidestep.get() * speed / 30.0), 1, sidestep.get() + 2);
    }

    /**
     * The usual search first; if it finds no way through for the whole lookahead, a wider and
     * taller one, and the route that gets further wins. The highway is always meant to have a way
     * through, so this is for the gap that is just outside the usual box.
     */
    public static Plan planWidening(Open open, int bx, int by, int bz, int refX, int refY, int refZ, int flyY,
                                    int fx, int fz, int len, int w, int below, int above, int topY, int turnSteps) {
        return planWidening(open, bx, by, bz, refX, refY, refZ, flyY, fx, fz, len, w, below, above, topY, turnSteps, null);
    }

    public static Plan planWidening(Open open, int bx, int by, int bz, int refX, int refY, int refZ, int flyY,
                                    int fx, int fz, int len, int w, int below, int above, int topY, int turnSteps,
                                    java.util.Map<Integer, double[]> prefer) {
        Plan plan = plan(open, bx, by, bz, refX, refY, refZ, flyY, fx, fz, len, w, below, above, topY, turnSteps, prefer);
        if (plan != null && plan.length >= len) return plan;
        Plan wide = plan(open, bx, by, bz, refX, refY, refZ, flyY, fx, fz, len, w + 3, below + 2, above + 2, topY, turnSteps, prefer);
        if (wide != null && (plan == null || wide.length > plan.length)) return wide;
        return plan;
    }

    /**
     * The planner itself, on its own so it can be tested without a world. See plan().
     *
     * A grid of forward steps, sideways lanes and heights. From each cell the route either goes
     * one block straight on, or moves one lane sideways and/or one level up or down over
     * turnSteps blocks, the way an elytra actually turns: for all of those blocks you straddle
     * both lanes, so all of those cells must be open. Every cell reached keeps the cheapest way to
     * it, where being away from the middle or the flying height, flying just over the floor, and
     * changing course all cost something.
     */
    public static Plan plan(Open openAt, int bx, int by, int bz, int refX, int refY, int refZ, int flyY, int fx, int fz,
                            int len, int w, int below, int above, int topY, int turnSteps,
                            java.util.Map<Integer, double[]> prefer) {
        int px = -fz, pz = fx;
        int lanes = w * 2 + 1;
        int levels = below + above + 1;
        int steps = Math.max(1, turnSteps);

        // Where you are now, across the highway, measured from the line you started on.
        int across = Math.round((float) ((bx - refX) * px + (bz - refZ) * pz) / (px * px + pz * pz));
        // Back onto the line: the cell on your starting line level with you.
        int lineX = bx - across * px, lineZ = bz - across * pz;
        int startLane = clamp(across, -w, w) + w;
        int startLevel = clamp(by - refY, -below, above) + below;

        // Which cells are open, each looked up once.
        boolean[][][] open = new boolean[len + 1][lanes][levels];
        boolean[][][] onFloor = new boolean[len + 1][lanes][levels];
        for (int k = 0; k <= len; k++) {
            for (int l = 0; l < lanes; l++) {
                int lane = l - w;
                int x = lineX + k * fx + lane * px, z = lineZ + k * fz + lane * pz;
                for (int v = 0; v < levels; v++) {
                    int y = refY - below + v;
                    if (y > topY) continue;
                    open[k][l][v] = openAt.test(x, y, z);
                }
                for (int v = 0; v < levels; v++) {
                    if (!open[k][l][v]) continue;
                    boolean clearBelow = v > 0 ? open[k][l][v - 1] : openAt.test(x, refY - below + v - 1, z);
                    onFloor[k][l][v] = !clearBelow;
                }
            }
        }
        open[0][startLane][startLevel] = true;

        // What being in a cell costs: away from the middle, away from the flying height, and on
        // the floor, where one dip ends the glide.
        // And away from the route flown last tick, so the route does not flip between two near
        // equal ways round something from one tick to the next and swing you across between them.
        float[][][] stay = new float[len + 1][lanes][levels];
        for (int k = 0; k <= len; k++) {
            double[] was = prefer == null ? null : prefer.get((lineX + k * fx) * fx + (lineZ + k * fz) * fz);
            for (int l = 0; l < lanes; l++)
                for (int v = 0; v < levels; v++) {
                    float c = Math.abs(l - w) * 0.6f + Math.abs(refY - below + v - flyY) * 0.4f + (onFloor[k][l][v] ? 4f : 0f);
                    if (was != null) c += (float) (Math.abs(l - w - was[0]) + Math.abs(refY - below + v - was[1])) * 0.8f;
                    stay[k][l][v] = c;
                }
        }

        float[][][] cost = new float[len + 1][lanes][levels];
        byte[][][] move = new byte[len + 1][lanes][levels];
        for (float[][] a : cost) for (float[] b : a) java.util.Arrays.fill(b, Float.MAX_VALUE);
        cost[0][startLane][startLevel] = 0;

        for (int k = 0; k < len; k++) {
            for (int l = 0; l < lanes; l++) {
                for (int v = 0; v < levels; v++) {
                    float c = cost[k][l][v];
                    if (c == Float.MAX_VALUE) continue;

                    // Straight on.
                    if (open[k + 1][l][v]) {
                        float to = c + stay[k + 1][l][v];
                        if (to < cost[k + 1][l][v]) {
                            cost[k + 1][l][v] = to;
                            move[k + 1][l][v] = 4;
                        }
                    }

                    // A turn into the next lane and/or level, over the next few blocks.
                    if (k + steps > len) continue;
                    for (int dl = -1; dl <= 1; dl++) {
                        for (int dv = -1; dv <= 1; dv++) {
                            if (dl == 0 && dv == 0) continue;
                            int nl = l + dl, nv = v + dv;
                            if (nl < 0 || nl >= lanes || nv < 0 || nv >= levels) continue;
                            boolean ok = true;
                            float along = 0;
                            // From the cell it starts level with to the one it ends level with: the
                            // 0.6 wide hitbox reaches into the new lane while still alongside the
                            // starting cell.
                            for (int j = 0; j <= steps && ok; j++) {
                                int kk = k + j;
                                ok = (j == 0 || open[kk][l][v]) && open[kk][nl][nv]
                                    && (dl == 0 || dv == 0 || (open[kk][nl][v] && open[kk][l][nv]));
                                if (j > 0) along += (stay[kk][l][v] + stay[kk][nl][nv]) * 0.5f;
                            }
                            // You trail the route a little through a turn, so the lane you are
                            // leaving must stay open a couple of blocks past the end of it too.
                            // This also makes turns finish well before the block that needs them.
                            for (int j = steps + 1; j <= steps + SETTLE && ok; j++) {
                                if (k + j > len) break;
                                ok = open[k + j][l][v] && (dl == 0 || dv == 0 || (open[k + j][nl][v] && open[k + j][l][nv]));
                            }
                            if (!ok) continue;
                            float to = c + along + 1.5f * (Math.abs(dl) + Math.abs(dv));
                            int kk = k + steps;
                            if (to < cost[kk][nl][nv]) {
                                cost[kk][nl][nv] = to;
                                move[kk][nl][nv] = (byte) ((dl + 1) * 3 + (dv + 1));
                            }
                        }
                    }
                }
            }
        }

        int reached = 0;
        for (int k = len; k >= 1 && reached == 0; k--) {
            for (int l = 0; l < lanes && reached == 0; l++)
                for (int v = 0; v < levels; v++)
                    if (cost[k][l][v] != Float.MAX_VALUE) {
                        reached = k;
                        break;
                    }
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
        double[] laneAt = new double[reached + 1], levelAt = new double[reached + 1];
        int k = reached, l = bl, v = bv;
        while (k > 0) {
            int m = move[k][l][v];
            int dl = m / 3 - 1, dv = m % 3 - 1;
            if (dl == 0 && dv == 0) {
                laneAt[k] = l;
                levelAt[k] = v;
                k--;
            } else {
                // Spread evenly over the turn, so the route is a smooth slant, not a step.
                for (int j = steps; j >= 1; j--) {
                    double f = (double) j / steps;
                    laneAt[k - steps + j] = l - dl + dl * f;
                    levelAt[k - steps + j] = v - dv + dv * f;
                }
                k -= steps;
                l -= dl;
                v -= dv;
            }
        }

        double[][] points = new double[reached][];
        java.util.Map<Integer, double[]> laneMap = new java.util.HashMap<>();
        for (int i = 1; i <= reached; i++) {
            double lane = laneAt[i] - w;
            points[i - 1] = new double[] {
                lineX + i * fx + lane * px + 0.5,
                refY - below + levelAt[i] + 0.5,
                lineZ + i * fz + lane * pz + 0.5 };
            laneMap.put((lineX + i * fx) * fx + (lineZ + i * fz) * fz, new double[] { lane, refY - below + levelAt[i] });
        }
        return new Plan(points, reached, laneMap);
    }

    // Steering

    /** The point on the route at a distance along the highway, between the route's points. */
    private Vec3d routeAt(Plan plan, double along) {
        double[] prev = null;
        for (int i = 0; i < plan.length; i++) {
            double[] p = plan.points[i];
            double f = p[0] * fx + p[2] * fz;
            if (f >= along) {
                if (prev == null) {
                    // Before the first point: its line and height, at that distance along.
                    double back = (f - along) / (fx * fx + fz * fz);
                    return new Vec3d(p[0] - fx * back, p[1], p[2] - fz * back);
                }
                double fp = prev[0] * fx + prev[2] * fz;
                double u = (along - fp) / (f - fp);
                return new Vec3d(prev[0] + (p[0] - prev[0]) * u, prev[1] + (p[1] - prev[1]) * u, prev[2] + (p[2] - prev[2]) * u);
            }
            prev = p;
        }
        double[] last = plan.points[plan.length - 1];
        return new Vec3d(last[0], last[1], last[2]);
    }

    // Steering

    private static final int HORIZON = 10;
    private static final int HOLD = 3;
    private static final float[] OFFSETS = { -20f, -10f, -5f, 0f, 5f, 10f, 20f };

    /**
     * Picks where to look by trying it out.
     *
     * Each of a spread of looks, a little left, right, up and down of straight at the route, is
     * held for a few ticks and then the route is followed, and the next half second of flight is
     * worked out with the game's own elytra and rocket sums: where you would be each tick, and
     * whether your hitbox would touch a block on the way. The look that stays clear longest, and
     * then keeps closest to the route, is the one used. Planning moves through gaps and this
     * makes sure you actually fit through them at the speed you are going, with the momentum you
     * already have.
     */
    private void steer(Plan plan, boolean boosted) {
        Vec3d pos = dev.jjstools.compat.Compat.pos(mc.player);
        Vec3d vel = mc.player.getVelocity();
        float curYaw = mc.player.getYaw(), curPitch = mc.player.getPitch();
        float[] base = aim(plan, pos, vel);
        float max = turnSpeed.get().floatValue();
        java.util.Map<Long, Boolean> blocks = new java.util.HashMap<>();
        BlockPos.Mutable probe = new BlockPos.Mutable();
        Open open = (x, y, z) -> blocks.computeIfAbsent(BlockPos.asLong(x, y, z), k -> free(probe.set(x, y, z)));

        double bestScore = -Double.MAX_VALUE;
        float bestYaw = curYaw, bestPitch = curPitch;
        for (float dy : OFFSETS) {
            for (float dp : OFFSETS) {
                float yaw = curYaw + MathHelper.clamp(MathHelper.wrapDegrees(base[0] + dy - curYaw), -max, max);
                float pitch = MathHelper.clamp(curPitch + MathHelper.clamp(base[1] + dp - curPitch, -max, max), -90f, 90f);
                double score = simulate(plan, open, pos, vel, yaw, pitch, boosted);
                // A small nudge toward looking straight at the route, so ties go that way.
                score -= (Math.abs(dy) + Math.abs(dp)) * 0.002;
                if (score > bestScore) {
                    bestScore = score;
                    bestYaw = yaw;
                    bestPitch = pitch;
                }
            }
        }
        mc.player.setYaw(bestYaw);
        mc.player.setPitch(bestPitch);
    }

    /** Where to look to head for the route a couple of ticks ahead of a position, as yaw and pitch. */
    private float[] aim(Plan plan, Vec3d feet, Vec3d vel) {
        Vec3d centre = feet.add(0, 0.3, 0);
        Vec3d target;
        if (plan != null) {
            double along = centre.x * fx + centre.z * fz + Math.max(1.0, (vel.x * fx + vel.z * fz)) * 2;
            target = routeAt(plan, along);
        } else {
            target = new Vec3d(centre.x + fx * 3, flyY + 0.5, centre.z + fz * 3);
        }
        Vec3d d = target.subtract(centre);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        return new float[] { (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f, (float) -Math.toDegrees(Math.atan2(d.y, flat)) };
    }

    /**
     * Flies a look forward through the next half second and scores it: a big loss for touching a
     * block, the sooner the bigger, and a small one for every tick spent away from the route.
     */
    private double simulate(Plan plan, Open open, Vec3d pos, Vec3d vel, float yaw, float pitch, boolean boosted) {
        double score = 0;
        float y = yaw, p = pitch;
        for (int t = 0; t < HORIZON; t++) {
            if (t >= HOLD) {
                float[] a = aim(plan, pos, vel);
                y = a[0];
                p = a[1];
            }
            Vec3d look = lookOf(y, p);
            vel = glide(vel, p, look);
            Vec3d next = move(open, pos, vel);
            if (next == null) return score - (HORIZON - t) * 1000.0;
            pos = next;
            // Your rocket pushes after you move, so its pull shows in the next tick's move.
            if (boosted) vel = rocket(vel, look);
            if (plan != null) {
                Vec3d centre = pos.add(0, 0.3, 0);
                Vec3d on = routeAt(plan, centre.x * fx + centre.z * fz);
                score -= on.squaredDistanceTo(centre);
            }
        }
        return score;
    }

    /**
     * One tick's move, the way the game does it: up or down first, then along whichever of the
     * two flat directions it moves furthest in, then the other, each swept in small steps. Null if
     * the hitbox touches a block on the way. Done in that order because the game does it in that
     * order: moving forward fast while still a hair across into the next lane catches a block
     * there that a straight line would miss.
     */
    private static Vec3d move(Open open, Vec3d pos, Vec3d vel) {
        pos = sweep(open, pos, 0, vel.y, 0);
        if (pos == null) return null;
        if (Math.abs(vel.z) > Math.abs(vel.x)) {
            pos = sweep(open, pos, 0, 0, vel.z);
            if (pos == null) return null;
            return sweep(open, pos, vel.x, 0, 0);
        }
        pos = sweep(open, pos, vel.x, 0, 0);
        if (pos == null) return null;
        return sweep(open, pos, 0, 0, vel.z);
    }

    private static Vec3d sweep(Open open, Vec3d pos, double dx, double dy, double dz) {
        double dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        int n = Math.max(1, (int) Math.ceil(dist / 0.25));
        for (int i = 1; i <= n; i++) {
            Vec3d at = pos.add(dx * i / n, dy * i / n, dz * i / n);
            if (hits(open, at)) return null;
        }
        return pos.add(dx, dy, dz);
    }

    /** Whether a gliding hitbox, 0.6 each way, at these feet touches a block. */
    private static boolean hits(Open open, Vec3d feet) {
        int x0 = MathHelper.floor(feet.x - 0.3), x1 = MathHelper.floor(feet.x + 0.3 - 1e-7);
        int y0 = MathHelper.floor(feet.y), y1 = MathHelper.floor(feet.y + 0.6 - 1e-7);
        int z0 = MathHelper.floor(feet.z - 0.3), z1 = MathHelper.floor(feet.z + 0.3 - 1e-7);
        for (int x = x0; x <= x1; x++)
            for (int yy = y0; yy <= y1; yy++)
                for (int z = z0; z <= z1; z++)
                    if (!open.test(x, yy, z)) return true;
        return false;
    }

    /** The way a yaw and pitch look, the same as the game works it out. */
    private static Vec3d lookOf(float yaw, float pitch) {
        float f = pitch * 0.017453292F, g = -yaw * 0.017453292F;
        float h = MathHelper.cos(g), i = MathHelper.sin(g), j = MathHelper.cos(f), k = MathHelper.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    /** One tick of elytra flight, the game's own sum. */
    private static Vec3d glide(Vec3d v, float pitch, Vec3d look) {
        double pr = pitch * 0.017453292F;
        double hl = Math.sqrt(look.x * look.x + look.z * look.z);
        double hv = v.horizontalLength();
        double cs = Math.cos(pr) * Math.cos(pr);
        v = v.add(0, 0.08 * (-1.0 + cs * 0.75), 0);
        if (v.y < 0 && hl > 0) {
            double m = v.y * -0.1 * cs;
            v = v.add(look.x * m / hl, m, look.z * m / hl);
        }
        if (pr < 0 && hl > 0) {
            double m = hv * -MathHelper.sin((float) pr) * 0.04;
            v = v.add(-look.x * m / hl, m * 3.2, -look.z * m / hl);
        }
        if (hl > 0) v = v.add((look.x / hl * hv - v.x) * 0.1, 0, (look.z / hl * hv - v.z) * 0.1);
        return v.multiply(0.99, 0.98, 0.99);
    }

    /** One tick of a rocket's push, the game's own sum. */
    private static Vec3d rocket(Vec3d v, Vec3d look) {
        return v.add(look.x * 0.1 + (look.x * 1.5 - v.x) * 0.5, look.y * 0.1 + (look.y * 1.5 - v.y) * 0.5, look.z * 0.1 + (look.z * 1.5 - v.z) * 0.5);
    }

    // Rockets

    /** Whether one of your rockets is still pushing you: a rocket that is riding along with you. */
    private boolean boosting() {
        Vec3d pos = dev.jjstools.compat.Compat.pos(mc.player);
        for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
            if (e instanceof net.minecraft.entity.projectile.FireworkRocketEntity
                && dev.jjstools.compat.Compat.pos(e).squaredDistanceTo(pos) < 4) return true;
        }
        return false;
    }

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
