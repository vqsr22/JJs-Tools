package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows where mobs can spawn as flat squares. Same logic as Meteor's Light Overlay (same
 * BlockIterator scan and BlockUtils.isValidMobSpawn check every tick), drawn as squares instead
 * of crosses, and it can switch itself off inside the spawn areas.
 */
public class LightLevels extends Module {
    public enum SpawnArea {
        Off("Off"),
        Size1000("1000 x 1000"),
        Size2000("2000 x 2000"),
        Size4000("4000 x 4000"),
        Custom("Custom");

        private final String title;

        SpawnArea(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * Hitboxes of the mobs that can be filtered for, width then height in blocks.
     *
     * Written out rather than read from the entity registry, because these are the numbers the
     * filter is actually reasoning about and they are worth being able to read here. Width is what
     * separates a spider from everything else: at 1.4 blocks it spills a fifth of a block into each
     * neighbour, so it cannot spawn in a one wide corridor the way a zombie can. Height is what
     * separates an enderman, which needs three blocks of headroom where the rest need two.
     */
    private static final double[] ZOMBIE = {0.6, 1.95};
    private static final double[] SKELETON = {0.6, 1.99};
    private static final double[] CREEPER = {0.6, 1.7};
    private static final double[] SPIDER = {1.4, 0.9};
    private static final double[] ENDERMAN = {0.6, 2.9};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMobs = settings.createGroup("Mobs");
    private final SettingGroup sgColours = settings.createGroup("Colours");
    private final SettingGroup sgSpawn = settings.createGroup("Hide In Spawn");

    // General (same defaults as Meteor's Light Overlay)

    private final Setting<Integer> horizontalRange = sgGeneral.add(new IntSetting.Builder()
        .name("horizontal-range")
        .description("Horizontal range in blocks.")
        .defaultValue(8)
        .min(0)
        .build()
    );

    private final Setting<Integer> verticalRange = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-range")
        .description("Vertical range in blocks.")
        .defaultValue(4)
        .min(0)
        .build()
    );

    private final Setting<Boolean> seeThroughBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("see-through-blocks")
        .description("Show the squares through blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> lightLevel = sgGeneral.add(new IntSetting.Builder()
        .name("light-level")
        .description("Which light levels to show. 0 for 1.18 and newer, 7 for older servers.")
        .defaultValue(0)
        .min(0)
        .sliderMax(15)
        .build()
    );

    private final Setting<Double> squareSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("square-size")
        .description("Size of each square. 1 fills the whole block top.")
        .defaultValue(1.0)
        .range(0.2, 1.0)
        .sliderRange(0.2, 1.0)
        .build()
    );

    // Mobs

    private final Setting<Boolean> filterByMob = sgMobs.add(new BoolSetting.Builder()
        .name("filter-by-mob")
        .description("Only show spots where one of the mobs ticked below would actually fit. Off shows every spot dark enough for anything to spawn.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> zombie = sgMobs.add(new BoolSetting.Builder()
        .name("zombie")
        .description("Also covers husks, drowned and villager zombies, which are the same size.")
        .visible(filterByMob::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> skeleton = sgMobs.add(new BoolSetting.Builder()
        .name("skeleton")
        .description("Also covers strays, which are the same size.")
        .visible(filterByMob::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> creeper = sgMobs.add(new BoolSetting.Builder()
        .name("creeper")
        .visible(filterByMob::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> spider = sgMobs.add(new BoolSetting.Builder()
        .name("spider")
        .description("Wide enough to need its neighbours clear as well, so it rules out corridors the others can use.")
        .visible(filterByMob::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enderman = sgMobs.add(new BoolSetting.Builder()
        .name("enderman")
        .description("Needs three blocks of headroom rather than two.")
        .visible(filterByMob::get)
        .defaultValue(false)
        .build()
    );

    // Colours

    private final Setting<SettingColor> spawnColour = sgColours.add(new ColorSetting.Builder()
        .name("will-spawn-colour")
        .description("Dark enough for mobs to spawn right now.")
        .defaultValue(new SettingColor(225, 25, 25, 90))
        .build()
    );

    private final Setting<SettingColor> nightColour = sgColours.add(new ColorSetting.Builder()
        .name("night-spawn-colour")
        .description("Only lit by the sky, so mobs can spawn here at night.")
        .defaultValue(new SettingColor(225, 225, 25, 90))
        .build()
    );

    // Hide in spawn

    private final Setting<SpawnArea> overworldArea = sgSpawn.add(new EnumSetting.Builder<SpawnArea>()
        .name("overworld")
        .description("Hide the squares inside this area around 0, 0 in the Overworld.")
        .defaultValue(SpawnArea.Size4000)
        .build()
    );

    private final Setting<Integer> overworldCustom = sgSpawn.add(new IntSetting.Builder()
        .name("overworld-custom-size")
        .description("Width of the Overworld area in blocks, centred on 0, 0 (4000 = from -2000 to 2000).")
        .defaultValue(4000)
        .min(2)
        .sliderRange(500, 20000)
        .visible(() -> overworldArea.get() == SpawnArea.Custom)
        .build()
    );

    private final Setting<SpawnArea> netherArea = sgSpawn.add(new EnumSetting.Builder<SpawnArea>()
        .name("nether")
        .description("Hide the squares inside this area around 0, 0 in the Nether.")
        .defaultValue(SpawnArea.Size1000)
        .build()
    );

    private final Setting<Integer> netherCustom = sgSpawn.add(new IntSetting.Builder()
        .name("nether-custom-size")
        .description("Width of the Nether area in blocks, centred on 0, 0 (1000 = from -500 to 500).")
        .defaultValue(1000)
        .min(2)
        .sliderRange(100, 5000)
        .visible(() -> netherArea.get() == SpawnArea.Custom)
        .build()
    );

    private final Setting<SpawnArea> endArea = sgSpawn.add(new EnumSetting.Builder<SpawnArea>()
        .name("end")
        .description("Hide the squares inside this area around 0, 0 in the End. The main island is all lit obsidian anyway.")
        .defaultValue(SpawnArea.Size1000)
        .build()
    );

    private final Setting<Integer> endCustom = sgSpawn.add(new IntSetting.Builder()
        .name("end-custom-size")
        .description("Width of the End area in blocks, centred on 0, 0 (1000 = from -500 to 500).")
        .defaultValue(1000)
        .min(2)
        .sliderRange(100, 5000)
        .visible(() -> endArea.get() == SpawnArea.Custom)
        .build()
    );

    private final Setting<Boolean> only2b2t = sgSpawn.add(new BoolSetting.Builder()
        .name("only-on-2b2t")
        .description("Only hide while connected to 2b2t.")
        .defaultValue(true)
        .build()
    );

    private final Pool<Square> squarePool = new Pool<>(Square::new);
    private final List<Square> squares = new ArrayList<>();

    public LightLevels() {
        super(JJsTools.CATEGORY, "light-levels", "Improved from Meteor. Shows where mobs can spawn with clean coloured squares.");
    }

    @Override
    public void onDeactivate() {
        squarePool.freeAll(squares);
        squares.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        squarePool.freeAll(squares);
        squares.clear();

        if (mc.player == null || mc.world == null || hiddenHere()) return;

        BlockIterator.register(horizontalRange.get(), verticalRange.get(), (blockPos, blockState) -> {
            switch (BlockUtils.isValidMobSpawn(blockPos, blockState, lightLevel.get())) {
                case Potential -> {
                    if (roomFor(blockPos)) squares.add(squarePool.get().set(blockPos, true));
                }
                case Always -> {
                    if (roomFor(blockPos)) squares.add(squarePool.get().set(blockPos, false));
                }
                default -> {
                }
            }
        });
    }

    /**
     * True if any of the ticked mobs would fit standing here.
     *
     * The light and the floor are already settled by the time this runs; what is left is whether
     * the mob's own body has room, which is the whole of what separates one mob from another for
     * spawning purposes. The box is tested against the world the same way the game does it when it
     * places a mob, so an overhang two blocks up rules out a zombie while leaving a spider, and a
     * one wide gap rules out the spider while leaving the zombie.
     */
    private boolean roomFor(BlockPos pos) {
        if (!filterByMob.get()) return true;

        return (zombie.get() && fits(pos, ZOMBIE))
            || (skeleton.get() && fits(pos, SKELETON))
            || (creeper.get() && fits(pos, CREEPER))
            || (spider.get() && fits(pos, SPIDER))
            || (enderman.get() && fits(pos, ENDERMAN));
    }

    private boolean fits(BlockPos pos, double[] size) {
        if (mc.world == null) return false;

        double half = size[0] / 2.0;
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;

        // No entity is passed, so a player or mob already standing on the spot does not hide it.
        return mc.world.isSpaceEmpty(new Box(x - half, pos.getY(), z - half, x + half, pos.getY() + size[1], z + half));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (squares.isEmpty()) return;

        Renderer3D renderer = seeThroughBlocks.get() ? event.renderer : event.depthRenderer;
        double inset = (1.0 - squareSize.get()) / 2.0;

        for (Square square : squares) {
            square.render(renderer, inset);
        }
    }

    private boolean hiddenHere() {
        SpawnArea area;
        int custom;
        if (mc.world.getRegistryKey() == World.OVERWORLD) {
            area = overworldArea.get();
            custom = overworldCustom.get();
        } else if (mc.world.getRegistryKey() == World.NETHER) {
            area = netherArea.get();
            custom = netherCustom.get();
        } else if (mc.world.getRegistryKey() == World.END) {
            area = endArea.get();
            custom = endCustom.get();
        } else {
            return false;
        }
        if (area == SpawnArea.Off) return false;
        if (only2b2t.get() && !on2b2t()) return false;

        double half = switch (area) {
            case Size1000 -> 500;
            case Size2000 -> 1000;
            case Size4000 -> 2000;
            case Custom -> custom / 2.0;
            default -> 0;
        };
        return Math.abs(mc.player.getX()) <= half && Math.abs(mc.player.getZ()) <= half;
    }

    private boolean on2b2t() {
        ServerInfo server = mc.getCurrentServerEntry();
        return server != null && server.address != null && server.address.toLowerCase(Locale.ROOT).contains("2b2t");
    }

    private class Square {
        private double x, y, z;
        private boolean potential;

        public Square set(BlockPos blockPos, boolean potential) {
            x = blockPos.getX();
            y = blockPos.getY() + 0.0075;
            z = blockPos.getZ();
            this.potential = potential;
            return this;
        }

        public void render(Renderer3D renderer, double inset) {
            Color c = potential ? nightColour.get() : spawnColour.get();
            renderer.quadHorizontal(x + inset, y, z + inset, x + 1 - inset, z + 1 - inset, c);
        }
    }
}
