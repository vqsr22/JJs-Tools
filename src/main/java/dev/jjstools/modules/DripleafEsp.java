package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import java.util.function.Predicate;

public class DripleafEsp extends Module {
    private static final Predicate<BlockState> leaf = state -> state.isOf(Blocks.SMALL_DRIPLEAF);

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Render");
    private final SettingGroup traces = this.settings.createGroup("Tracers");
    private final SettingGroup markers = this.settings.createGroup("Chunk");

    private final Setting<Integer> range = this.general.add(new IntSetting.Builder()
        .name("scan-range")
        .description("Chunk radius around the player to scan.")
        .defaultValue(12)
        .min(1)
        .sliderRange(2, 32)
        .build()
    );

    private final Setting<Integer> interval = this.general.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Ticks between scans.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How detected dripleaf blocks are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> side = this.visuals.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill color of the highlighted blocks.")
        .defaultValue(new SettingColor(0, 255, 120, 75))
        .build()
    );

    private final Setting<SettingColor> line = this.visuals.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color of the highlighted blocks.")
        .defaultValue(new SettingColor(0, 255, 120, 255))
        .build()
    );

    private final Setting<Boolean> tracers = this.traces.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draws tracers to detected dripleaf blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> tracer = this.traces.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the tracer lines.")
        .defaultValue(new SettingColor(0, 255, 120, 255))
        .visible(this.tracers::get)
        .build()
    );

    private final Setting<Boolean> chunks = this.markers.add(new BoolSetting.Builder()
        .name("mark-chunks")
        .description("Highlights chunks containing detected dripleaf blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Height> height = this.markers.add(new EnumSetting.Builder<Height>()
        .name("chunk-height")
        .description("Y level of the chunk marker.")
        .defaultValue(Height.Dripleaf)
        .visible(this.chunks::get)
        .build()
    );

    private final Setting<Integer> fixed = this.markers.add(new IntSetting.Builder()
        .name("chunk-y")
        .description("Y level for chunk markers in Fixed mode.")
        .defaultValue(64)
        .range(-64, 320)
        .visible(() -> this.chunks.get() && this.height.get() == Height.Fixed)
        .build()
    );

    private final Setting<ShapeMode> outline = this.markers.add(new EnumSetting.Builder<ShapeMode>()
        .name("chunk-shape-mode")
        .description("How chunk markers are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(this.chunks::get)
        .build()
    );

    private final Setting<SettingColor> fill = this.markers.add(new ColorSetting.Builder()
        .name("chunk-side-color")
        .description("Fill color of chunk markers.")
        .defaultValue(new SettingColor(0, 255, 120, 35))
        .visible(this.chunks::get)
        .build()
    );

    private final Setting<SettingColor> border = this.markers.add(new ColorSetting.Builder()
        .name("chunk-line-color")
        .description("Outline color of chunk markers.")
        .defaultValue(new SettingColor(0, 255, 120, 200))
        .visible(this.chunks::get)
        .build()
    );

    private final Long2ObjectMap<LongArrayList> found = new Long2ObjectOpenHashMap<>();
    private final BlockPos.Mutable below = new BlockPos.Mutable();
    private World world;
    private int ticks;

    public DripleafEsp() {
        super(JJsTools.CATEGORY, "dripleaf-esp",
            "Highlights small dripleaf upper halves without matching lower halves."
        );
    }

    @Override
    public void onActivate() {
        this.reset();
    }

    @Override
    public void onDeactivate() {
        this.reset();
    }

    @EventHandler
    private void leave(GameLeftEvent event) {
        this.reset();
    }

    @EventHandler
    private void tick(TickEvent.Post event) {
        if (this.mc.world == null || this.mc.player == null) return;

        if (this.world != this.mc.world) {
            this.reset();
            this.world = this.mc.world;
        }

        if (this.ticks-- > 0) return;
        this.ticks = this.interval.get() - 1;
        this.scan();
    }

    private void scan() {
        Long2ObjectMap<LongArrayList> result = new Long2ObjectOpenHashMap<>();
        ClientChunkManager manager = this.mc.world.getChunkManager();
        int cx = this.mc.player.getBlockX() >> 4;
        int cz = this.mc.player.getBlockZ() >> 4;
        int radius = this.range.get();

        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                WorldChunk chunk = manager.getWorldChunk(x, z, false);
                if (chunk == null) continue;

                LongArrayList list = this.scan(chunk);
                if (list != null) result.put(ChunkPos.toLong(x, z), list);
            }
        }

        this.found.clear();
        this.found.putAll(result);
    }

    private LongArrayList scan(WorldChunk chunk) {
        LongArrayList result = null;
        ChunkSection[] sections = chunk.getSectionArray();
        int ox = chunk.getPos().getStartX();
        int oz = chunk.getPos().getStartZ();

        for (int idx = 0; idx < sections.length; idx++) {
            ChunkSection section = sections[idx];
            if (section == null || section.isEmpty() || !section.hasAny(leaf)) continue;

            int oy = chunk.getBottomY() + (idx << 4);

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!upper(state)) continue;

                        this.below.set(ox + x, oy + y - 1, oz + z);
                        if (lower(chunk.getBlockState(this.below))) continue;

                        if (result == null) result = new LongArrayList();
                        result.add(BlockPos.asLong(ox + x, oy + y, oz + z));
                    }
                }
            }
        }

        return result;
    }

    private static boolean upper(BlockState state) {
        return state.isOf(Blocks.SMALL_DRIPLEAF)
            && state.get(TallPlantBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    private static boolean lower(BlockState state) {
        return state.isOf(Blocks.SMALL_DRIPLEAF)
            && state.get(TallPlantBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    @EventHandler
    private void render(Render3DEvent event) {
        if (this.mc.world == null || this.mc.player == null) return;

        Vec3d center = RenderUtils.center;

        for (Long2ObjectMap.Entry<LongArrayList> entry : this.found.long2ObjectEntrySet()) {
            LongArrayList list = entry.getValue();
            int minimum = Integer.MAX_VALUE;

            for (int idx = 0; idx < list.size(); idx++) {
                long key = list.getLong(idx);
                int x = BlockPos.unpackLongX(key);
                int y = BlockPos.unpackLongY(key);
                int z = BlockPos.unpackLongZ(key);
                minimum = Math.min(minimum, y);

                event.renderer.box(x, y, z, x + 1, y + 1, z + 1,
                    this.side.get(), this.line.get(), this.shape.get(), 0
                );

                if (this.tracers.get()) {
                    event.renderer.line(center.x, center.y, center.z,
                        x + 0.5, y + 0.5, z + 0.5, this.tracer.get()
                    );
                }
            }

            if (!this.chunks.get() || minimum == Integer.MAX_VALUE) continue;

            double y = switch (this.height.get()) {
                case Dripleaf -> minimum;
                case Player -> this.mc.player.getY();
                case Fixed -> this.fixed.get();
            };

            int x = ChunkPos.getPackedX(entry.getLongKey()) << 4;
            int z = ChunkPos.getPackedZ(entry.getLongKey()) << 4;

            event.renderer.side(x, y, z, x + 16, y, z,
                x + 16, y, z + 16, x, y, z + 16,
                this.fill.get(), this.border.get(), this.outline.get()
            );
        }
    }

    private void reset() {
        this.found.clear();
        this.world = null;
        this.ticks = 0;
    }

    public enum Height {
        Dripleaf,
        Player,
        Fixed
    }
}
