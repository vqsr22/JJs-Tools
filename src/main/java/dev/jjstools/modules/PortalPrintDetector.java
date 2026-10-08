package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.Ping;
import dev.jjstools.util.SeenStore;
import dev.jjstools.util.SpawnArea;
import dev.jjstools.util.VisitedChunks;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

/**
 * Finds the footprint a nether portal leaves behind after the obsidian is taken.
 *
 * A portal frame is four blocks wide. When someone builds one into the ground and the obsidian is
 * later removed, what stays is a four long, one wide, one deep line that does not match the terrain
 * around it: either bare netherrack sitting in nylium, or an empty slot in an otherwise solid
 * surface. Natural terrain almost never makes a run of exactly four with different blocks at both
 * ends, which is what this looks for.
 *
 * Nether only. Scanning is spread over ticks so it does not stall the game.
 *
 * "only-on-trails" asks XaeroPlus whether a chunk was newly generated. A chunk that is NOT new has
 * been loaded by a player before, which is the closest thing to "someone has been here". This is a
 * soft dependency, reached by reflection: without XaeroPlus the setting simply does nothing.
 */
public class PortalPrintDetector extends Module {
    /** Presets for how fussy the netherrack patch test is. */
    public enum Strictness {
        Strict("Strict"),
        Normal("Normal"),
        Loose("Loose"),
        Custom("Custom");

        private final String title;
        Strictness(String title) { this.title = title; }
        @Override public String toString() { return title; }

        public int gaps() {
            return switch (this) { case Strict -> 0; case Normal -> 1; case Loose -> 3; case Custom -> 0; };
        }
        public int width() {
            return switch (this) { case Strict, Normal -> 1; case Loose -> 2; case Custom -> 1; };
        }
        public int minLength() {
            return switch (this) { case Strict, Normal -> 4; case Loose -> 3; case Custom -> 4; };
        }
        public int maxLength() {
            return switch (this) { case Strict -> 4; case Normal -> 5; case Loose -> 6; case Custom -> 4; };
        }
        /** Strict and Normal demand a solid rectangle; Loose tolerates a ragged edge. */
        public boolean solidRectangle() {
            return this != Loose;
        }
    }

    public enum Print {
        /** Bare netherrack embedded in nylium. */
        Netherrack,
        /** A four long gap in an otherwise solid surface. */
        Hole,
        Both
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgSound = settings.createGroup("Sound");
    private final SettingGroup sgSpawn = settings.createGroup("Hide In Spawn");

    private final Setting<SpawnArea> spawnArea = sgSpawn.add(new EnumSetting.Builder<SpawnArea>()
        .name("nether")
        .description("Ignore everything inside this area around 0, 0. Spawn is churned up beyond recognition.")
        .defaultValue(SpawnArea.Size100000)
        .build()
    );

    private final Setting<Integer> spawnCustom = sgSpawn.add(new IntSetting.Builder()
        .name("custom-size")
        .description("Width of the ignored area in blocks, centred on 0, 0 (100000 = from -50000 to 50000).")
        .defaultValue(100000).min(2).sliderRange(1000, 200000)
        .visible(() -> spawnArea.get() == SpawnArea.Custom)
        .build()
    );

    private final Setting<Boolean> avoidHighways = sgSpawn.add(new BoolSetting.Builder()
        .name("hide-near-highways")
        .description("Stop scanning while you are near an axis, diagonal or ring road.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> highwayMargin = sgSpawn.add(new IntSetting.Builder()
        .name("highway-margin")
        .description("How many blocks either side of a highway counts as being on it.")
        .defaultValue(200).min(16).sliderRange(32, 1000)
        .visible(avoidHighways::get)
        .build()
    );

    private final Setting<Print> printType = sgGeneral.add(new EnumSetting.Builder<Print>()
        .name("print-type")
        .description("Hole is the trench left by a removed frame and is what you want. Netherrack only fires where bare netherrack sits in a different material.")
        .defaultValue(Print.Both)
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("How many chunks out from you to scan.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("Scan budget. Lower this if you see stutter.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Boolean> onlyOnTrails = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-trails")
        .description("Only scan where someone has actually been. A chunk that already existed was loaded by a player at some point, whether that was in 1.12 or 1.19; virgin terrain is skipped. Needs XaeroPlus with its Palette New Chunks module switched on, or it hides everything.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Strictness> strictness = sgGeneral.add(new EnumSetting.Builder<Strictness>()
        .name("strictness")
        .description("How exactly a patch has to match a portal footprint. Strict is 4x1 ringed entirely by nylium. Loose finds half buried ones at the cost of noise. Custom uses the numbers below.")
        .defaultValue(Strictness.Normal)
        .build()
    );

    private final Setting<Integer> minLength = sgGeneral.add(new IntSetting.Builder()
        .name("min-length")
        .description("Shortest netherrack patch that counts. A portal frame is four wide, so four is the real answer; drop it only if you are finding partly covered ones.")
        .defaultValue(4).min(1).sliderRange(1, 8)
        .visible(() -> printType.get() != Print.Hole && strictness.get() == Strictness.Custom)
        .build()
    );

    private final Setting<Integer> maxLength = sgGeneral.add(new IntSetting.Builder()
        .name("max-length")
        .description("Longest netherrack patch that counts. Above four it is a stripe of bare ground, not a footprint.")
        .defaultValue(4).min(1).sliderRange(1, 12)
        .visible(() -> printType.get() != Print.Hole && strictness.get() == Strictness.Custom)
        .build()
    );

    private final Setting<Integer> allowedGaps = sgGeneral.add(new IntSetting.Builder()
        .name("allowed-gaps")
        .description("How many blocks around the patch may be something other than nylium. 0 means fully surrounded, which is what a real footprint looks like.")
        .defaultValue(0).min(0).sliderRange(0, 6)
        .visible(() -> printType.get() != Print.Hole && strictness.get() == Strictness.Custom)
        .build()
    );

    private final Setting<Integer> maxWidth = sgGeneral.add(new IntSetting.Builder()
        .name("max-width")
        .description("How wide the patch may be across its short side. A portal footprint is one block wide. Raising this lets fat smears of bare ground through.")
        .defaultValue(1).min(1).sliderRange(1, 5)
        .visible(() -> printType.get() != Print.Hole && strictness.get() == Strictness.Custom)
        .build()
    );

    private final Setting<Boolean> ignoreNylium = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-nylium-floor")
        .description("Skip slots whose floor is crimson or warped nylium. That is natural ground, not a footprint.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreUnderLava = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-under-lava")
        .description("Skip prints sitting under a lava lake. Source blocks only, so lava falls do not count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rememberFound = sgGeneral.add(new BoolSetting.Builder()
        .name("only-ping-once")
        .description("Never announce the same print twice, even after a relog. The box still draws; only the chat line and the sound are held back.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatMessage = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-message")
        .description("Announce each find in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-coords")
        .description("Leave coordinates out of the chat message, for streaming or screenshots.")
        .defaultValue(false)
        .visible(chatMessage::get)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from you to each print.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> sideColour = sgRender.add(new ColorSetting.Builder()
        .name("side-colour")
        .defaultValue(new SettingColor(160, 60, 255, 50))
        .build()
    );

    private final Setting<SettingColor> lineColour = sgRender.add(new ColorSetting.Builder()
        .name("line-colour")
        .defaultValue(new SettingColor(160, 60, 255, 220))
        .build()
    );

    private final Setting<Ping> ping = sgSound.add(new EnumSetting.Builder<Ping>()
        .name("sound")
        .description("Sound played when a print is found.")
        .defaultValue(Ping.Pling)
        .build()
    );

    private final Setting<Double> volume = sgSound.add(new DoubleSetting.Builder()
        .name("volume")
        .defaultValue(1.0).min(0.1).sliderRange(0.1, 2.0)
        .visible(() -> ping.get() != Ping.Off)
        .build()
    );

    private final Setting<Integer> semitones = sgSound.add(new IntSetting.Builder()
        .name("semitones")
        .description("Pitch shift in semitones. 0 is normal, +12 is one octave up, -12 one octave down.")
        .defaultValue(12)
        .min(-24)
        .max(24)
        .sliderRange(-24, 24)
        .visible(() -> ping.get() != Ping.Off)
        .build()
    );

    /** Found prints, keyed by their start position so the same one is never reported twice. */
    private final Map<Long, Box> found = new LinkedHashMap<>();
    private final Queue<Long> toScan = new ArrayDeque<>();

    /**
     * Chunks already looked at. The scanner used to re-walk every block of every nearby chunk
     * twice a second, checking two runs per block, which is what was wrecking the 1% lows.
     */
    private final java.util.Set<Long> scanned = new java.util.HashSet<>();

    /** Instant block-update checks still allowed this tick. */
    private int instantBudget;
    private static final int INSTANT_PER_TICK = 16;

    /** Netherrack already walked by checkPatch, so one patch is not flood filled from every block. */
    private final Set<Long> patchSeen = new HashSet<>();

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private long lastQueueRefill;

    // XaeroPlus, resolved once and only if present.
    private boolean xaeroChecked;
    private Object paletteNewChunks;
    private Method isNewChunk;

    public PortalPrintDetector() {
        super(JJsTools.CATEGORY, "portal-print-detector", "Finds the four block footprint left behind by removed nether portals.");
    }

    @Override
    public void onActivate() {
        found.clear();
        scanned.clear();
        patchSeen.clear();
        toScan.clear();
        lastQueueRefill = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        found.clear();
        scanned.clear();
        patchSeen.clear();
        toScan.clear();
    }

    // Scanning

    /*
     * Scanning each chunk once is what fixed the frame drops, but on its own it also meant a chunk
     * was never looked at again. These two put the real-time behaviour back without the cost: a
     * chunk is re-queued only when something in it actually changes, or when it first arrives.
     */
    /**
     * Whether the chunk holding a box is still loaded.
     *
     * Out of render distance the client has no blocks there, so a box is only a memory of what was
     * true when you flew past. Drawing it suggests the find is still there and still that shape,
     * which it may not be.
     */
    private boolean chunkLoaded(Box box) {
        return mc.world != null && mc.world.getChunkManager()
            .isChunkLoaded((int) Math.floor(box.minX) >> 4, (int) Math.floor(box.minZ) >> 4);
    }

    private boolean inNether() {
        return mc.world != null && mc.world.getRegistryKey() == World.NETHER;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || !inNether()) return;

        // Lava spreading or a chunk of terrain going at once can fire hundreds of these in a
        // tick. The instant path is a luxury, so it gets a budget and the rest fall back to the
        // ordinary chunk pass.
        if (instantBudget <= 0) {
            long key = ChunkPos.toLong(event.pos.getX() >> 4, event.pos.getZ() >> 4);
            if (scanned.remove(key)) toScan.add(key);
            return;
        }
        instantBudget--;

        /*
         * Check around the changed block immediately. A print is at most four long, so a run that
         * now includes this block can start up to four blocks away on either axis. Re-queueing the
         * whole chunk and waiting for its turn is what made digging a hole take seconds to
         * register.
         */
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int x = event.pos.getX(), y = event.pos.getY(), z = event.pos.getZ();

        if (!inSpawn(x, z)) {
            for (int offset = -4; offset <= 0; offset++) {
                if (printType.get() != Print.Netherrack) {
                    checkRun(null, pos, x + offset, y, z, 1, 0);
                    checkRun(null, pos, x, y, z + offset, 0, 1);
                }
            }
            if (printType.get() != Print.Hole) {
                // The patch flood fill finds the whole blob from any block in it, so one call is
                // enough, but the cached walk has to be cleared or it will skip straight past.
                patchSeen.remove(BlockPos.asLong(x, y, z));
                checkPatch(pos, x, y, z);
            }
        }

        long key = ChunkPos.toLong(x >> 4, z >> 4);
        if (scanned.remove(key)) toScan.add(key);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!inNether()) return;

        long key = event.chunk().getPos().toLong();
        scanned.remove(key);
        toScan.add(key);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        instantBudget = INSTANT_PER_TICK;
        if (mc.world == null || mc.player == null) return;

        if (mc.world.getRegistryKey() != World.NETHER) {
            // Boxes from a previous Nether session must not hang around in another dimension.
            if (!found.isEmpty() || !toScan.isEmpty() || !scanned.isEmpty()) {
                found.clear();
                scanned.clear();
                patchSeen.clear();
                toScan.clear();
            }
            return;
        }

        if (toScan.isEmpty() && System.currentTimeMillis() - lastQueueRefill > 250) {
            refillQueue();
            lastQueueRefill = System.currentTimeMillis();
        }

        for (int i = 0; i < chunksPerTick.get() && !toScan.isEmpty(); i++) {
            long key = toScan.poll();
            scanChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
        }
    }

    private void refillQueue() {
        ChunkPos centre = mc.player.getChunkPos();
        int r = range.get();
        for (int x = centre.x - r; x <= centre.x + r; x++) {
            for (int z = centre.z - r; z <= centre.z + r; z++) {
                long key = ChunkPos.toLong(x, z);
                if (!scanned.contains(key)) toScan.add(key);
            }
        }

        int forget = r + 6;
        scanned.removeIf(key -> Math.abs(ChunkPos.getPackedX(key) - centre.x) > forget
            || Math.abs(ChunkPos.getPackedZ(key) - centre.z) > forget);
    }

    private void scanChunk(int chunkX, int chunkZ) {
        if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) return;
        scanned.add(ChunkPos.toLong(chunkX, chunkZ));
        if (inSpawn(chunkX << 4, chunkZ << 4)) return;
        if (avoidHighways.get() && onHighway(chunkX << 4, chunkZ << 4)) return;
        if (onlyOnTrails.get() && !VisitedChunks.visited(chunkX, chunkZ)) {
            if (VisitedChunks.warnOnce()) warning("XaeroPlus not found, so only-on-trails is doing nothing.");
            return;
        }

        Chunk chunk = mc.world.getChunk(chunkX, chunkZ);
        int bottom = mc.world.getBottomY();
        int top = Math.min(mc.world.getTopYInclusive(), 127);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        // Hoisted out of the loop: these were being read 32,000 times per chunk.
        boolean doPatch = printType.get() != Print.Hole;
        boolean doRun = printType.get() != Print.Netherrack;

        for (int y = bottom + 1; y <= top; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = (chunkX << 4) + lx;
                    int wz = (chunkZ << 4) + lz;

                    /*
                     * One block lookup decides whether anything else runs.
                     *
                     * The old loop called checkPatch and two checkRuns at every single position,
                     * each doing its own lookups, over 128 y levels and 256 columns per chunk.
                     * That is where the frame time was going. Almost every block in the Nether is
                     * neither air nor bare netherrack, so this throws most of them out for the
                     * cost of one state fetch.
                     */
                    BlockState state = mc.world.getBlockState(pos.set(wx, y, wz));

                    if (doPatch && state.getBlock() == Blocks.NETHERRACK) {
                        checkPatch(pos, wx, y, wz);
                    }
                    else if (doRun && state.isAir()) {
                        checkRun(chunk, pos, wx, y, wz, 1, 0);
                        checkRun(chunk, pos, wx, y, wz, 0, 1);
                    }
                }
            }
        }
    }

    /**
     * A print is a four long, one wide, one deep slot recessed into the ground.
     *
     * Walled in on both long sides, capped at both ends, floor underneath, open above.
     *
     * The material also has to differ from its surroundings. Without that, a Netherrack run is just
     * four blocks of ordinary nether floor, which is why boxes were appearing over open ground
     * everywhere. A hole has to be genuinely enclosed, and bare netherrack has to be sitting in
     * something that is not netherrack.
     */
    private void checkRun(Chunk chunk, BlockPos.Mutable pos, int x, int y, int z, int dx, int dz) {
        if (matchesPrint(pos.set(x - dx, y, z - dz))) return;
        if (matchesPrint(pos.set(x + dx * 4, y, z + dz * 4))) return;

        if (!isWall(pos.set(x - dx, y, z - dz))) return;
        if (!isWall(pos.set(x + dx * 4, y, z + dz * 4))) return;

        /*
         * The surface around the slot must be FLAT.
         *
         * This is what separates a portal footprint from an ordinary dip in the terrain. A print
         * is cut down into level ground, so the blocks ringing it are all at the same height and
         * nothing sits on top of them. A natural gap between two lumps of rock fails here, which
         * is what was still getting through.
         */
        if (!mc.world.getBlockState(pos.set(x - dx, y + 1, z - dz)).isAir()) return;
        if (!mc.world.getBlockState(pos.set(x + dx * 4, y + 1, z + dz * 4)).isAir()) return;

        int px = dz;
        int pz = dx;

        for (int i = 0; i < 4; i++) {
            int bx = x + dx * i;
            int bz = z + dz * i;

            if (!matchesPrint(pos.set(bx, y, bz))) return;

            if (!isWall(pos.set(bx, y - 1, bz))) return;
            // Nylium underneath means undisturbed forest floor, not somewhere a frame stood.
            if (ignoreNylium.get() && isNylium(pos.set(bx, y - 1, bz))) return;
            if (!mc.world.getBlockState(pos.set(bx, y + 1, bz)).isAir()) return;

            if (!isWall(pos.set(bx + px, y, bz + pz))) return;
            if (!isWall(pos.set(bx - px, y, bz - pz))) return;

            // Level ground either side, not the bottom of a crevice.
            if (!mc.world.getBlockState(pos.set(bx + px, y + 1, bz + pz)).isAir()) return;
            if (!mc.world.getBlockState(pos.set(bx - px, y + 1, bz - pz)).isAir()) return;
        }

        if (ignoreUnderLava.get() && underLavaLake(pos, x, y, z, dx, dz)) return;

        report(new BlockPos(x, y, z), dx, dz);
    }

    /**
     * checkRun only ever looks for holes now.
     *
     * In Both mode this used to accept netherrack as well, and isWall only rejected netherrack
     * walls in the Netherrack mode, so any four blocks of flat nether floor with a different block
     * at each end qualified. That is what was drawing boxes all over open ground.
     *
     * Bare netherrack is checkPatch's job, and that insists on nylium around it.
     */
    private boolean matchesPrint(BlockPos pos) {
        return mc.world.getBlockState(pos).isAir();
    }

    /**
     * A block that can form the wall or floor of the slot.
     *
     * For the netherrack variant the wall must NOT be netherrack, otherwise every patch of flat
     * nether floor qualifies.
     */
    /**
     * A block that can form the wall or floor of the slot.
     *
     * Soul sand and soul soil are listed by hand because soul sand is not a full cube, so
     * isSolidBlock says no and every hole in a soul sand valley was being thrown out before it got
     * anywhere near the other checks.
     */
    private boolean isWall(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || state.isLiquid()) return false;

        Block block = state.getBlock();
        if (block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL) return true;

        return state.isSolidBlock(mc.world, pos);
    }

    /**
     * A patch of bare netherrack sitting in nylium.
     *
     * Portal footprints are rarely a tidy straight line: the ground is uneven, part gets covered,
     * or the frame sat at an angle. So instead of insisting on four in a row, this flood fills the
     * netherrack and accepts any blob of about the right size whose edge is mostly nylium.
     *
     * Requiring a straight run is what was missing the irregular ones.
     */
    private void checkPatch(BlockPos.Mutable pos, int x, int y, int z) {
        if (mc.world.getBlockState(pos.set(x, y, z)).getBlock() != Blocks.NETHERRACK) return;
        // Surface only: it must be exposed, with something solid underneath.
        if (!mc.world.getBlockState(pos.set(x, y + 1, z)).isAir()) return;

        long startKey = BlockPos.asLong(x, y, z);
        if (patchSeen.contains(startKey)) return;

        Set<Long> patch = new HashSet<>();
        Deque<BlockPos> stack = new ArrayDeque<>();
        BlockPos origin = new BlockPos(x, y, z);
        stack.push(origin);
        patch.add(startKey);

        int minX = x, maxX = x, minZ = z, maxZ = z;
        int nyliumEdge = 0, otherEdge = 0;

        /*
         * THE bug that kept these alive.
         *
         * The loop used to stop the moment the patch outgrew its cap and then carried straight on
         * to the size checks. The bounds came only from the blocks that happened to be popped
         * before it gave up, so a patch spreading across an open netherrack floor got measured as
         * whatever first ten blocks came out, which was frequently a tidy 4x1. It passed every
         * test after that.
         *
         * An overgrown patch is now a rejection, not a truncation.
         */
        int cap = maxLen() * (width() + 1) + 2;
        boolean overgrown = false;

        while (!stack.isEmpty()) {
            if (patch.size() > cap) {
                overgrown = true;
                break;
            }

            BlockPos current = stack.pop();

            minX = Math.min(minX, current.getX()); maxX = Math.max(maxX, current.getX());
            minZ = Math.min(minZ, current.getZ()); maxZ = Math.max(maxZ, current.getZ());

            // Flat patch, so only the four horizontal neighbours at this level.
            for (Direction dir : HORIZONTAL) {
                pos.set(current.getX() + dir.getOffsetX(), y, current.getZ() + dir.getOffsetZ());

                Block block = mc.world.getBlockState(pos).getBlock();

                if (block == Blocks.NETHERRACK && mc.world.getBlockState(pos.up()).isAir()) {
                    if (patch.add(pos.asLong())) stack.push(pos.toImmutable());
                }
                else if (isNylium(pos)) nyliumEdge++;
                else otherEdge++;
            }
        }

        patchSeen.addAll(patch);

        // Bail out before measuring anything: the bounds are meaningless for a patch we abandoned.
        if (overgrown) return;

        /*
         * A portal footprint is four long and one wide, so measure those two things directly.
         *
         * Counting blocks instead was the mistake: a 3x1 patch has three blocks and sailed through
         * a "at least three" test, and a fat smear can have the right block count with entirely
         * the wrong shape.
         */
        int width = Math.min(maxX - minX, maxZ - minZ) + 1;
        int length = Math.max(maxX - minX, maxZ - minZ) + 1;

        if (width > width()) return;
        if (length < minLen() || length > maxLen()) return;

        // The patch has to fill its own bounding box. A diagonal scatter of four blocks measures
        // 4 long and 4 wide, and without this an L shape would pass as a straight line.
        if (strictness.get().solidRectangle() && patch.size() != width * length) return;

        /*
         * Every block around the patch has to be nylium.
         *
         * This used to be a ratio, "more nylium than anything else", which passed a patch of bare
         * netherrack sitting in more bare netherrack with a bit of nylium off to one side. A real
         * footprint is a hole punched in nylium, so the whole edge is nylium.
         */
        if (nyliumEdge == 0) return;
        if (otherEdge > gaps()) return;

        if (ignoreUnderLava.get() && underLavaLake(pos, minX, y, minZ, 1, 0)) return;

        reportBox(new Box(minX, y, minZ, maxX + 1, y + 1, maxZ + 1), new BlockPos(minX, y, minZ));
    }

    private boolean inSpawn(int x, int z) {
        int half = spawnArea.get().half(spawnCustom.get());
        return half > 0 && Math.abs(x) <= half && Math.abs(z) <= half;
    }

    /** Axis highways, the four diagonals, and the ring roads. */
    private boolean onHighway(int x, int z) {
        int m = highwayMargin.get();

        if (Math.abs(x) <= m || Math.abs(z) <= m) return true;
        if (Math.abs(Math.abs(x) - Math.abs(z)) <= m) return true;

        for (int ring : SpawnArea.RING_ROADS) {
            boolean onX = Math.abs(Math.abs(x) - ring) <= m && Math.abs(z) <= ring + m;
            boolean onZ = Math.abs(Math.abs(z) - ring) <= m && Math.abs(x) <= ring + m;
            if (onX || onZ) return true;
        }
        return false;
    }

    private int gaps() {
        return strictness.get() == Strictness.Custom ? allowedGaps.get() : strictness.get().gaps();
    }

    private int width() {
        return strictness.get() == Strictness.Custom ? maxWidth.get() : strictness.get().width();
    }

    private int minLen() {
        return strictness.get() == Strictness.Custom ? minLength.get() : strictness.get().minLength();
    }

    private int maxLen() {
        return strictness.get() == Strictness.Custom ? maxLength.get() : strictness.get().maxLength();
    }

    private boolean isNylium(BlockPos pos) {
        Block block = mc.world.getBlockState(pos).getBlock();
        return block == Blocks.CRIMSON_NYLIUM || block == Blocks.WARPED_NYLIUM;
    }

    /** True if there is still lava sitting above the slot, source blocks only. */
    private boolean underLavaLake(BlockPos.Mutable pos, int x, int y, int z, int dx, int dz) {
        for (int i = 0; i < 4; i++) {
            for (int up = 1; up <= 8; up++) {
                BlockState state = mc.world.getBlockState(pos.set(x + dx * i, y + up, z + dz * i));
                if (state.getBlock() != Blocks.LAVA) continue;
                // Source blocks have a level of 0. Anything else is flowing and not a lake.
                if (state.getFluidState().isStill()) return true;
            }
        }
        return false;
    }

    private void reportBox(Box box, BlockPos at) {
        long key = at.asLong();
        if (found.containsKey(key)) return;

        found.put(key, box);
        announce(at, "netherrack");
    }

    private void report(BlockPos start, int dx, int dz) {
        long key = start.asLong() | ((long) (dx == 1 ? 1 : 0) << 62);
        if (found.containsKey(key)) return;

        Box box = new Box(
            start.getX(), start.getY(), start.getZ(),
            start.getX() + (dx == 1 ? 4 : 1),
            start.getY() + 1,
            start.getZ() + (dz == 1 ? 4 : 1)
        );
        found.put(key, box);
        announce(start, "hole");
    }

    /** The kind is in the message so a bad hit can be traced to the detector that made it. */
    private void announce(BlockPos at, String kind) {
        if (rememberFound.get() && mc.world != null
            && !SeenStore.addIfNew("seen-prints", mc.world.getRegistryKey().getValue().getPath(), at)) {
            return;
        }

        if (chatMessage.get()) {
            info(Text.literal(hideCoords.get()
                ? "Portal print found (%s)".formatted(kind)
                : "Portal print (%s) at %d, %d, %d".formatted(kind, at.getX(), at.getY(), at.getZ())));
        }

        playPling();
    }

    private void playPling() {
        if (mc.player == null) return;

        float pitch = (float) Math.max(0.5, Math.min(2.0, Math.pow(2, semitones.get() / 12.0)));
        mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, pitch);
    }

    // Render

    @EventHandler
    private void onRender(Render3DEvent event) {
        // Outside the Nether there is nothing to draw and nothing to iterate.
        if (!inNether()) return;

        if (found.isEmpty() || mc.player == null) return;

        for (Box box : found.values()) {
            if (!chunkLoaded(box)) continue;

            event.renderer.box(box, sideColour.get(), lineColour.get(), ShapeMode.Both, 0);

            if (tracers.get() && RenderUtils.center != null) {
                /*
                 * Started from RenderUtils.center, not the camera.
                 *
                 * A line beginning exactly at the camera sits on the near plane and is clipped
                 * away entirely, which is why these never appeared. Meteor's own Tracers uses this
                 * same point: just in front of the camera, so the line has somewhere to start.
                 */
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    box.minX + (box.maxX - box.minX) / 2,
                    box.minY + 0.5,
                    box.minZ + (box.maxZ - box.minZ) / 2,
                    lineColour.get()
                );
            }
        }
    }

    /**
     * A real button rather than a toggle that flips itself back. Meteor has no button setting
     * type, but a module can render its own widget under its settings, which is what this is.
     */
    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList list = theme.horizontalList();

        WButton preview = list.add(theme.button("Preview sound")).widget();
        preview.action = this::playPling;

        return list;
    }

    @Override
    public String getInfoString() {
        return found.isEmpty() ? null : String.valueOf(found.size());
    }
}
