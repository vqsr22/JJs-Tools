package dev.jjstools.panel;

import dev.jjstools.JJsTools;
import dev.jjstools.modules.ControlPanel;
import dev.jjstools.tint.StorageMemory;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Your stashes: every chest, barrel and shulker box you have opened within reach of one.
 *
 * A stash is a place you set and a radius around it, 5000 blocks unless you change it. From then
 * on, any container you open inside that radius, on that server and in that dimension, is
 * written down slot for slot, items inside shulker boxes included, and kept up to date each time
 * you open it again.
 *
 * Each stash is one file in a folder of your choosing. Point two people's Control Panels at the
 * same synced folder, a Dropbox, Google Drive or OneDrive folder say, and both see everything
 * either of them has opened. The file is checked for changes every couple of seconds; on each
 * save it is read again first and the two are merged container by container, the newest look at
 * each one winning, so neither of you overwrites what the other saw.
 */
public final class StashStore {
    public static final class Container {
        public int x, y, z;
        public String kind;
        public long updated;
        public String by;
        public boolean removed;
        public List<String> items = new ArrayList<>();

        transient List<ItemStack> decoded;

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        /** Its items, read once into real items and kept. */
        public List<ItemStack> stacks() {
            if (decoded == null) {
                List<ItemStack> list = new ArrayList<>(items.size());
                for (String s : items) list.add(StorageMemory.decodeStack(s));
                if (MinecraftClient.getInstance().world != null) decoded = list;
                return list;
            }
            return decoded;
        }
    }

    public static final class Stash {
        public String name;
        public String server = "";
        public String dimension = "minecraft:overworld";
        public int x, y, z;
        public boolean located;
        public int radius = 5000;
        public long metaChanged;
        public Map<String, Container> containers = new LinkedHashMap<>();

        transient Path file;
        transient long fileTime;
        transient boolean dirty;
        transient long changedAt;
        /** Bumped on every change, so worked-out totals know when to work themselves out again. */
        public transient int revision;
        /** Containers changed here and not yet sent to the sync server, and whether its settings were. */
        transient java.util.Set<String> toSend;
        transient boolean settingsToSend;

        java.util.Set<String> toSend() {
            if (toSend == null) toSend = new java.util.HashSet<>();
            return toSend;
        }

        public int count() {
            int n = 0;
            for (Container c : containers.values()) if (!c.removed) n++;
            return n;
        }

        public long lastUpdated() {
            long t = 0;
            for (Container c : containers.values()) if (!c.removed) t = Math.max(t, c.updated);
            return t;
        }

        public boolean contains(String server, String dimension, BlockPos pos) {
            // The server address only has to match when that setting asks for it: players joining
            // through ZenithProxy, or by different addresses, are all on the same server really.
            if (!located || !this.dimension.equals(dimension)) return false;
            if (!ControlPanel.stashAnyServer() && !this.server.equalsIgnoreCase(server)) return false;
            double dx = pos.getX() - x, dz = pos.getZ() - z;
            return dx * dx + dz * dz <= (double) radius * radius;
        }
    }

    /**
     * What makes two items the same kind for counting: the item, and its enchantments, the
     * enchantments stored in a book, and what is in a potion or tipped arrow. So a Sharpness V
     * sword and a plain one are counted apart.
     */
    public record Kind(Item item, Object enchantments, Object stored, Object potion, Object box, Object name, Object trim) {
        /**
         * When shulker boxes are counted as boxes, each box with different contents or a
         * different name is its own kind, so each can be told apart and looked inside.
         */
        static Kind of(ItemStack stack, boolean boxes) {
            boolean shulker = boxes && stack.contains(DataComponentTypes.CONTAINER);
            Object enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
            Object stored = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
            if (ignoreEnchantments) {
                // Any enchantments count as the same, but enchanted and plain are still kept apart.
                enchantments = enchanted(stack.get(DataComponentTypes.ENCHANTMENTS)) ? Boolean.TRUE : null;
                stored = enchanted(stack.get(DataComponentTypes.STORED_ENCHANTMENTS)) ? Boolean.TRUE : null;
            }
            Object name = ignoreNames ? null : stack.get(DataComponentTypes.CUSTOM_NAME);
            // Ignoring trims counts trimmed and plain armour together, whatever the trim.
            Object trim = ignoreTrims ? null : stack.get(DataComponentTypes.TRIM);
            return new Kind(stack.getItem(), enchantments, stored, stack.get(DataComponentTypes.POTION_CONTENTS),
                shulker ? stack.get(DataComponentTypes.CONTAINER) : null, name, trim);
        }

        private static boolean enchanted(net.minecraft.component.type.ItemEnchantmentsComponent c) {
            return c != null && !c.isEmpty();
        }
    }

    /** One kind of item across a whole stash: how many, and which containers hold it. */
    public record Total(Kind kind, Item item, ItemStack sample, long count, List<Holding> where) {}

    public record Holding(Container container, long count) {}

    private static final Map<String, Stash> STASHES = new LinkedHashMap<>();
    private static boolean loaded;
    private static Path loadedFrom;
    private static int ticks;

    private static BlockPos clickedPos;
    private static long clickedAt;

    private static ScreenHandler openHandler;
    private static Stash openStash;
    private static String openKey;
    private static BlockPos openPos;
    private static String openKind;
    private static int openSlots;
    private static int openTicks;

    private StashStore() {}

    // Files

    /**
     * The folder stashes are kept in: the one set in Control Panel's settings when syncing by
     * folder, otherwise JJ's Tools' own (where the server's copies are kept too, for when it
     * cannot be reached).
     */
    public static Path folder() {
        String set = ControlPanel.syncFolder();
        if (ControlPanel.syncMode() == ControlPanel.SyncMode.Folder && set != null && !set.isBlank()) {
            try {
                return Path.of(set.trim());
            } catch (Exception ignored) {
                // Falls back to the default below.
            }
        }
        return FabricLoader.getInstance().getGameDir().resolve("jjs-tools").resolve("stashes");
    }

    private static void ensureLoaded() {
        Path folder = folder();
        if (loaded && folder.equals(loadedFrom)) return;
        loaded = true;
        loadedFrom = folder;
        STASHES.clear();

        try {
            Files.createDirectories(folder);
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    Stash s = PanelFiles.read(file, Stash.class, null);
                    if (s == null || s.name == null) continue;
                    s.file = file;
                    s.fileTime = Files.getLastModifiedTime(file).toMillis();
                    if (s.containers == null) s.containers = new LinkedHashMap<>();
                    STASHES.put(s.name.toLowerCase(Locale.ROOT), s);
                }
            }
        } catch (Exception e) {
            JJsTools.LOG.error("Control Panel could not read the stash folder " + folder, e);
        }
    }

    /** Looks at the folder again, as after changing it in the settings. */
    public static synchronized void reload() {
        loaded = false;
        ensureLoaded();
    }

    private static Path fileFor(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9 _.@-]", "_").trim();
        if (safe.isEmpty()) safe = "stash";
        return folder().resolve(safe + ".json");
    }

    public static synchronized Collection<Stash> all() {
        ensureLoaded();
        return new ArrayList<>(STASHES.values());
    }

    public static synchronized Stash get(String name) {
        ensureLoaded();
        return name == null ? null : STASHES.get(name.toLowerCase(Locale.ROOT));
    }

    /** A new stash, set where you are standing. */
    public static synchronized Stash create(String name) {
        ensureLoaded();
        Stash s = new Stash();
        s.name = name;
        s.radius = ControlPanel.newStashRadius();
        s.file = fileFor(name);
        STASHES.put(name.toLowerCase(Locale.ROOT), s);
        setLocationHere(s);
        return s;
    }

    public static synchronized void delete(Stash s) {
        ensureLoaded();
        STASHES.remove(s.name.toLowerCase(Locale.ROOT));
        if (ControlPanel.syncMode() == ControlPanel.SyncMode.Server) StashSync.queueDelete(s.name);
        try {
            if (s.file != null) Files.deleteIfExists(s.file);
        } catch (Exception e) {
            JJsTools.LOG.error("Control Panel could not delete stash file " + s.file, e);
        }
    }

    /** Moves a stash to where you are standing, on this server and in this dimension. */
    public static synchronized void setLocationHere(Stash s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        s.x = mc.player.getBlockX();
        s.y = mc.player.getBlockY();
        s.z = mc.player.getBlockZ();
        s.server = server();
        s.dimension = mc.world.getRegistryKey().getValue().toString();
        s.located = true;
        s.metaChanged = System.currentTimeMillis();
        s.settingsToSend = true;
        changed(s);
    }

    public static synchronized void setRadius(Stash s, int radius) {
        s.radius = Math.max(16, Math.min(radius, 1_000_000));
        s.metaChanged = System.currentTimeMillis();
        s.settingsToSend = true;
        changed(s);
    }

    /** Forgets one container, for everyone sharing the file. */
    public static synchronized void forget(Stash s, Container c) {
        c.removed = true;
        c.items = new ArrayList<>();
        c.decoded = null;
        c.updated = System.currentTimeMillis();
        for (Map.Entry<String, Container> e : s.containers.entrySet()) if (e.getValue() == c) s.toSend().add(e.getKey());
        changed(s);
    }

    private static void changed(Stash s) {
        s.dirty = true;
        s.changedAt = System.currentTimeMillis();
        s.revision++;
    }

    /**
     * Writes a stash, merged with whatever is in its file now, so a friend's changes since it
     * was last read are kept rather than written over.
     */
    private static void save(Stash s) {
        if (s.file == null) s.file = fileFor(s.name);
        try {
            if (Files.exists(s.file)) {
                Stash onDisk = PanelFiles.read(s.file, Stash.class, null);
                if (onDisk != null) merge(s, onDisk);
            }
        } catch (Exception ignored) {
            // A file that cannot be read is simply written over.
        }
        PanelFiles.write(s.file, s);
        try {
            s.fileTime = Files.getLastModifiedTime(s.file).toMillis();
        } catch (Exception ignored) {
            s.fileTime = System.currentTimeMillis();
        }
        s.dirty = false;
    }

    /** Takes in anything newer from another copy of the same stash. Returns whether anything changed. */
    private static boolean merge(Stash into, Stash from) {
        boolean changed = false;
        if (from.metaChanged > into.metaChanged) {
            into.server = from.server;
            into.dimension = from.dimension;
            into.x = from.x;
            into.y = from.y;
            into.z = from.z;
            into.located = from.located;
            into.radius = from.radius;
            into.metaChanged = from.metaChanged;
            changed = true;
        }
        if (from.containers == null) return changed;
        for (Map.Entry<String, Container> e : from.containers.entrySet()) {
            Container mine = into.containers.get(e.getKey());
            Container theirs = e.getValue();
            if (theirs == null) continue;
            if (mine == null || theirs.updated > mine.updated) {
                into.containers.put(e.getKey(), theirs);
                changed = true;
            }
        }
        if (changed) into.revision++;
        return changed;
    }

    /** Takes in changes made to stash files by someone else, and new stash files they made. */
    private static void sync() {
        Path folder = folder();
        if (!folder.equals(loadedFrom)) {
            reload();
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                long time = Files.getLastModifiedTime(file).toMillis();
                Stash known = null;
                for (Stash s : STASHES.values()) if (file.equals(s.file)) known = s;
                if (known != null && time <= known.fileTime) continue;

                Stash read = PanelFiles.read(file, Stash.class, null);
                if (read == null || read.name == null) continue;
                if (read.containers == null) read.containers = new LinkedHashMap<>();

                if (known == null) {
                    read.file = file;
                    read.fileTime = time;
                    STASHES.put(read.name.toLowerCase(Locale.ROOT), read);
                } else {
                    merge(known, read);
                    known.fileTime = time;
                }
            }
        } catch (Exception ignored) {
            // The folder may be briefly unavailable while a sync client works on it.
        }
    }

    // Syncing with a server

    /** Stashes already sent to the server whole this session, after finding it did not have them. */
    private static final java.util.Set<String> uploaded = new java.util.HashSet<>();

    /** Takes in what the last round brought back, and starts the next when it is due. */
    private static void serverTick() {
        StashSync.Result r;
        while ((r = StashSync.RESULTS.poll()) != null) take(r);
        if (!StashSync.due()) return;

        List<StashSync.Push> pushes = new ArrayList<>();
        for (Stash s : STASHES.values()) {
            if (s.toSend().isEmpty() && !s.settingsToSend) continue;
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            if (s.settingsToSend) body.add("meta", meta(s));
            com.google.gson.JsonObject cs = new com.google.gson.JsonObject();
            List<String> keys = new ArrayList<>(s.toSend());
            for (String k : keys) {
                Container c = s.containers.get(k);
                if (c != null) cs.add(k, PanelFiles.GSON.toJsonTree(c));
            }
            body.add("containers", cs);
            pushes.add(new StashSync.Push(s.name, PanelFiles.GSON.toJson(body), keys, s.settingsToSend));
            s.toSend().clear();
            s.settingsToSend = false;
        }
        StashSync.start(pushes);
    }

    private static com.google.gson.JsonObject meta(Stash s) {
        com.google.gson.JsonObject m = new com.google.gson.JsonObject();
        m.addProperty("server", s.server);
        m.addProperty("dimension", s.dimension);
        m.addProperty("x", s.x);
        m.addProperty("y", s.y);
        m.addProperty("z", s.z);
        m.addProperty("located", s.located);
        m.addProperty("radius", s.radius);
        m.addProperty("metaChanged", s.metaChanged);
        return m;
    }

    private static void take(StashSync.Result r) {
        for (StashSync.Push p : StashSync.finished(r)) {
            // Not sent: sent again next round.
            Stash s = STASHES.get(p.name().toLowerCase(Locale.ROOT));
            if (s == null) continue;
            s.toSend().addAll(p.keys());
            if (p.meta()) s.settingsToSend = true;
        }
        if (r.error() != null) return;

        for (StashSync.Change ch : r.changes()) {
            String key = ch.name().toLowerCase(Locale.ROOT);
            Stash s = STASHES.get(key);
            if (s == null) {
                s = new Stash();
                s.name = ch.name();
                s.file = fileFor(ch.name());
                STASHES.put(key, s);
            }
            Stash from = new Stash();
            from.containers = new LinkedHashMap<>(ch.containers());
            if (ch.meta() != null) {
                com.google.gson.JsonObject m = ch.meta();
                from.server = m.has("server") ? m.get("server").getAsString() : s.server;
                from.dimension = m.has("dimension") ? m.get("dimension").getAsString() : s.dimension;
                from.x = m.has("x") ? m.get("x").getAsInt() : s.x;
                from.y = m.has("y") ? m.get("y").getAsInt() : s.y;
                from.z = m.has("z") ? m.get("z").getAsInt() : s.z;
                from.located = m.has("located") && m.get("located").getAsBoolean();
                from.radius = m.has("radius") ? m.get("radius").getAsInt() : s.radius;
                from.metaChanged = m.has("metaChanged") ? m.get("metaChanged").getAsLong() : 0;
            }
            if (merge(s, from)) {
                s.dirty = true;
                s.changedAt = System.currentTimeMillis();
            }
            if (ch.full()) {
                // The first look at it this session: anything newer here goes up.
                for (Map.Entry<String, Container> e : s.containers.entrySet()) {
                    Container theirs = ch.containers().get(e.getKey());
                    if (theirs == null || e.getValue().updated > theirs.updated) s.toSend().add(e.getKey());
                }
                if (ch.meta() == null || s.metaChanged > from.metaChanged) s.settingsToSend = true;
            }
            StashSync.known.put(key, ch.version());
        }

        // Stashes the server does not have yet go up whole, once.
        java.util.Set<String> remote = new java.util.HashSet<>();
        for (String n : r.remote().keySet()) remote.add(n.toLowerCase(Locale.ROOT));
        for (Stash s : new ArrayList<>(STASHES.values())) {
            String key = s.name.toLowerCase(Locale.ROOT);
            Long gone = r.deleted().get(key);
            if (!remote.contains(key) && gone != null && Math.max(s.lastUpdated(), s.metaChanged) < gone && s.toSend().isEmpty()) {
                // Someone deleted it after its last change here.
                STASHES.remove(key);
                try {
                    if (s.file != null) Files.deleteIfExists(s.file);
                } catch (Exception ignored) {
                    // Gone from the list either way.
                }
                continue;
            }
            if (!remote.contains(key) && uploaded.add(key)) {
                s.toSend().addAll(s.containers.keySet());
                s.settingsToSend = true;
            }
        }
    }

    // Capturing

    /** About to right-click a block: remembered so the screen that opens next can be matched to it. */
    public static void onUse(BlockHitResult hit) {
        if (hit == null) return;
        clickedPos = hit.getBlockPos().toImmutable();
        clickedAt = System.currentTimeMillis();
    }

    public static synchronized void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ensureLoaded();
        ticks++;

        if (mc.player != null && mc.world != null) {
            capture(mc);
            checkBroken(mc);
        }

        ControlPanel.SyncMode mode = ControlPanel.syncMode();
        if (mode == ControlPanel.SyncMode.Folder && ticks % 40 == 0) sync();
        if (mode == ControlPanel.SyncMode.Server) serverTick();
        long now = System.currentTimeMillis();
        for (Stash s : STASHES.values()) {
            if (s.dirty && now - s.changedAt > 1500) save(s);
        }
    }

    public static synchronized void saveAll() {
        for (Stash s : STASHES.values()) if (s.dirty) save(s);
    }

    private static void capture(MinecraftClient mc) {
        ScreenHandler current = mc.player.currentScreenHandler;
        if (!(current instanceof GenericContainerScreenHandler) && !(current instanceof ShulkerBoxScreenHandler)) current = null;

        if (current != openHandler) {
            if (openHandler != null) record(mc, openHandler);
            openHandler = current;
            openStash = null;
            openKey = null;
            openTicks = 0;
            if (current != null) bind(mc, current);
        }

        if (openHandler == null || openStash == null) return;
        if (openTicks < 2) {
            openTicks++;
            return;
        }
        if (mc.player.age % 10 == 0) record(mc, openHandler);
    }

    private static void bind(MinecraftClient mc, ScreenHandler handler) {
        if (clickedPos == null || System.currentTimeMillis() - clickedAt > 10_000) return;

        BlockState state = mc.world.getBlockState(clickedPos);
        Block block = state.getBlock();
        String kind;
        int slots;
        BlockPos pos = clickedPos;

        if (block instanceof ShulkerBoxBlock) {
            if (!(handler instanceof ShulkerBoxScreenHandler)) return;
            kind = "Shulker";
            slots = 27;
        } else if (block instanceof BarrelBlock) {
            if (!(handler instanceof GenericContainerScreenHandler g) || g.getRows() != 3) return;
            kind = "Barrel";
            slots = 27;
        } else if (block instanceof ChestBlock) {
            if (!(handler instanceof GenericContainerScreenHandler g)) return;
            boolean dbl = state.contains(ChestBlock.CHEST_TYPE) && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE;
            slots = g.getRows() * 9;
            if (slots != (dbl ? 54 : 27)) return;
            kind = dbl ? "Double chest" : "Chest";
            if (dbl) {
                Direction toOther = ChestBlock.getFacing(state);
                BlockPos other = pos.offset(toOther);
                if (other.asLong() < pos.asLong()) pos = other;
            }
        } else {
            return;
        }

        String server = server();
        String dimension = mc.world.getRegistryKey().getValue().toString();

        Stash best = null;
        double bestDist = Double.MAX_VALUE;
        for (Stash s : STASHES.values()) {
            if (!s.contains(server, dimension, pos)) continue;
            double d = pos.getSquaredDistance(s.x, s.y, s.z);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        if (best == null) return;

        openStash = best;
        openPos = pos.toImmutable();
        openKey = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        openKind = kind;
        openSlots = slots;
    }

    private static void record(MinecraftClient mc, ScreenHandler handler) {
        if (openStash == null || openKey == null || openTicks < 2) return;
        if (handler.slots.size() < openSlots) return;

        List<String> items = new ArrayList<>(openSlots);
        List<ItemStack> stacks = new ArrayList<>(openSlots);
        for (int i = 0; i < openSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack().copy();
            stacks.add(stack);
            items.add(StorageMemory.encodeStack(stack));
        }

        Container old = openStash.containers.get(openKey);
        if (old != null && !old.removed && old.items.equals(items)) return;

        Container c = new Container();
        c.x = openPos.getX();
        c.y = openPos.getY();
        c.z = openPos.getZ();
        c.kind = openKind;
        c.updated = System.currentTimeMillis();
        c.by = dev.jjstools.compat.Compat.name(mc.player.getGameProfile());
        c.items = items;
        c.decoded = stacks;
        openStash.containers.put(openKey, c);
        openStash.toSend().add(openKey);
        changed(openStash);
    }

    // Broken containers

    /** Containers seen turning into something else, to look at again in a moment. */
    private static final Map<BlockPos, Long> brokenAt = new HashMap<>();

    /**
     * A block changed in a loaded chunk. A container that stops being one is checked again a
     * second later, so a break the server undoes (a ghost block, a cancelled mine) is not taken
     * as real, and then dropped from any stash that knew what was in it.
     */
    public static synchronized void onBlockChanged(BlockPos pos, BlockState old, BlockState now) {
        if (old == null || now == null || STASHES.isEmpty()) return;
        if (!isContainer(old.getBlock()) || now.getBlock() == old.getBlock()) return;
        brokenAt.put(pos.toImmutable(), System.currentTimeMillis());
    }

    private static boolean isContainer(Block block) {
        return block instanceof ShulkerBoxBlock || block instanceof BarrelBlock || block instanceof ChestBlock;
    }

    private static void checkBroken(MinecraftClient mc) {
        if (brokenAt.isEmpty()) return;
        long now = System.currentTimeMillis();
        var it = brokenAt.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue() < 1000) continue;
            it.remove();
            BlockPos pos = e.getKey();
            if (!mc.world.isPosLoaded(pos)) continue;
            Block block = mc.world.getBlockState(pos).getBlock();
            // Still a container of the same sort (a chest that only lost or gained a half is
            // handled below): nothing was broken.
            if (isContainer(block) && !(block instanceof ChestBlock)) continue;
            forgetAt(mc, pos, block instanceof ChestBlock);
        }
    }

    /** Drops what any stash here knew about the container at pos, and a double chest it was half of. */
    private static void forgetAt(MinecraftClient mc, BlockPos pos, boolean stillChest) {
        String server = server();
        String dimension = mc.world.getRegistryKey().getValue().toString();
        for (Stash s : STASHES.values()) {
            if (!s.contains(server, dimension, pos)) continue;
            Container here = s.containers.get(key(pos));
            // A single chest still standing where a single chest was is the same chest.
            if (here != null && !here.removed && !(stillChest && "Chest".equals(here.kind) && single(mc, pos))) forget(s, here);
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos n = pos.offset(d);
                Container c = s.containers.get(key(n));
                if (c == null || c.removed || !"Double chest".equals(c.kind)) continue;
                // The other half of a double chest filed under its neighbour: if that neighbour
                // is no longer a double chest, what was recorded no longer describes it.
                if (!single(mc, n) && mc.world.getBlockState(n).getBlock() instanceof ChestBlock) continue;
                forget(s, c);
            }
        }
    }

    private static boolean single(MinecraftClient mc, BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.getBlock() instanceof ChestBlock && (!state.contains(ChestBlock.CHEST_TYPE) || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE);
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // Storage previews

    /**
     * What a stash knows is in the container at pos, for Storage GUI Tweaks' previews to fall
     * back on when it has no record of its own, such as a container a friend opened. Null if no
     * stash here has it, or it was recorded as another kind of container.
     */
    public static synchronized List<ItemStack> knownAt(BlockPos pos, String kind) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || STASHES.isEmpty()) return null;
        String server = server();
        String dimension = mc.world.getRegistryKey().getValue().toString();
        String k = key(pos);
        Container best = null;
        for (Stash s : STASHES.values()) {
            if (!s.contains(server, dimension, pos)) continue;
            Container c = s.containers.get(k);
            if (c == null || c.removed || !kind.equals(c.kind)) continue;
            if (best == null || c.updated > best.updated) best = c;
        }
        return best == null ? null : best.stacks();
    }

    /**
     * Storage GUI Tweaks learnt what is in a container, by it being opened or by placing a
     * shulker box whose contents it knew. Any stash it lies in takes that in too.
     */
    public static synchronized void fromPreview(BlockPos pos, String kind, List<ItemStack> stacks) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || pos == null || stacks == null) return;
        ensureLoaded();
        String server = server();
        String dimension = mc.world.getRegistryKey().getValue().toString();
        Stash best = null;
        double bestDist = Double.MAX_VALUE;
        for (Stash s : STASHES.values()) {
            if (!s.contains(server, dimension, pos)) continue;
            double d = pos.getSquaredDistance(s.x, s.y, s.z);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        if (best == null) return;

        List<String> items = new ArrayList<>(stacks.size());
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copies.add(stack.copy());
            items.add(StorageMemory.encodeStack(stack));
        }
        String k = key(pos);
        Container old = best.containers.get(k);
        if (old != null && !old.removed && kind.equals(old.kind) && old.items.equals(items)) return;

        Container c = new Container();
        c.x = pos.getX();
        c.y = pos.getY();
        c.z = pos.getZ();
        c.kind = kind;
        c.updated = System.currentTimeMillis();
        c.by = dev.jjstools.compat.Compat.name(mc.player.getGameProfile());
        c.items = items;
        c.decoded = copies;
        best.containers.put(k, c);
        best.toSend().add(k);
        changed(best);
    }

    private static String server() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getCurrentServerEntry() != null) return mc.getCurrentServerEntry().address.toLowerCase(Locale.ROOT);
        return "singleplayer";
    }

    // Totals

    private static Stash totalsFor;
    private static int totalsRevision = -1;
    /** Kinds whose items had more than one name between them. */
    private static final java.util.Set<Kind> mixedNames = new java.util.HashSet<>();
    private static boolean totalsContents;
    private static boolean totalsNames, totalsEnchantments, totalsTrims;

    /** Whether items are told apart by their names, and by which enchantments they have. Set by the stash manager. */
    public static boolean ignoreNames = true;
    public static boolean ignoreEnchantments = true;
    public static boolean ignoreTrims = true;
    private static List<Total> totals = List.of();

    /**
     * Every kind of item in a stash with how many there are and where, biggest first.
     *
     * Counting contents, a shulker box with things in it counts as what is in it and the box
     * itself is left out, so 27 stacks of obsidian in boxes show as obsidian. Counting boxes,
     * shulker boxes are counted as they are, each kind of box on its own.
     */
    public static synchronized List<Total> totals(Stash s, boolean contents) {
        if (s == totalsFor && s.revision == totalsRevision && contents == totalsContents
            && ignoreNames == totalsNames && ignoreEnchantments == totalsEnchantments && ignoreTrims == totalsTrims) return totals;

        Map<Kind, long[]> counts = new HashMap<>();
        Map<Kind, ItemStack> samples = new HashMap<>();
        mixedNames.clear();
        Map<Kind, Map<Container, long[]>> where = new HashMap<>();

        for (Container c : s.containers.values()) {
            if (c.removed) continue;
            for (ItemStack stack : c.stacks()) {
                if (stack.isEmpty()) continue;

                ContainerComponent inside = stack.get(DataComponentTypes.CONTAINER);
                boolean full = inside != null && inside.iterateNonEmpty().iterator().hasNext();
                if (contents && full) {
                    for (ItemStack inner : inside.iterateNonEmpty()) {
                        add(counts, samples, where, c, inner, (long) inner.getCount() * stack.getCount(), false);
                    }
                } else {
                    add(counts, samples, where, c, stack, stack.getCount(), !contents);
                }
            }
        }

        List<Total> list = new ArrayList<>(counts.size());
        for (Map.Entry<Kind, long[]> e : counts.entrySet()) {
            List<Holding> holders = new ArrayList<>();
            for (Map.Entry<Container, long[]> h : where.get(e.getKey()).entrySet()) holders.add(new Holding(h.getKey(), h.getValue()[0]));
            holders.sort((a, b) -> Long.compare(b.count(), a.count()));
            ItemStack sample = samples.get(e.getKey());
            // Items under different names counted together are shown by what they really are: two
            // maces called different things show as Mace.
            if (mixedNames.contains(e.getKey())) {
                sample = sample.copy();
                sample.remove(DataComponentTypes.CUSTOM_NAME);
            }
            list.add(new Total(e.getKey(), e.getKey().item(), sample, e.getValue()[0], holders));
        }
        list.sort((a, b) -> Long.compare(b.count(), a.count()));

        totalsFor = s;
        totalsRevision = s.revision;
        totalsContents = contents;
        totalsNames = ignoreNames;
        totalsEnchantments = ignoreEnchantments;
        totalsTrims = ignoreTrims;
        totals = list;
        return list;
    }

    private static void add(Map<Kind, long[]> counts, Map<Kind, ItemStack> samples, Map<Kind, Map<Container, long[]>> where,
                            Container c, ItemStack stack, long amount, boolean boxes) {
        Kind kind = Kind.of(stack, boxes);
        counts.computeIfAbsent(kind, k -> new long[1])[0] += amount;
        ItemStack first = samples.putIfAbsent(kind, stack.copyWithCount(1));
        if (first != null && !java.util.Objects.equals(first.get(DataComponentTypes.CUSTOM_NAME), stack.get(DataComponentTypes.CUSTOM_NAME))) mixedNames.add(kind);
        where.computeIfAbsent(kind, k -> new LinkedHashMap<>()).computeIfAbsent(c, k -> new long[1])[0] += amount;
    }

    /**
     * The best container to fetch an item from: the one holding the most of it among those
     * within walking distance of you, or if none is that close, simply the nearest.
     */
    public static Holding best(Total total, double walk) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || total.where().isEmpty()) return total.where().isEmpty() ? null : total.where().get(0);

        Holding most = null;
        Holding nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Holding h : total.where()) {
            double d = Math.sqrt(mc.player.getBlockPos().getSquaredDistance(h.container().pos()));
            if (d <= walk && (most == null || h.count() > most.count())) most = h;
            if (d < nearestDist) {
                nearestDist = d;
                nearest = h;
            }
        }
        return most != null ? most : nearest;
    }
}
