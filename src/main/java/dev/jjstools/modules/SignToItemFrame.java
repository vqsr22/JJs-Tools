package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.HangingSignBlock;
import net.minecraft.block.WallHangingSignBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.TexturedRenderLayers;
//#if MC >= 1.21.5
import net.minecraft.client.render.model.BlockStateManagers;
import net.minecraft.client.render.model.BlockStateModel;
//#endif
//#if MC >= 1.21.9
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.block.entity.state.SignBlockEntityRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
//#else
//$$ import net.minecraft.block.entity.BlockEntity;
//$$ import net.minecraft.client.render.VertexConsumerProvider;
//$$ import net.minecraft.client.render.WorldRenderer;
//#endif
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.Item;
//#if MC >= 1.21.5
import net.minecraft.item.ItemDisplayContext;
//#endif
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.item.equipment.trim.ArmorTrim;
import net.minecraft.item.equipment.trim.ArmorTrimMaterial;
import net.minecraft.item.equipment.trim.ArmorTrimPattern;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.BlockView;
import dev.jjstools.util.BbeBridge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a sign into an item frame, for you only.
 *
 * Write an item's name on a sign and that item is drawn on its face: `Netherite Sword`, or
 * `Netherite Sword:enchanted` for one with the glint. Nobody else sees any of it and the sign
 * itself is untouched, so it works on any server and on signs you did not place.
 *
 * It exists because a wall of labelled signs is the usual way a stash is organised, and reading
 * sixteen of them is slower than glancing at sixteen items. The sign is still there underneath,
 * which is why the click can be handed on to whatever it is covering.
 *
 * Names are matched exactly as the game writes them, case and spaces included, rather than by
 * registry id. That is a deliberate trade: `Netherite Sword` is what you would write on a sign
 * anyway, and matching loosely would turn ordinary writing into items by accident.
 */
public class SignToItemFrame extends Module {
    public enum Frame { None, Normal, Glow }
    public enum Sign { Hidden, Blank, Shown }

    /** Which way round to spell the handful of items the two Englishes disagree about. */
    public enum Spelling { Both, American, British }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");

    private final Setting<Boolean> bothSides = sgGeneral.add(new BoolSetting.Builder()
        .name("both-sides")
        .description("Read the back of the sign too, not just the front.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Frame> frame = sgGeneral.add(new EnumSetting.Builder<Frame>()
        .name("frame")
        .description("Draw a real item frame around the item, the same model the entity uses.")
        .defaultValue(Frame.Glow)
        .build()
    );

    private final Setting<Sign> signVisible = sgGeneral.add(new EnumSetting.Builder<Sign>()
        .name("sign")
        .description("What to do with the sign underneath. Hidden takes it away completely, board and all, which is what you want with a frame drawn over it. Blank keeps the board and loses only the writing. Shown leaves it alone. Changing this rebuilds the chunks around you, so expect a moment's pause.")
        .defaultValue(Sign.Hidden)
        .onChanged(value -> rebuild())
        .build()
    );

    private final Setting<Spelling> spelling = sgGeneral.add(new EnumSetting.Builder<Spelling>()
        .name("spelling")
        .description("Which spelling to accept for the few items the two Englishes disagree about, which is Gray against Grey and Armor against Armour. Both takes either.")
        .defaultValue(Spelling.Both)
        .onChanged(value -> forgetNames())
        .build()
    );

    private final Setting<Boolean> clickThrough = sgGeneral.add(new BoolSetting.Builder()
        .name("click-through")
        .description("Right-clicking a sign that shows an item opens what is behind it instead. Only blocks that hold something, so a sign on a plain wall still does nothing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> removeHitbox = sgGeneral.add(new BoolSetting.Builder()
        .name("remove-hitbox")
        .description("Takes away the hitbox of a sign that shows an item, so your crosshair goes straight through it to the chest behind. Crosshair previews and clicks then land on the chest, and there is no outline around the sign. Turn it off when you want to break one of those signs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> frameSize = sgPlacement.add(new DoubleSetting.Builder()
        .name("frame-size")
        .description("How wide the frame is drawn, in blocks. A real item frame is one block across.")
        .defaultValue(1.0)
        .min(0.2).max(2.0).sliderRange(0.4, 1.2)
        .visible(() -> frame.get() != Frame.None)
        .build()
    );

    private final Setting<Double> frameDepth = sgPlacement.add(new DoubleSetting.Builder()
        .name("frame-depth")
        .description("Nudge the frame out from the wall, in blocks. Zero sits flat on it; the default sinks it a pixel in, which is where it stops fighting the block behind it.")
        .defaultValue(-0.063)
        .min(-1.0).max(1.0).sliderRange(-0.3, 0.3)
        .visible(() -> frame.get() != Frame.None)
        .build()
    );

    private final Setting<Double> itemSize = sgPlacement.add(new DoubleSetting.Builder()
        .name("item-size")
        .description("How tall the item is drawn, in blocks.")
        .defaultValue(0.55)
        .min(0.05)
        .max(2.0)
        .sliderRange(0.1, 1.2)
        .build()
    );

    private final Setting<Double> lift = sgPlacement.add(new DoubleSetting.Builder()
        .name("lift")
        .description("Nudge the item out from the board, in blocks. Zero sits on it; negative sinks it in.")
        .defaultValue(0.0)
        .min(-0.5)
        .max(0.5)
        .sliderRange(-0.2, 0.2)
        .build()
    );

    private final Setting<Double> offsetY = sgPlacement.add(new DoubleSetting.Builder()
        .name("height")
        .description("Nudge the item up or down the face of the sign, in blocks.")
        .defaultValue(0.0)
        .min(-1.0)
        .max(1.0)
        .sliderRange(-0.5, 0.5)
        .build()
    );

    /** Vanilla's own reach for blocks, matching Anti Item Frame. */
    private static final double REACH = 4.5;

    /** Every item in the game, by the name the game writes for it. Built once, in game. */
    private static volatile @Nullable Map<String, ItemStack> byName = null;

    /** The four things a potion can be, each of which names itself differently. */
    private static final Item[] POTION_ITEMS = {
        Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW
    };

    /** Parsed signs, because the renderer asks the same question of the same sign every frame. */
    private static final Map<String, ItemStack> CACHE = Collections.synchronizedMap(new HashMap<>());
    private static final int CACHE_LIMIT = 4096;

    /*
     * Two caches, both keyed by identity, both there because this runs per sign per frame.
     *
     * A wall of signs made that plain: every frame it was rebuilding the candidate strings for
     * every sign and then rebuilding an item model for each, and that is what ate the frame
     * rate. Sign text is immutable and replaced wholesale when a sign changes, so its identity
     * is a safe key, and the stacks come from the cache above so theirs is too.
     *
     * Both are reached from the chunk building threads as well as from the render thread, since
     * deciding whether to leave a sign board out of a chunk asks the same question, so both are
     * wrapped. The render state cache below is not: nothing but the render thread touches it.
     */
    private static final Map<SignText, ItemStack> BY_TEXT = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<ItemStack, ItemRenderState> STATES = new IdentityHashMap<>();
    /** The render thread's own copy of BY_TEXT, read without a lock. */
    private static final Map<SignText, ItemStack> RENDER_BY_TEXT = new IdentityHashMap<>();
    private static Thread renderThread;
    private static volatile int namesVersion;
    private static int renderNamesVersion;

    /** Stands in for "this text names nothing", so a miss is not worked out again every frame. */
    private static final ItemStack NOTHING = new ItemStack(Items.AIR);

    /** Set while a click is being handed through, so the pass-through cannot call itself. */
    private static boolean passingThrough = false;

    /** Four empty lines, which is what a sign with nothing written on it draws. */
    private static final SignText BLANK = new SignText(
        new Text[] { Text.empty(), Text.empty(), Text.empty(), Text.empty() },
        new Text[] { Text.empty(), Text.empty(), Text.empty(), Text.empty() },
        DyeColor.BLACK, false);

    /*
     * The sign whose writing is currently swapped out, and what it really says.
     *
     * Hiding the writing is done by handing vanilla a blank sign to draw rather than by stopping
     * it drawing, which keeps the board and loses only the letters. The swap is put back the
     * moment vanilla is finished with it, so nothing is left altered between frames: render
     * states may well be rebuilt every frame, but a module that only works if they are is a
     * module waiting to go wrong.
     *
     * Rendering is one thread, so one slot is enough.
     */
    //#if MC >= 1.21.9
    private static SignBlockEntityRenderState swapped;
    //#else
    //$$ private static SignBlockEntity swapped;
    //#endif
    private static SignText swappedFront;
    private static SignText swappedBack;

    public SignToItemFrame() {
        super(JJsTools.CATEGORY, "sign-to-item-frame", "Draws the item a sign names on the sign itself, like an item frame. Write the name exactly as the game spells it, over as many lines as it needs. Add :enchanted for the glint, :trim:gold or :trim:gold:silence for a trim, :name:Rainbow to pick a resource pack variant, and :x16 for a count. IMPORTANT, IF YOU USE BETTER BLOCK ENTITIES: its sign optimisation bakes signs into the terrain where this cannot change them, so the two cannot work together. While this module is on, BBE's sign optimisation is switched off automatically, and it is switched back on when you turn this off. Do not turn it back on in BBE's settings while this is on, or your signs will show as plain signs again.");
    }

    public static SignToItemFrame get() {
        return Modules.get().get(SignToItemFrame.class);
    }

    public static boolean on() {
        SignToItemFrame module = get();
        return module != null && module.isActive();
    }

    @Override
    public void onActivate() {
        // Before the rebuild below, so the chunks are built again with BBE leaving the signs alone.
        if (BbeBridge.suspendSigns()) {
            info("Turned off Better Block Entities' sign optimisation while this is on, since signs it optimises cannot be changed. It goes back on when you turn this off.");
        }
        clearCaches();
    }

    @Override
    public void onDeactivate() {
        BbeBridge.restoreSigns();
        clearCaches();
    }

    private static void clearCaches() {
        forgetNames();
        STATES.clear();
        rebuild();
    }

    // Reading the sign

    /**
     * The item this side of the sign names, or null.
     *
     * A name too long for one line is the normal case, not the exception: "Enchanted Golden
     * Apple" does not fit. So every run of consecutive lines is tried, longest first, which is
     * what lets a name be written across two or three lines and still be found. Longest first
     * matters: on a sign reading "Netherite" then "Sword", the short readings have to lose.
     */
    private static @Nullable ItemStack fromText(@Nullable SignText text) {
        if (text == null) return null;

        // The render thread asks for every sign every frame, so it has its own copy with no lock:
        // waiting on the shared one, which chunk building also uses, showed up in profiles.
        boolean render = Thread.currentThread() == renderThread;
        if (render) {
            if (renderNamesVersion != namesVersion) {
                renderNamesVersion = namesVersion;
                RENDER_BY_TEXT.clear();
            }
            ItemStack mine = RENDER_BY_TEXT.get(text);
            if (mine != null) return mine == NOTHING ? null : mine;
        }

        // One identity lookup stands in for rebuilding every candidate string again.
        ItemStack known = BY_TEXT.get(text);
        if (known == null) {
            ItemStack found = read(text);
            known = found == null ? NOTHING : found;
            if (BY_TEXT.size() >= CACHE_LIMIT) BY_TEXT.clear();
            BY_TEXT.put(text, known);
        }
        if (render) {
            if (RENDER_BY_TEXT.size() >= CACHE_LIMIT) RENDER_BY_TEXT.clear();
            RENDER_BY_TEXT.put(text, known);
        }
        return known == NOTHING ? null : known;
    }

    private static @Nullable ItemStack read(SignText text) {
        Text[] messages = text.getMessages(false);
        if (messages == null) return null;

        List<String> lines = new ArrayList<>();
        for (Text message : messages) lines.add(message == null ? "" : message.getString().trim());

        return match(lines);
    }

    /** Tries every run of lines, longest first, and returns the first that names an item. */
    private static @Nullable ItemStack match(List<String> lines) {

        for (int length = lines.size(); length >= 1; length--) {
            for (int start = 0; start + length <= lines.size(); start++) {
                StringBuilder joined = new StringBuilder();
                for (int i = start; i < start + length; i++) {
                    String line = lines.get(i);
                    if (line.isEmpty()) continue;
                    if (joined.length() > 0) joined.append(' ');
                    joined.append(line);
                }

                String candidate = joined.toString();
                if (candidate.isEmpty()) continue;

                ItemStack stack = parse(candidate);
                if (stack != null) return stack;
            }
        }

        return null;
    }

    /** One piece of text as an item, with anything after a colon applied to it. */
    public static @Nullable ItemStack parse(String candidate) {
        if (candidate == null || candidate.isEmpty()) return null;
        if (CACHE.containsKey(candidate)) return CACHE.get(candidate);

        ItemStack stack = build(candidate);
        if (CACHE.size() >= CACHE_LIMIT) CACHE.clear();
        CACHE.put(candidate, stack);
        return stack;
    }

    private static @Nullable ItemStack build(String candidate) {
        String name = candidate;
        List<String> modifiers = new ArrayList<>();

        // Split off the modifiers from the right, so an item whose own name has a colon in it
        // is still reachable by writing it out in full.
        while (true) {
            ItemStack known = names().get(name);
            if (known != null) {
                // A copy, because the modifiers below write to it and the table is shared.
                ItemStack stack = known.copy();

                // Collected right to left on the way in, so reversed here to read as written.
                List<String> words = new ArrayList<>(modifiers);
                Collections.reverse(words);

                for (int i = 0; i < words.size(); i++) {
                    /*
                     * A custom name, which is how a resource pack is told which of its
                     * variants to draw. Packs key their totems and the rest off the name on
                     * the item, so setting it here is what makes the right one appear.
                     */
                    if (words.get(i).equalsIgnoreCase("name") || words.get(i).equalsIgnoreCase("named")) {
                        if (i + 1 < words.size()) {
                            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(words.get(i + 1)));
                            i++;
                        }
                        continue;
                    }

                    if (words.get(i).equalsIgnoreCase("trim")) {
                        i += trim(stack,
                            i + 1 < words.size() ? words.get(i + 1) : null,
                            i + 2 < words.size() ? words.get(i + 2) : null);
                        continue;
                    }
                    modify(stack, words.get(i));
                }

                return stack;
            }

            int colon = name.lastIndexOf(':');
            if (colon < 0) return null;

            modifiers.add(name.substring(colon + 1).trim());
            name = name.substring(0, colon).trim();
        }
    }

    /**
     * Every item keyed by its written name.
     *
     * Built on first use rather than at startup, because the names come from the language file
     * and that is not loaded when the add-on initialises. Where two items somehow share a name
     * the first wins, which is stable because the registry iterates in a fixed order.
     */
    private static Map<String, ItemStack> names() {
        Map<String, ItemStack> known = byName;
        if (known != null) return known;

        SignToItemFrame module = get();
        Spelling how = module == null ? Spelling.Both : module.spelling.get();

        Map<String, ItemStack> built = new HashMap<>();

        Registries.ITEM.stream().forEach(item -> {
            if (item == Items.AIR) return;
            put(built, how, new ItemStack(item));
        });

        /*
         * Potions name themselves from what is in them, not from the item.
         *
         * Ask the potion item alone what it is called and it answers "Potion", because the
         * strength and the splash and the lingering all live in a component on the stack
         * rather than in the item. So every potion is built as an actual stack and asked for
         * its name, which is how "Potion of the Turtle Master" and "Splash Potion of Strength"
         * get into this table at all. Tipped arrows are the same story.
         */
        for (Potion potion : Registries.POTION) {
            PotionContentsComponent contents = new PotionContentsComponent(Registries.POTION.getEntry(potion));

            for (Item item : POTION_ITEMS) {
                ItemStack stack = new ItemStack(item);
                stack.set(DataComponentTypes.POTION_CONTENTS, contents);
                put(built, how, stack);
            }
        }

        byName = built;
        return built;
    }

    /**
     * Files one stack under the name or names it should answer to.
     *
     * The two spellings are each worked out from the game's own name rather than from one
     * another, so a swap cannot be applied twice and turn Armour into Armourur.
     */
    private static void put(Map<String, ItemStack> built, Spelling how, ItemStack stack) {
        String canonical = stack.getName().getString();
        String american = canonical.replace("Grey", "Gray").replace("Armour", "Armor");
        String british = canonical.replace("Gray", "Grey").replace("Armor", "Armour");

        switch (how) {
            case American -> built.putIfAbsent(american, stack);
            case British -> built.putIfAbsent(british, stack);
            case Both -> {
                built.putIfAbsent(canonical, stack);
                built.putIfAbsent(american, stack);
                built.putIfAbsent(british, stack);
            }
        }
    }

    /**
     * Throws away the name table and everything worked out from it.
     *
     * Needed whenever the names themselves could have changed: a different spelling asked for,
     * or a resource pack swapped, since an item's name comes from the language file and a pack
     * is free to rewrite it.
     */
    private static void forgetNames() {
        byName = null;
        CACHE.clear();
        BY_TEXT.clear();
        // The render thread empties its own copy when it next looks, as only it touches that.
        namesVersion++;
    }

    /** Every trim material and pattern the game ships, by the word you would write. */
    private static final Set<String> MATERIALS = words(CustomTrims.Material.values());
    private static final Set<String> PATTERNS = words(CustomTrims.Pattern.values());

    private static Set<String> words(Enum<?>[] values) {
        Set<String> set = new HashSet<>();
        for (Enum<?> value : values) set.add(value.name().toLowerCase(java.util.Locale.ROOT));
        return set;
    }

    /**
     * Reads a trim off the words after "trim" and puts it on the item.
     *
     * Written as `Netherite Chestplate:trim:gold`, and with a pattern after it if you want one,
     * as in `:trim:gold:silence`. The two are told apart by which list the word is in rather
     * than by which comes first, so writing the pattern first works just as well.
     *
     * Only the words it recognises are taken, and the count of them is handed back so the rest
     * of the line carries on being read as ordinary modifiers. A material on its own gets
     * Sentry, which is the plainest pattern and the one that reads as "just the colour".
     *
     * @return how many of the two words were used
     */
    private static int trim(ItemStack stack, @Nullable String first, @Nullable String second) {
        String material = null;
        String pattern = null;
        int used = 0;

        for (String word : new String[] { first, second }) {
            if (word == null) break;
            String lower = word.toLowerCase(java.util.Locale.ROOT);

            if (material == null && MATERIALS.contains(lower)) material = lower;
            else if (pattern == null && PATTERNS.contains(lower)) pattern = lower;
            else break;

            used++;
        }

        if (material != null) apply(stack, material, pattern == null ? "sentry" : pattern);
        return used;
    }

    /**
     * Looks the trim up and sets it.
     *
     * Trims live in the registries the server sends, not in the game's own, so a server that
     * has removed one simply has nothing to find and the item is left plain rather than the
     * whole name failing to match.
     */
    private static void apply(ItemStack stack, String material, String pattern) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return;

        try {
            Optional<RegistryEntry.Reference<ArmorTrimMaterial>> mat = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.TRIM_MATERIAL)
                .getOptional(RegistryKey.of(RegistryKeys.TRIM_MATERIAL, Identifier.ofVanilla(material)));

            Optional<RegistryEntry.Reference<ArmorTrimPattern>> pat = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.TRIM_PATTERN)
                .getOptional(RegistryKey.of(RegistryKeys.TRIM_PATTERN, Identifier.ofVanilla(pattern)));

            if (mat.isEmpty() || pat.isEmpty()) return;

            stack.set(DataComponentTypes.TRIM, new ArmorTrim(mat.get(), pat.get()));
        } catch (Throwable ignored) {
            // A registry that is not there yet is not worth a crash over.
        }
    }

    /** What the words after a colon do to the item. */
    private static void modify(ItemStack stack, String word) {
        String lower = word.toLowerCase(java.util.Locale.ROOT);

        switch (lower) {
            case "enchanted", "glint", "ench" ->
                // The glint comes from the override component rather than a real enchantment, so
                // nothing has to be looked up in a registry that may not be loaded yet.
                stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            default -> {
                if (lower.startsWith("x")) {
                    try {
                        stack.setCount(Math.max(1, Math.min(99, Integer.parseInt(lower.substring(1)))));
                    } catch (NumberFormatException ignored) {
                        // Not a count, so not a modifier this understands. Left alone.
                    }
                }
            }
        }
    }

    /**
     * Draws the item on a sign, in the same breath as the block entity it belongs to.
     *
     * Everything here is in blocks, because the matrices arrive in world space measured from
     * the camera. That is a change from the earlier version, which borrowed the sign renderer's
     * text space and had to divide its magnification back out; this is plainer and the numbers
     * in the settings mean what they say.
     */
    //#if MC >= 1.21.9
    /** Where the item and frame are sent to be drawn: the render queue from 1.21.9, a vertex consumer provider before. */
    private record Out(OrderedRenderCommandQueue queue) {}

    public static boolean onBlockEntity(BlockEntityRenderState state, MatrixStack matrices,
                                        OrderedRenderCommandQueue queue) {
        if (!on() || state == null || state.pos == null || state.blockState == null) return false;
        if (!(state.blockState.getBlock() instanceof AbstractSignBlock)) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;

        // The writing is already in the render state, so the sign is not looked up in the world.
        SignText frontText, backText;
        if (state instanceof SignBlockEntityRenderState signState && signState.frontText != null) {
            frontText = signState.frontText;
            backText = signState.backText;
        } else if (mc.world.getBlockEntity(state.pos) instanceof SignBlockEntity sign) {
            frontText = sign.getText(true);
            backText = sign.getText(false);
        } else {
            return false;
        }

        Shown shown = draw(state.blockState, frontText, backText, state.lightmapCoordinates, matrices, new Out(queue));
        if (shown == null) return false;
        if (shown.hide()) return true;

        if (shown.blank() && state instanceof SignBlockEntityRenderState sign2) {
            swapped = sign2;
            swappedFront = sign2.frontText;
            swappedBack = sign2.backText;

            // Only the side showing an item loses its writing. A label on the back of a sign
            // whose front is an item is still a label.
            if (shown.front()) sign2.frontText = BLANK;
            if (shown.back()) sign2.backText = BLANK;
        }

        return false;
    }

    /** Puts the writing back, once vanilla has drawn the sign without it. */
    public static void afterBlockEntity(BlockEntityRenderState state) {
        if (swapped == null || swapped != state) return;

        swapped.frontText = swappedFront;
        swapped.backText = swappedBack;

        swapped = null;
        swappedFront = null;
        swappedBack = null;
    }
    //#else
    //$$ /** Where the item and frame are sent to be drawn: the render queue from 1.21.9, a vertex consumer provider before. */
    //$$ private record Out(VertexConsumerProvider vertices) {}
    //$$
    //$$ /** As above, for versions before render states: the sign itself is drawn, so its own writing is swapped. */
    //$$ public static boolean onBlockEntity(BlockEntity blockEntity, MatrixStack matrices, VertexConsumerProvider vertices) {
    //$$     if (!on() || !(blockEntity instanceof SignBlockEntity sign) || sign.getWorld() == null) return false;
    //$$     BlockState blockState = sign.getCachedState();
    //$$     if (!(blockState.getBlock() instanceof AbstractSignBlock)) return false;
    //$$
    //$$     int light = WorldRenderer.getLightmapCoordinates(sign.getWorld(), sign.getPos());
    //$$     Shown shown = draw(blockState, sign.getText(true), sign.getText(false), light, matrices, new Out(vertices));
    //$$     if (shown == null) return false;
    //$$     if (shown.hide()) return true;
    //$$
    //$$     if (shown.blank()) {
    //$$         var access = (dev.jjstools.mixin.SignBlockEntityAccessor) sign;
    //$$         swapped = sign;
    //$$         swappedFront = access.jjstools$getFrontText();
    //$$         swappedBack = access.jjstools$getBackText();
    //$$         if (shown.front()) access.jjstools$setFrontText(BLANK);
    //$$         if (shown.back()) access.jjstools$setBackText(BLANK);
    //$$     }
    //$$     return false;
    //$$ }
    //$$
    //$$ /** Puts the writing back, once vanilla has drawn the sign without it. */
    //$$ public static void afterBlockEntity(BlockEntity blockEntity) {
    //$$     if (swapped == null || swapped != blockEntity) return;
    //$$
    //$$     var access = (dev.jjstools.mixin.SignBlockEntityAccessor) swapped;
    //$$     access.jjstools$setFrontText(swappedFront);
    //$$     access.jjstools$setBackText(swappedBack);
    //$$
    //$$     swapped = null;
    //$$     swappedFront = null;
    //$$     swappedBack = null;
    //$$ }
    //#endif

    /** What was drawn on a sign: which sides show an item, and what to do with the sign itself. */
    private record Shown(boolean front, boolean back, boolean hide, boolean blank) {}

    /** Draws the item on whichever sides name one; null when neither does. */
    private static Shown draw(BlockState blockState, SignText frontText, SignText backText, int light,
                              MatrixStack matrices, Out sink) {
        if (renderThread == null) renderThread = Thread.currentThread();

        SignToItemFrame module = get();
        if (module == null) return null;

        ItemStack front = fromText(frontText);
        ItemStack back = module.bothSides.get() ? fromText(backText) : null;
        if (front == null && back == null) return null;

        float yaw = faceYaw(blockState);

        /*
         * Where the board actually is, taken from the numbers vanilla's own sign renderer uses
         * rather than from guesswork.
         *
         * Vanilla stands at the middle of the block, turns to face the writing, and then, for a
         * wall sign only, steps back 0.4375 to the wall and down to 0.4375 high. A standing
         * sign's board is already in the middle of its block at 0.75 high. The writing itself
         * is then drawn about 0.07 out from the board's middle, which is the number that puts
         * the item on the face instead of inside it.
         *
         * Using one depth for every kind of sign is what put the item a block out in front of
         * everything: a wall sign was being pushed forward half a block when it should have
         * been pulled back nearly half a block.
         */
        var block = blockState.getBlock();
        boolean wall = block instanceof WallSignBlock;
        boolean hanging = block instanceof WallHangingSignBlock || block instanceof HangingSignBlock;

        boolean framed = module.frame.get() != Frame.None;

        /*
         * Height, and then how far out along the facing.
         *
         * A frame is not a sign and does not want the sign's numbers. A real item frame hangs
         * flat on the block behind it, in the middle of the space in front of it, so with a
         * frame drawn the anchor is the wall itself and the middle of the block. Without one
         * the item goes on the board, where the writing would have been.
         */
        double centre = (framed ? 0.5 : wall ? 0.4375 : hanging ? 0.31 : 0.75) + module.offsetY.get();

        // The middle of the board, and then out from it to the face being read. The two are
        // kept apart because the back of a sign mirrors the first and not the second.
        double board = wall ? -0.4375 : 0.0;
        double out = 0.07;

        // Where the back of the frame sits: flat against the wall for a wall sign, and on the
        // board's own back for a sign standing in the open, which has no wall to hang on.
        double frameBack = (wall ? -0.5 : board - 0.03) + module.frameDepth.get();

        /*
         * The item goes on the frame, not on the sign.
         *
         * The frame's plate is half a pixel thick, so clearing it takes 0.031, and then half the
         * item's own thickness again to stop it being drawn half inside. Anchoring the item to
         * the board instead is what left it floating in front of the frame.
         */
        double itemDepth = (framed ? frameBack + 0.044 : board + out) + module.lift.get();

        if (front != null) face(module, matrices, sink, light, yaw, centre, itemDepth, frameBack, front);
        if (back != null) face(module, matrices, sink, light, yaw + 180.0F, centre,
            framed ? itemDepth : -board + out + module.lift.get(), -frameBack, back);

        return new Shown(front != null, back != null, module.signVisible.get() == Sign.Hidden,
            module.signVisible.get() == Sign.Blank);
    }

    /**
     * Whether this sign's board should be left out of the chunk entirely.
     *
     * Called while a chunk is being built, which is not the render thread and not necessarily
     * one thread, so this reads from the world snapshot it is handed rather than from the live
     * client world, and everything it touches on the way is shared safely.
     *
     * A mesh is built once and kept, so an answer here lasts until that chunk is built again.
     * Toggling the module asks the renderer to start over for exactly that reason.
     */
    public static boolean hideBoard(BlockState blockState, BlockPos pos, BlockRenderView world) {
        if (!on() || blockState == null || pos == null || world == null) return false;
        if (!(blockState.getBlock() instanceof AbstractSignBlock)) return false;

        SignToItemFrame module = get();
        if (module == null || module.signVisible.get() != Sign.Hidden) return false;

        if (!(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) return false;

        if (fromText(sign.getText(true)) != null) return true;
        return module.bothSides.get() && fromText(sign.getText(false)) != null;
    }

    /**
     * Asks the renderer to build its chunks again.
     *
     * Needed whenever the answer hideBoard would give changes, because the old answer is already
     * baked into every mesh around you. It is not cheap, which is why it is only done when the
     * thing that decides it changes rather than on any setting at all.
     */
    private static void rebuild() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.world != null && mc.worldRenderer != null) mc.worldRenderer.reload();
    }

    /** The compass direction the writing faces, in degrees. */
    /**
     * A turn about the up axis, straight on the matrices.
     *
     * The usual way builds a rotation and multiplies it in as a general turn, which was most of
     * this module's cost in a profile: with a few signs on screen it ran thousands of times a
     * second. A turn about one axis is far cheaper to apply as just that.
     */
    private static void turnY(MatrixStack matrices, float degrees) {
        float radians = (float) Math.toRadians(degrees);
        MatrixStack.Entry entry = matrices.peek();
        entry.getPositionMatrix().rotateY(radians);
        entry.getNormalMatrix().rotateY(radians);
    }

    //#if MC >= 1.21.5
    /** The two item frame states, looked up once rather than built from the state table every sign, every frame. */
    private static BlockState frameState, glowFrameState;

    private static BlockState frameState(boolean glow) {
        if (glow) {
            if (glowFrameState == null) glowFrameState = BlockStateManagers.getStateForItemFrame(true, false);
            return glowFrameState;
        }
        if (frameState == null) frameState = BlockStateManagers.getStateForItemFrame(false, false);
        return frameState;
    }
    //#endif

    /** Which way each sign state faces, worked out once per state; states are shared, so this stays small. */
    private static final Map<BlockState, Float> YAWS = new IdentityHashMap<>();

    private static float faceYaw(BlockState blockState) {
        Float known = YAWS.get(blockState);
        if (known != null) return known;
        float yaw = workOutYaw(blockState);
        YAWS.put(blockState, yaw);
        return yaw;
    }

    private static float workOutYaw(BlockState blockState) {
        if (blockState.contains(Properties.ROTATION)) {
            return blockState.get(Properties.ROTATION) * 360.0F / 16.0F;
        }
        if (blockState.contains(Properties.HORIZONTAL_FACING)) {
            return blockState.get(Properties.HORIZONTAL_FACING).getPositiveHorizontalDegrees();
        }
        return 0.0F;
    }

    /** One side of one sign: out to the middle of the block, turned to match it, then out to the board. */
    private static void face(SignToItemFrame module, MatrixStack matrices, Out queue,
                             int light, float yaw, double centre, double depth,
                             double frameBack, ItemStack stack) {
        MinecraftClient mc = MinecraftClient.getInstance();

        /*
         * No camera in this: the matrices arrive already standing at the block.
         *
         * The first attempt subtracted the camera position as well, which is right when you
         * draw from the world origin and wrong here. The giveaway was that the item slid about
         * as you walked, since the error was the camera offset itself. Everything below is
         * inside the block, nought to one.
         */
        matrices.push();
        matrices.translate(0.5, centre, 0.5);
        turnY(matrices, -yaw);

        // Out to the board.
        matrices.translate(0.0, 0.0, depth);

        Frame style = module.frame.get();
        if (style != Frame.None) {
            // The frame hangs off the wall, the item off the frame, so the two are placed from
            // their own anchors rather than one from the other.
            matrices.push();
            matrices.translate(0.0, 0.0, frameBack - depth);
            frame(module, mc, matrices, queue, style, light);
            matrices.pop();
        }

        render(mc, matrices, queue, light, stack, module.itemSize.get().floatValue());
        matrices.pop();
    }

    /**
     * Draws a real item frame, the same model the entity uses.
     *
     * An item frame is not a block, so there was nothing obvious to ask for its model. It turns
     * out the game keeps one anyway: it registers a state manager for frames purely so their
     * models can be looked up the way a block's is, and a frame is then an ordinary block state
     * model from that point on. Rendering an item frame item instead, which is the thing that
     * looks like it should work, only ever gives you the flat inventory icon.
     *
     * The model sits at the far face of its own block and faces the other way to everything
     * here, hence the turn and the step back before it is drawn. The depth setting is there
     * because that is read off the model's own geometry rather than measured in game.
     */
    private static void frame(SignToItemFrame module, MinecraftClient mc, MatrixStack matrices,
                              Out queue, Frame style, int light) {
        //#if MC >= 1.21.5
        BlockStateModel model = mc.getBlockRenderManager()
            .getModel(frameState(style == Frame.Glow));
        //#else
        //$$ // 1.21.4 keeps the frame models by id rather than as block states.
        //$$ var model = mc.getBakedModelManager().getModel(style == Frame.Glow
        //$$     ? net.minecraft.client.render.model.BlockStatesLoader.GLOW_ITEM_FRAME_MODEL_ID
        //$$     : net.minecraft.client.render.model.BlockStatesLoader.ITEM_FRAME_MODEL_ID);
        //#endif
        if (model == null) return;

        // A glow frame lights its own contents, the same as the entity does.
        int frameLight = style == Frame.Glow ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light;

        float size = module.frameSize.get().floatValue();

        matrices.push();
        turnY(matrices, 180.0F);
        matrices.scale(size, size, 1.0F);

        /*
         * The model fills its own block with the plate as a sliver at the far face, and it is
         * built facing the other way to everything here, which is what the turn above is for.
         * A whole block back from that lands the plate's back exactly on the anchor, so the
         * frame sits flat on the wall with only its own thickness standing proud, the same as
         * a real one.
         */
        matrices.translate(-0.5, -0.5, -1.0);

        //#if MC >= 1.21.9
        queue.queue().getBatchingQueue(0).submitBlockStateModel(matrices, TexturedRenderLayers.getEntitySolid(),
            model, 1.0F, 1.0F, 1.0F, frameLight, OverlayTexture.DEFAULT_UV, 0);
        //#elif MC >= 1.21.5
        //$$ net.minecraft.client.render.block.BlockModelRenderer.render(matrices.peek(),
        //$$     queue.vertices().getBuffer(TexturedRenderLayers.getEntitySolid()), model, 1.0F, 1.0F, 1.0F,
        //$$     frameLight, OverlayTexture.DEFAULT_UV);
        //#else
        //$$ mc.getBlockRenderManager().getModelRenderer().render(matrices.peek(),
        //$$     queue.vertices().getBuffer(TexturedRenderLayers.getEntitySolid()), null, model, 1.0F, 1.0F, 1.0F,
        //$$     frameLight, OverlayTexture.DEFAULT_UV);
        //#endif
        matrices.pop();
    }

    private static void render(MinecraftClient mc, MatrixStack matrices, Out queue,
                               int light, ItemStack stack, float size) {
        ItemRenderState render = STATES.get(stack);
        if (render == null) {
            render = new ItemRenderState();
            //#if MC >= 1.21.5
            mc.getItemModelManager().update(render, stack, ItemDisplayContext.FIXED, mc.world, null, 0);
            //#else
            //$$ mc.getItemModelManager().update(render, stack, net.minecraft.item.ModelTransformationMode.FIXED, mc.world, null, 0);
            //#endif
            if (STATES.size() >= CACHE_LIMIT) STATES.clear();
            STATES.put(stack, render);
        }
        if (render.isEmpty()) return;

        /*
         * Turned to face you, which is what fixes the item coming out mirrored.
         *
         * The fixed display context is the one an item frame uses, and it carries a half turn
         * of its own: an item drawn in it faces away from whoever placed the frame. For a flat
         * item like a sword that is a sprite with no thickness, so seeing its back is
         * indistinguishable from seeing it flipped left to right, which is what it looked like.
         * The same half turn the frame needs undoes it.
         */
        matrices.push();
        turnY(matrices, 180.0F);
        matrices.scale(size, size, size);
        //#if MC >= 1.21.9
        render.render(matrices, queue.queue(), light, OverlayTexture.DEFAULT_UV, 0);
        //#else
        //$$ render.render(matrices, queue.vertices(), light, OverlayTexture.DEFAULT_UV);
        //#endif
        matrices.pop();
    }

    // Clicking through

    /**
     * Opens what the sign is covering, or returns false to let the click land normally.
     *
     * A sign is a block rather than an entity, so the ray cannot simply be fired again with
     * entities left out: it would hit the same sign. It is walked forward instead, a step at a
     * time, skipping signs, until it reaches something that holds items.
     */
    public static boolean clickThrough(BlockHitResult hit, Hand hand) {
        if (passingThrough) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (mc.currentScreen != null) return false;

        SignToItemFrame module = get();
        if (module == null || !module.isActive() || !module.clickThrough.get()) return false;

        BlockPos signPos = hit.getBlockPos();
        if (!(mc.world.getBlockState(signPos).getBlock() instanceof AbstractSignBlock)) return false;
        if (!showsItem(mc, signPos)) return false;

        BlockPos behind = firstContainerBehind(mc, signPos);
        if (behind == null) return false;

        BlockHitResult target = new BlockHitResult(
            Vec3d.ofCenter(behind), hit.getSide().getOpposite(), behind, false);

        passingThrough = true;
        try {
            mc.interactionManager.interactBlock(mc.player, hand, target);
        } finally {
            passingThrough = false;
        }

        return true;
    }

    /**
     * Whether this sign should have no hitbox: it shows an item and remove-hitbox is on.
     *
     * Asked whenever the game wants a block's outline, which includes the server thread in
     * singleplayer. Only the client's own aiming should go through the sign, so anything not on
     * the client thread gets the real shape. The world it is handed is read rather than the live
     * client world, since that is the one the question is about.
     */
    public static boolean passThrough(BlockState state, BlockPos pos, BlockView world) {
        if (!(state.getBlock() instanceof AbstractSignBlock) || pos == null || world == null) return false;

        if (Modules.get() == null) return false;
        SignToItemFrame module = get();
        if (module == null || !module.isActive() || !module.removeHitbox.get()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !mc.isOnThread()) return false;

        if (!(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) return false;
        if (fromText(sign.getText(true)) != null) return true;
        return module.bothSides.get() && fromText(sign.getText(false)) != null;
    }

    /** True if this sign is one the module has turned into an item. */
    private static boolean showsItem(MinecraftClient mc, BlockPos pos) {
        if (!(mc.world.getBlockEntity(pos) instanceof SignBlockEntity sign)) return false;
        return fromText(sign.getText(true)) != null || fromText(sign.getText(false)) != null;
    }

    /**
     * The first block holding something, walking the line of sight on from the sign.
     *
     * Only blocks with a block entity count, for the same reason as Anti Item Frame: anything
     * else and a block in your hand would be placed against the wall, which is a worse accident
     * than the click doing nothing.
     */
    private static @Nullable BlockPos firstContainerBehind(MinecraftClient mc, BlockPos signPos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0F);

        BlockPos last = null;
        for (double d = 0.0; d <= REACH; d += 0.1) {
            BlockPos pos = BlockPos.ofFloored(eye.add(look.multiply(d)));
            if (pos.equals(last)) continue;
            last = pos;

            if (pos.equals(signPos)) continue;
            if (mc.world.getBlockState(pos).getBlock() instanceof AbstractSignBlock) continue;
            if (mc.world.isAir(pos)) continue;

            // The first solid thing decides it: a container opens, anything else stops the walk
            // so the click is not passed through a wall to whatever is on the far side.
            return mc.world.getBlockEntity(pos) != null ? pos : null;
        }

        return null;
    }
}
