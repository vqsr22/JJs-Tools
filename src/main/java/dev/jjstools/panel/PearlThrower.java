package dev.jjstools.panel;

import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.FreeLook;
import dev.jjstools.modules.ControlPanel;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls your pearl from a pearlbot's stasis chamber and puts a new one back in.
 *
 * Pulling whispers the pearlbot, which flips a trapdoor and brings you to the base. Bases
 * usually have many chambers side by side and there is no telling which one is yours, so the
 * chamber is found from where you land: the trapdoor with water at it closest to you the moment
 * you teleport. A couple of seconds later you need a pearl: one you already carry, or one the
 * bot handed you. Without one, Baritone walks you to the container you saved for that base and
 * takes a single pearl. Then you walk onto dry ground beside that chamber, across from its
 * trapdoor where there is room, flip the trapdoor back if it has been left the wrong way,
 * crouch-walk to the edge, throw a pearl straight down into the water and step back, ready for
 * next time.
 *
 * Each pearlbot is saved with the name of its base and its pearl container, from the pearls
 * panel, which is what starts it. Its options live in the Control Panel module's Pearls group.
 */
public final class PearlThrower {
    public static final PearlThrower INSTANCE = new PearlThrower();
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** One pearlbot and its base. Positions are x, y, z, or null when not set. */
    public static final class Bot {
        public String base = "";
        public String bot = "";
        /** What to whisper to the pearlbot to pull. */
        public String message = "";
        public String dimension = "minecraft:overworld";
        public int[] chest;

        public static BlockPos pos(int[] p) {
            return p == null || p.length < 3 ? null : new BlockPos(p[0], p[1], p[2]);
        }

        public static int[] of(BlockPos p) {
            return p == null ? null : new int[] { p.getX(), p.getY(), p.getZ() };
        }

        public String label() {
            return base.isBlank() ? (bot.isBlank() ? "unnamed" : bot) : base;
        }
    }

    private static final class Saved {
        List<Bot> bots = new ArrayList<>();
        int last = -1;
    }

    private enum Step { Idle, Arriving, Settling, ToChest, Opening, ToStand, Flipping, Edging, Aiming, Throwing, Letting, Retrieving }

    private Saved saved;
    private Bot job;
    private boolean pullQueued;
    private Step step = Step.Idle;
    private long since;
    private int tries;
    private boolean walking;
    private String status = "";
    /** Where you were last tick, to tell when the pull teleports you. */
    private Vec3d lastPos;
    private String lastDimension;
    /** Where you were when the pull was sent. */
    private Vec3d pullPos;
    /** Where you landed, and the chamber and throw spot worked out from it. */
    private BlockPos landing;
    private BlockPos chamber;
    private BlockPos stand;
    /** The chamber's water, and which way the throw spot is from it. */
    private BlockPos water;
    private Direction side;
    private double lastPast;
    /**
     * Rubber bands: water columns and Grim do not get on, so the server pulling you back is
     * expected rather than a failure. When it last did, and how many times a walk has been
     * started again because of one, or because Baritone gave up.
     */
    private volatile long rubberbandAt;
    private int retries;
    private long pathAt;
    private static final int MAX_RETRIES = 10;
    private int stalled;
    /**
     * Where the pearl was in your inventory when it was moved to the ninth hotbar slot, or -1.
     * Whatever had been in that hotbar slot went there, and is put back once the pearl is thrown.
     */
    private int movedFrom = -1;
    /**
     * What was in the ninth hotbar slot when it was dropped to make room for a pearl, with your
     * inventory full, and where you were when it went. Picked back up at the end.
     */
    private net.minecraft.item.ItemStack dropped;
    private Vec3d droppedAt;
    /** What to say once the dropped item is back: null after a throw, a reason after giving up. */
    private String afterRetrieve;
    private long droppedTime;
    /** Where the pearl was first seen near you, which is where it came from. */
    private Vec3d pearlFrom;
    private boolean retrieveFailed;

    private boolean subscribed;

    private PearlThrower() {}

    public static PearlThrower get() {
        if (!INSTANCE.subscribed) {
            INSTANCE.subscribed = true;
            MeteorClient.EVENT_BUS.subscribe(INSTANCE);
        }
        return INSTANCE;
    }

    // Saved pearlbots

    private static Path file() {
        return PanelFiles.file("pearlbots.json");
    }

    private Saved saved() {
        if (saved == null) saved = PanelFiles.read(file(), new TypeToken<Saved>() {}.getType(), new Saved());
        if (saved.bots == null) saved.bots = new ArrayList<>();
        // A file edited by hand, or from an older version, may leave fields out.
        saved.bots.removeIf(java.util.Objects::isNull);
        for (Bot b : saved.bots) {
            if (b.base == null) b.base = "";
            if (b.bot == null) b.bot = "";
            if (b.message == null) b.message = "";
        }
        return saved;
    }

    public List<Bot> bots() {
        return saved().bots;
    }

    public int lastUsed() {
        return saved().last;
    }

    public void save() {
        PanelFiles.write(file(), saved());
    }

    public Bot add() {
        Bot b = new Bot();
        if (mc.world != null) b.dimension = mc.world.getRegistryKey().getValue().toString();
        bots().add(b);
        save();
        return b;
    }

    public void remove(Bot b) {
        int i = bots().indexOf(b);
        if (i < 0) return;
        bots().remove(i);
        if (saved().last == i) saved().last = -1;
        else if (saved().last > i) saved().last--;
        if (job == b) stop("stopped");
        save();
    }

    // Running

    /** Whether a pull or reset is under way. */
    public boolean running() {
        return step != Step.Idle;
    }

    public Bot current() {
        return running() ? job : null;
    }

    /** A few words on what it is doing, for the panel. */
    public String status() {
        return status;
    }

    /** Pulls the pearl at this base and resets the chamber after; or, without pulling, only resets it. */
    public void run(Bot b, boolean pull) {
        if (b == null || running()) return;
        job = b;
        pullQueued = pull;
        saved().last = bots().indexOf(b);
        save();
        begin();
    }

    /** Stops whatever it is doing. */
    public void cancel() {
        if (running()) stop("stopped");
    }

    /** Free Look's own settings and state before it was borrowed, to put back after. Null when not borrowed. */
    private FreeLook.Mode freeLookMode;
    private boolean freeLookToggle;

    /**
     * Lets you look around freely while it turns your player: Meteor's Free Look in camera
     * mode moves your view with the mouse and leaves your player's rotation to this. Borrowed
     * only if it is not already on, and put back exactly as it was afterwards.
     */
    private void startFreeLook() {
        if (!ControlPanel.pearlFreeLook() || freeLookMode != null) return;
        FreeLook fl = Modules.get().get(FreeLook.class);
        if (fl == null || fl.isActive()) return;
        freeLookMode = fl.mode.get();
        freeLookToggle = fl.togglePerspective.get();
        fl.mode.set(FreeLook.Mode.Camera);
        fl.togglePerspective.set(false);
        fl.toggle();
    }

    private void stopFreeLook() {
        if (freeLookMode == null) return;
        FreeLook fl = Modules.get().get(FreeLook.class);
        if (fl != null) {
            // You carry on looking where you were looking while it ran, rather than the view
            // jumping to wherever the throw left your player facing.
            if (fl.isActive() && mc.player != null) {
                mc.player.setYaw(fl.cameraYaw);
                mc.player.setPitch(fl.cameraPitch);
            }
            if (fl.isActive()) fl.toggle();
            fl.mode.set(freeLookMode);
            fl.togglePerspective.set(freeLookToggle);
        }
        freeLookMode = null;
    }

    private void cleanUp() {
        putBack();
        dropped = null;
        droppedAt = null;
        pearlFrom = null;
        // Looking straight ahead again rather than down at the water, unless free look was on,
        // which hands back the view you had instead.
        if (mc.player != null && freeLookMode == null && (step == Step.Edging || step == Step.Aiming || step == Step.Throwing || step == Step.Letting)) mc.player.setPitch(0);
        stopFreeLook();
        if (walking) StashWalker.stopPath();
        walking = false;
        if (step == Step.Edging || step == Step.Aiming || step == Step.Throwing || step == Step.Letting) {
            mc.options.sneakKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
        }
        // Only once it had taken your keys: stopping while you wait leaves what you hold alone.
        if (step != Step.Arriving && step != Step.Idle) {
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
        }
        step = Step.Idle;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket && running()) rubberbandAt = System.currentTimeMillis();
    }

    private boolean rubberbanded(long withinMs) {
        return System.currentTimeMillis() - rubberbandAt < withinMs;
    }

    /**
     * Keeps a walk going: if Baritone has stopped short, perhaps after a rubber band, it waits a
     * moment for things to settle and walks again, up to a limit. False once it has run out.
     */
    private boolean keepWalking(BlockPos to, boolean onIt) {
        if (rubberbanded(400)) return true;
        if (walking && StashWalker.isPathing()) return true;
        if (System.currentTimeMillis() - pathAt < 1200) return true;
        if (retries >= MAX_RETRIES) return false;
        if (pathAt != 0) retries++;
        walking = StashWalker.pathTo(to, onIt);
        pathAt = System.currentTimeMillis();
        return walking;
    }

    /** Goes back to walking to the throw spot, after being pulled away from it. */
    private void backToStand() {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        if (retries++ >= MAX_RETRIES) {
            fail("Kept getting pulled back. Try again once the water settles.");
            return;
        }
        walking = false;
        pathAt = 0;
        step = Step.ToStand;
        since = System.currentTimeMillis();
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) {
        if (running()) stop("stopped: left the server");
    }

    private void begin() {
        if (mc.player == null || mc.world == null) {
            stop("not in a world");
            return;
        }
        // Free look starts with the reset; while waiting to be pulled you play as normal.
        if (!pullQueued) startFreeLook();
        tries = 0;
        retries = 0;
        chamber = null;
        stand = null;
        lastPos = dev.jjstools.compat.Compat.pos(mc.player);
        pullPos = lastPos;
        lastDimension = dimension();
        if (pullQueued) {
            String template = job.message == null ? "" : job.message.trim();
            if (job.bot.isBlank()) {
                stop("give " + job.label() + " a pearlbot username first");
                return;
            }
            if (template.isEmpty()) {
                stop("give " + job.label() + " a pull message first");
                return;
            }
            String msg = template.trim().replace("{bot}", job.bot).replace("{player}", dev.jjstools.compat.Compat.name(mc.player.getGameProfile()));
            // Plain words are whispered to the pearlbot; a command is sent as it is.
            if (!msg.startsWith("/")) msg = "/msg " + job.bot + " " + msg;
            ChatUtils.sendPlayerMsg(msg);
            go(Step.Arriving, "pulling at " + job.label());
        } else {
            landing = mc.player.getBlockPos();
            go(Step.Settling, "resetting the chamber here");
            since = 0;
        }
    }

    private void go(Step next, String words) {
        step = next;
        since = System.currentTimeMillis();
        tries = 0;
        if (words != null) status = words;
    }

    private void stop(String why) {
        status = why;
        cleanUp();
    }

    private void finish(String words) {
        ChatUtils.info("%s", words);
        stop(words);
    }

    private void fail(String why) {
        ChatUtils.warning("%s", why);
        stop(why);
    }

    private long elapsed() {
        return System.currentTimeMillis() - since;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (step == Step.Idle || mc.player == null || mc.world == null || job == null) return;
        BlockPos trapdoor = chamber;

        // Until the pull teleports you, you move as normal. From then on none of your movement
        // keys do anything, only the mouse; the steps below press what they need. This runs
        // before your player moves each tick, so a key you press in between is let go first.
        if (step != Step.Arriving) {
            boolean crouching = step == Step.Edging || step == Step.Aiming || step == Step.Throwing || step == Step.Letting;
            mc.options.forwardKey.setPressed(step == Step.Edging);
            mc.options.backKey.setPressed(step == Step.Letting);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.sneakKey.setPressed(crouching);
        }

        switch (step) {
            case Arriving -> {
                // The pull shows up as a jump in where you are: far in one tick, or another
                // dimension. Where you land is next to the chamber your pearl was in.
                Vec3d now = dev.jjstools.compat.Compat.pos(mc.player);
                String dim = dimension();
                // Or, if you were near the base already, the server moving you a few blocks after
                // the message went.
                boolean jumped = !dim.equals(lastDimension) || now.squaredDistanceTo(lastPos) > 16 * 16
                    || (rubberbandAt > since && now.squaredDistanceTo(pullPos) > 3 * 3);
                lastPos = now;
                lastDimension = dim;
                if (jumped) {
                    landing = mc.player.getBlockPos();
                    job.dimension = dim;
                    startFreeLook();
                    go(Step.Settling, "at " + job.label() + ", waiting for a pearl");
                } else if (elapsed() > ControlPanel.pearlArriveTimeout() * 1000L) {
                    fail("Never arrived at " + job.label() + ". Did the pearlbot get the message?");
                }
            }
            case Settling -> {
                if (chamber == null) {
                    // Found straight away, while you are still standing where you landed.
                    chamber = findChamber(landing);
                    if (chamber == null) {
                        fail("Could not find a stasis chamber where you landed.");
                        return;
                    }
                    water = waterOf(chamber);
                    // Water out of sight under a shut trapdoor: the pearl goes where the trapdoor is.
                    if (water == null) water = chamber;
                    stand = standSpot(chamber);
                }
                // Facing the trapdoor the whole time you wait, so anything dropped to make room
                // for a pearl lands on it, against the wall, and never goes the pearlbot's way.
                // With free look on your view stays where it is.
                faceTrapdoor();
                boolean pearl = InvUtils.find(Items.ENDER_PEARL).found();
                boolean full = mc.player.getInventory().getEmptySlot() == -1;
                // Watched from the moment you land, so where a pearl is first seen is close to
                // where it was thrown from.
                if (pearlFrom == null) pearlNearby();
                // A full inventory cannot pick up a pearl, so once a pearl is thrown or given to
                // you (a pearl lying on the ground near you), the ninth slot is emptied for it.
                // Nothing is dropped unless a pearl is actually coming.
                if (!pearl && full) {
                    var held = mc.player.getInventory().getStack(8);
                    if (dropped == null) {
                        var coming = pearlNearby();
                        if (coming != null) dropNinth(held, coming);
                    } else if (net.minecraft.item.ItemStack.areItemsAndComponentsEqual(held, dropped)) {
                        // Your own dropped items can be picked up again after two seconds; if it
                        // came back before the pearl, it goes out again, the same way.
                        dropNinth(held, pearlNearby());
                    }
                }
                if (elapsed() < ControlPanel.pearlWaitTime() * 1000) return;
                if (pearl) {
                    toStand();
                } else if (dropped != null) {
                    // Room was made: the pearl has the wait time from then to be picked up, or
                    // the throw is stopped and the item picked back up.
                    if (System.currentTimeMillis() - droppedTime < ControlPanel.pearlWaitTime() * 1000) return;
                    retrieve("No pearl came within " + ControlPanel.pearlWaitTime() + " seconds, so the throw was stopped.", true);
                } else if (Bot.pos(job.chest) != null) {
                    walking = StashWalker.pathTo(Bot.pos(job.chest), false);
                    pathAt = System.currentTimeMillis();
                    if (!walking) fail("No pearls, and Baritone could not walk to the pearl container.");
                    else go(Step.ToChest, "fetching pearls");
                } else {
                    fail("No pearls, and no pearl container saved for " + job.label() + ".");
                }
            }
            case ToChest -> {
                BlockPos chest = Bot.pos(job.chest);
                if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(chest)) <= 4.3 * 4.3) {
                    StashWalker.stopPath();
                    walking = false;
                    click(chest);
                    go(Step.Opening, null);
                } else if (elapsed() > 120_000 || !keepWalking(chest, false)) {
                    fail("Could not reach the pearl container.");
                }
            }
            case Opening -> {
                ScreenHandler h = mc.player.currentScreenHandler;
                boolean open = h instanceof GenericContainerScreenHandler || h instanceof ShulkerBoxScreenHandler;
                if (!open) {
                    if (elapsed() > 3000) {
                        // Pulled out of reach before it opened, most likely: walk back and try again.
                        if (retries++ >= MAX_RETRIES) {
                            fail("The pearl container did not open.");
                            return;
                        }
                        walking = false;
                        pathAt = 0;
                        step = Step.ToChest;
                        since = System.currentTimeMillis();
                    }
                    return;
                }
                // Let the slots arrive before reading them.
                if (elapsed() < 300) return;
                int own = h.slots.size() - 36;
                int found = -1;
                for (int i = 0; i < own; i++) {
                    if (h.getSlot(i).getStack().isOf(Items.ENDER_PEARL)) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    mc.player.closeHandledScreen();
                    fail("The pearl container has no pearls in it.");
                    return;
                }
                // Just the one: pick the stack up, put a single pearl in an empty slot of yours,
                // and put the rest back where it was.
                int empty = -1;
                for (int i = own; i < h.slots.size(); i++) {
                    if (h.getSlot(i).getStack().isEmpty()) {
                        empty = i;
                        break;
                    }
                }
                if (empty < 0) {
                    mc.player.closeHandledScreen();
                    fail("No room in your inventory for a pearl.");
                    return;
                }
                mc.interactionManager.clickSlot(h.syncId, found, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(h.syncId, empty, 1, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(h.syncId, found, 0, SlotActionType.PICKUP, mc.player);
                mc.player.closeHandledScreen();
                toStand();
            }
            case ToStand -> {
                if (stand == null) {
                    fail("No room to stand across from the chamber's trapdoor.");
                    return;
                }
                // Baritone counts the spot reached anywhere in the block, so that is what counts here
                // too; the walk to the edge works from wherever in it you are.
                if (mc.player.getBlockPos().equals(stand) && mc.player.isOnGround()) {
                    if (walking) StashWalker.stopPath();
                    walking = false;
                    go(Step.Flipping, "resetting the chamber");
                    return;
                }
                if (elapsed() > 90_000 || !keepWalking(stand, true)) fail("Could not reach the throw spot.");
            }
            case Flipping -> {
                if (rubberbanded(300) || mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(trapdoor)) > 4.5 * 4.5) {
                    backToStand();
                    return;
                }
                BlockState state = mc.world.getBlockState(trapdoor);
                if (!(state.getBlock() instanceof TrapdoorBlock)) {
                    fail("The chamber's trapdoor is gone.");
                    return;
                }
                boolean want = ControlPanel.pearlTrapdoorOpen();
                if (state.get(Properties.OPEN) == want) {
                    // Crouching keeps you from stepping off the edge into the water.
                    mc.options.sneakKey.setPressed(true);
                    lastPast = past();
                    stalled = 0;
                    go(Step.Edging, null);
                    return;
                }
                // Give each flip a moment to come back from the server before trying again.
                if (tries > 0 && elapsed() < 400) return;
                if (tries >= 3) {
                    fail("The trapdoor would not flip.");
                    return;
                }
                click(trapdoor);
                tries++;
                since = System.currentTimeMillis();
            }
            case Edging -> {
                // Leaning out moves you a little over the water, so only a real move away counts.
                Vec3d at = dev.jjstools.compat.Compat.pos(mc.player);
                double ox = at.x - (stand.getX() + 0.5), oz = at.z - (stand.getZ() + 0.5);
                if (rubberbanded(300) || fellIn() || ox * ox + oz * oz > 1.0) {
                    backToStand();
                    return;
                }
                // Crouch-walk to the very edge, leaning out over the water, so the pearl can go
                // straight down into it.
                mc.options.sneakKey.setPressed(true);
                mc.player.setYaw((float) Rotations.getYaw(Vec3d.ofCenter(water)));
                mc.player.setPitch(0);
                mc.options.forwardKey.setPressed(true);
                double past = past();
                stalled = Math.abs(past - lastPast) < 0.003 ? stalled + 1 : 0;
                lastPast = past;
                // A pearl is a quarter of a block wide, so straight down only clears the edge of the
                // block you stand on once you lean out further than half of that.
                if (past >= 0.27 || (elapsed() > 300 && stalled >= 4) || elapsed() > 3000) {
                    mc.options.forwardKey.setPressed(false);
                    go(Step.Aiming, null);
                }
            }
            case Aiming -> {
                if (rubberbanded(300) || fellIn()) {
                    backToStand();
                    return;
                }
                mc.options.sneakKey.setPressed(true);
                FindItemResult hotbar = InvUtils.findInHotbar(Items.ENDER_PEARL);
                if (!hotbar.found()) {
                    FindItemResult any = InvUtils.find(Items.ENDER_PEARL);
                    if (!any.found()) {
                        fail("Out of pearls.");
                        return;
                    }
                    movedFrom = any.slot();
                    InvUtils.move().from(movedFrom).toHotbar(8);
                    since = System.currentTimeMillis();
                    return;
                }
                // A few ticks crouched first, so the server has you crouching before the throw.
                if (elapsed() < 250) return;
                if (past() >= 0.15) {
                    // Leaning out over the water: straight down.
                    mc.player.setPitch(90);
                } else {
                    // Could not get over the edge: aim into the water instead.
                    Vec3d centre = Vec3d.ofCenter(water).add(0, 0.3, 0);
                    mc.player.setYaw((float) Rotations.getYaw(centre));
                    mc.player.setPitch((float) Rotations.getPitch(centre));
                }
                go(Step.Throwing, null);
            }
            case Throwing -> {
                if (rubberbanded(300) || fellIn()) {
                    backToStand();
                    return;
                }
                // One tick after turning, so the throw goes where you now look.
                FindItemResult hotbar = InvUtils.findInHotbar(Items.ENDER_PEARL);
                if (!hotbar.found()) {
                    fail("Out of pearls.");
                    return;
                }
                InvUtils.swap(hotbar.slot(), ControlPanel.pearlSwapBack());
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);
                if (ControlPanel.pearlSwapBack()) InvUtils.swapBack();
                // Straight back off the edge, so you do not touch the pearl as it drops.
                mc.options.backKey.setPressed(true);
                go(Step.Letting, null);
            }
            case Letting -> {
                // Back to the middle of the block you threw from, still crouched, facing the water
                // and looking straight ahead.
                mc.options.sneakKey.setPressed(true);
                if (freeLookMode == null) mc.player.setPitch(0);
                mc.player.setYaw((float) Rotations.getYaw(Vec3d.ofCenter(water)));
                mc.options.backKey.setPressed(true);
                if (past() > -0.45 && elapsed() < 2000) return;
                mc.options.backKey.setPressed(false);
                mc.options.sneakKey.setPressed(false);
                if (dropped != null) retrieve("Reset the stasis chamber at " + job.label() + ".", false);
                else finish("Reset the stasis chamber at " + job.label() + ".");
            }
            case Retrieving -> {
                // Walks onto the item dropped to make room, until it is picked up or gone.
                var item = droppedItem();
                if (item == null || elapsed() > 10_000) {
                    if (walking) StashWalker.stopPath();
                    walking = false;
                    String words = afterRetrieve;
                    if (item != null) words += " Could not pick the dropped " + dropped.getName().getString().toLowerCase(java.util.Locale.ROOT) + " back up.";
                    if (retrieveFailed) fail(words);
                    else finish(words);
                    return;
                }
                if (!keepWalking(item.getBlockPos(), true)) {
                    if (walking) StashWalker.stopPath();
                    walking = false;
                    String words = afterRetrieve + " Could not walk to the dropped " + dropped.getName().getString().toLowerCase(java.util.Locale.ROOT) + ".";
                    if (retrieveFailed) fail(words);
                    else finish(words);
                }
            }
            default -> {
            }
        }
    }

    /** A pearl lying on the ground near you, thrown or given by the pearlbot, or null. */
    private net.minecraft.entity.ItemEntity pearlNearby() {
        for (var e : mc.world.getEntities()) {
            if (e instanceof net.minecraft.entity.ItemEntity item && item.isAlive()
                && item.getStack().isOf(Items.ENDER_PEARL)
                && item.squaredDistanceTo(mc.player) <= 8 * 8) {
                if (pearlFrom == null) pearlFrom = dev.jjstools.compat.Compat.pos(item);
                return item;
            }
        }
        return null;
    }

    /** Drops the ninth hotbar slot to make room for a pearl, onto the trapdoor you are facing. */
    private void dropNinth(net.minecraft.item.ItemStack held, net.minecraft.entity.ItemEntity pearlItem) {
        // Already facing the trapdoor since you landed, so it goes there.
        if (dropped == null) {
            dropped = held.copy();
            droppedAt = dev.jjstools.compat.Compat.pos(mc.player);
            status = "pearl coming, inventory full: dropped " + held.getName().getString().toLowerCase(java.util.Locale.ROOT) + " onto the trapdoor";
        }
        droppedTime = System.currentTimeMillis();
        InvUtils.drop().slotHotbar(8);
    }

    /** Faces the chamber's trapdoor, a little past its middle, so a dropped item settles against the wall behind. */
    private void faceTrapdoor() {
        if (chamber == null) return;
        Vec3d centre = Vec3d.ofCenter(chamber);
        Vec3d me = mc.player.getEyePos();
        Vec3d flat = new Vec3d(centre.x - me.x, 0, centre.z - me.z);
        if (flat.lengthSquared() > 1.0E-4) flat = flat.normalize().multiply(0.35);
        Vec3d target = new Vec3d(centre.x + flat.x, chamber.getY() + 0.6, centre.z + flat.z);
        mc.player.setYaw((float) Rotations.getYaw(target));
        mc.player.setPitch((float) Math.max(-30.0, Math.min(80.0, Rotations.getPitch(target))));
    }

    /** Goes to pick up the item dropped to make room, then finishes, or fails when failed is true, with words. */
    private void retrieve(String words, boolean failed) {
        afterRetrieve = words;
        retrieveFailed = failed;
        walking = false;
        pathAt = 0;
        retries = 0;
        go(Step.Retrieving, "picking the dropped item back up");
    }

    /** The item dropped to make room, still on the ground near where it was dropped, or null. */
    private net.minecraft.entity.ItemEntity droppedItem() {
        if (dropped == null || droppedAt == null) return null;
        net.minecraft.entity.ItemEntity best = null;
        double bestDist = 8 * 8;
        for (var e : mc.world.getEntities()) {
            if (!(e instanceof net.minecraft.entity.ItemEntity item) || !item.isAlive()) continue;
            if (!net.minecraft.item.ItemStack.areItemsAndComponentsEqual(item.getStack(), dropped)) continue;
            double d = dev.jjstools.compat.Compat.pos(item).squaredDistanceTo(droppedAt);
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        return best;
    }

    /** Puts what was in the ninth hotbar slot back there, the pearl now thrown, if the pearl had moved it. */
    private void putBack() {
        int from = movedFrom;
        movedFrom = -1;
        if (from < 0 || mc.player == null || mc.interactionManager == null) return;
        // Only from your own inventory screen; a chest still open would have other slot numbers.
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;
        if (mc.player.getInventory().getStack(from).isEmpty()) return;
        InvUtils.move().from(from).toHotbar(8);
    }

    private void toStand() {
        walking = false;
        pathAt = 0;
        go(Step.ToStand, "walking to the chamber");
    }

    private void click(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d d = eye.subtract(Vec3d.ofCenter(pos));
        Direction face = Direction.getFacing(d.x, d.y, d.z);
        Vec3d hit = Vec3d.ofCenter(pos).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private String dimension() {
        return mc.world.getRegistryKey().getValue().toString();
    }

    /**
     * The stasis chamber you were pulled from: of the trapdoors with water at them near where
     * you landed, the closest. The pearl sat in that water, and you arrive where the pearl was.
     */
    private BlockPos findChamber(BlockPos around) {
        if (around == null) return null;
        // Standing on the trapdoor itself (it is shut, so you land on it): that is the chamber,
        // whether or not its water can be seen from here.
        for (BlockPos p : new BlockPos[] { around, around.down(), mc.player.getBlockPos(), mc.player.getBlockPos().down() }) {
            if (mc.world.getBlockState(p).getBlock() instanceof TrapdoorBlock) return p.toImmutable();
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -3; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = around.add(dx, dy, dz);
                    if (!(mc.world.getBlockState(p).getBlock() instanceof TrapdoorBlock)) continue;
                    BlockPos water = waterOf(p);
                    if (water == null) continue;
                    // Chambers sit a block or two apart, so what counts is whose water column
                    // you are in: across matters far more than up and down.
                    double dx2 = water.getX() - around.getX(), dz2 = water.getZ() - around.getZ();
                    double dy2 = water.getY() - around.getY();
                    double d = (dx2 * dx2 + dz2 * dz2) * 4 + dy2 * dy2;
                    if (d < bestDist) {
                        bestDist = d;
                        best = p.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    /**
     * The chamber's water: in the trapdoor's own block, under it (the usual build, a hole of
     * water in the floor with the trapdoor standing open above it, sometimes a few blocks
     * down), or beside it. Null if none.
     */
    private BlockPos waterOf(BlockPos trapdoor) {
        if (water(trapdoor)) return trapdoor;
        // Under it, up to a few blocks down for a chamber whose water sits lower.
        for (int down = 1; down <= 3; down++) {
            if (water(trapdoor.down(down))) return trapdoor.down(down);
        }
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (water(trapdoor.offset(d))) return trapdoor.offset(d);
        }
        return null;
    }

    private boolean water(BlockPos p) {
        return mc.world.getFluidState(p).isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }

    /**
     * Where to stand to throw: dry ground right beside the chamber's water, never in it. The
     * side across from the trapdoor comes first (an open trapdoor lies against the side opposite
     * the way it faces, so across from it is the way it faces), then the sides, then behind.
     */
    private BlockPos standSpot(BlockPos trapdoor) {
        if (trapdoor == null || water == null) return null;
        BlockState state = mc.world.getBlockState(trapdoor);
        Direction away = state.getBlock() instanceof TrapdoorBlock ? state.get(Properties.HORIZONTAL_FACING) : Direction.NORTH;
        Direction[] order = { away, away.rotateYClockwise(), away.rotateYCounterclockwise(), away.getOpposite() };
        int[] heights = { 1, 0, 2, -1 };
        for (Direction d : order) {
            BlockPos column = water.offset(d);
            for (int dy : heights) {
                BlockPos feet = column.up(dy);
                if (standable(feet)) {
                    side = d;
                    return feet;
                }
            }
        }
        return null;
    }

    /** Somewhere dry to stand: room for you, no water, and solid ground that is not water either. */
    private boolean standable(BlockPos feet) {
        return clear(feet) && clear(feet.up())
            && !mc.world.getBlockState(feet.down()).getCollisionShape(mc.world, feet.down()).isEmpty()
            && mc.world.getFluidState(feet.down()).isEmpty();
    }

    private boolean clear(BlockPos p) {
        return mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty() && mc.world.getFluidState(p).isEmpty();
    }

    /** Whether you have dropped off the throw spot, into the chamber or off the side. */
    private boolean fellIn() {
        return stand != null && mc.player.getY() < stand.getY() - 0.4;
    }

    /** How far your middle has leant out past the edge of the throw spot, over the water. */
    private double past() {
        if (water == null || side == null) return 0;
        Vec3d edge = Vec3d.ofCenter(water).add(side.getOffsetX() * 0.5, 0, side.getOffsetZ() * 0.5);
        Vec3d at = dev.jjstools.compat.Compat.pos(mc.player);
        return -((at.x - edge.x) * side.getOffsetX() + (at.z - edge.z) * side.getOffsetZ());
    }
}
