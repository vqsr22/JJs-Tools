package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lights an area with as few torches as it can get away with.
 *
 * Mobs spawn where the block light is zero, so the job is not "light everything up", it is "leave
 * no block at zero". A torch gives light 14 and that falls off by one per block travelled, so one
 * torch covers a surprising distance and the usual habit of placing them every five blocks wastes
 * most of them.
 *
 * Each pass it finds the dark spots you could stand on, puts a torch on the one nearest you, then
 * writes off everything that torch now covers and looks again. That is a greedy choice rather than
 * a perfect one, but the difference is a torch or two over a large room and it costs nothing to
 * compute.
 */
public class AutoTorch extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("How far out to look for dark ground. Placing is still limited by your reach.")
        .defaultValue(12).min(2).sliderRange(2, 32)
        .build()
    );

    private final Setting<Integer> maxLight = sgGeneral.add(new IntSetting.Builder()
        .name("dark-at-or-below")
        .description("Block light at or under this counts as dark. 0 is the real spawn threshold; raise it to light an area more heavily than it strictly needs.")
        .defaultValue(0).min(0).max(14).sliderRange(0, 14)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between torches. Placing as fast as possible is what gets you kicked.")
        .defaultValue(4).min(0).sliderRange(0, 20)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("How far you will place from. Vanilla servers refuse anything past about 4.5, so the extra range is only useful where the server allows it.")
        .defaultValue(4.0).min(1).max(8).sliderRange(1, 8)
        .build()
    );

    private final Setting<Boolean> soulTorches = sgGeneral.add(new BoolSetting.Builder()
        .name("use-soul-torches")
        .description("Use soul torches if you have no ordinary ones. They give light 10 rather than 14, so they are spaced closer.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Face each spot through Meteor's silent rotation system. Leave on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseWhenEmpty = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-out")
        .description("Switch off when you run out of torches.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPlan = sgRender.add(new BoolSetting.Builder()
        .name("show-plan")
        .description("Draw a box on every spot a torch is going to go.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColour = sgRender.add(new ColorSetting.Builder()
        .name("side-color").defaultValue(new SettingColor(255, 190, 60, 50)).build());

    private final Setting<SettingColor> lineColour = sgRender.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(255, 190, 60, 220)).build());

    /** Where torches are going, worked out once a tick and reused by the renderer. */
    private final List<BlockPos> plan = new ArrayList<>();
    private int timer;

    public AutoTorch() {
        super(JJsTools.CATEGORY, "auto-torch", "Lights an area with as few torches as possible, rather than one every few blocks.");
    }

    @Override
    public void onActivate() {
        plan.clear();
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        FindItemResult torch = findTorch();
        if (!torch.found()) {
            if (pauseWhenEmpty.get()) {
                error("Out of torches.");
                toggle();
            }
            return;
        }

        buildPlan(torch);

        if (timer > 0) {
            timer--;
            return;
        }

        for (BlockPos pos : plan) {
            if (!inReach(pos)) continue;

            place(pos, torch);
            timer = delay.get();
            return;
        }
    }

    /**
     * Which torch we are using.
     *
     * Recorded as a flag rather than read back off the result, because FindItemResult is a record
     * holding a slot and a count and has no way to hand you the stack.
     */
    private boolean usingSoulTorch;

    private FindItemResult findTorch() {
        FindItemResult torch = InvUtils.find(Items.TORCH);
        if (torch.found()) {
            usingSoulTorch = false;
            return torch;
        }

        if (!soulTorches.get()) return torch;

        FindItemResult soul = InvUtils.find(Items.SOUL_TORCH);
        usingSoulTorch = soul.found();
        return soul;
    }

    /**
     * Works out where the torches go.
     *
     * Dark spots are collected first, then the nearest one is chosen, everything it would light is
     * struck off, and the search repeats. Choosing the nearest rather than the one covering the
     * most is deliberate: you light your way forward as you walk instead of the module reaching
     * for a corner behind you.
     */
    private void buildPlan(FindItemResult torch) {
        plan.clear();

        int light = usingSoulTorch ? 10 : 14;
        int reachOut = light - 1 - maxLight.get();
        if (reachOut < 1) reachOut = 1;

        List<BlockPos> dark = findDark();
        Set<Long> covered = new HashSet<>();

        for (BlockPos spot : dark) {
            if (covered.contains(spot.asLong())) continue;
            if (!canPlace(spot)) continue;

            plan.add(spot);

            // Light spreads one block at a time through open space, so a torch reaches this far
            // measured in steps rather than straight-line distance.
            for (BlockPos other : dark) {
                if (spot.getManhattanDistance(other) <= reachOut) covered.add(other.asLong());
            }

            if (plan.size() >= 32) break;
        }
    }

    /** Ground you could stand on that is dark enough for something to spawn there. */
    private List<BlockPos> findDark() {
        List<BlockPos> dark = new ArrayList<>();
        int r = range.get();
        BlockPos origin = mc.player.getBlockPos();

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -r / 2; y <= r / 2; y++) {
                    BlockPos pos = origin.add(x, y, z);

                    if (!mc.world.getBlockState(pos).isAir()) continue;
                    if (mc.world.getLightLevel(LightType.BLOCK, pos) > maxLight.get()) continue;

                    BlockState below = mc.world.getBlockState(pos.down());
                    if (below.isAir() || !below.isSolidBlock(mc.world, pos.down())) continue;

                    dark.add(pos);
                }
            }
        }

        dark.sort((a, b) -> Double.compare(
            mc.player.squaredDistanceTo(Vec3d.ofCenter(a)),
            mc.player.squaredDistanceTo(Vec3d.ofCenter(b))));

        return dark;
    }

    /** A torch needs air to sit in and a solid top underneath it. */
    private boolean canPlace(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isAir()) return false;

        BlockState below = mc.world.getBlockState(pos.down());
        return !below.isAir() && below.isSolidBlock(mc.world, pos.down());
    }

    private boolean inReach(BlockPos pos) {
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= reach.get() * reach.get();
    }

    private void place(BlockPos pos, FindItemResult torch) {
        // Clicked on the top of the block below, which is where a standing torch attaches.
        BlockPos against = pos.down();
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(against, 1.0), Direction.UP, against, false);

        Runnable action = () -> {
            InvUtils.swap(torch.slot(), true);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(against), Rotations.getPitch(Vec3d.ofCenter(against)), 50, action);
        }
        else action.run();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!showPlan.get()) return;

        for (BlockPos pos : plan) {
            Box box = new Box(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);

            event.renderer.box(box, sideColour.get(), lineColour.get(), ShapeMode.Both, 0);
        }
    }

    @Override
    public String getInfoString() {
        return plan.isEmpty() ? null : String.valueOf(plan.size());
    }
}
