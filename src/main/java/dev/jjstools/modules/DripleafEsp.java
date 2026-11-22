package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.Ping;
import dev.jjstools.util.XaeroWaypoints;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class DripleafEsp extends Module {
    private static final Predicate<BlockState> leaf = state -> state.isOf(Blocks.SMALL_DRIPLEAF);

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Render");
    private final SettingGroup traces = this.settings.createGroup("Tracers");
    private final SettingGroup markers = this.settings.createGroup("Chunk");
    private final SettingGroup alerts = this.settings.createGroup("Alerts");

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

    private final Setting<Ping> ping = this.alerts.add(new EnumSetting.Builder<Ping>()
        .name("ping")
        .description("Sound played when new dripleaf is found. Off for none.")
        .defaultValue(Ping.Pling)
        .build()
    );

    private final Setting<Double> pingVolume = this.alerts.add(new DoubleSetting.Builder()
        .name("ping-volume")
        .description("How loud the ping is.")
        .defaultValue(1)
        .range(0, 2)
        .sliderRange(0, 2)
        .visible(() -> this.ping.get() != Ping.Off)
        .build()
    );

    private final Setting<Boolean> waypoints = this.alerts.add(new BoolSetting.Builder()
        .name("xaero-waypoints")
        .description("Adds a waypoint in Xaero's Minimap (shown on the minimap and world map) where dripleaf is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> waypointName = this.alerts.add(new StringSetting.Builder()
        .name("waypoint-name")
        .description("Name of the waypoints.")
        .defaultValue("Dripleaf")
        .visible(this.waypoints::get)
        .build()
    );

    private final Setting<String> waypointInitials = this.alerts.add(new StringSetting.Builder()
        .name("waypoint-icon")
        .description("The letters shown on the waypoint's icon, up to two.")
        .defaultValue("D")
        .visible(this.waypoints::get)
        .build()
    );

    private final Setting<XaeroColour> waypointColour = this.alerts.add(new EnumSetting.Builder<XaeroColour>()
        .name("waypoint-colour")
        .description("Colour of the waypoints.")
        .defaultValue(XaeroColour.Green)
        .visible(this.waypoints::get)
        .build()
    );

    private final Setting<Integer> waypointSpacing = this.alerts.add(new IntSetting.Builder()
        .name("waypoint-spacing")
        .description("Dripleaf within this many blocks of an existing waypoint shares it, so one patch gets one waypoint.")
        .defaultValue(16)
        .min(0)
        .sliderRange(0, 64)
        .visible(this.waypoints::get)
        .build()
    );

    private final Setting<Boolean> temporary = this.alerts.add(new BoolSetting.Builder()
        .name("temporary-waypoints")
        .description("Temporary waypoints are not saved: they are gone when you leave. Off keeps them.")
        .defaultValue(true)
        .visible(this.waypoints::get)
        .build()
    );

    private final Setting<Boolean> removeOnDisable = this.alerts.add(new BoolSetting.Builder()
        .name("remove-on-disable")
        .description("Removes the waypoints this added when the module is turned off.")
        .defaultValue(true)
        .visible(this.waypoints::get)
        .build()
    );

    /** Dripleaf already pinged and given a waypoint (or a nearby one), so they are only reported once. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet alerted = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /** Where this put waypoints, and the waypoints themselves. */
    private final List<BlockPos> waypointAt = new ArrayList<>();
    private final List<XaeroWaypoints.Added> added = new ArrayList<>();
    private boolean warnedNoXaero;

    private final Long2ObjectMap<LongArrayList> found = new Long2ObjectOpenHashMap<>();
    private final BlockPos.Mutable below = new BlockPos.Mutable();
    private World world;
    private int ticks;

    public DripleafEsp() {
        super(JJsTools.CATEGORY, "dripleaf-esp",
            "Highlights small dripleaf upper halves without matching lower halves, with a ping and a Xaero waypoint when found."
        );
    }

    @Override
    public void onActivate() {
        this.reset();
    }

    @Override
    public void onDeactivate() {
        this.reset();
        if (this.removeOnDisable.get()) {
            for (XaeroWaypoints.Added a : this.added) XaeroWaypoints.remove(a);
        }
        this.added.clear();
        this.waypointAt.clear();
        this.alerted.clear();
    }

    @EventHandler
    private void leave(GameLeftEvent event) {
        this.reset();
        // Temporary waypoints go with the world; saved ones stay in Xaero.
        this.added.clear();
        this.waypointAt.clear();
        this.alerted.clear();
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
        this.alert();
    }

    /** Pings once for any dripleaf not reported before, and puts a waypoint at each new patch. */
    private void alert() {
        boolean fresh = false;
        int spacing = this.waypointSpacing.get();
        long spacingSq = (long) spacing * spacing;
        for (LongArrayList list : this.found.values()) {
            for (int i = 0; i < list.size(); i++) {
                long key = list.getLong(i);
                if (!this.alerted.add(key)) continue;
                fresh = true;
                if (!this.waypoints.get()) continue;

                BlockPos pos = BlockPos.fromLong(key);
                boolean near = false;
                for (BlockPos at : this.waypointAt) {
                    if (at.getSquaredDistance(pos) <= spacingSq) {
                        near = true;
                        break;
                    }
                }
                if (near) continue;

                if (!XaeroWaypoints.installed()) {
                    if (!this.warnedNoXaero) this.warning("Xaero's Minimap is not installed, so no waypoints.");
                    this.warnedNoXaero = true;
                    continue;
                }
                String initials = this.waypointInitials.get().trim();
                if (initials.isEmpty()) initials = "D";
                if (initials.length() > 2) initials = initials.substring(0, 2);
                XaeroWaypoints.Added a = XaeroWaypoints.add(pos.getX(), pos.getY(), pos.getZ(), this.waypointName.get(),
                    initials, this.waypointColour.get().ordinal(), this.temporary.get());
                if (a != null) {
                    this.added.add(a);
                    this.waypointAt.add(pos);
                }
            }
        }
        if (fresh) this.ping.get().play(0, this.pingVolume.get());
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

    /** Xaero's waypoint colours, in its order. */
    public enum XaeroColour {
        Black, DarkBlue, DarkGreen, DarkAqua, DarkRed, DarkPurple, Gold, Gray,
        DarkGray, Blue, Green, Aqua, Red, LightPurple, Yellow, White
    }

    public enum Height {
        Dripleaf,
        Player,
        Fixed
    }
}
