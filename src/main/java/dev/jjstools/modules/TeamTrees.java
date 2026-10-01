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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Plants saplings, either on a fixed plot you replant over and over, or scattered across the land
 * around you.
 *
 * TREE FARM works a plot you have set up. You give it the coordinates of the DIRT you plant on,
 * not where the sapling ends up, and it replants that plot whenever the space above it is clear
 * again. Big trees need a 2x2 of dirt, so that mode takes two corners instead of one position.
 *
 * REFOREST ignores the coordinates entirely and plants across whatever ground is near you, keeping
 * the saplings spaced out so the trees have room to grow. Every spot it would use is drawn as a
 * box, so you can see where it is going before it gets there.
 */
public class TeamTrees extends Module {
    public enum Mode { TreeFarm, Reforest }

    public enum Ground {
        Dirt("Dirt only"),
        Grass("Grass only"),
        Both("Dirt and grass");

        private final String title;
        Ground(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    public enum Size {
        Single("1x1"),
        Quad("2x2");

        private final String title;
        Size(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    /** How the tree gets removed, which decides when it is safe to replant. */
    public enum Harvest {
        Farming("Farming"),
        ManualChop("Manual chop");

        private final String title;
        Harvest(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    /**
     * Common setups, so you do not have to remember which species need a 2x2.
     *
     * Dark oak and pale oak are 2x2 only: a single sapling of either will sit there forever, so
     * there is deliberately no 1x1 option for them. Spruce and jungle are the two that work both
     * ways, small from one sapling or giant from four.
     */
    public enum Preset {
        OakSingle("Oak (1x1)", Items.OAK_SAPLING, Size.Single),
        BirchSingle("Birch (1x1)", Items.BIRCH_SAPLING, Size.Single),
        SpruceSingle("Spruce (1x1)", Items.SPRUCE_SAPLING, Size.Single),
        JungleSingle("Jungle (1x1)", Items.JUNGLE_SAPLING, Size.Single),
        AcaciaSingle("Acacia (1x1)", Items.ACACIA_SAPLING, Size.Single),
        CherrySingle("Cherry (1x1)", Items.CHERRY_SAPLING, Size.Single),
        MangroveSingle("Mangrove (1x1)", Items.MANGROVE_PROPAGULE, Size.Single),

        SpruceQuad("Spruce (2x2)", Items.SPRUCE_SAPLING, Size.Quad),
        JungleQuad("Jungle (2x2)", Items.JUNGLE_SAPLING, Size.Quad),
        DarkOakQuad("Dark Oak (2x2)", Items.DARK_OAK_SAPLING, Size.Quad),
        PaleOakQuad("Pale Oak (2x2)", Items.PALE_OAK_SAPLING, Size.Quad);

        private final String title;
        public final Item sapling;
        public final Size size;

        Preset(String title, Item sapling, Size size) {
            this.title = title;
            this.sapling = sapling;
            this.size = size;
        }

        @Override public String toString() { return title; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFarm = settings.createGroup("Tree Farm");
    private final SettingGroup sgReforest = settings.createGroup("Reforest");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Tree Farm replants a plot you have set coordinates for. Reforest scatters saplings over the ground around you.")
        .defaultValue(Mode.TreeFarm)
        .build()
    );

    private final Setting<Ground> ground = sgGeneral.add(new EnumSetting.Builder<Ground>()
        .name("plant-on")
        .description("Which ground counts as plantable.")
        .defaultValue(Ground.Both)
        .build()
    );

    private final Setting<Preset> preset = sgGeneral.add(new EnumSetting.Builder<Preset>()
        .name("preset")
        .description("Which tree to plant. This sets both the sapling and whether the plot is 1x1 or 2x2.")
        .defaultValue(Preset.OakSingle)
        .onChanged(this::applyPreset)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Turn toward each block through Meteor's silent rotation system, so the server sees a sane look direction while your view stays where it is. Leave this on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between placements.")
        .defaultValue(4).min(0).sliderRange(0, 20)
        .build()
    );

    // Tree Farm

    private final Setting<BlockPos> dirtLocation = sgFarm.add(new BlockPosSetting.Builder()
        .name("dirt-location")
        .description("The DIRT block you plant on, not where the sapling ends up. Stand on the plot and read it off F3.")
        .defaultValue(BlockPos.ORIGIN)
        .visible(() -> mode.get() == Mode.TreeFarm && size() == Size.Single)
        .build()
    );

    private final Setting<BlockPos> corner1 = sgFarm.add(new BlockPosSetting.Builder()
        .name("dirt-corner-1")
        .description("One corner of the 2x2 of DIRT you plant on, not where the saplings end up.")
        .defaultValue(BlockPos.ORIGIN)
        .visible(() -> mode.get() == Mode.TreeFarm && size() == Size.Quad)
        .build()
    );

    private final Setting<BlockPos> corner2 = sgFarm.add(new BlockPosSetting.Builder()
        .name("dirt-corner-2")
        .description("The opposite corner of that 2x2, diagonal from the first.")
        .defaultValue(BlockPos.ORIGIN)
        .visible(() -> mode.get() == Mode.TreeFarm && size() == Size.Quad)
        .build()
    );

    private final Setting<Harvest> harvest = sgFarm.add(new EnumSetting.Builder<Harvest>()
        .name("harvest")
        .description("Farming replants the instant the space clears, for a farm that breaks the tree itself. Manual chop waits until you have finished and stepped back, so a sapling never appears in front of your axe.")
        .defaultValue(Harvest.Farming)
        .visible(() -> mode.get() == Mode.TreeFarm)
        .build()
    );

    private final Setting<Integer> clearHeight = sgFarm.add(new IntSetting.Builder()
        .name("clear-height")
        .description("How many blocks above the dirt must be empty before replanting. Stops it planting under a trunk you are still working on.")
        .defaultValue(4).min(1).sliderRange(1, 10)
        .visible(() -> mode.get() == Mode.TreeFarm && harvest.get() == Harvest.ManualChop)
        .build()
    );

    private final Setting<Integer> settle = sgFarm.add(new IntSetting.Builder()
        .name("settle-ticks")
        .description("Wait this long after the last block breaks before replanting, so falling leaves and drops are out of the way.")
        .defaultValue(10).min(0).sliderRange(0, 60)
        .visible(() -> mode.get() == Mode.TreeFarm && harvest.get() == Harvest.ManualChop)
        .build()
    );

    private final Setting<Double> farmRange = sgFarm.add(new DoubleSetting.Builder()
        .name("range")
        .description("You have to be within this far of the plot for it to be planted.")
        .defaultValue(4.0).min(1).sliderRange(1, 6)
        .visible(() -> mode.get() == Mode.TreeFarm)
        .build()
    );

    // Reforest

    private final Setting<Integer> reforestRange = sgReforest.add(new IntSetting.Builder()
        .name("range")
        .description("How far out to look for plantable ground.")
        .defaultValue(10).min(1).sliderRange(1, 16)
        .visible(() -> mode.get() == Mode.Reforest)
        .build()
    );

    private final Setting<Integer> spacing = sgReforest.add(new IntSetting.Builder()
        .name("spacing")
        .description("Blocks between saplings, so the trees have room to grow instead of choking each other.")
        .defaultValue(5).min(1).sliderRange(1, 12)
        .visible(() -> mode.get() == Mode.Reforest)
        .build()
    );

    // Render

    private final Setting<Boolean> showSpots = sgRender.add(new BoolSetting.Builder()
        .name("show-spots")
        .description("Draw a box on every spot a sapling would go.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColour = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(60, 220, 90, 50))
        .visible(showSpots::get)
        .build()
    );

    private final Setting<SettingColor> lineColour = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(60, 220, 90, 220))
        .visible(showSpots::get)
        .build()
    );

    /** Spots worked out this tick, reused by the renderer so both agree on what is plantable. */
    private final List<BlockPos> spots = new ArrayList<>();
    private int timer;

    /** Counts down once the plot looks clear, for the manual chop settle period. */
    private int settleTimer;
    private boolean wasClear;

    public TeamTrees() {
        super(JJsTools.CATEGORY, "team-trees", "Plants saplings on a fixed plot or scattered across the land around you.");
    }

    /** The preset is the single source of truth for both the sapling and the plot size. */
    private Item sapling() {
        return preset.get().sapling;
    }

    private Size size() {
        return preset.get().size;
    }

    private void applyPreset(Preset chosen) {
        // Switching tree can change 1x1 to 2x2, which changes which coordinate boxes are shown.
        spots.clear();
    }

    @Override
    public void onActivate() {
        spots.clear();
        timer = 0;
        settleTimer = 0;
        wasClear = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        spots.clear();
        if (mode.get() == Mode.TreeFarm) collectFarmSpots();
        else collectReforestSpots();

        if (mode.get() == Mode.TreeFarm && harvest.get() == Harvest.ManualChop && !readyAfterChop()) return;

        if (timer > 0) {
            timer--;
            return;
        }

        FindItemResult sapling = InvUtils.find(stack -> stack.getItem() == sapling());
        if (!sapling.found()) return;

        for (BlockPos dirt : spots) {
            if (!inReach(dirt)) continue;

            plant(dirt, sapling);
            timer = delay.get();
            return;
        }
    }

    /**
     * In manual chop, hold off until you have actually finished.
     *
     * Three things have to be true: nothing is being broken right now, the column above every spot
     * is clear to the configured height, and that has stayed true for the settle period. Without
     * this the sapling pops in while you are still swinging and you break it by accident.
     */
    private boolean readyAfterChop() {
        if (mc.interactionManager.isBreakingBlock()) {
            settleTimer = 0;
            wasClear = false;
            return false;
        }

        boolean clear = true;
        for (BlockPos dirt : spots) {
            for (int up = 1; up <= clearHeight.get(); up++) {
                if (!mc.world.getBlockState(dirt.up(up)).isAir()) { clear = false; break; }
            }
            if (!clear) break;
        }

        if (!clear) {
            settleTimer = 0;
            wasClear = false;
            return false;
        }

        if (!wasClear) {
            wasClear = true;
            settleTimer = settle.get();
        }

        if (settleTimer > 0) {
            settleTimer--;
            return false;
        }
        return true;
    }

    /**
     * The plot's dirt blocks, from the corners you set.
     *
     * A spot only counts while the space above it is clear, which is what makes this replant: once
     * the tree is chopped the air comes back and the block is offered again.
     */
    private void collectFarmSpots() {
        if (size() == Size.Single) {
            BlockPos only = dirtLocation.get();
            if (plantable(only)) spots.add(only);
            return;
        }

        BlockPos a = corner1.get();
        BlockPos b = corner2.get();
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos pos = new BlockPos(x, a.getY(), z);
                if (plantable(pos)) spots.add(pos);
            }
        }
    }

    /**
     * Ground near you, thinned out so saplings are not crammed together.
     *
     * Existing saplings count towards spacing as well as planned ones, so running this twice over
     * the same ground does not fill in the gaps you deliberately left.
     */
    private void collectReforestSpots() {
        int r = reforestRange.get();
        int gap = spacing.get();
        int gapSq = gap * gap;

        BlockPos origin = mc.player.getBlockPos();
        List<BlockPos> chosen = new ArrayList<>();

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -r; y <= r; y++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!plantable(pos)) continue;
                    if (nearSapling(pos, gap)) continue;

                    boolean clash = false;
                    for (BlockPos other : chosen) {
                        if (other.getSquaredDistance(pos) < gapSq) { clash = true; break; }
                    }
                    if (clash) continue;

                    chosen.add(pos);
                }
            }
        }

        spots.addAll(chosen);
    }

    /** A sapling already in the ground nearby, so spacing survives a second pass. */
    private boolean nearSapling(BlockPos pos, int gap) {
        for (int x = -gap; x <= gap; x++) {
            for (int z = -gap; z <= gap; z++) {
                for (int y = -1; y <= 1; y++) {
                    Block block = mc.world.getBlockState(pos.add(x, y + 1, z)).getBlock();
                    if (block.asItem() == sapling()) return true;
                }
            }
        }
        return false;
    }

    private boolean plantable(BlockPos pos) {
        Block block = mc.world.getBlockState(pos).getBlock();

        boolean dirt = block == Blocks.DIRT || block == Blocks.COARSE_DIRT || block == Blocks.ROOTED_DIRT || block == Blocks.PODZOL;
        boolean grass = block == Blocks.GRASS_BLOCK;

        boolean right = switch (ground.get()) {
            case Dirt -> dirt;
            case Grass -> grass;
            case Both -> dirt || grass;
        };
        if (!right) return false;

        // Clear above is both the planting requirement and the replant trigger.
        return mc.world.getBlockState(pos.up()).isAir();
    }

    private boolean inReach(BlockPos pos) {
        double range = mode.get() == Mode.TreeFarm ? farmRange.get() : 4.0;
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range;
    }

    private void plant(BlockPos dirt, FindItemResult sapling) {
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(dirt, 1.0), Direction.UP, dirt, false);

        Runnable action = () -> {
            InvUtils.swap(sapling.slot(), true);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(dirt), Rotations.getPitch(Vec3d.ofCenter(dirt)), 50, action);
        }
        else action.run();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!showSpots.get() || spots.isEmpty()) return;

        for (BlockPos pos : spots) {
            // Drawn on the sapling's own space rather than the dirt, since that is what you are
            // actually looking at when you check the layout.
            Box box = new Box(pos.getX(), pos.getY() + 1, pos.getZ(),
                pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1);

            event.renderer.box(box, sideColour.get(), lineColour.get(), ShapeMode.Both, 0);
        }
    }

    @Override
    public String getInfoString() {
        return spots.isEmpty() ? null : String.valueOf(spots.size());
    }
}
