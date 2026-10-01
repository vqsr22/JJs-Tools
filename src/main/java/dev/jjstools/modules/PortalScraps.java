package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.Ping;
import dev.jjstools.util.SpawnArea;
import dev.jjstools.util.VisitedChunks;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Finds leftover obsidian in the Nether: bases, stashes, old builds and abandoned portals.
 *
 * Nether only. Ruined portals are filtered out by their giveaways, since they are the one thing
 * that puts obsidian everywhere naturally. Scanning is budgeted per tick.
 *
 * Highway and ring road detection uses the 2b2t layout: axes along X=0 and Z=0, diagonals where
 * |x| equals |z|, and ring roads at 1k, 5k, 10k, 15k, 25k, 50k, 125k, 250k and 3.75M. Those are
 * paved in obsidian, so every one of them is a false positive factory.
 */
public class PortalScraps extends Module {
    public enum Ping {
        Pling("Note block pling"),
        Bell("Bell"),
        Chime("Note block chime"),
        Bit("Note block bit"),
        ExperienceOrb("Experience orb"),
        Off("Off");

        private final String title;
        Ping(String title) { this.title = title; }
        @Override public String toString() { return title; }

        public SoundEvent sound() {
            return switch (this) {
                case Pling -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
                case Bell -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
                case Chime -> SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value();
                case Bit -> SoundEvents.BLOCK_NOTE_BLOCK_BIT.value();
                case ExperienceOrb -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
                case Off -> null;
            };
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    /** What has to be present before a lump is written off as a ruined portal. */
    public enum RuinTest {
        CryingObsidian("Crying obsidian"),
        Both("Crying obsidian and chest");

        private final String title;
        RuinTest(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }

    private final SettingGroup sgFilter = settings.createGroup("Filters");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgSound = settings.createGroup("Sound");
    private final SettingGroup sgSpawn = settings.createGroup("Hide In Spawn");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range").description("How many chunks out from you to scan.")
        .defaultValue(8).min(1).sliderRange(1, 16).build());

    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-per-tick").description("Scan budget. Raise this to find things sooner, lower it if you see stutter.")
        .defaultValue(4).min(1).sliderRange(1, 32).build());

    private final Setting<Integer> minCluster = sgGeneral.add(new IntSetting.Builder()
        .name("min-cluster")
        .description("Ignore anything smaller than this many obsidian in one spot.")
        .defaultValue(1).min(1).sliderRange(1, 32).build());

    private final Setting<Integer> recheckTicks = sgGeneral.add(new IntSetting.Builder()
        .name("recheck-ticks")
        .description("How often to re-check finds are still there, in ticks. 20 is once a second.")
        .defaultValue(20).min(1).sliderRange(1, 200).build());

    private final Setting<Boolean> detectPortals = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-portals")
        .description("Call out obsidian arranged as a working portal frame that has not been lit.")
        .defaultValue(true).build());

    private final Setting<Integer> allowedMissing = sgGeneral.add(new IntSetting.Builder()
        .name("allowed-missing")
        .description("How many frame blocks may be missing and it still counts as a portal. 0 means perfect frames only.")
        .defaultValue(2).min(0).sliderRange(0, 6)
        .visible(() -> detectPortals.get()).build());

    private final Setting<Boolean> chatMessage = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-message").description("Announce each find in chat.")
        .defaultValue(true).build());

    private final Setting<Boolean> hideCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-coords")
        .description("Leave coordinates out of the chat message, for streaming or screenshots.")
        .defaultValue(false)
        .visible(chatMessage::get).build());

    // Filters

    private final Setting<Boolean> ignoreRuinedPortals = sgFilter.add(new BoolSetting.Builder()
        .name("ignore-ruined-portals")
        .description("Skip lumps containing crying obsidian, which every ruined portal has and player builds almost never do.")
        .defaultValue(true).build());

    private final Setting<Boolean> onlyOldChunks = sgFilter.add(new BoolSetting.Builder()
        .name("only-old-chunks")
        .description("Only scan chunks a player has loaded before, whether that was in 1.12 or 1.19. Needs XaeroPlus with its Palette New Chunks module switched on, or it hides everything.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RuinTest> ruinTest = sgFilter.add(new EnumSetting.Builder<RuinTest>()
        .name("ruined-portal-test")
        .description("What marks a lump as a ruined portal. Both is stricter and lets through ruined portals whose chest is long gone, which on an old server is most of them.")
        .defaultValue(RuinTest.Both)
        .visible(() -> ignoreRuinedPortals.get())
        .build()
    );

    private final Setting<Integer> chestRadius = sgFilter.add(new IntSetting.Builder()
        .name("chest-radius")
        .description("How far from the obsidian to look for the portal's loot chest.")
        .defaultValue(6).min(1).sliderRange(1, 16)
        .visible(() -> ignoreRuinedPortals.get() && ruinTest.get() == RuinTest.Both)
        .build()
    );

    private final Setting<SpawnArea> spawnArea = sgSpawn.add(new EnumSetting.Builder<SpawnArea>()
        .name("nether").description("Ignore everything inside this area around 0, 0. Spawn is wall to wall obsidian.")
        .defaultValue(SpawnArea.Size100000).build());

    private final Setting<Integer> spawnCustom = sgSpawn.add(new IntSetting.Builder()
        .name("custom-size").description("Width of the ignored area in blocks, centred on 0, 0 (100000 = -50000 to 50000).")
        .defaultValue(100000).min(2).sliderRange(1000, 200000)
        .visible(() -> spawnArea.get() == SpawnArea.Custom).build());

    private final Setting<Boolean> avoidHighways = sgSpawn.add(new BoolSetting.Builder()
        .name("hide-near-highways")
        .description("Stop scanning while you are near an axis, diagonal or ring road. They are paved in obsidian.")
        .defaultValue(true).build());

    private final Setting<Integer> highwayMargin = sgSpawn.add(new IntSetting.Builder()
        .name("highway-margin")
        .description("How many blocks either side of a highway counts as being on it.")
        .defaultValue(200).min(16).sliderRange(32, 1000)
        .visible(avoidHighways::get).build());

    // Render

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from you to each find.").defaultValue(true).build());

    private final Setting<SettingColor> sideColour = sgRender.add(new ColorSetting.Builder()
        .name("side-colour").defaultValue(new SettingColor(120, 0, 200, 50)).build());

    private final Setting<SettingColor> lineColour = sgRender.add(new ColorSetting.Builder()
        .name("line-colour").defaultValue(new SettingColor(120, 0, 200, 220)).build());

    // Sound

    private final Setting<Ping> ping = sgSound.add(new EnumSetting.Builder<Ping>()
        .name("ping").description("Sound played when something is found.")
        .defaultValue(Ping.Pling).build());

    private final Setting<Double> volume = sgSound.add(new DoubleSetting.Builder()
        .name("volume")
        .defaultValue(1.0).min(0.1).sliderRange(0.1, 2.0)
        .visible(() -> ping.get() != Ping.Off)
        .build()
    );

    private final Setting<Integer> semitones = sgSound.add(new IntSetting.Builder()
        .name("semitones").description("Pitch shift in semitones. 0 is normal, +12 is one octave up.")
        .defaultValue(12).min(-24).max(24).sliderRange(-24, 24)
        .visible(() -> ping.get() != Ping.Off).build());

    /** One reported lump of obsidian. */
    private static class Find {
        final Box box;
        final Set<Long> blocks;
        final BlockPos origin;
        boolean portal;

        Find(Box box, Set<Long> blocks, BlockPos origin) {
            this.box = box;
            this.blocks = blocks;
            this.origin = origin;
        }
    }

    private static final int MAX_CLUSTER = 4096;

    /** Confirmed finds, keyed by cluster origin so nothing is reported twice. */
    private final Map<Long, Find> found = new LinkedHashMap<>();

    /** Every obsidian block already walked, so the scan never re-reports the same lump. */
    private final Set<Long> claimed = new HashSet<>();

    /**
     * Chunks already looked at. Without this the scanner re-walked every block of every nearby
     * chunk twice a second forever, which is what was destroying the 1% lows.
     */
    private final Set<Long> scanned = new HashSet<>();

    /** Instant block-update checks still allowed this tick. */
    private int instantBudget;
    private static final int INSTANT_PER_TICK = 16;

    private int tickCounter;
    private final Queue<Long> toScan = new ArrayDeque<>();
    private long lastRefill;


    public PortalScraps() {
        super(JJsTools.CATEGORY, "portal-scraps", "Finds leftover obsidian in the Nether, skipping highways and ruined portals.");
    }

    @Override
    public void onActivate() {
        found.clear();
        claimed.clear();
        scanned.clear();
        toScan.clear();
        lastRefill = 0;
        tickCounter = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        found.clear();
        claimed.clear();
        scanned.clear();
        toScan.clear();
    }

    /*
     * Scanning each chunk once is what fixed the frame drops, but on its own it also meant a chunk
     * was never looked at again. These two put the real-time behaviour back without the cost: a
     * chunk is re-queued only when something in it actually changes, or when it first arrives.
     */
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world.getRegistryKey() != World.NETHER) return;

        // Lava spreading can fire hundreds of these in a tick, and each one can start a flood
        // fill. Budget them and let the ordinary chunk pass pick up the overflow.
        if (instantBudget <= 0) {
            long key = ChunkPos.toLong(event.pos.getX() >> 4, event.pos.getZ() >> 4);
            if (scanned.remove(key)) toScan.add(key);
            return;
        }
        instantBudget--;

        /*
         * Check the block itself straight away rather than only re-queueing its chunk. Waiting for
         * the chunk to come back round the queue meant a block you just placed took seconds to
         * show up; this way it is the same tick.
         */
        if (event.newState.getBlock() == Blocks.OBSIDIAN && !claimed.contains(event.pos.asLong())) {
            if (!inSpawn(event.pos.getX(), event.pos.getZ())
                && !(avoidHighways.get() && onHighway(event.pos.getX(), event.pos.getZ()))) {
                checkCluster(event.pos.toImmutable());
            }
        }

        // Still re-queue the chunk, so a block being broken is picked up by the next full pass.
        long key = ChunkPos.toLong(event.pos.getX() >> 4, event.pos.getZ() >> 4);
        if (scanned.remove(key)) toScan.add(key);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        long key = event.chunk().getPos().toLong();
        scanned.remove(key);
        toScan.add(key);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        instantBudget = INSTANT_PER_TICK;
        if (mc.world == null || mc.player == null) return;
        if (mc.world.getRegistryKey() != World.NETHER) {
            if (!found.isEmpty() || !toScan.isEmpty() || !scanned.isEmpty()) {
                found.clear();
                claimed.clear();
                scanned.clear();
                toScan.clear();
            }
            return;
        }
        if (avoidHighways.get() && onHighway(mc.player.getBlockX(), mc.player.getBlockZ())) return;

        if (toScan.isEmpty() && System.currentTimeMillis() - lastRefill > 250) {
            refill();
            lastRefill = System.currentTimeMillis();
        }

        for (int i = 0; i < chunksPerTick.get() && !toScan.isEmpty(); i++) {
            long key = toScan.poll();
            scanChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
        }

        if (++tickCounter >= recheckTicks.get()) {
            tickCounter = 0;
            recheck();
        }
    }

    /** Drops finds whose obsidian has since been mined, so old boxes do not hang around. */
    private void recheck() {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        found.values().removeIf(find -> {
            if (!mc.world.getChunkManager().isChunkLoaded(find.origin.getX() >> 4, find.origin.getZ() >> 4)) {
                return false;
            }
            int gone = 0;
            for (long key : find.blocks) {
                pos.set(BlockPos.unpackLongX(key), BlockPos.unpackLongY(key), BlockPos.unpackLongZ(key));
                if (mc.world.getBlockState(pos).getBlock() != Blocks.OBSIDIAN) gone++;
            }
            if (gone == find.blocks.size()) {
                find.blocks.forEach(claimed::remove);
                return true;
            }
            return false;
        });
    }

    private void refill() {
        ChunkPos centre = mc.player.getChunkPos();
        int r = range.get();
        for (int x = centre.x - r; x <= centre.x + r; x++) {
            for (int z = centre.z - r; z <= centre.z + r; z++) {
                long key = ChunkPos.toLong(x, z);
                if (!scanned.contains(key)) toScan.add(key);
            }
        }

        // Forget chunks well outside the search area so they are scanned again if you return.
        int forget = r + 6;
        scanned.removeIf(key -> Math.abs(ChunkPos.getPackedX(key) - centre.x) > forget
            || Math.abs(ChunkPos.getPackedZ(key) - centre.z) > forget);
    }

    private void scanChunk(int chunkX, int chunkZ) {
        if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) return;
        scanned.add(ChunkPos.toLong(chunkX, chunkZ));
        if (onlyOldChunks.get() && !VisitedChunks.visited(chunkX, chunkZ)) {
            if (VisitedChunks.warnOnce()) warning("XaeroPlus not found, so only-old-chunks is doing nothing.");
            return;
        }
        if (inSpawn(chunkX << 4, chunkZ << 4)) return;
        if (avoidHighways.get() && onHighway(chunkX << 4, chunkZ << 4)) return;

        int bottom = mc.world.getBottomY();
        int top = Math.min(mc.world.getTopYInclusive(), 127);
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int y = bottom; y <= top; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    pos.set((chunkX << 4) + lx, y, (chunkZ << 4) + lz);
                    if (mc.world.getBlockState(pos).getBlock() != Blocks.OBSIDIAN) continue;
                    if (claimed.contains(pos.asLong())) continue;
                    checkCluster(pos.toImmutable());
                }
            }
        }
    }

    /**
     * Walks the whole connected lump of obsidian, then decides what it is.
     *
     * Every obsidian block is collected first and the ruined-portal test runs afterwards, so a
     * genuine build standing near a ruined portal is still reported. The earlier version bailed
     * out the moment it saw a marker block, which is why finds were going missing.
     *
     * The box is the bounding box of the whole lump, so a rectangular build draws as one clean
     * shape instead of a scatter of small boxes.
     */
    private void checkCluster(BlockPos origin) {
        Set<Long> cluster = new HashSet<>();
        Deque<BlockPos> stack = new ArrayDeque<>();
        stack.push(origin);
        cluster.add(origin.asLong());

        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;

        BlockPos.Mutable probe = new BlockPos.Mutable();

        while (!stack.isEmpty() && cluster.size() < MAX_CLUSTER) {
            BlockPos current = stack.pop();

            minX = Math.min(minX, current.getX()); maxX = Math.max(maxX, current.getX());
            minY = Math.min(minY, current.getY()); maxY = Math.max(maxY, current.getY());
            minZ = Math.min(minZ, current.getZ()); maxZ = Math.max(maxZ, current.getZ());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        probe.set(current.getX() + dx, current.getY() + dy, current.getZ() + dz);
                        Block neighbour = mc.world.getBlockState(probe).getBlock();
                        // Crying obsidian joins the lump so a ruined portal is seen as one thing.
                        if (neighbour != Blocks.OBSIDIAN && neighbour != Blocks.CRYING_OBSIDIAN) continue;
                        if (!cluster.add(probe.asLong())) continue;
                        stack.push(probe.toImmutable());
                    }
                }
            }
        }

        if (cluster.size() < minCluster.get()) return;
        if (ignoreRuinedPortals.get() && looksLikeRuinedPortal(cluster)) return;

        // Only claim a lump once it is actually reported. Claiming rejected lumps meant that
        // changing a setting afterwards could never bring them back.
        for (long key : cluster) claimed.add(key);

        Box box = new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        Find find = new Find(box, cluster, origin);
        found.put(origin.asLong(), find);

        if (detectPortals.get() && isUnlitPortal(minX, minY, minZ, maxX, maxY, maxZ)) {
            find.portal = true;
        }

        report(origin, find);
    }

    /**
     * Is this lump a ruined portal?
     *
     * Decided by what the lump is MADE of, not what is near it. The old version looked for chests,
     * gold blocks and magma within a block or two, which rejected practically every real find,
     * since stashes on 2b2t have chests next to obsidian constantly. That is why nothing was
     * showing up.
     *
     * Every vanilla ruined portal contains crying obsidian, and player builds essentially never
     * do, so that one block is the whole test.
     */
    private boolean looksLikeRuinedPortal(Set<Long> cluster) {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        boolean crying = false;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (long key : cluster) {
            int x = BlockPos.unpackLongX(key), y = BlockPos.unpackLongY(key), z = BlockPos.unpackLongZ(key);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);

            if (mc.world.getBlockState(pos.set(x, y, z)).getBlock() == Blocks.CRYING_OBSIDIAN) crying = true;
        }

        if (!crying) return false;
        if (ruinTest.get() == RuinTest.CryingObsidian) return true;

        // Only now is the chest worth looking for. Searching for it on its own was the old
        // mistake: every stash has a chest near obsidian, so it threw away real finds.
        return hasChestNear(pos, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private boolean hasChestNear(BlockPos.Mutable pos, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int r = chestRadius.get();

        for (int x = minX - r; x <= maxX + r; x++) {
            for (int y = minY - r; y <= maxY + r; y++) {
                for (int z = minZ - r; z <= maxZ + r; z++) {
                    if (mc.world.getBlockState(pos.set(x, y, z)).getBlock() == Blocks.CHEST) return true;
                }
            }
        }
        return false;
    }

    /**
     * True if the lump is a complete portal frame with air inside and no portal blocks in it.
     *
     * Any legal size counts, from the 2x3 opening upwards, not just the full 4x5. The frame is
     * checked on whichever vertical plane the lump is flat against.
     */
    private boolean isUnlitPortal(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int missing = 0;
        int height = maxY - minY + 1;
        if (height < 5 || height > 23) return false;

        boolean alongX = (maxX - minX) >= (maxZ - minZ);
        int width = alongX ? maxX - minX + 1 : maxZ - minZ + 1;
        if (width < 4 || width > 23) return false;

        // Must be one block thick on the other axis, or it is a wall rather than a frame.
        int thickness = alongX ? maxZ - minZ + 1 : maxX - minX + 1;
        if (thickness != 1) return false;

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int a = 0; a < width; a++) {
            for (int b = 0; b < height; b++) {
                int x = alongX ? minX + a : minX;
                int z = alongX ? minZ : minZ + a;
                int y = minY + b;
                pos.set(x, y, z);

                boolean edge = a == 0 || a == width - 1 || b == 0 || b == height - 1;
                Block block = mc.world.getBlockState(pos).getBlock();

                if (edge) {
                    // Corners are not part of a vanilla frame, so they may be anything.
                    boolean corner = (a == 0 || a == width - 1) && (b == 0 || b == height - 1);
                    // Someone mining a block or two out of a frame is exactly the thing worth
                    // finding, so a few gaps are allowed rather than disqualifying the whole thing.
                    if (!corner && block != Blocks.OBSIDIAN && ++missing > allowedMissing.get()) return false;
                }
                else {
                    // Already lit, so not a find worth calling out.
                    if (block == Blocks.NETHER_PORTAL) return false;
                    if (!mc.world.getBlockState(pos).isAir()) return false;
                }
            }
        }
        return true;
    }

    private void report(BlockPos pos, Find find) {
        if (chatMessage.get()) {
            String what = find.portal ? "Unlit full portal" : "Obsidian";
            info(Text.literal(hideCoords.get()
                ? "%s found".formatted(what)
                : "%s at %d, %d, %d".formatted(what, pos.getX(), pos.getY(), pos.getZ())));
        }
        playPing();
    }

    private void playPing() {
        SoundEvent sound = ping.get().sound();
        if (sound == null || mc.player == null) return;

        float pitch = (float) Math.max(0.5, Math.min(2.0, Math.pow(2, semitones.get() / 12.0)));
        mc.player.playSound(sound, 1.0f, pitch);
    }

    // Filters

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
            // Ring roads are squares, so it is on one if either coordinate sits on the ring
            // while the other is inside it.
            boolean onX = Math.abs(Math.abs(x) - ring) <= m && Math.abs(z) <= ring + m;
            boolean onZ = Math.abs(Math.abs(z) - ring) <= m && Math.abs(x) <= ring + m;
            if (onX || onZ) return true;
        }
        return false;
    }

    // Render

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (found.isEmpty() || mc.player == null) return;

        for (Find find : found.values()) {
            Box box = find.box;
            event.renderer.box(box, sideColour.get(), lineColour.get(), ShapeMode.Both, 0);

            if (tracers.get()) {
                var cam = mc.gameRenderer.getCamera().getCameraPos();
                event.renderer.line(cam.x, cam.y, cam.z,
                    (box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2,
                    lineColour.get());
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
        preview.action = this::playPing;

        return list;
    }

    @Override
    public String getInfoString() {
        return found.isEmpty() ? null : String.valueOf(found.size());
    }
}
