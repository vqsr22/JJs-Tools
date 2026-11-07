package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import dev.jjstools.mixin.accessor.BlockItemInvoker;
import dev.jjstools.util.HeldRotation;
import dev.jjstools.util.LitematicaBridge;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.Dir;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds your Litematica schematics for you, as you walk past them.
 *
 * Every tick it looks at the blocks around you, asks Litematica what the schematic wants in each,
 * and places what is missing, nearest first. Wherever a schematic is placed is where it prints:
 * there is nothing to load or set up here.
 *
 * How it keeps up its speed:
 *  - Blocks of one kind go down together. The block is put in your hand once and every spot
 *    in reach that wants it is placed from it in the same tick, rather than swapping for each.
 *  - By default it clicks the spot itself instead of a block next to it (air placing), at the
 *    point of it nearest your eyes, so there is no hunting for a neighbour to click and
 *    nothing in the way of a block going anywhere it can reach.
 *  - It only turns to face a block when the block cares which way you face. Turning is a
 *    packet of its own, so a stone wall goes down without any.
 *  - A spot it has just placed is left alone for a few ticks while the server answers, so
 *    nothing is ever placed twice and no clicks are wasted on a block already on its way.
 *  - However many it could place, it stops at a set number of clicks a tick, under the point
 *    where servers start kicking for it.
 *
 * Getting a block the right way round is worked out by asking the block itself: for each face
 * it could click, where on that face and which way to look, it works out what the block would
 * become if placed like that, the way the game does, and uses the first that gives what the
 * schematic wants. What it found for each kind of block is kept, so the next one of the same
 * kind needs a single check. Anything shaped by its neighbours afterwards, such as stair corners
 * or fence connections, is left for the game to sort out, as it does for a person.
 *
 * Needs Litematica installed. Written for JJ's Tools.
 */
public class PrinterGoBrrr extends Module {
    public enum PlaceMode { AirPlace, Neighbour }
    public enum RotateMode { Rotate, None }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacing = settings.createGroup("Placing");
    private final SettingGroup sgSound = settings.createGroup("Sound");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgMisplaced = settings.createGroup("Render Misplaced");

    // General

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("How far away it will place, in blocks, from your eyes to the point it clicks. Servers refuse much past 4.5.")
        .defaultValue(4.5).min(1.0).max(6.0).sliderRange(1.0, 6.0).build());

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick").description("Most blocks placed in one tick. Eight is about where servers start to count it as too many.")
        .defaultValue(8).min(1).max(20).sliderRange(1, 12).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("burst-delay").description("Ticks to wait after each burst of placing before the next. It places a burst of blocks in one tick, waits this long for the server to catch up, then goes again. 0 places every tick.")
        .defaultValue(2).min(0).max(40).sliderRange(0, 10).build());

    private final Setting<Integer> retryAfter = sgGeneral.add(new IntSetting.Builder()
        .name("retry-after").description("Ticks to leave a spot alone after placing there, while the server answers, before trying it again if the block did not appear.")
        .defaultValue(5).min(1).max(40).sliderRange(1, 20).build());

    private final Setting<Boolean> layers = sgGeneral.add(new BoolSetting.Builder()
        .name("only-shown-layers").description("Only print the layers Litematica is set to show.")
        .defaultValue(true).build());

    private final Setting<Boolean> pauseWhileEating = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-while-eating").description("Stop while you eat, drink or use an item, and while you hold food you can eat, so it never swaps the food out of your hand mid bite. Works with other clients' auto eat, such as RusherHack's.")
        .defaultValue(true).build());

    private final Setting<Integer> eatGrace = sgGeneral.add(new IntSetting.Builder()
        .name("eat-grace").description("Ticks to keep waiting after eating stops, so an auto eat that takes a tick or two between bites is not interrupted.")
        .defaultValue(0).min(0).max(40).sliderRange(0, 20).visible(pauseWhileEating::get).build());

    private final Setting<List<Block>> ignored = sgGeneral.add(new BlockListSetting.Builder()
        .name("ignored-blocks").description("Blocks it never places. Starts with blocks you cannot get in survival, so a schematic with them in never holds the printer up.")
        .defaultValue(Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.END_PORTAL_FRAME, Blocks.BEDROCK, Blocks.BARRIER,
            Blocks.LIGHT, Blocks.STRUCTURE_VOID, Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW, Blocks.COMMAND_BLOCK,
            Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.REINFORCED_DEEPSLATE, Blocks.BUDDING_AMETHYST,
            Blocks.PETRIFIED_OAK_SLAB)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug").description("Explain in chat why blocks are not being placed: no block to place, out of reach, no click that gives the right result, and so on. Each reason is said once every few seconds.")
        .defaultValue(false).build());

    // Placing

    private final Setting<PlaceMode> placeMode = sgPlacing.add(new EnumSetting.Builder<PlaceMode>()
        .name("place-mode").description("Air Place clicks the spot itself, the quickest way and fine on 2b2t. Neighbour clicks a block next to the spot, the way a person has to, for servers that do not allow air placing.")
        .defaultValue(PlaceMode.AirPlace).build());

    private final Setting<RotateMode> rotate = sgPlacing.add(new EnumSetting.Builder<RotateMode>()
        .name("rotations").description("How it faces blocks that care which way you face. Rotate turns once a burst, the way a player turns, and places every block that comes out right facing that way; the rest wait for a later burst. None never turns, so those blocks only go down when your own view gets them right.")
        .defaultValue(RotateMode.Rotate).build());

    private final Setting<Integer> rotationDelay = sgPlacing.add(new IntSetting.Builder()
        .name("rotation-delay").description("Ticks to hold a new rotation before placing with it, for servers that are slow to take it in. 0 places in the same tick it turns.")
        .defaultValue(4).min(0).max(10).sliderRange(0, 5).visible(() -> rotate.get() == RotateMode.Rotate).build());

    private final Setting<Boolean> visibleFaces = sgPlacing.add(new BoolSetting.Builder()
        .name("only-visible-faces").description("Only click faces you could see from where your eyes are, never the underside of a block you are standing on and the like. Anticheats such as Grim count clicks on hidden faces against you.")
        .defaultValue(false).build());

    private final Setting<Boolean> fromInventory = sgPlacing.add(new BoolSetting.Builder()
        .name("use-inventory").description("Take blocks from your whole inventory, swapping them into a hotbar slot, not only from the hotbar.")
        .defaultValue(true).build());

    private final Setting<Integer> swapSlots = sgPlacing.add(new IntSetting.Builder()
        .name("swap-slots").description("How many hotbar slots, counting left from slot 9, it may bring blocks from your inventory into. With more, blocks it brought in stay ready for next time instead of being swapped back and forth. It never uses the slot you are holding.")
        .defaultValue(4).min(1).max(9).sliderRange(1, 9).visible(fromInventory::get).build());

    private final Setting<Integer> inventoryDelay = sgPlacing.add(new IntSetting.Builder()
        .name("inventory-swap-delay").description("Ticks between bringing blocks in from your inventory, one at a time. Moving them too often is what gets you kicked.")
        .defaultValue(5).min(1).max(40).sliderRange(1, 20).visible(fromInventory::get).build());

    private final Setting<Boolean> gravityCheck = sgPlacing.add(new BoolSetting.Builder()
        .name("gravity-check").description("Wait to place sand, gravel and the like until there is something under them, so they do not fall.")
        .defaultValue(true).build());

    private final Setting<Boolean> swing = sgPlacing.add(new BoolSetting.Builder()
        .name("swing").description("Swing your hand for each block.")
        .defaultValue(true).build());

    private final Setting<Boolean> signText = sgPlacing.add(new BoolSetting.Builder()
        .name("sign-text").description("Write the schematic's text on the front of each sign it places, instead of leaving the sign editor open.")
        .defaultValue(true).build());

    private final Setting<Boolean> flipBlocks = sgPlacing.add(new BoolSetting.Builder()
        .name("set-correct-state").description("Click blocks into the state the schematic has them in: trapdoors, doors and fence gates open or shut, levers on or off, repeater delays, comparator modes, daylight detectors and note block notes. Fence gates are opened from the side that keeps them facing the right way. Not while you are sneaking.")
        .defaultValue(true).build());

    // Sound

    private final Setting<Boolean> sounds = sgSound.add(new BoolSetting.Builder()
        .name("place-sounds").description("Play each block's place sound as it goes down, so printing is not silent.")
        .defaultValue(true).build());

    private final Setting<Integer> volume = sgSound.add(new IntSetting.Builder()
        .name("sound-volume").description("How loud the place sounds are, from 0 to 100. 100 is as loud as placing by hand.")
        .defaultValue(100).min(0).max(100).sliderRange(0, 100).visible(sounds::get).build());

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Outline the blocks that are still to be placed, through walls and in the shape each will be: a slab as a slab, a stair as a stair.")
        .defaultValue(true).build());

    private final Setting<Boolean> renderWhenOff = sgRender.add(new BoolSetting.Builder()
        .name("render-when-off").description("Keep outlining blocks still to be placed while Printer Go Brrr is switched off.")
        .defaultValue(false).visible(render::get).build());

    private final Setting<Integer> renderRange = sgRender.add(new IntSetting.Builder()
        .name("render-range").description("How far around you to outline blocks still to be placed. Large builds are checked a slice at a time, so far away blocks can take a moment to update.")
        .defaultValue(8).min(4).max(256).sliderRange(8, 128).visible(render::get).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Lines).visible(render::get).build());

    private final Setting<SettingColor> sideColour = sgRender.add(new ColorSetting.Builder()
        .name("side-colour").defaultValue(new SettingColor(255, 255, 255, 0)).visible(render::get).build());

    private final Setting<SettingColor> lineColour = sgRender.add(new ColorSetting.Builder()
        .name("line-colour").defaultValue(new SettingColor(255, 255, 255, 255)).visible(render::get).build());

    // Render misplaced

    private final Setting<Boolean> showMisplaced = sgMisplaced.add(new BoolSetting.Builder()
        .name("render-misplaced").description("Outline blocks that are the wrong block or facing the wrong way, through walls, so you can find and fix them. The printer never breaks anything, so these are left for you.")
        .defaultValue(true).build());

    private final Setting<Boolean> misplacedWhenOff = sgMisplaced.add(new BoolSetting.Builder()
        .name("misplaced-when-off").description("Keep outlining misplaced blocks while Printer Go Brrr is switched off, so you can check a build without printing.")
        .defaultValue(false).visible(showMisplaced::get).build());

    private final Setting<Integer> misplacedRange = sgMisplaced.add(new IntSetting.Builder()
        .name("misplaced-range").description("How far around you to outline misplaced blocks.")
        .defaultValue(128).min(4).max(256).sliderRange(8, 128).visible(showMisplaced::get).build());

    private final Setting<Boolean> showExtra = sgMisplaced.add(new BoolSetting.Builder()
        .name("render-blocks-in-air").description("Also outline blocks sitting where the schematic has air, such as a stray block left in a doorway or scaffolding you forgot to take down. Only inside your schematics. Grass, snow and other blocks you can place over are not counted.")
        .defaultValue(false).visible(showMisplaced::get).build());

    private final Setting<ShapeMode> misplacedShape = sgMisplaced.add(new EnumSetting.Builder<ShapeMode>()
        .name("misplaced-shape-mode").defaultValue(ShapeMode.Both).visible(showMisplaced::get).build());

    private final Setting<SettingColor> misplacedSide = sgMisplaced.add(new ColorSetting.Builder()
        .name("misplaced-side-colour").description("Fill of misplaced blocks. Clear by default so you can still see the block.")
        .defaultValue(new SettingColor(255, 0, 0, 0)).visible(showMisplaced::get).build());

    private final Setting<SettingColor> misplacedLine = sgMisplaced.add(new ColorSetting.Builder()
        .name("misplaced-line-colour").defaultValue(new SettingColor(255, 0, 0, 255)).visible(showMisplaced::get).build());

    public PrinterGoBrrr() {
        super(JJsTools.CATEGORY, "printer-go-brrr", "Automatically places blocks of Litematica schematics.");
        MeteorClient.EVENT_BUS.subscribe(esp);
        // Toggled with Z unless you bind it to something else.
        keybind.set(true, org.lwjgl.glfw.GLFW.GLFW_KEY_Z, 0);
    }

    private int ticks;
    private int timer;
    private boolean warned;

    /** Spots still to place nearby, nearest first. */
    private final List<BlockPos> missing = new ArrayList<>();

    /** Placed blocks still to be clicked into the right state, and how many clicks each needs. */
    private record Flip(BlockPos pos, int clicks) {}
    private final List<Flip> flips = new ArrayList<>();

    /** Outlines missing and misplaced blocks, whether or not the printer is on. */
    private final Esp esp = new Esp();

    /** Spots placed recently, with the tick they were placed, left alone until the server has answered. */
    private final Map<BlockPos, Integer> pending = new HashMap<>();

    /** What worked for each kind of block, so the next one of that kind needs only checking. */
    private final Map<BlockState, Strategy> strategies = new HashMap<>();

    /** The tick each hotbar slot was last placed from or filled, so the least used one is refilled first. */
    private final int[] slotUsed = new int[9];

    /** The tick you were last seen eating or about to, for the pause while eating. */
    private int lastEat = -1000;

    /** The tick blocks were last brought in from the inventory. */
    private int lastMove = -1000;

    /** A burst is waiting for its rotation to reach the server, and since which tick. */
    private float[] burstLook;
    private float[] lastLook;

    /** The rotation being held before placing with it, when there is a rotation delay. */
    private float heldYaw = Float.NaN;
    private float heldPitch = Float.NaN;
    private int holdLeft;

    /** Set while one of this module's own placements is going down, so its sound can be handled. */
    private boolean placing;
    private BlockState placingState;
    private BlockPos placingPos;

    @Override
    public void onActivate() {
        timer = 0;
        lastMove = -1000;
        java.util.Arrays.fill(slotUsed, 0);
        warned = false;
        burstLook = null;
        lastLook = null;
        heldYaw = Float.NaN;
        holdLeft = 0;
        lastProgress = ticks;
        missing.clear();
        flips.clear();
        pending.clear();
        strategies.clear();
        said.clear();
    }

    @Override
    public void onDeactivate() {
        HeldRotation.clear();
        burstLook = null;
        lastLook = null;
        missing.clear();
        flips.clear();
        pending.clear();
    }

    // Ticking

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) return;

        World schematic = LitematicaBridge.schematicWorld();
        if (schematic == null) {
            if (!warned) {
                warning(LitematicaBridge.installed()
                    ? "Litematica has no schematic world yet. Load and place a schematic in Litematica."
                    : "Litematica is not installed, so there is nothing to print.");
                warned = true;
            }
            missing.clear();
            flips.clear();
            return;
        }

        pending.values().removeIf(at -> ticks - at > retryAfter.get());
        collectMissing(schematic);
        watchForStall(schematic);

        if (timer > 0) {
            timer--;
            // Keep facing the last burst's way between bursts rather than flicking back and forth.
            if (lastLook != null && turning()) HeldRotation.set(lastLook[0], lastLook[1]);
            return;
        }
        lastLook = null;
        if (pauseWhileEating.get()) {
            if (eating()) lastEat = ticks;
            if (ticks - lastEat <= eatGrace.get()) return;
        }
        if (mc.currentScreen != null) return;
        if (missing.isEmpty() && flips.isEmpty()) return;

        startBurst(schematic);
    }

    /**
     * Sets up one burst of placing.
     *
     * A burst places with one rotation, as a player facing one way would. It is the way the
     * nearest block that cares about facing needs, or your own view if none does. Blocks that
     * come out the same whichever way you face go down in the same burst too.
     *
     * With rotations on, the facing is held from the start of the tick and goes to the server in
     * your ordinary movement packet, with your movement that tick worked out facing that way
     * too so the server has nothing to pull you back for. The burst runs at the end of the
     * tick, after that packet, so the server has the right facing before the first click and
     * you never flick between several in one tick. Blocks wanting another facing wait for a
     * later burst.
     */
    private void startBurst(World schematic) {
        float[] look = burstRotation(schematic);

        // Nothing in this burst needs turning, as with only whole blocks: your facing is left
        // completely alone. The burst still waits for the end of the tick, so the server has this
        // tick's real facing before any click.
        if (!turning() || (look[0] == mc.player.getYaw() && look[1] == mc.player.getPitch())) {
            lastLook = null;
            burstLook = new float[] { mc.player.getYaw(), mc.player.getPitch() };
            return;
        }

        // Faced from the start of the tick, so the movement this tick is worked out that way too.
        HeldRotation.set(look[0], look[1]);
        lastLook = look;

        // Hold a new rotation for a while first, when asked to.
        if (rotationDelay.get() > 0) {
            if (heldYaw != look[0] || heldPitch != look[1]) {
                heldYaw = look[0];
                heldPitch = look[1];
                holdLeft = rotationDelay.get();
            }
            if (holdLeft > 0) {
                holdLeft--;
                return;
            }
        }

        // Placed at the end of the tick, once the movement packet carrying the facing has gone.
        burstLook = look;
    }

    /** Turning for blocks: rotations on, looking through your own eyes, and not flying an elytra. */
    private boolean turning() {
        return rotate.get() == RotateMode.Rotate && mc.getCameraEntity() == mc.player && !mc.player.isGliding();
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        float[] look = burstLook;
        boolean held = HeldRotation.active();
        burstLook = null;
        if (look != null && mc.player != null && mc.world != null && mc.interactionManager != null) {
            World schematic = LitematicaBridge.schematicWorld();
            // Without a held facing the server has your real one, as it is now.
            if (schematic != null) runBurst(schematic, held ? look[0] : mc.player.getYaw(), held ? look[1] : mc.player.getPitch());
        }
        HeldRotation.clear();
    }

    /**
     * The facing for this burst: what the nearest block that cares needs, then what a fence
     * gate about to be opened needs, then simply your own view.
     */
    private float[] burstRotation(World schematic) {
        float ownYaw = mc.player.getYaw();
        float ownPitch = mc.player.getPitch();

        if (rotate.get() == RotateMode.Rotate) {
            int looked = 0;
            for (BlockPos pos : missing) {
                if (!nearEnough(pos)) continue;
                if (++looked > 24) break;

                BlockState want = schematic.getBlockState(pos);
                ItemStack stack = stackFor(want);
                if (stack == null || !ready(pos, want)) continue;

                float[] needed = neededRotation(pos, want, stack);
                if (needed != null) return needed;
            }

            for (Flip flip : flips) {
                BlockState have = mc.world.getBlockState(flip.pos());
                BlockState want = schematic.getBlockState(flip.pos());
                Direction facing = flipFacing(have, want);
                if (facing != null && inReach(flipHit(flip.pos()))) return new float[] { yawOf(facing), ownPitch };
            }
        }
        return new float[] { ownYaw, ownPitch };
    }

    /**
     * The rotation one block needs, or null if it comes out right facing any way. Your own
     * view is preferred when it works, so there is no turning when there is no need to.
     */
    private float[] neededRotation(BlockPos pos, BlockState want, ItemStack stack) {
        Strategy kept = strategies.get(want);
        if (kept != null && kept.free()) return null;

        float ownYaw = mc.player.getYaw();
        float ownPitch = mc.player.getPitch();
        List<Candidate> candidates = candidates(pos, want);

        // Comes out right whichever way you face: no rotation needed at all.
        for (Candidate c : candidates) {
            if (worksFacingAnyWay(want, stack, c.hit())) {
                strategies.put(want, c.strategy(true));
                return null;
            }
        }

        for (Candidate c : candidates) {
            if (result(want, stack, c.hit(), ownYaw, ownPitch)) return new float[] { ownYaw, ownPitch };
        }
        for (float[] look : looks(want)) {
            for (Candidate c : candidates) {
                if (result(want, stack, c.hit(), look[0], look[1])) return look;
            }
        }
        debug("No click gives the right " + name(want) + " at " + pos.toShortString() + ".");
        return null;
    }

    /** Whether a click places the wanted block whichever way you face, checked for every facing it could. */
    private boolean worksFacingAnyWay(BlockState want, ItemStack stack, BlockHitResult hit) {
        for (float[] look : looks(want)) {
            if (!result(want, stack, hit, look[0], look[1])) return false;
        }
        return true;
    }

    /**
     * Places everything that comes out right facing yaw and pitch, a kind of block at a time,
     * up to the click limit, then clicks blocks into their right state with what is left.
     *
     * Each kind is put in your hand once and every spot in reach wanting it goes down before
     * the next kind. A block only in your inventory is brought onto the hotbar after the burst
     * and placed from a later one, so the server has the move first; in creative it is simply
     * taken from the creative menu.
     */
    private void runBurst(World schematic, float yaw, float pitch) {
        int budget = perTick.get();
        int done = 0;

        List<BlockPos> todo = new ArrayList<>(missing);
        todo.removeIf(pending::containsKey);
        Item bringIn = null;

        while (done < budget && !todo.isEmpty()) {
            BlockPos firstPos = todo.get(0);
            Block kind = schematic.getBlockState(firstPos).getBlock();
            Item item = kind.asItem();

            // In creative, blocks that carry data, such as banners with their patterns, are
            // each taken from the creative menu with that data, one at a time.
            if (creative() && kind.getDefaultState().hasBlockEntity()) {
                done += placeWithData(schematic, kind, todo, budget - done, yaw, pitch);
                todo.removeIf(p -> schematic.getBlockState(p).getBlock() == kind);
                continue;
            }

            FindItemResult found = InvUtils.findInHotbar(item);
            int slot = found.found() ? found.slot() : -1;
            if (slot < 0 && creative() && nearEnough(firstPos)) slot = creativeGive(item, schematic);

            if (slot < 0) {
                if (nearEnough(firstPos)) {
                    if (bringIn == null && !creative() && InvUtils.find(item).found()) bringIn = item;
                    else if (!InvUtils.find(item).found()) debug("No " + name(kind.getDefaultState()) + " to place.");
                }
                todo.removeIf(p -> schematic.getBlockState(p).getBlock() == kind);
                continue;
            }

            ItemStack stack = mc.player.getInventory().getStack(slot);
            slotUsed[slot] = ticks;
            InvUtils.swap(slot, true);
            try {
                for (Iterator<BlockPos> it = todo.iterator(); it.hasNext() && done < budget; ) {
                    BlockPos pos = it.next();
                    BlockState want = schematic.getBlockState(pos);
                    if (want.getBlock() != kind) continue;
                    it.remove();

                    // Ran out of this block part way through.
                    if (stack.isEmpty() || stack.getItem() != item) break;
                    if (!ready(pos, want)) continue;

                    BlockHitResult hit = planAt(pos, want, stack, yaw, pitch);
                    if (hit == null) continue;

                    click(hit, yaw, pitch, want, pos);
                    done++;
                }
                // Anything left of this kind waits for a later burst.
                todo.removeIf(p -> schematic.getBlockState(p).getBlock() == kind);
            } finally {
                InvUtils.swapBack();
            }
        }

        if (done < budget && flipBlocks.get()) done += flipBatch(schematic, budget - done, yaw);
        if (bringIn != null) bringIn(bringIn, schematic);

        if (done > 0) {
            timer = delay.get();
            lastProgress = ticks;
        }
    }

    /**
     * Places blocks of one kind in creative, each from a stack carrying what the schematic has
     * stored for it, the way picking the block with its data would: a banner's patterns and
     * name, a container's name, and so on. Returns how many went down.
     */
    private int placeWithData(World schematic, Block kind, List<BlockPos> todo, int budget, float yaw, float pitch) {
        int done = 0;
        for (BlockPos pos : todo) {
            if (done >= budget) break;
            BlockState want = schematic.getBlockState(pos);
            if (want.getBlock() != kind || !ready(pos, want)) continue;

            ItemStack desired = pickStack(schematic, pos, want);
            int slot = -1;
            for (int i = 0; i < 9; i++) {
                if (ItemStack.areItemsAndComponentsEqual(mc.player.getInventory().getStack(i), desired)) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) slot = creativeGive(desired, schematic);
            if (slot < 0) break;

            ItemStack stack = mc.player.getInventory().getStack(slot);
            slotUsed[slot] = ticks;
            InvUtils.swap(slot, true);
            try {
                BlockHitResult hit = planAt(pos, want, stack, yaw, pitch);
                if (hit == null) continue;
                click(hit, yaw, pitch, want, pos);
                done++;
            } finally {
                InvUtils.swapBack();
            }
        }
        return done;
    }

    /** The block with its schematic data, as picking it with its data would give, or the plain block. */
    private static ItemStack pickStack(World schematic, BlockPos pos, BlockState want) {
        Item item = want.getBlock().asItem();
        try {
            ItemStack stack = want.getPickStack(schematic, pos, true);
            if (!stack.isEmpty() && stack.getItem() == item) return stack;
        } catch (Exception ignored) {
            // Falls back to the plain block.
        }
        return new ItemStack(item);
    }

    /**
     * A click at pos that places the wanted block when facing yaw and pitch, or null if there is
     * none from here. The kind of block's kept click is tried first.
     */
    private BlockHitResult planAt(BlockPos pos, BlockState want, ItemStack stack, float yaw, float pitch) {
        Strategy kept = strategies.get(want);
        if (kept != null) {
            BlockHitResult hit = kept.hit(this, pos);
            if (hit != null && usable(hit) && result(want, stack, hit, yaw, pitch)) return hit;
        }

        boolean anyInReach = false;
        for (Candidate c : candidates(pos, want)) {
            anyInReach = true;
            if (result(want, stack, c.hit(), yaw, pitch)) {
                if (kept == null) strategies.put(want, c.strategy(false));
                return c.hit();
            }
        }
        if (!anyInReach) debug(name(want) + " at " + pos.toShortString() + " has no face in reach you can see.");
        return null;
    }

    /** The block to place for a wanted state, if one is on the hotbar or creative can give it. */
    private ItemStack stackFor(BlockState want) {
        Item item = want.getBlock().asItem();
        FindItemResult found = InvUtils.findInHotbar(item);
        if (found.found()) return mc.player.getInventory().getStack(found.slot());
        if (creative() || InvUtils.find(item).found()) return new ItemStack(item);
        return null;
    }

    /** Every nearby block the schematic wants that is not there yet, nearest first. */
    private void collectMissing(World schematic) {
        missing.clear();
        flips.clear();
        boolean flipping = flipBlocks.get();

        int r = (int) Math.ceil(range.get()) + 1;
        BlockPos centre = mc.player.getBlockPos();
        BlockPos feet = mc.player.getBlockPos();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    pos.set(centre.getX() + x, centre.getY() + y, centre.getZ() + z);

                    BlockState want = schematic.getBlockState(pos);

                    if (!printable(want)) continue;
                    if (layers.get() && !LitematicaBridge.inLayerRange(pos)) continue;

                    BlockState have = mc.world.getBlockState(pos);
                    if (matches(have, want)) {
                        if (flipping) {
                            int clicks = clicksToFix(have, want);
                            if (clicks > 0) flips.add(new Flip(pos.toImmutable(), clicks));
                        }
                        continue;
                    }

                    // Something else is in the way: printing never breaks anything, so it is
                    // only outlined, for you to deal with. Half a double slab is not in the way:
                    // the other half goes in on top of it.
                    if (!have.isReplaceable() && !doubling(have, want)) continue;

                    // Not inside yourself.
                    if (pos.equals(feet) || pos.equals(feet.up())) continue;

                    missing.add(pos.toImmutable());
                }
            }
        }

        Vec3d eye = mc.player.getEyePos();
        missing.removeIf(pending::containsKey);
        missing.sort(Comparator.comparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))));
        flips.removeIf(f -> pending.containsKey(f.pos()));
        flips.sort(Comparator.comparingDouble(f -> eye.squaredDistanceTo(Vec3d.ofCenter(f.pos()))));
    }

    /**
     * A single slab placed where the schematic has a double one of the same slab. Either half
     * will do as the first: the second slab then goes into it and makes it whole.
     */
    private static boolean halfOfDouble(BlockState placed, BlockState want) {
        return want.contains(Properties.SLAB_TYPE) && want.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
            && placed.getBlock() == want.getBlock() && placed.get(Properties.SLAB_TYPE) != SlabType.DOUBLE;
    }

    /** A single slab where the schematic has a double one of the same slab: one more slab finishes it. */
    private static boolean doubling(BlockState have, BlockState want) {
        return want.contains(Properties.SLAB_TYPE) && want.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
            && have.getBlock() == want.getBlock() && have.get(Properties.SLAB_TYPE) != SlabType.DOUBLE;
    }

    /**
     * Whether a spot can take its block right now: nothing standing in it, and for sand, gravel
     * and the like, something under it to stop it falling. Finishing a double slab only needs
     * the half that is there.
     */
    private boolean ready(BlockPos pos, BlockState want) {
        BlockState have = mc.world.getBlockState(pos);
        if (doubling(have, want)) return true;
        if (!BlockUtils.canPlace(pos, true)) return false;
        if (gravityCheck.get() && want.getBlock() instanceof FallingBlock && FallingBlock.canFallThrough(mc.world.getBlockState(pos.down()))) return false;
        return true;
    }

    // Stalls

    /** The tick something was last placed or clicked. */
    private int lastProgress;

    /**
     * Gets going again if it has stopped for no good reason.
     *
     * When there are blocks in reach and their blocks are to hand, but nothing has gone down
     * for three seconds, whatever it had worked out may be stale: kept clicks, spots it thought
     * were on their way, a rotation it was waiting on. All of it is dropped and worked out
     * afresh.
     */
    private void watchForStall(World schematic) {
        boolean work = false;
        for (BlockPos pos : missing) {
            if (!nearEnough(pos)) continue;
            Item item = schematic.getBlockState(pos).getBlock().asItem();
            if (item != Items.AIR && (creative() || InvUtils.find(item).found())) {
                work = true;
                break;
            }
        }
        if (!work) {
            lastProgress = ticks;
            return;
        }
        if (ticks - lastProgress < 60) return;

        debug("Nothing placed for three seconds with blocks to place in reach. Starting over.");
        strategies.clear();
        pending.clear();
        burstLook = null;
        lastLook = null;
        holdLeft = 0;
        heldYaw = Float.NaN;
        timer = 0;
        lastProgress = ticks;
    }

    // Debug

    private final Map<String, Integer> said = new HashMap<>();

    /** Says why something is not being placed, at most once every five seconds for the same reason. */
    private void debug(String message) {
        if (!debug.get()) return;
        Integer at = said.get(message);
        if (at != null && ticks - at < 100) return;
        said.put(message, ticks);
        if (said.size() > 256) said.values().removeIf(t -> ticks - t >= 100);
        info("%s", message);
    }

    private static String name(BlockState state) {
        return state.getBlock().getName().getString();
    }

    // Flipping

    /**
     * How many clicks turn a placed block into the state the schematic has, or 0 if none will.
     *
     * Only states a hand can change count: iron doors and trapdoors need redstone, so they are
     * left alone. A repeater steps through four delays and a note block through 25 notes, so
     * those can take several clicks.
     */
    private static int clicksToFix(BlockState have, BlockState want) {
        Block block = want.getBlock();

        if (want.contains(Properties.OPEN) && (block instanceof TrapdoorBlock || block instanceof DoorBlock || block instanceof FenceGateBlock)) {
            if (block == Blocks.IRON_DOOR || block == Blocks.IRON_TRAPDOOR) return 0;
            return have.get(Properties.OPEN) != want.get(Properties.OPEN) ? 1 : 0;
        }
        if (block instanceof LeverBlock) return have.get(Properties.POWERED) != want.get(Properties.POWERED) ? 1 : 0;
        if (block instanceof RepeaterBlock) return Math.floorMod(want.get(Properties.DELAY) - have.get(Properties.DELAY), 4);
        if (block instanceof ComparatorBlock) return have.get(Properties.COMPARATOR_MODE) != want.get(Properties.COMPARATOR_MODE) ? 1 : 0;
        if (block instanceof DaylightDetectorBlock) return have.get(Properties.INVERTED) != want.get(Properties.INVERTED) ? 1 : 0;
        if (block instanceof NoteBlock) return Math.floorMod(want.get(Properties.NOTE) - have.get(Properties.NOTE), 25);
        return 0;
    }

    /**
     * Which way you have to face while clicking, or null if any way does.
     *
     * A fence gate opened from in front swings to face away from you, turning itself round. So
     * one that should open facing a certain way is opened while facing that way too.
     */
    private Direction flipFacing(BlockState have, BlockState want) {
        if (want.getBlock() instanceof FenceGateBlock && want.get(Properties.OPEN) && !have.get(Properties.OPEN)) {
            return want.get(Properties.HORIZONTAL_FACING);
        }
        return null;
    }

    /**
     * Clicks blocks into their right state, up to the clicks left in the burst, facing yaw.
     * A fence gate that needs another facing waits for a burst turned its way.
     */
    private int flipBatch(World schematic, int budget, float yaw) {
        if (mc.player.isSneaking()) return 0;

        int done = 0;
        for (Flip flip : flips) {
            if (done >= budget) break;
            if (pending.containsKey(flip.pos())) continue;

            BlockState have = mc.world.getBlockState(flip.pos());
            BlockState want = schematic.getBlockState(flip.pos());
            BlockHitResult hit = flipHit(flip.pos());
            if (!usable(hit)) continue;

            Direction facing = flipFacing(have, want);
            if (facing != null && facingOf(yaw) != facing) continue;

            int clicks = Math.min(flip.clicks(), budget - done);
            flipClick(flip.pos(), hit, clicks, yaw);
            done += clicks;
        }
        return done;
    }

    /** The click a person would make on a block: the face of it most in view, at the point nearest your eyes. */
    private BlockHitResult flipHit(BlockPos pos) {
        Direction face = faceInView(pos);
        Vec3d eye = mc.player.getEyePos();
        return new BlockHitResult(pointOn(pos, face, clampFrac(eye.y - pos.getY()), Double.NaN, eye), face, pos, false);
    }

    private void flipClick(BlockPos pos, BlockHitResult hit, int clicks, float yaw) {
        float realYaw = mc.player.getYaw();
        try {
            mc.player.setYaw(yaw);
            for (int i = 0; i < clicks; i++) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
            }
        } finally {
            mc.player.setYaw(realYaw);
        }
        pending.put(pos, ticks);
    }

    /** The way the game counts you as facing for a yaw, as it works it out. */
    private static Direction facingOf(float yaw) {
        return switch (MathHelper.floor(yaw / 90.0 + 0.5) & 3) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private static float yawOf(Direction facing) {
        return switch (facing) {
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            case EAST -> 270.0F;
            default -> 0.0F;
        };
    }

    private static boolean inside(List<int[]> boxes, BlockPos pos) {
        for (int[] b : boxes) {
            if (pos.getX() >= b[0] && pos.getY() >= b[1] && pos.getZ() >= b[2]
                && pos.getX() <= b[3] && pos.getY() <= b[4] && pos.getZ() <= b[5]) return true;
        }
        return false;
    }

    /** Things it would place: a real block with an item, and not the half that comes with the other. */
    private boolean printable(BlockState want) {
        if (want.isAir() || want.getBlock().asItem() == Items.AIR) return false;
        if (ignored.get().contains(want.getBlock())) return false;

        // The top of a door or a tall plant and the head of a bed come with the part placed first.
        if (want.contains(Properties.DOUBLE_BLOCK_HALF) && want.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return false;
        if (want.contains(Properties.BED_PART) && want.get(Properties.BED_PART) == BedPart.HEAD) return false;
        return true;
    }

    /**
     * Whether you are eating or about to.
     *
     * Using an item covers it once a bite has started. An auto eat in another client puts the
     * food in your hand a tick or so before it starts, and between bites stops using for a
     * moment, and swapping slots in either gap cancels the bite. So food you could eat in either
     * hand counts too: something always edible like a golden apple, or anything while you are
     * hungry. The grace after covers the rest.
     */
    private boolean eating() {
        if (mc.player.isUsingItem()) return true;
        return edible(mc.player.getMainHandStack()) || edible(mc.player.getOffHandStack());
    }

    private boolean edible(ItemStack stack) {
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        if (food == null) return false;
        return food.canAlwaysEat() || mc.player.getHungerManager().isNotFull();
    }

    /** Whether a spot is close enough to be worth bringing its block in for. */
    private boolean nearEnough(BlockPos pos) {
        double r = range.get() + 0.9;
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= r * r;
    }

    /**
     * Brings a block from the inventory onto the hotbar, at most once every so many ticks.
     *
     * It goes into one of the swap slots: an empty one if there is one, then one holding
     * something no nearby spot wants, and only then the one used longest ago. So blocks the
     * schematic keeps asking for stay on the hotbar, and it is not swapping the same slot back
     * and forth each tick. The slot you are holding is left alone.
     */
    private boolean bringIn(Item item, World schematic) {
        if (!fromInventory.get() || ticks - lastMove < inventoryDelay.get()) return false;

        FindItemResult stored = InvUtils.find(item);
        if (!stored.found() || stored.isHotbar() || stored.isOffhand()) return false;

        int best = pickSlot(schematic);
        if (best < 0) return false;

        InvUtils.quickSwap().fromId(best).to(stored.slot());
        slotUsed[best] = ticks;
        lastMove = ticks;
        return true;
    }

    private boolean creative() {
        return mc.player.getAbilities().creativeMode;
    }

    /**
     * Takes a stack of a block from the creative menu into a swap slot, as picking it from the
     * menu would, and returns the slot, or -1 if no slot is free to use.
     */
    private int creativeGive(Item item, World schematic) {
        return creativeGive(new ItemStack(item), schematic);
    }

    private int creativeGive(ItemStack wanted, World schematic) {
        int slot = pickSlot(schematic);
        if (slot < 0) return -1;

        ItemStack stack = wanted.copyWithCount(wanted.getMaxCount());
        mc.player.getInventory().setStack(slot, stack);
        mc.interactionManager.clickCreativeStack(stack, 36 + slot);
        slotUsed[slot] = ticks;
        return slot;
    }

    /**
     * The swap slot to fill next: an empty one, then one holding something no nearby spot
     * wants, then the one used longest ago. Never the slot you are holding.
     */
    private int pickSlot(World schematic) {
        Set<Item> wanted = new HashSet<>();
        for (BlockPos pos : missing) {
            if (nearEnough(pos)) wanted.add(schematic.getBlockState(pos).getBlock().asItem());
        }

        int selected = mc.player.getInventory().getSelectedSlot();
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 9 - swapSlots.get(); slot < 9; slot++) {
            if (slot == selected) continue;

            ItemStack held = mc.player.getInventory().getStack(slot);
            int score;
            if (held.isEmpty()) score = 2_000_000_000;
            else if (!wanted.contains(held.getItem())) score = 1_000_000_000 - slotUsed[slot];
            else score = -slotUsed[slot];

            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    // Placing

    /** One click that could place a block, and where it came from, so it can be kept as the way for its kind. */
    private record Candidate(BlockHitResult hit, boolean natural, Direction face, double fracY, double lateral) {
        Strategy strategy(boolean free) {
            return new Strategy(natural, face, fracY, lateral, free);
        }
    }

    /**
     * The click that worked for a kind of block, kept apart from any one position: the click a
     * person would make, or a face with how far up and along it. Free means it comes out right
     * whichever way you face, so the block never needs a rotation.
     */
    private record Strategy(boolean natural, Direction face, double fracY, double lateral, boolean free) {
        BlockHitResult hit(PrinterGoBrrr printer, BlockPos pos) {
            return natural ? printer.naturalHit(pos) : printer.faceHit(pos, face, fracY, lateral);
        }
    }

    /**
     * Click with the block in hand, facing yaw and pitch.
     *
     * The client works out what it places from the way you face at that moment, as the server
     * does from the facing it was last sent, so the client faces the burst's way for the click
     * and goes straight back after. Nothing is drawn in between.
     */
    private void click(BlockHitResult hit, float yaw, float pitch, BlockState want, BlockPos pos) {
        float realYaw = mc.player.getYaw();
        float realPitch = mc.player.getPitch();

        placing = true;
        placingState = want;
        placingPos = pos;
        if (want.getBlock() instanceof AbstractSignBlock) {
            lastSign = pos;
            lastSignAt = ticks;
        }
        try {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        } finally {
            placing = false;
            mc.player.setYaw(realYaw);
            mc.player.setPitch(realPitch);
        }

        pending.put(pos, ticks);
    }

    /**
     * Every click that could place a block at pos and is allowed from here, best first: the one
     * a person would make, then each face at the middle or nearer its top or bottom, which is
     * what decides top and bottom slabs, stairs and trapdoors. Doors also try each side of a
     * face, since which half is clicked decides the side the hinge goes on.
     */
    private List<Candidate> candidates(BlockPos pos, BlockState want) {
        List<Candidate> list = new ArrayList<>();

        BlockHitResult natural = naturalHit(pos);
        if (natural != null && usable(natural)) list.add(new Candidate(natural, true, natural.getSide(), 0, Double.NaN));

        double[] laterals = want.contains(Properties.DOOR_HINGE) ? new double[] { Double.NaN, 0.2, 0.8 } : new double[] { Double.NaN };
        for (Direction face : Direction.values()) {
            double[] fracs = face.getAxis().isHorizontal() ? new double[] { 0.25, 0.75 } : new double[] { 0.5 };
            for (double f : fracs) {
                for (double lateral : laterals) {
                    BlockHitResult hit = faceHit(pos, face, f, lateral);
                    if (hit != null && usable(hit)) list.add(new Candidate(hit, false, face, f, lateral));
                }
            }
        }
        return list;
    }

    /** In reach, and on a face you can see when only visible faces are allowed. */
    private boolean usable(BlockHitResult hit) {
        return inReach(hit) && (!visibleFaces.get() || faceVisible(hit));
    }

    private boolean inReach(BlockHitResult hit) {
        double r = range.get();
        return mc.player.getEyePos().squaredDistanceTo(hit.getPos()) <= r * r;
    }

    /** Whether your eyes are on the outside of the face clicked, so you could see it. */
    private boolean faceVisible(BlockHitResult hit) {
        Vec3d eye = mc.player.getEyePos();
        BlockPos b = hit.getBlockPos();
        return switch (hit.getSide()) {
            case UP -> eye.y > b.getY() + 1;
            case DOWN -> eye.y < b.getY();
            case EAST -> eye.x > b.getX() + 1;
            case WEST -> eye.x < b.getX();
            case SOUTH -> eye.z > b.getZ() + 1;
            case NORTH -> eye.z < b.getZ();
        };
    }

    /** The face of the block at pos your eyes are furthest outside of, the one most in view. */
    private Direction faceInView(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Direction best = Direction.UP;
        double most = Double.NEGATIVE_INFINITY;
        for (Direction d : Direction.values()) {
            double out = switch (d) {
                case UP -> eye.y - (pos.getY() + 1);
                case DOWN -> pos.getY() - eye.y;
                case EAST -> eye.x - (pos.getX() + 1);
                case WEST -> pos.getX() - eye.x;
                case SOUTH -> eye.z - (pos.getZ() + 1);
                case NORTH -> pos.getZ() - eye.z;
            };
            if (out > most) {
                most = out;
                best = d;
            }
        }
        return best;
    }

    private static double clampFrac(double f) {
        return Math.max(0.05, Math.min(0.95, f));
    }

    /**
     * The click a person would make: on the face most in view, at the point of it nearest your
     * eyes, inset from the edges. In air place mode that is the spot itself; otherwise it is the
     * neighbour behind that face, if there is one solid enough to click.
     */
    BlockHitResult naturalHit(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Direction face = faceInView(pos);
        double frac = clampFrac(eye.y - pos.getY());

        if (placeMode.get() == PlaceMode.AirPlace) return new BlockHitResult(pointOn(pos, face, frac, Double.NaN, eye), face, pos, false);
        return faceHit(pos, face, frac, Double.NaN);
    }

    /**
     * A click that puts the block at pos, on the given face, frac of the way up it.
     *
     * In air place mode the face is one of the spot's own. Otherwise the spot is reached through
     * the block on the far side of that face, clicking the side of it that touches the spot, and
     * only if that block is solid and not something a click would open instead.
     */
    BlockHitResult faceHit(BlockPos pos, Direction face, double frac, double lateral) {
        Vec3d eye = mc.player.getEyePos();

        if (placeMode.get() == PlaceMode.AirPlace) {
            return new BlockHitResult(pointOn(pos, face, frac, lateral, eye), face, pos, false);
        }

        BlockPos against = pos.offset(face.getOpposite());
        BlockState neighbour = mc.world.getBlockState(against);
        if (neighbour.isReplaceable() || BlockUtils.isClickable(neighbour.getBlock())) return null;

        return new BlockHitResult(pointOn(pos, face.getOpposite(), frac, lateral, eye), face, against, false);
    }

    /**
     * A point exactly on one face of the block at pos, frac of the way up it. Across the face it
     * is at lateral of the way along, or if lateral is NaN, as near your eyes as the face allows.
     * Always inset from the edges: a point even a hair outside the block is a click on nothing,
     * which anticheats notice.
     */
    private static Vec3d pointOn(BlockPos pos, Direction face, double frac, double lateral, Vec3d eye) {
        double x = pos.getX() + 0.5 + face.getOffsetX() * 0.5;
        double y = face.getAxis() == Direction.Axis.Y ? pos.getY() + 0.5 + face.getOffsetY() * 0.5 : pos.getY() + clampFrac(frac);
        double z = pos.getZ() + 0.5 + face.getOffsetZ() * 0.5;

        boolean near = Double.isNaN(lateral);
        if (face.getAxis() != Direction.Axis.X) x = pos.getX() + (near ? clampFrac(eye.x - pos.getX()) : clampFrac(lateral));
        if (face.getAxis() != Direction.Axis.Z) z = pos.getZ() + (near ? clampFrac(eye.z - pos.getZ()) : clampFrac(lateral));
        return new Vec3d(x, y, z);
    }

    /**
     * Every facing worth trying: the four ways round, or sixteen for signs, banners and the
     * like, each looking level, straight down and straight up.
     */
    private static float[][] looks(BlockState want) {
        List<float[]> looks = new ArrayList<>();
        int around = want.contains(Properties.ROTATION) ? 16 : 4;
        for (int i = 0; i < around; i++) {
            float yaw = i * (360.0F / around);
            looks.add(new float[] { yaw, 0.0F });
            looks.add(new float[] { yaw, 90.0F });
            looks.add(new float[] { yaw, -90.0F });
        }
        return looks.toArray(new float[0][]);
    }

    /**
     * Whether this click, facing this way, places exactly the block the schematic wants, where
     * it wants it.
     *
     * Asks the item, the same as the game does when you click: so a torch, sign or banner comes
     * out standing or on a wall just as it would, and it is only counted if the block could
     * really stand there. The spot it would go is checked too, so a click on the wrong side of
     * a slab is not taken for finishing it.
     */
    private boolean result(BlockState want, ItemStack stack, BlockHitResult hit, float yaw, float pitch) {
        float realYaw = mc.player.getYaw();
        float realPitch = mc.player.getPitch();
        BlockPos target = placeMode.get() == PlaceMode.AirPlace ? hit.getBlockPos() : hit.getBlockPos().offset(hit.getSide());

        try {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            ItemPlacementContext context = new ItemPlacementContext(mc.player, Hand.MAIN_HAND, stack, hit);
            if (!context.canPlace() || !context.getBlockPos().equals(target)) return false;

            BlockState placed = stack.getItem() instanceof BlockItem item
                ? ((BlockItemInvoker) item).jjsTools$getPlacementState(context)
                : want.getBlock().getPlacementState(context);
            return placed != null && (matches(placed, want) || halfOfDouble(placed, want));
        } catch (Exception e) {
            return false;
        } finally {
            mc.player.setYaw(realYaw);
            mc.player.setPitch(realPitch);
        }
    }

    /**
     * The parts of a block placing decides, and nothing else.
     *
     * A stair's corner shape, a fence's connections, whether something is powered or waterlogged:
     * those follow from what is around the block, not from how it was put down, so they are left
     * out of the comparison. What is left is the block itself and the way it faces, its axis, its
     * half and the like.
     */
    private static final List<Property<?>> PLACED = List.of(
        Properties.FACING, Properties.HORIZONTAL_FACING, Properties.HOPPER_FACING,
        Properties.AXIS, Properties.HORIZONTAL_AXIS, Properties.BLOCK_HALF, Properties.SLAB_TYPE,
        Properties.BLOCK_FACE, Properties.ROTATION, Properties.ORIENTATION, Properties.ATTACHMENT,
        Properties.DOOR_HINGE
    );

    private static boolean matches(BlockState have, BlockState want) {
        if (have.getBlock() != want.getBlock()) return false;
        for (Property<?> property : PLACED) {
            if (!want.contains(property)) continue;
            if (!have.contains(property) || !have.get(property).equals(want.get(property))) return false;
        }
        return true;
    }

    // Signs

    /** The last sign placed and when, so its editor can be filled in when the server opens it. */
    private BlockPos lastSign;
    private int lastSignAt;

    /**
     * Fills in a sign the printer has just placed.
     *
     * Placing a sign makes the server open the editor for its front. Rather than leave that open,
     * the schematic's text for the front is sent back as if typed, and the editor never shows.
     */
    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!signText.get() || !(event.screen instanceof AbstractSignEditScreen)) return;
        if (lastSign == null || ticks - lastSignAt > 40 || mc.getNetworkHandler() == null) return;

        World schematic = LitematicaBridge.schematicWorld();
        if (schematic == null || !(schematic.getBlockEntity(lastSign) instanceof SignBlockEntity sign)) return;

        SignText text = sign.getFrontText();
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) lines[i] = text.getMessage(i, false).getString();

        mc.getNetworkHandler().sendPacket(new UpdateSignC2SPacket(lastSign, true, lines[0], lines[1], lines[2], lines[3]));
        event.cancel();
        lastSign = null;
    }

    // Sound

    /**
     * The sound of each block the printer places.
     *
     * The game plays it as the block goes down. That one is held back and the block's own place
     * sound played instead at the chosen volume, the same sound and pitch placing by hand makes,
     * so printing can be as loud or as quiet as wanted. Off plays nothing.
     */
    @EventHandler
    private void onSound(PlaySoundEvent event) {
        if (!placing || placingState == null) return;

        event.cancel();
        if (!sounds.get() || volume.get() <= 0) return;

        var group = placingState.getSoundGroup();
        float loudness = (float) ((group.getVolume() + 1.0F) / 2.0F * (volume.get() / 100.0));
        float pitch = group.getPitch() * 0.8F;

        placing = false;
        try {
            mc.getSoundManager().play(new PositionedSoundInstance(group.getPlaceSound(), SoundCategory.BLOCKS,
                loudness, pitch, SoundInstance.createRandom(), placingPos));
        } finally {
            placing = true;
        }
    }

    // Render

    /** One outlined block: where it is, and its shape if it is not a whole block. */
    private record Entry(BlockPos pos, BlockState state, VoxelShape shape) {}

    /**
     * Finds and outlines missing and misplaced blocks across the whole of your schematics, far
     * further than the printer reaches.
     *
     * Always listening, so it carries on while the printer is off when asked to.
     * Only the parts of the world your schematics cover are looked at, and a big build is gone
     * through a slice each tick rather than all at once, so it never costs a lag spike; what
     * is drawn is the last full look, with anything fixed since taken off straight away.
     * Outlines are drawn through walls, like an ESP.
     */
    private final class Esp {
        private static final int PER_TICK = 24_000;
        private static final int MAX = 16_384;

        private List<Entry> shownMissing = new ArrayList<>();
        private List<Entry> shownMisplaced = new ArrayList<>();
        private List<Entry> nextMissing = new ArrayList<>();
        private List<Entry> nextMisplaced = new ArrayList<>();

        private final Outline missingOutline = new Outline();
        private final Outline misplacedOutline = new Outline();

        private List<int[]> regions;
        private boolean extras;
        private int region;
        private int x, y, z;

        private boolean wantMissing() {
            return render.get() && (isActive() || renderWhenOff.get());
        }

        private boolean wantMisplaced() {
            return showMisplaced.get() && (isActive() || misplacedWhenOff.get());
        }

        private boolean wanted() {
            return wantMissing() || wantMisplaced();
        }

        private void clear() {
            shownMissing = new ArrayList<>();
            shownMisplaced = new ArrayList<>();
            regions = null;
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (mc.player == null || mc.world == null || !wanted()) {
                clear();
                return;
            }
            World schematic = LitematicaBridge.schematicWorld();
            if (schematic == null) {
                clear();
                return;
            }

            prune();
            scan(schematic);
        }

        /** Takes off anything put right since it was found, or just placed and waiting on the server. */
        private void prune() {
            prune(shownMissing, shownMisplaced);
        }

        private void prune(List<Entry> missingList, List<Entry> misplacedList) {
            missingList.removeIf(e -> pending.containsKey(e.pos()) || matches(mc.world.getBlockState(e.pos()), e.state()));
            misplacedList.removeIf(e -> mc.world.getBlockState(e.pos()) != e.state());
        }

        private void scan(World schematic) {
            if (regions == null) start();

            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int n = 0; n < PER_TICK; n++) {
                if (region >= regions.size()) {
                    // A look takes several ticks, so blocks placed while it went on are taken off
                    // before it is shown, rather than flashing back up for a moment.
                    prune(nextMissing, nextMisplaced);
                    shownMissing = nextMissing;
                    shownMisplaced = nextMisplaced;
                    start();
                    return;
                }

                int[] r = regions.get(region);
                pos.set(x, y, z);
                check(schematic, pos);

                if (++y > r[4]) {
                    y = r[1];
                    if (++z > r[5]) {
                        z = r[2];
                        if (++x > r[3]) {
                            region++;
                            if (region < regions.size()) cursorTo(regions.get(region));
                        }
                    }
                }
            }
        }

        /**
         * Starts a fresh look: the parts of each shown schematic within range of you, or if
         * Litematica cannot say where its schematics are, a box around you.
         */
        private void start() {
            nextMissing = new ArrayList<>();
            nextMisplaced = new ArrayList<>();
            regions = new ArrayList<>();
            region = 0;

            int r = Math.max(wantMissing() ? renderRange.get() : 0, wantMisplaced() ? misplacedRange.get() : 0);
            BlockPos me = mc.player.getBlockPos();
            int bottom = mc.world.getBottomY();
            int top = mc.world.getTopYInclusive();

            List<int[]> boxes = LitematicaBridge.placementBoxes();
            extras = !boxes.isEmpty();
            if (boxes.isEmpty()) {
                int h = Math.min(r, 32);
                boxes = List.of(new int[] { me.getX() - h, me.getY() - h, me.getZ() - h, me.getX() + h, me.getY() + h, me.getZ() + h });
            }

            for (int[] b : boxes) {
                int[] cut = {
                    Math.max(b[0], me.getX() - r), Math.max(Math.max(b[1], me.getY() - r), bottom), Math.max(b[2], me.getZ() - r),
                    Math.min(b[3], me.getX() + r), Math.min(Math.min(b[4], me.getY() + r), top), Math.min(b[5], me.getZ() + r)
                };
                if (cut[0] <= cut[3] && cut[1] <= cut[4] && cut[2] <= cut[5]) regions.add(cut);
            }
            if (!regions.isEmpty()) cursorTo(regions.get(0));
        }

        private void cursorTo(int[] r) {
            x = r[0];
            y = r[1];
            z = r[2];
        }

        private void check(World schematic, BlockPos.Mutable pos) {
            if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return;

            BlockState want = schematic.getBlockState(pos);

            // The schematic wants this spot empty: a block here is one too many.
            if (want.isAir()) {
                if (!extras || !wantMisplaced() || !showExtra.get()) return;
                BlockState have = mc.world.getBlockState(pos);
                if (have.isAir() || have.isReplaceable()) return;
                if (layers.get() && !LitematicaBridge.inLayerRange(pos)) return;
                add(nextMisplaced, pos, have, mc.world);
                return;
            }

            if (!printable(want)) return;
            BlockState have = mc.world.getBlockState(pos);
            if (matches(have, want)) return;
            if (layers.get() && !LitematicaBridge.inLayerRange(pos)) return;

            if (!have.isReplaceable() && !doubling(have, want)) {
                if (wantMisplaced()) add(nextMisplaced, pos, have, mc.world);
            } else if (wantMissing()) {
                add(nextMissing, pos, want, schematic);
            }
        }

        private void add(List<Entry> list, BlockPos pos, BlockState state, World world) {
            if (list.size() >= MAX) return;
            BlockPos at = pos.toImmutable();
            VoxelShape shape;
            try {
                shape = state.getOutlineShape(world, at);
            } catch (Exception e) {
                shape = VoxelShapes.fullCube();
            }
            boolean whole = shape.isEmpty() || Block.isShapeFullCube(shape);
            list.add(new Entry(at, state, whole ? null : shape));
        }

        @EventHandler
        private void onRender(Render3DEvent event) {
            if (mc.player == null) return;
            if (wantMissing()) draw(event, shownMissing, missingOutline, renderRange.get(), sideColour.get(), lineColour.get(), shapeMode.get());
            if (wantMisplaced()) draw(event, shownMisplaced, misplacedOutline, misplacedRange.get(), misplacedSide.get(), misplacedLine.get(), misplacedShape.get());
        }
    }

    /** A straight line a set of edges lies along: its axis and the two coordinates fixed along it, in 1/1024ths. */
    private record Line(int axis, long a, long b) {}

    /**
     * The outline of a set of blocks taken together, worked out once and drawn every frame
     * until the set changes or you move a few blocks.
     *
     * Every edge of every block's own shape goes in, then edges lying along the same line are
     * counted wherever they overlap. Where two shapes meet flat, as two slabs side by side or a
     * slab against a full block, the same stretch of edge comes from both and cancels out, so no
     * line is drawn down the middle; at an inside corner three meet and the line stays. What is
     * left is the outline of the whole, whatever mix of whole blocks, slabs and stairs it is.
     */
    private final class Outline {
        private List<Entry> source;
        private int size = -1;
        private int range = -1;
        private BlockPos at;
        private long builtAt;
        private final List<double[]> lines = new ArrayList<>();

        List<double[]> get(List<Entry> entries, int range) {
            BlockPos me = mc.player.getBlockPos();
            if (entries == source && entries.size() == size && range == this.range && at != null && at.getManhattanDistance(me) < 4) return lines;

            // While printing the set changes every tick; working it out at most once a tick keeps
            // that from costing frames without leaving placed blocks outlined.
            long now = System.nanoTime();
            if (at != null && entries == source && now - builtAt < 50_000_000L) return lines;
            builtAt = now;

            source = entries;
            size = entries.size();
            this.range = range;
            at = me;
            build(entries, range);
            return lines;
        }

        private void build(List<Entry> entries, int range) {
            lines.clear();
            double limit = (double) range * range;
            Vec3d eye = mc.player.getEyePos();
            Map<Line, List<double[]>> byLine = new HashMap<>();

            int count = 0;
            for (Entry e : entries) {
                if (eye.squaredDistanceTo(Vec3d.ofCenter(e.pos())) > limit) continue;
                if (++count > 8192) break;

                VoxelShape shape = e.shape() == null ? VoxelShapes.fullCube() : e.shape();
                double ox = e.pos().getX(), oy = e.pos().getY(), oz = e.pos().getZ();
                shape.forEachEdge((x1, y1, z1, x2, y2, z2) -> addEdge(byLine, ox + x1, oy + y1, oz + z1, ox + x2, oy + y2, oz + z2));
            }

            for (Map.Entry<Line, List<double[]>> entry : byLine.entrySet()) {
                Line line = entry.getKey();
                List<double[]> spans = entry.getValue();

                // Sweep along the line counting how many edges cover each stretch: odd counts are outline.
                List<double[]> ends = new ArrayList<>(spans.size() * 2);
                for (double[] span : spans) {
                    ends.add(new double[] { span[0], 1 });
                    ends.add(new double[] { span[1], -1 });
                }
                ends.sort(Comparator.comparingDouble(end -> end[0]));

                int covering = 0;
                double from = Double.NaN;
                for (int k = 0; k < ends.size(); ) {
                    double where = ends.get(k)[0];
                    boolean wasOdd = (covering & 1) == 1;
                    while (k < ends.size() && Math.abs(ends.get(k)[0] - where) < 1e-6) covering += (int) ends.get(k++)[1];
                    boolean isOdd = (covering & 1) == 1;

                    if (!wasOdd && isOdd) from = where;
                    else if (wasOdd && !isOdd) lines.add(segment(line, from, where));
                }
            }
        }

        private void addEdge(Map<Line, List<double[]>> byLine, double x1, double y1, double z1, double x2, double y2, double z2) {
            int axis;
            double f1, f2, lo, hi;
            if (Math.abs(x1 - x2) > 1e-6) {
                axis = 0; f1 = y1; f2 = z1; lo = Math.min(x1, x2); hi = Math.max(x1, x2);
            } else if (Math.abs(y1 - y2) > 1e-6) {
                axis = 1; f1 = x1; f2 = z1; lo = Math.min(y1, y2); hi = Math.max(y1, y2);
            } else if (Math.abs(z1 - z2) > 1e-6) {
                axis = 2; f1 = x1; f2 = y1; lo = Math.min(z1, z2); hi = Math.max(z1, z2);
            } else {
                return;
            }
            Line key = new Line(axis, Math.round(f1 * 1024), Math.round(f2 * 1024));
            byLine.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[] { lo, hi });
        }

        private static double[] segment(Line line, double from, double to) {
            double a = line.a() / 1024.0;
            double b = line.b() / 1024.0;
            return switch (line.axis()) {
                case 0 -> new double[] { from, a, b, to, a, b };
                case 1 -> new double[] { a, from, b, a, to, b };
                default -> new double[] { a, b, from, a, b, to };
            };
        }
    }

    /**
     * Outlines blocks, through walls, as the shape they make together: whole blocks, slabs,
     * stairs and the rest each in their own shape, with no lines where they meet flat. Faces are
     * filled only where nothing of the set covers them.
     */
    private void draw(Render3DEvent event, List<Entry> entries, Outline outline, int range, SettingColor side, SettingColor line, ShapeMode mode) {
        boolean sides = mode.sides() && side.a > 0;
        boolean lines = mode.lines() && line.a > 0;

        if (lines) {
            for (double[] l : outline.get(entries, range)) {
                event.renderer.line(l[0], l[1], l[2], l[3], l[4], l[5], line);
            }
        }
        if (!sides) return;

        double limit = (double) range * range;
        Vec3d eye = mc.player.getEyePos();
        LongOpenHashSet whole = new LongOpenHashSet();
        for (Entry e : entries) {
            if (e.shape() == null) whole.add(e.pos().asLong());
        }

        int drawn = 0;
        for (Entry e : entries) {
            BlockPos pos = e.pos();
            if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > limit) continue;
            if (++drawn > 8192) break;

            if (e.shape() != null) {
                for (Box box : e.shape().getBoundingBoxes()) {
                    event.renderer.box(box.offset(pos), side, line, ShapeMode.Sides, 0);
                }
                continue;
            }

            int covered = 0;
            int count = 0;
            for (Direction d : Direction.values()) {
                if (!whole.contains(pos.offset(d).asLong())) continue;
                covered |= Dir.get(d);
                count++;
            }
            if (count < 6) {
                event.renderer.box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                    side, line, ShapeMode.Sides, covered);
            }
        }
    }

    @Override
    public String getInfoString() {
        int wrong = esp.shownMisplaced.size();
        if (missing.isEmpty() && wrong == 0) return null;
        return wrong == 0 ? String.valueOf(missing.size()) : missing.size() + ", " + wrong + " wrong";
    }
}
