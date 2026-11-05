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
            if (!located || !this.server.equalsIgnoreCase(server) || !this.dimension.equals(dimension)) return false;
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
            // Any trim counts as the same when trims are ignored, but trimmed and plain armour are kept apart.
            Object trim = stack.get(DataComponentTypes.TRIM);
            if (ignoreTrims && trim != null) trim = Boolean.TRUE;
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

    /** The folder stashes are kept in: the one set in Control Panel's settings, or JJ's Tools' own. */
    public static Path folder() {
        String set = ControlPanel.syncFolder();
        if (set != null && !set.isBlank()) {
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
        changed(s);
    }

    public static synchronized void setRadius(Stash s, int radius) {
        s.radius = Math.max(16, Math.min(radius, 1_000_000));
        s.metaChanged = System.currentTimeMillis();
        changed(s);
    }

    /** Forgets one container, for everyone sharing the file. */
    public static synchronized void forget(Stash s, Container c) {
        c.removed = true;
        c.items = new ArrayList<>();
        c.decoded = null;
        c.updated = System.currentTimeMillis();
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

        if (mc.player != null && mc.world != null) capture(mc);

        if (ticks % 40 == 0) sync();
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
        c.by = mc.player.getGameProfile().name();
        c.items = items;
        c.decoded = stacks;
        openStash.containers.put(openKey, c);
        changed(openStash);
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
