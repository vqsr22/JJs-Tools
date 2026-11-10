package dev.jjstools.tint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import dev.jjstools.util.JJFolder;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
//#if MC >= 1.21.9
import net.minecraft.block.CopperChestBlock;
//#endif
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What is inside the storage you have placed or looked in: shulker boxes, chests, barrels and
 * your ender chest.
 *
 * The server never tells the client what a placed container holds. It sends the block and
 * nothing else; the contents only arrive when it is opened, and only for as long as it is open.
 * So a crosshair preview has nothing to go on except what this client has already seen, and that
 * is exactly what this keeps:
 *
 *  - Opening a container records it, slot for slot, every tick for as long as it is open and
 *    once more as it closes, so taking things out or putting things in is reflected the moment
 *    you close it.
 *  - Placing a shulker box from your hand records what the item was carrying, which is how a
 *    box you filled yourself shows up without ever being opened. Chests and barrels have no
 *    such case: as items they are always empty.
 *
 * A double chest is one record shared by both halves. The ender chest is one record for every
 * ender chest on the server, because that is what it is: the same 27 slots wherever you open it.
 *
 * Anything else is unknown and stays unknown. Things a hopper or another player changes while
 * you are not looking are not seen either; the record is right as of the last time you saw
 * inside, which for a chest on a busy sorting system may not be long.
 *
 * Kept per server and per dimension, saved to .minecraft/jjs-tools/accounts/&lt;uuid&gt;/, and pruned
 * as you pass by: a remembered container whose block is gone or has changed shape is dropped.
 */
public final class StorageMemory {
    private static final String FILE = "storage-previews.json";

    /** What this used to be called when it only knew shulker boxes. Read once and moved across. */
    private static final String OLD_FILE = "shulker-previews.json";

    private static final Gson GSON = new GsonBuilder().create();

    /** Slots in a shulker box, a single chest, a barrel or an ender chest. A double chest has twice this. */
    public static final int SIZE = 27;

    /** What kind of container a record belongs to, so a box is never shown on a chest that replaced it. */
    public enum Kind { Shulker, Chest, Barrel, Ender }

    /** How long a placement may take to show up as a block before it is given up on. */
    private static final long PENDING_MS = 3_000;

    /** Ticks to let the server fill an opened container before its slots are trusted. */
    private static final int SETTLE_TICKS = 2;

    /** How recent a click on a container has to be for the screen that opens to be that container. */
    private static final long CLICK_MS = 10_000;

    private static final long SAVE_EVERY_MS = 5_000;
    private static final int PRUNE_EVERY_TICKS = 40;

    /** One remembered container: its custom name if it had one, its slots, and what it is. */
    public record Box(@Nullable String name, List<ItemStack> items, Kind kind) {
        public int rows() {
            return Math.max(1, items.size() / 9);
        }
    }

    private static final Map<String, Box> BOXES = new HashMap<>();
    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;

    /*
     * Each record as it will be written out, kept so that saving does not have to work every
     * item out again.
     *
     * Saving used to turn every item of every remembered container into the game's item format
     * and write the lot, on the game's own thread, every time you closed a container. With a few
     * hundred boxes remembered that is a lot of work in one frame, and it was the lag spike on
     * closing. Now only the records that changed are converted, and the file itself is written
     * on a thread of its own.
     */
    private static final Map<String, JsonObject> ENCODED = new HashMap<>();
    private static RegistryOps<NbtElement> lastNbtOps;

    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JJs Tools storage previews");
        thread.setDaemon(true);
        return thread;
    });

    /** Files a record, and drops its saved form so it is worked out afresh on the next save. */
    private static void put(String key, Box box) {
        BOXES.put(key, box);
        ENCODED.remove(key);
        dirty = true;
    }
    private static int ticks;

    private static ScreenHandler openHandler;
    private static String openKey;
    private static BlockPos openPos;
    private static Kind openKind;
    private static int openSlots;
    private static String openName;
    private static int openTicks;

    private static BlockPos clickedPos;
    private static long clickedAt;

    private static BlockPos pendingPos;
    private static String pendingKey;
    private static Box pendingBox;
    private static long pendingAt;

    private StorageMemory() {}

    // Reading

    /**
     * The remembered contents of the container at this position in the current world, if any.
     *
     * Either half of a double chest answers for the pair. Any ender chest answers with yours.
     */
    public static @Nullable Box get(BlockPos pos) {
        if (!ensureLoaded()) return null;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return null;

        BlockState state = mc.world.getBlockState(pos);
        Kind kind = kindOf(state.getBlock());
        if (kind == null) return null;

        String key = keyFor(pos, state, kind);
        if (key == null) return null;

        Box box = BOXES.get(key);
        if (box == null && kind != Kind.Ender) {
            // Nothing of its own: the stash manager may know, from a friend or an earlier look.
            BlockPos at = kind == Kind.Chest ? canonical(pos, state) : pos;
            List<ItemStack> known = dev.jjstools.panel.StashStore.knownAt(at, stashKind(kind, expectedSlots(state)));
            if (known != null) return new Box(null, known, kind);
        }
        if (box == null || box.kind() != kind) return null;

        // A double chest record on what is now a single chest, or the other way round.
        if (kind == Kind.Chest && box.items().size() != expectedSlots(state)) return null;
        return box;
    }

    /** The name the stash manager files this kind of container under. */
    private static String stashKind(Kind kind, int slots) {
        return switch (kind) {
            case Shulker -> "Shulker";
            case Barrel -> "Barrel";
            case Chest -> slots > SIZE ? "Double chest" : "Chest";
            case Ender -> "Ender";
        };
    }

    /** Tells the stash manager what is in a container at pos, for any stash it lies in. */
    private static void share(BlockPos pos, Kind kind, List<ItemStack> items) {
        if (pos == null || kind == null || kind == Kind.Ender) return;
        dev.jjstools.panel.StashStore.fromPreview(pos, stashKind(kind, items.size()), items);
    }

    /** Your ender chest on this server, as last seen, if it has been opened here. */
    public static @Nullable Box ender() {
        if (!ensureLoaded()) return null;
        Box box = BOXES.get(serverPrefix() + "|ender");
        return box != null && box.kind() == Kind.Ender ? box : null;
    }

    /** What sort of container a block is, as far as this goes, or null for anything else. */
    public static @Nullable Kind kindOf(Block block) {
        if (block instanceof ShulkerBoxBlock) return Kind.Shulker;
        if (block instanceof EnderChestBlock) return Kind.Ender;
        if (block instanceof BarrelBlock) return Kind.Barrel;

        // Copper chests are chests underneath too, but are left out on purpose.
        //#if MC >= 1.21.9
        if (block instanceof ChestBlock && !(block instanceof CopperChestBlock)) return Kind.Chest;
        //#else
        //$$ if (block instanceof ChestBlock) return Kind.Chest;
        //#endif
        return null;
    }

    // Capturing

    /**
     * About to right-click with something in hand.
     *
     * A container being clicked is remembered, so the screen that opens next can be matched to
     * it. Holding a shulker box, what it is carrying and where it should land are remembered too,
     * and committed once the block actually appears there. Not committed straight away, because a
     * right click that opens a chest instead of placing would otherwise pin the box's contents to
     * a space nothing went into.
     */
    public static void onUse(Hand hand, BlockHitResult hit) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || mc.player == null || hit == null) return;

        if (kindOf(mc.world.getBlockState(hit.getBlockPos()).getBlock()) != null) {
            clickedPos = hit.getBlockPos().toImmutable();
            clickedAt = System.currentTimeMillis();
        }

        ItemStack held = mc.player.getStackInHand(hand);
        if (!(held.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof ShulkerBoxBlock)) return;

        BlockPos clicked = hit.getBlockPos();
        BlockPos target = mc.world.getBlockState(clicked).isReplaceable() ? clicked : clicked.offset(hit.getSide());

        DefaultedList<ItemStack> slots = DefaultedList.ofSize(SIZE, ItemStack.EMPTY);
        ContainerComponent container = held.get(DataComponentTypes.CONTAINER);
        if (container != null) container.copyTo(slots);

        Text custom = held.get(DataComponentTypes.CUSTOM_NAME);

        pendingPos = target.toImmutable();
        pendingKey = key(pendingPos);
        pendingBox = new Box(custom == null ? null : custom.getString(), copyOf(slots, SIZE), Kind.Shulker);
        pendingAt = System.currentTimeMillis();
    }

    /** Called every tick while Storage GUI Tweaks is on. */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || mc.player == null) return;
        if (!ensureLoaded()) return;

        ticks++;
        copyOpenBox(mc);
        commitPlacement(mc);
        if (ticks % PRUNE_EVERY_TICKS == 0) prune(mc);

        if (dirty && System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) save();
    }

    /**
     * Keeps the record of the open container in step with what is in it.
     *
     * Goes by the player's own open container rather than by the screen being built. That is
     * the one thing that is only ever set by a container the server really opened for you: a
     * tooltip preview shows a shulker screen without changing it.
     *
     * A chest screen is only trusted when it has exactly the slots the clicked block should
     * have, 27 for a single chest, a barrel or an ender chest and 54 for a double. Servers build
     * their menus out of chest screens too, and one of those opened by clicking a chest, or just
     * after, would otherwise be written down as that chest's contents.
     */
    private static void copyOpenBox(MinecraftClient mc) {
        ScreenHandler current = mc.player.currentScreenHandler;
        boolean storage = current instanceof ShulkerBoxScreenHandler || current instanceof GenericContainerScreenHandler;
        if (!storage) current = null;

        if (current != openHandler) {
            // The container that was open has closed, or been swapped for another. Its slots
            // still hold how you left it, so that is recorded one last time before letting go:
            // taking something out and closing within the same tick would otherwise be missed.
            if (openHandler != null) {
                record(openHandler);
                if (dirty) save();
            }

            openHandler = current;
            openKey = null;
            openKind = null;
            openName = null;
            openTicks = 0;

            if (current != null) bind(mc, current);
        }

        if (openHandler == null) return;

        // The slots arrive a moment after the screen; reading them sooner would record it empty.
        if (openTicks < SETTLE_TICKS) {
            openTicks++;
            return;
        }

        record(openHandler);
    }

    /** Works out which container in the world a freshly opened screen belongs to, if any. */
    private static void bind(MinecraftClient mc, ScreenHandler handler) {
        BlockPos pos = recentClick(mc);
        if (pos == null) return;

        BlockState state = mc.world.getBlockState(pos);
        Kind kind = kindOf(state.getBlock());
        if (kind == null) return;

        int slots;
        if (kind == Kind.Shulker) {
            if (!(handler instanceof ShulkerBoxScreenHandler)) return;
            slots = SIZE;
        }
        else {
            if (!(handler instanceof GenericContainerScreenHandler generic)) return;
            slots = generic.getRows() * 9;
            int expected = kind == Kind.Chest ? expectedSlots(state) : SIZE;
            if (slots != expected) return;
        }

        String key = keyFor(pos, state, kind);
        if (key == null) return;

        openKey = key;
        openKind = kind;
        openSlots = slots;
        openPos = kind == Kind.Chest ? canonical(pos, state) : pos.toImmutable();

        // A container left with its own name is titled with that; anything else is the default.
        String shown = mc.currentScreen == null ? null : mc.currentScreen.getTitle().getString();
        openName = shown == null || shown.isBlank() || shown.equals(defaultTitle(kind, slots)) ? null : shown;
    }

    /** Copies an open container into the record, if it is known which one it is and it has settled. */
    private static void record(ScreenHandler handler) {
        if (openKey == null || openTicks < SETTLE_TICKS) return;
        if (handler.slots.size() < openSlots) return;

        List<ItemStack> items = new ArrayList<>(openSlots);
        for (int i = 0; i < openSlots; i++) items.add(handler.getSlot(i).getStack().copy());

        Box now = new Box(openName, items, openKind);
        if (!same(now, BOXES.get(openKey))) {
            put(openKey, now);
            share(openPos, openKind, items);
        }
    }

    /** The container you just clicked, if the click is recent and it is still there. */
    private static @Nullable BlockPos recentClick(MinecraftClient mc) {
        if (clickedPos == null || System.currentTimeMillis() - clickedAt > CLICK_MS) return null;
        if (kindOf(mc.world.getBlockState(clickedPos).getBlock()) == null) return null;
        return clickedPos;
    }

    private static void commitPlacement(MinecraftClient mc) {
        if (pendingPos == null) return;

        if (System.currentTimeMillis() - pendingAt > PENDING_MS) {
            pendingPos = null;
            return;
        }

        if (mc.world.getBlockState(pendingPos).getBlock() instanceof ShulkerBoxBlock) {
            put(pendingKey, pendingBox);
            share(pendingPos, Kind.Shulker, pendingBox.items());
            pendingPos = null;
        }
    }

    /**
     * Drops remembered containers that are no longer there.
     *
     * Only ones in this world and in chunks the client actually has are judged: a container in an
     * unloaded chunk is not known to be gone, only out of sight. A record is also dropped when
     * the block is now a different kind of container, or a double chest has lost a half, since
     * its slots no longer describe what is there. The ender chest is never pruned: it is not
     * anywhere in particular.
     */
    private static void prune(MinecraftClient mc) {
        String prefix = worldPrefix();
        Iterator<Map.Entry<String, Box>> it = BOXES.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, Box> entry = it.next();
            String key = entry.getKey();
            if (!key.startsWith(prefix)) continue;

            BlockPos pos;
            try {
                pos = BlockPos.fromLong(Long.parseLong(key.substring(prefix.length())));
            } catch (NumberFormatException e) {
                it.remove();
                ENCODED.remove(key);
                dirty = true;
                continue;
            }

            if (!mc.world.isPosLoaded(pos)) continue;

            BlockState state = mc.world.getBlockState(pos);
            Box box = entry.getValue();
            boolean fits = kindOf(state.getBlock()) == box.kind()
                && (box.kind() != Kind.Chest || (box.items().size() == expectedSlots(state) && pos.equals(canonical(pos, state))));

            if (!fits) {
                it.remove();
                ENCODED.remove(key);
                dirty = true;
            }
        }
    }

    // Chests

    /** 54 for either half of a double chest, 27 for a single one. */
    private static int expectedSlots(BlockState state) {
        if (!state.contains(ChestBlock.CHEST_TYPE)) return SIZE;
        return state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE ? SIZE : SIZE * 2;
    }

    /**
     * The one position a double chest is filed under, whichever half you are looking at.
     *
     * The block says which way its other half lies. Of the two, the one with the lower packed
     * position is used, which is the same answer from either side. A half whose partner is not
     * actually there, which a chunk border can briefly show, is treated as its own position.
     */
    private static BlockPos canonical(BlockPos pos, BlockState state) {
        if (!state.contains(ChestBlock.CHEST_TYPE) || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) return pos;

        BlockPos other = pos.offset(ChestBlock.getFacing(state));
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || !(mc.world.getBlockState(other).getBlock() instanceof ChestBlock)) return pos;

        return other.asLong() < pos.asLong() ? other.toImmutable() : pos.toImmutable();
    }

    /** What the screen is called when the container has no name of its own. */
    private static String defaultTitle(Kind kind, int slots) {
        String id = switch (kind) {
            case Shulker -> "container.shulkerBox";
            case Barrel -> "container.barrel";
            case Ender -> "container.enderchest";
            case Chest -> slots > SIZE ? "container.chestDouble" : "container.chest";
        };
        return Text.translatable(id).getString();
    }

    // Keys

    /** The record a container is filed under, or null if it cannot be worked out. */
    private static @Nullable String keyFor(BlockPos pos, BlockState state, Kind kind) {
        return switch (kind) {
            case Ender -> serverPrefix() + "|ender";
            case Chest -> key(canonical(pos, state));
            default -> key(pos);
        };
    }

    /**
     * Server, then dimension, then position.
     *
     * The same coordinates mean a different container on another server or in another
     * dimension, so all three are part of the name. The ender chest is filed by server alone,
     * which keeps it out of the per-dimension pruning too.
     */
    private static String key(BlockPos pos) {
        return worldPrefix() + pos.asLong();
    }

    private static String worldPrefix() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String dimension = mc.world == null ? "none" : mc.world.getRegistryKey().getValue().toString();
        return serverPrefix() + "|" + dimension + "|";
    }

    private static String serverPrefix() {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.getCurrentServerEntry() != null) {
            return mc.getCurrentServerEntry().address.toLowerCase(java.util.Locale.ROOT);
        }
        if (mc.isInSingleplayer() && mc.getServer() != null) {
            return "singleplayer:" + mc.getServer().getSaveProperties().getLevelName();
        }
        return "unknown";
    }

    // Comparing

    private static boolean same(Box a, @Nullable Box b) {
        if (b == null || a.kind() != b.kind()) return false;
        if (a.name() == null ? b.name() != null : !a.name().equals(b.name())) return false;
        if (a.items().size() != b.items().size()) return false;

        for (int i = 0; i < a.items().size(); i++) {
            if (!ItemStack.areEqual(a.items().get(i), b.items().get(i))) return false;
        }
        return true;
    }

    private static List<ItemStack> copyOf(List<ItemStack> source, int size) {
        List<ItemStack> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(i < source.size() ? source.get(i).copy() : ItemStack.EMPTY);
        return out;
    }

    // Saving

    /**
     * Loads on first use, once there is a world to read the item registries from.
     *
     * Items are written with the game's own codec, the one it uses to save chests, so potions,
     * enchantments and everything else come back exactly as they went in. A file from before
     * chests were remembered is read the same way, every record in it being a shulker box.
     */
    private static boolean ensureLoaded() {
        if (loaded) return true;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return false;

        loaded = true;
        Path path = JJFolder.file(FILE);
        boolean fromOld = false;

        if (!Files.exists(path)) {
            Path old = JJFolder.file(OLD_FILE);
            if (!Files.exists(old)) return true;
            path = old;
            fromOld = true;
        }

        RegistryOps<JsonElement> ops = mc.world.getRegistryManager().getOps(JsonOps.INSTANCE);
        RegistryOps<NbtElement> nbtOps = mc.world.getRegistryManager().getOps(NbtOps.INSTANCE);

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    JsonObject box = entry.getValue().getAsJsonObject();
                    String name = box.has("name") ? box.get("name").getAsString() : null;

                    Kind kind = Kind.Shulker;
                    if (box.has("kind")) {
                        try {
                            kind = Kind.valueOf(box.get("kind").getAsString());
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }
                    }

                    JsonArray array = box.getAsJsonArray("items");
                    int size = array != null && array.size() > SIZE ? SIZE * 2 : SIZE;

                    List<ItemStack> items = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = ItemStack.EMPTY;
                        if (array != null && i < array.size()) {
                            stack = readStack(array.get(i), ops, nbtOps);
                        }
                        items.add(stack);
                    }

                    BOXES.put(entry.getKey(), new Box(name, items, kind));

                    // Already in the current format: kept exactly as read, so the first save
                    // after loading has nothing to convert.
                    // Items still written the old way, as JSON, are left out so they are
                    // converted to the item format the next time anything is saved.
                    boolean current = !fromOld;
                    if (current && array != null) {
                        for (JsonElement item : array) {
                            if (item.isJsonObject()) {
                                current = false;
                                break;
                            }
                        }
                    }
                    if (current) ENCODED.put(entry.getKey(), box);
                    else dirty = true;
                } catch (Exception ignored) {
                    // One unreadable record is not worth losing the rest over.
                }
            }
        } catch (Exception e) {
            System.err.println("[JJs Tools] Could not read " + path.getFileName() + ": " + e);
        }

        // Written straight out under the new name, so the old file is only ever read the once.
        if (fromOld) {
            dirty = true;
            save();
        }

        return true;
    }

    // Items

    /*
     * Items are kept in the game's own binary format, the one chests are saved to the world in,
     * written as text so they can sit in the JSON file.
     *
     * They used to go through the game's item codec straight into JSON. Most things came back,
     * but not everything: some of what an item can carry cannot be written as JSON and read back
     * the same, and an item that failed either way was quietly dropped, which is how a box of
     * armour would come back after a restart with the armour missing. The binary format is what
     * the codec was made for, so anything the game can save in a chest it can save here.
     *
     * Files from before are still read the old way. A box comes back right once it is opened
     * again and saved in the new format.
     */
    /** An item as text, the same way remembered containers are saved, for other stores to use. Empty for nothing. */
    public static String encodeStack(ItemStack stack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || stack.isEmpty()) return "";
        JsonElement json = writeStack(stack, mc.world.getRegistryManager().getOps(NbtOps.INSTANCE));
        return json.isJsonPrimitive() ? json.getAsString() : "";
    }

    /** An item back from encodeStack's text, or nothing if it cannot be read in this world. */
    public static ItemStack decodeStack(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || text == null || text.isEmpty()) return ItemStack.EMPTY;
        return readStack(new JsonPrimitive(text), mc.world.getRegistryManager().getOps(JsonOps.INSTANCE),
            mc.world.getRegistryManager().getOps(NbtOps.INSTANCE));
    }

    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    /** Says why an item could not be saved or read, once each, so a real problem shows in the log. */
    private static final java.util.Set<String> REPORTED = new java.util.HashSet<>();

    private static JsonElement writeStack(ItemStack stack, RegistryOps<NbtElement> nbtOps) {
        if (stack.isEmpty()) return new JsonPrimitive("");

        var result = ItemStack.CODEC.encodeStart(nbtOps, stack);
        NbtElement element = result.result().orElse(null);
        if (element == null) {
            report("save", stack, result.error().map(Object::toString).orElse("unknown"));
            // A shulker box is one item with everything inside it, so one item inside that the
            // game cannot write would lose the whole box. Saved again without just that item.
            element = withoutUnsavable(stack, nbtOps);
            if (element == null) return new JsonPrimitive("");
        }

        try {
            NbtCompound wrapper = new NbtCompound();
            wrapper.put("i", element);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompound(wrapper, new DataOutputStream(bytes));
            return new JsonPrimitive(B64.encodeToString(bytes.toByteArray()));
        } catch (Exception e) {
            report("save", stack, e.toString());
            return new JsonPrimitive("");
        }
    }

    /** A container item written out with the items inside it that cannot be written left out, or null. */
    private static @Nullable NbtElement withoutUnsavable(ItemStack stack, RegistryOps<NbtElement> nbtOps) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) return null;
        DefaultedList<ItemStack> inside = DefaultedList.ofSize(SIZE, ItemStack.EMPTY);
        container.copyTo(inside);
        for (int i = 0; i < inside.size(); i++) {
            ItemStack in = inside.get(i);
            if (!in.isEmpty() && ItemStack.CODEC.encodeStart(nbtOps, in).result().isEmpty()) inside.set(i, ItemStack.EMPTY);
        }
        ItemStack copy = stack.copy();
        copy.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(inside));
        return ItemStack.CODEC.encodeStart(nbtOps, copy).result().orElse(null);
    }

    private static ItemStack readStack(JsonElement json, RegistryOps<JsonElement> ops, RegistryOps<NbtElement> nbtOps) {
        // Older files: the item written out as JSON.
        if (json.isJsonObject()) {
            return ItemStack.OPTIONAL_CODEC.parse(ops, json).result().orElse(ItemStack.EMPTY);
        }

        String text = json.isJsonPrimitive() ? json.getAsString() : "";
        if (text.isEmpty()) return ItemStack.EMPTY;

        try {
            NbtCompound wrapper = NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(B64D.decode(text))));
            NbtElement element = wrapper.get("i");
            if (element == null) return ItemStack.EMPTY;

            var result = ItemStack.CODEC.parse(nbtOps, element);
            ItemStack stack = result.result().orElse(null);
            if (stack == null) {
                report("read", null, result.error().map(Object::toString).orElse("unknown"));
                return ItemStack.EMPTY;
            }
            return stack;
        } catch (Exception e) {
            report("read", null, e.toString());
            return ItemStack.EMPTY;
        }
    }

    private static void report(String what, @Nullable ItemStack stack, String why) {
        String item = stack == null ? "an item" : stack.getItem().toString();
        if (REPORTED.add(what + item + why)) {
            dev.jjstools.JJsTools.LOG.warn("Storage previews could not " + what + " " + item + ": " + why);
        }
    }

    /** Writes everything out, if anything changed. */
    public static void save() {
        save(false);
    }

    /**
     * Writes everything out. The records that changed are converted here, on the game's thread,
     * where the item registries can be read; the writing itself is handed to a thread of its own
     * unless now is set, which waits for it, for leaving a world.
     */
    private static void save(boolean now) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!loaded || mc == null) return;

        // The world's item registries, or the last ones seen: leaving a world can clear the
        // world before this is called, and saving nothing then lost whatever had changed since
        // the last save.
        if (mc.world != null) lastNbtOps = mc.world.getRegistryManager().getOps(NbtOps.INSTANCE);
        RegistryOps<NbtElement> nbtOps = lastNbtOps;
        if (nbtOps == null) return;
        JsonObject root = new JsonObject();

        for (Map.Entry<String, Box> entry : BOXES.entrySet()) {
            JsonObject box = ENCODED.get(entry.getKey());
            if (box == null) {
                box = encode(entry.getValue(), nbtOps);
                ENCODED.put(entry.getKey(), box);
            }
            // Saved forms are never changed once made, only replaced, so the writer can read
            // them while the game carries on.
            root.add(entry.getKey(), box);
        }

        dirty = false;
        lastSave = System.currentTimeMillis();

        if (now) write(root);
        else WRITER.execute(() -> write(root));
    }

    private static JsonObject encode(Box value, RegistryOps<NbtElement> nbtOps) {
        JsonObject box = new JsonObject();
        if (value.name() != null) box.addProperty("name", value.name());
        box.addProperty("kind", value.kind().name());

        JsonArray array = new JsonArray();
        for (ItemStack stack : value.items()) array.add(writeStack(stack, nbtOps));
        box.add("items", array);
        return box;
    }

    private static synchronized void write(JsonObject root) {
        Path path = JJFolder.file(FILE);
        try {
            Files.createDirectories(path.getParent());

            // Written beside the real file and moved over it, so a crash mid-write cannot leave
            // half a file where every remembered container used to be.
            Path temp = path.resolveSibling(FILE + ".tmp");
            String text = GSON.toJson(root);
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception moveFailed) {
                // Windows refuses to replace a file something else has open for a moment (an
                // antivirus scan, a backup tool). Tried again shortly, then written straight over
                // rather than failing every time and keeping nothing new.
                try {
                    Thread.sleep(200);
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception again) {
                    Files.writeString(path, text, StandardCharsets.UTF_8);
                    Files.deleteIfExists(temp);
                }
            }
        } catch (Exception e) {
            dev.jjstools.JJsTools.LOG.warn("Could not write " + FILE, e);
        }
    }

    /** Leaving a world: save what changed and forget anything half finished. */
    public static void onLeave() {
        if (dirty) save(true);
        openHandler = null;
        openKey = null;
        pendingPos = null;
        clickedPos = null;
    }
}
