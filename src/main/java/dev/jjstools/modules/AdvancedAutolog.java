package dev.jjstools.modules;

import java.util.List;
import java.util.ArrayDeque;
import dev.jjstools.JJsTools;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.item.Items;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import meteordevelopment.meteorclient.systems.friends.Friends;
import dev.jjstools.util.MsgUtil;
import org.jetbrains.annotations.Nullable;
import net.minecraft.item.ItemStack;
import dev.jjstools.util.JJUtil;
import dev.jjstools.util.LogDetails;
import net.minecraft.sound.SoundEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import dev.jjstools.util.JJConfig;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.component.DataComponentTypes;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
//#if MC >= 1.21.6
import meteordevelopment.meteorclient.systems.modules.player.AntiAFK;
//#else
//$$ import meteordevelopment.meteorclient.systems.modules.movement.AntiAFK;
//#endif
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import dev.jjstools.mixin.accessor.DisconnectS2CPacketAccessor;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;

/**
 * @author Tas [0xTas] <root@0xTas.dev>
 **/
public class AdvancedAutolog extends Module {
    public AdvancedAutolog() {
        super(JJsTools.CATEGORY, "advanced-autolog", "Tools for AFK-travelling over long distances.");
    }

    @SuppressWarnings("unused")
    public enum ToggleModes {
        Module, Settings, None
    }

    private final SettingGroup sgAutoLog = settings.createGroup("AutoLog Settings");
    private final SettingGroup sgNotify = settings.createGroup("Notification Settings");
    private final SettingGroup sgCoords = settings.createGroup("Coords AutoLog");
    private final SettingGroup sgIdle = settings.createGroup("Standing Still");

    /** Which world the target coordinates belong to. */
    public enum TargetDimension {
        Any,
        Overworld,
        Nether,
        End
    }
    private final SettingGroup sgPreLog = settings.createGroup("Before Logging");

    /** What standing still should do: leave, or try to get moving again. */
    public enum IdleAction {
        Disconnect,
        Chat
    }

    private final Setting<Boolean> forceKick = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("illegal-disconnect")
            .description("Forces the server to kick you immediately by sending an illegal packet.")
            .defaultValue(true)
            .build()
    );
    private final Setting<ToggleModes> autoLogToggle = sgAutoLog.add(
        new EnumSetting.Builder<ToggleModes>()
            .name("disable-on-disconnect")
            .description("Disables either the module or the AutoLog settings on disconnect, to prevent kicks on rejoin.")
            .defaultValue(ToggleModes.Module)
            .build()
    );

    private final Setting<Boolean> disableAutoReconnect = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("disable-auto-reconnect")
            .description("Switch auto reconnect off on the way out, so a log you asked for is not undone a second later.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> onlyWhenGeared = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("only-when-geared")
            .description("Only log out for a player wearing armour. Applies to the player triggers below, since a naked player is usually a corpse run rather than a threat.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> antiTrapAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("anti-trap-autoLog")
            .description("Illegally disconnects you when any TNT minecarts are rendered, to avoid highway traps.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> timeoutAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("timer-autoLog")
            .description("Logs you out x minutes after enabling the module, or the setting.")
            .defaultValue(false)
            .onChanged(it -> {
                if (it) this.setLogOutTimer(this.timeoutAutoLogTimer.get());
                else this.disableLogOutTimer();
            })
            .build()
    );
    private final Setting<Integer> timeoutAutoLogTimer = sgAutoLog.add(
        new IntSetting.Builder()
            .name("timer-autoLog-seconds")
            .description("Logs you out x seconds after enabling the module, or the Timer AutoLog setting.")
            .min(0).noSlider().defaultValue(3600)
            .onChanged(it -> {
                if (timeoutAutoLog.get()) this.setLogOutTimer(it);
            })
            .build()
    );

    private final Setting<Boolean> lowElytraAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("elytraStock-autoLog")
            .description("Logs you out if your stock of elytras is running low.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> elytraStock = sgAutoLog.add(
        new IntSetting.Builder()
            .name("elytraStock-autoLog-threshold")
            .description("Logs you out if your stock of elytras is running low.")
            .range(0, 40).sliderRange(0, 30).defaultValue(1)
            .build()
    );

    private final Setting<Boolean> lowRocketsAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("rocketStock-autoLog")
            .description("Logs you out if your stock of firework rockets is running low.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> rocketStock = sgAutoLog.add(
        new IntSetting.Builder()
            .name("rocketStock-autoLog-threshold")
            .description("Logs you out if your stock of firework rockets is running low.")
            .range(0, 1024).sliderRange(0, 128).defaultValue(32)
            .build()
    );
    private final Setting<Boolean> lowFoodAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("foodStock-autoLog")
            .description("Logs you out if your stock of chosen food is running low.")
            .defaultValue(false)
            .build()
    );
    private final Setting<List<Item>> chosenFood = sgAutoLog.add(
        new ItemListSetting.Builder()
            .name("valid-food")
            .description("Which food to look for when deciding whether to disconnect you.")
            .filter(stack -> stack.getComponents().contains(DataComponentTypes.FOOD))
            .defaultValue(List.of(Items.GOLDEN_CARROT, Items.COOKED_BEEF, Items.ENCHANTED_GOLDEN_APPLE))
            .build()
    );
    private final Setting<Integer> foodStock = sgAutoLog.add(
        new IntSetting.Builder()
            .name("foodStock-autoLog-threshold")
            .description("Logs you out if your stock of chosen food is running low.")
            .range(0, 1024).sliderRange(0, 128).defaultValue(0)
            .build()
    );

    private final Setting<Boolean> yLevelAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("y-level-autoLog")
            .description("Logs you out if your Y level gets too low.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> yLevelThreshold = sgAutoLog.add(
        new IntSetting.Builder()
            .name("y-level-threshold")
            .description("Logs you out if your Y level is lower than the threshold.")
            .range(-69, 30000000).noSlider().defaultValue(-69)
            .build()
    );

    private final Setting<Boolean> healthAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("health-autoLog")
            .description("Logs you out when your health drops to the threshold.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Double> healthThreshold = sgAutoLog.add(
        new DoubleSetting.Builder()
            .name("health-threshold")
            .description("Half hearts. 8 is four hearts, which leaves room for one more hit while the disconnect goes through.")
            .visible(healthAutoLog::get)
            .range(1, 20).sliderRange(1, 20).defaultValue(8)
            .build()
    );

    private final Setting<Boolean> totemAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("totem-pop-autoLog")
            .description("Logs you out once this many totems have popped.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> totemPopLimit = sgAutoLog.add(
        new IntSetting.Builder()
            .name("totem-pops")
            .description("Pops to allow before logging. 1 means the first pop takes you off.")
            .visible(totemAutoLog::get)
            .range(1, 20).sliderRange(1, 10).defaultValue(1)
            .build()
    );

    private final Setting<Boolean> pearlPullAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("pearl-pulled-autoLog")
            .description("Logs you out when something teleports you a long way that you did not ask for, which on this server means a stasis chamber holding one of your pearls was just opened.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> pearlPullDistance = sgAutoLog.add(
        new IntSetting.Builder()
            .name("pearl-pull-distance")
            .description("Blocks a teleport has to move you before it counts as a pull. Well above the few blocks a lagback moves you, well below a pull worth worrying about.")
            .visible(pearlPullAutoLog::get)
            .range(8, 5000).sliderRange(16, 500).defaultValue(64)
            .build()
    );

    private final Setting<Boolean> crystalAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("crystal-autoLog")
            .description("Logs you out when an end crystal is placed close enough to hurt you, before it is hit rather than after.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> crystalRadius = sgAutoLog.add(
        new IntSetting.Builder()
            .name("crystal-radius")
            .description("Blocks. A crystal does its worst within about six, so eight leaves a little room.")
            .visible(crystalAutoLog::get)
            .range(2, 32).sliderRange(2, 16).defaultValue(8)
            .build()
    );

    private final Setting<Boolean> proximityAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("proximity-autoLog")
            .description("Logs you out when another player comes within range.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> proximityRadius = sgAutoLog.add(
        new IntSetting.Builder()
            .name("proximity-radius")
            .description("Blocks. Anyone closer than this sets it off.")
            .visible(proximityAutoLog::get)
            .range(8, 256).sliderRange(16, 128).defaultValue(64)
            .build()
    );
    private final Setting<Boolean> proximityIgnoreFriends = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("proximity-ignore-friends")
            .description("Do not log out for your Meteor friends.")
            .visible(proximityAutoLog::get)
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> proximityIgnoreNaked = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("proximity-ignore-naked")
            .description("Do not log out for a player wearing no armour.")
            .visible(proximityAutoLog::get)
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> elytraNotify = sgNotify.add(
        new BoolSetting.Builder()
            .name("elytra-notify")
            .description("Notify you with sound pings when your last elytra is below 5% durability.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> lagNotify = sgNotify.add(
        new BoolSetting.Builder()
            .name("lag-notify")
            .description("Play a sound notification if you've stopped making progress (excess rubberbanding, hit a roadblock, etc.)")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> pingVolume = sgNotify.add(
        new IntSetting.Builder()
            .name("ping-sound-volume")
            .description("How loud the sound is, from 0 to 100.")
            .defaultValue(50).min(0).max(100).sliderRange(0, 100)
            .build()
    );

    private final Setting<Boolean> totemPopNotify = sgNotify.add(new BoolSetting.Builder()
        .name("totem-pop-notify")
        .description("Say in chat when a totem pops and how many are left in your inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLogDetails = sgNotify.add(new BoolSetting.Builder()
        .name("show-log-details")
        .description("Print where you were, how you were doing and who was around at the moment you logged. Read it after reconnecting to work out what happened.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> restartAutoLog = sgAutoLog.add(new BoolSetting.Builder()
        .name("restart-autoLog")
        .description("Disconnect when the server announces a restart, rather than being thrown out by it.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> restartPhrases = sgAutoLog.add(new StringListSetting.Builder()
        .name("restart-phrases")
        .description("Chat text that means a restart is coming. Matched anywhere in the line, ignoring case.")
        .defaultValue(List.of("server restart", "restarting in", "server is restarting", "scheduled restart"))
        .visible(restartAutoLog::get)
        .build()
    );

    // Before Logging

    private final Setting<Boolean> preLogTask = sgPreLog.add(new BoolSetting.Builder()
        .name("interact-before-logging")
        .description("Click a block before disconnecting. Meant for shutting a trapdoor or flipping a lever behind you so you are not left exposed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<BlockPos> preLogPos = sgPreLog.add(new BlockPosSetting.Builder()
        .name("block")
        .description("The trapdoor, lever or button to click. Must be within reach when the log fires.")
        .defaultValue(BlockPos.ORIGIN)
        .visible(preLogTask::get)
        .build()
    );

    private final Setting<Boolean> preLogRotate = sgPreLog.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Turn towards the block through Meteor's silent rotation system, so the server sees a sane look direction while your view stays put.")
        .defaultValue(true)
        .visible(preLogTask::get)
        .build()
    );

    private final Setting<Integer> preLogDelay = sgPreLog.add(new IntSetting.Builder()
        .name("log-delay-after-interact")
        .description("Ticks to wait after clicking before actually disconnecting, so the server has time to act on it. 20 is one second.")
        .defaultValue(10).min(0).sliderRange(0, 100)
        .visible(preLogTask::get)
        .build()
    );

    private final Setting<Boolean> coordsAutoLog = sgCoords.add(new BoolSetting.Builder()
        .name("coords-autoLog")
        .description("Logs you out when you come within range of a spot you name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<TargetDimension> coordsDimension = sgCoords.add(new EnumSetting.Builder<TargetDimension>()
        .name("dimension")
        .description("Which world the coordinates are in. Nothing is translated between dimensions, so nether coordinates are nether coordinates.")
        .visible(coordsAutoLog::get)
        .defaultValue(TargetDimension.Any)
        .build()
    );

    private final Setting<Integer> coordsX = sgCoords.add(new IntSetting.Builder()
        .name("x-coord")
        .visible(coordsAutoLog::get)
        .range(-30000000, 30000000).noSlider().defaultValue(0)
        .build()
    );

    private final Setting<Integer> coordsZ = sgCoords.add(new IntSetting.Builder()
        .name("z-coord")
        .visible(coordsAutoLog::get)
        .range(-30000000, 30000000).noSlider().defaultValue(0)
        .build()
    );

    private final Setting<Integer> coordsRange = sgCoords.add(new IntSetting.Builder()
        .name("range")
        .description("Blocks. Measured flat, so your height does not matter.")
        .visible(coordsAutoLog::get)
        .range(1, 30000).sliderRange(1, 1000).defaultValue(200)
        .build()
    );

    private final Setting<Boolean> coordsInMessage = sgCoords.add(new BoolSetting.Builder()
        .name("show-coords")
        .description("Put the spot you arrived at in the disconnect message.")
        .visible(coordsAutoLog::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> idleAutoLog = sgIdle.add(new BoolSetting.Builder()
        .name("standing-still-autoLog")
        .description("Act when you have not moved, looked around or touched a key for a while. Mobs knocking you about do not count as you moving, so one zombie cannot keep you logged in all night.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> idleSeconds = sgIdle.add(new IntSetting.Builder()
        .name("standing-still-seconds")
        .description("How long with no movement and no input before it acts.")
        .visible(idleAutoLog::get)
        .range(3, 60).sliderRange(3, 60).defaultValue(60)
        .build()
    );

    private final Setting<Boolean> idleIgnoreAntiAfk = sgIdle.add(new BoolSetting.Builder()
        .name("ignore-anti-afk")
        .description("Do not let Meteor's Anti AFK count as you being there. Off means Anti AFK keeps the clock reset and this never fires while it is running.")
        .visible(idleAutoLog::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<IdleAction> idleAction = sgIdle.add(new EnumSetting.Builder<IdleAction>()
        .name("standing-still-action")
        .description("Disconnect leaves. Chat says the lines below instead and keeps waiting, so a stalled Baritone can be told to carry on.")
        .visible(idleAutoLog::get)
        .defaultValue(IdleAction.Disconnect)
        .build()
    );

    private final Setting<List<String>> idleLines = sgIdle.add(new StringListSetting.Builder()
        .name("lines")
        .description("One entry per message, said in order as though you typed them. A Baritone command works here.")
        .visible(() -> idleAutoLog.get() && idleAction.get() == IdleAction.Chat)
        .defaultValue(List.of("#resume"))
        .build()
    );

    private final Setting<Integer> idleLineDelay = sgIdle.add(new IntSetting.Builder()
        .name("delay-between-lines")
        .description("Ticks between each line. 20 is one second. Servers drop messages sent too close together.")
        .visible(() -> idleAutoLog.get() && idleAction.get() == IdleAction.Chat)
        .range(0, 200).sliderRange(0, 100).defaultValue(20)
        .build()
    );

    /** A log that is waiting on the pre-log task. */
    private @Nullable Text pendingReason = null;
    private boolean pendingForce = false;
    private int pendingTicks = 0;

    /**
     * The last thing that hurt you, kept because a disconnect wipes the world and with it any
     * chance of asking afterwards.
     */
    private @Nullable String lastDamage = null;
    private float lastHealth = -1f;

    private int timer = 0;
    private int ticksNotMoved = 0;

    /*
     * Ticks in which a teleport is something you caused rather than something done to you.
     *
     * Joining, changing dimension and landing your own pearl all move you a long way through the
     * same packet a pull arrives on, so the only thing separating them is whether you set it off.
     * Each of those starts a quiet period, and a teleport inside one is not a pull.
     */
    private int pullQuiet = 0;
    private RegistryKey<World> pullDimension = null;

    // Standing still, tracked separately from ticksNotMoved, which is only kept up to date while
    // the ETA readout is on and is reset by the lag warning.
    private int idleTicks = 0;
    private double idleX, idleY, idleZ;
    private float idleYaw, idlePitch;
    private double idleDrift = 0;
    private boolean idleKnown = false;

    /** Ticks after a hit during which being moved is the hit's doing rather than yours. */
    private static final int KNOCKBACK_TICKS = 20;

    /** How far you can be shoved in total before it stops being a shove and starts being travel. */
    private static final double DRIFT_LIMIT = 16.0;

    // The second idle line, waiting on its delay.
    /** Lines still waiting to be said, and the ticks left before the next one. */
    private final ArrayDeque<String> queuedLines = new ArrayDeque<>();
    private int queuedTicks = 0;

    // Totems, counted rather than read off a packet. See popCheck.
    private int lastTotems = -1;
    private int totemPops = 0;
    private int ticksSinceHurt = 999;
    private int ticksSinceWarned = 0;
    private long logOutTimer = -69L;
    private long timerTimestamp = 0L;
    private @Nullable BlockPos lastPos = null;
    private @Nullable Text disconnectReason = null;

    private void setLogOutTimer(int timer) {
        logOutTimer = timer;
        timerTimestamp = System.currentTimeMillis();
        if (timeoutAutoLog.get() && isActive()) {
            MsgUtil.updateModuleMsg("Set Timer AutoLog to disconnect you §a§o" + timeoutAutoLogTimer.get() + " §7seconds from now.", this.name, "timerAutoLog".hashCode());
        }
    }
    private void disableLogOutTimer() {
        logOutTimer = -69L;
    }

    @Override
    public void onActivate() {
        capturedDetails = null;
        lastDamage = null;
        lastHealth = -1f;
        disconnectReason = null;
        resetIdle();
        lastTotems = -1;
        totemPops = 0;
        queuedLines.clear();
        pullQuiet = SETTLE_TICKS;
        pullDimension = null;
        timerTimestamp = System.currentTimeMillis();
        if (timeoutAutoLog.get()) {
            MsgUtil.updateModuleMsg("Set Timer AutoLog to disconnect you §a§o" + timeoutAutoLogTimer.get()  + " §7seconds from now.", this.name, "timerAutoLog".hashCode());
        }
    }

    @Override
    public void onDeactivate() {
        pendingReason = null;
        pendingTicks = 0;
        timer = 0;
        lastPos = null;
        ticksNotMoved = 0;
        logOutTimer = -69L;
        timerTimestamp = 0L;
        ticksSinceWarned = 0;
        resetIdle();
        lastTotems = -1;
        totemPops = 0;
        queuedLines.clear();
        pullQuiet = SETTLE_TICKS;
        pullDimension = null;
    }

    private void handleDurabilityChecks() {
        if (mc.player == null) return;
        if (ticksSinceWarned < 100) return;

        boolean reset = false;
        if (elytraNotify.get()) {
            if (mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) return;

            ItemStack equippedElytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);

            int maxDurability = equippedElytra.getMaxDamage();
            int currentDurability = maxDurability - equippedElytra.getDamage();
            double percentDurability = Math.floor((currentDurability / (double) maxDurability) * 100);

            if (percentDurability <= 5) {
                mc.player.playSound(SoundEvents.ENTITY_ITEM_BREAK.value(), pingVolume.get() / 100.0F, 1f);
                MsgUtil.updateModuleMsg("Elytra durability: §c" + percentDurability + "§7%", this.name, "roadTripElytraWarn".hashCode());
                reset = true;
            }
        }

        if (lagNotify.get() && ticksNotMoved >= 80) {
            reset = true;
            ticksNotMoved = 0;
            mc.player.playSound(SoundEvents.ENTITY_PHANTOM_SWOOP, pingVolume.get() / 100.0F, 1f);
        }

        if (reset) ticksSinceWarned = 0;
    }

    /*
     * The stock checks used to walk only the 36 main slots.
     *
     * That misses three places things genuinely live: your offhand, which is where most people
     * keep rockets; your armour slots; and the cursor, where an item sits while you are holding
     * it with the inventory open. Picking a stack up to move it made the count drop, and on a
     * stack you were already near the threshold on, that was enough to log you out mid shuffle.
     */

    /** Every slot you are carrying in, plus one on the end for the cursor. */
    private int carriedSize() {
        return mc.player.getInventory().size() + 1;
    }

    private ItemStack carried(int index) {
        var inventory = mc.player.getInventory();
        if (index < inventory.size()) return inventory.getStack(index);

        return mc.player.currentScreenHandler.getCursorStack();
    }

    /**
     * Spare elytras in good repair.
     *
     * The one on your back is deliberately not counted. This is a stock check, so the threshold
     * you set is a number of spares, and counting the one you are flying in would quietly add one
     * to every reading you have already tuned.
     */
    private boolean hasEnoughElytras() {
        if (mc.player == null) return false;

        ItemStack worn = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        int good = 0;

        for (int n = 0; n < carriedSize(); n++) {
            ItemStack stack = carried(n);
            if (stack.getItem() != Items.ELYTRA || stack == worn) continue;

            int max = stack.getMaxDamage();
            if (max <= 0) continue;

            double percent = Math.floor(((max - stack.getDamage()) / (double) max) * 100);
            if (percent > 5) good++;
        }

        return good > elytraStock.get();
    }

    private boolean hasEnoughRockets() {
        if (mc.player == null) return false;

        int total = 0;
        for (int n = 0; n < carriedSize(); n++) {
            ItemStack stack = carried(n);
            if (stack.getItem() == Items.FIREWORK_ROCKET) total += stack.getCount();
        }

        return total > rocketStock.get();
    }

    private boolean hasEnoughFood() {
        if (mc.player == null) return false;

        int total = 0;
        for (int n = 0; n < carriedSize(); n++) {
            ItemStack stack = carried(n);
            if (chosenFood.get().contains(stack.getItem())) total += stack.getCount();
        }

        return total > foodStock.get();
    }

    /**
     * Watches for your health dropping and records what caused it.
     *
     * Checked every tick rather than hooked to a damage event, because the client is told the
     * damage source alongside the health change and this is the simplest place to catch both.
     */
    private void trackDamage() {
        if (mc.player == null) return;

        float health = mc.player.getHealth();
        if (lastHealth >= 0 && health < lastHealth) {
            String described = LogDetails.describe(mc.player.getRecentDamageSource());
            if (described != null) lastDamage = described;
        }
        lastHealth = health;
    }

    /**
     * The details, captured while the world still exists, to be shown on the disconnect screen.
     *
     * Gathered at request time rather than when the screen appears: by then the connection is
     * closing, the world is being torn down and there is nothing left to read.
     */
    private @Nullable Text capturedDetails = null;

    private void captureLogDetails() {
        capturedDetails = showLogDetails.get() ? LogDetails.asBlock(lastDamage) : null;
    }

    /** The reason with the details appended, which is what the disconnect screen shows. */
    private Text withDetails(Text reason) {
        if (capturedDetails == null) return reason;
        return reason.copy().append(capturedDetails);
    }

    private void resetIdle() {
        idleTicks = 0;
        idleDrift = 0;
        idleKnown = false;
    }

    private int countTotems() {
        if (mc.player == null) return 0;

        int total = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) total += stack.getCount();
        }
        return total;
    }

    /**
     * Counts totem pops by watching the count fall just after you were hit.
     *
     * A pop is not something the client is told about in so many words, so it is inferred. The
     * count dropping on its own means nothing, since putting totems in a shulker looks the same;
     * the count dropping within a few ticks of taking damage is a pop. That window is why the hit
     * is tracked rather than tested on the spot: the item change and the damage do not always land
     * on the same tick.
     */
    private void popCheck() {
        if (mc.player == null) return;

        if (mc.player.hurtTime > 0) ticksSinceHurt = 0;
        else if (ticksSinceHurt < 999) ticksSinceHurt++;

        int totems = countTotems();

        if (lastTotems > totems && ticksSinceHurt <= 5) {
            int popped = lastTotems - totems;
            totemPops += popped;

            if (totemPopNotify.get()) {
                MsgUtil.sendModuleMsg("Totem popped §8(§c" + totemPops + "§8) §7totems left: §a" + totems, this.name);
            }
        }

        lastTotems = totems;
    }

    /**
     * True if this player is wearing any armour at all.
     *
     * The four armour slots sit on the end of the inventory, which is why they are read from there
     * rather than asked for by slot: it is the one way that needs nothing but the inventory you can
     * already see on another player.
     */
    private boolean isGeared(PlayerEntity player) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!player.getEquippedStack(slot).isEmpty()) return true;
        }
        return false;
    }

    /** The closest player within the radius, friends skipped if asked, or null. */
    private @Nullable PlayerEntity nearbyPlayer() {
        if (mc.player == null || mc.world == null) return null;

        double limit = proximityRadius.get() * (double) proximityRadius.get();
        PlayerEntity closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive()) continue;
            if (proximityIgnoreFriends.get() && Friends.get().isFriend(player)) continue;
            if ((proximityIgnoreNaked.get() || onlyWhenGeared.get()) && !isGeared(player)) continue;

            double distance = mc.player.squaredDistanceTo(player);
            if (distance > limit || distance >= closestDistance) continue;

            closest = player;
            closestDistance = distance;
        }

        return closest;
    }

    /**
     * Standing still: either leave, or say your lines and keep waiting.
     *
     * Position is compared as a distance rather than by block, so drifting within one block still
     * counts as standing still, which is what a stalled Baritone looks like.
     */
    private void idleCheck() {
        if (mc.player == null) return;

        if (!idleAutoLog.get()) {
            resetIdle();
            return;
        }

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();

        if (!idleKnown) {
            idleX = x; idleY = y; idleZ = z;
            idleYaw = yaw; idlePitch = pitch;
            idleKnown = true;
            idleTicks = 0;
            idleDrift = 0;
            return;
        }

        /*
         * Standing still means nothing is happening, not just that you are not moving.
         *
         * Position alone called you idle while you stood reading a sign or sorting a shulker, and
         * called you busy whenever something else was carrying you. Turning or holding any key is
         * you at the keyboard, and either starts the clock again.
         *
         * Being moved is only yours if you caused it. A mob knocking you back or leaning on you
         * moves you without you touching anything, and counting that as activity is how a logout
         * never fires: the one zombie that found you keeps resetting the clock all night. Within a
         * second of a hit, or while a mob is against you, the position is simply re-read and the
         * clock keeps running.
         *
         * The cap is what stops that excusing real travel. Knockback is measured in blocks and
         * runs out; anything flying you moves you much further than this before the clock is up,
         * so passing the cap means you are going somewhere and the count starts over.
         */
        double dx = x - idleX, dy = y - idleY, dz = z - idleZ;
        boolean moved = dx * dx + dy * dy + dz * dz > 1.0;
        boolean looked = yaw != idleYaw || pitch != idlePitch;
        /*
         * Anti AFK exists to look like you are there, which is exactly what this is watching
         * for, so left alone the two cancel out and the logout never comes. It strafes, hops,
         * sneaks and turns, so its movement is treated like a shove: the position is re-read
         * and the clock keeps running. The drift cap still applies, so something actually
         * carrying you across the world is not excused by having Anti AFK switched on.
         */
        boolean antiAfk = ignoringAntiAfk();
        boolean shoved = antiAfk || ticksSinceHurt <= KNOCKBACK_TICKS || mobAgainstYou();
        if (antiAfk) looked = false;

        if (looked || keyHeld() || (moved && (!shoved || idleDrift > DRIFT_LIMIT))) {
            idleX = x; idleY = y; idleZ = z;
            idleYaw = yaw; idlePitch = pitch;
            idleTicks = 0;
            idleDrift = 0;
            return;
        }

        if (moved) {
            idleDrift += Math.sqrt(dx * dx + dy * dy + dz * dz);
            idleX = x; idleY = y; idleZ = z;
        }

        if (++idleTicks < idleSeconds.get() * 20) return;
        idleTicks = 0;

        if (idleAction.get() == IdleAction.Disconnect) {
            requestLogout(Text.literal("§8[§6Advanced Autolog§8] §fDisconnected you because nothing has happened for §6"
                + idleSeconds.get() + "§f seconds§c!"), forceKick.get());
            return;
        }

        // Queued whole rather than said in one go: lines sent in the same tick arrive as one and
        // the rest are thrown away.
        queuedLines.clear();
        for (String line : idleLines.get()) {
            if (line != null && !line.isBlank()) queuedLines.add(line.trim());
        }

        if (queuedLines.isEmpty()) return;

        say(queuedLines.poll());
        queuedTicks = idleLineDelay.get();
    }

    private boolean inTargetDimension() {
        if (mc.world == null) return false;

        return switch (coordsDimension.get()) {
            case Any -> true;
            case Overworld -> mc.world.getRegistryKey() == World.OVERWORLD;
            case Nether -> mc.world.getRegistryKey() == World.NETHER;
            case End -> mc.world.getRegistryKey() == World.END;
        };
    }

    /** True while a mob is close enough to be pushing you around. */
    private boolean mobAgainstYou() {
        if (mc.player == null || mc.world == null) return false;

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof MobEntity && entity.isAlive() && mc.player.squaredDistanceTo(entity) <= 4.0) return true;
        }

        return false;
    }

    /**
     * True while any key you would play with is down.
     *
     * Read as held state rather than through a key event, because a key event would have to be
     * consumed to be read and consuming it would take the press away from the game. A tap that
     * starts and ends inside one tick is missed, which does not matter against a clock measured
     * in seconds.
     */
    private boolean keyHeld() {
        var options = mc.options;
        if (options == null) return false;

        if (options.forwardKey.isPressed()
            || options.backKey.isPressed()
            || options.sprintKey.isPressed()
            || options.attackKey.isPressed()
            || options.useKey.isPressed()) return true;

        /*
         * The four Anti AFK drives are read last and only when it is not running.
         *
         * It does not simulate a key, it holds the real one down, so there is nothing in the
         * key itself to tell its strafe from yours. While it is on, those four stop counting;
         * forward, back, sprint, attack and use are untouched by it and still do.
         */
        if (ignoringAntiAfk()) return false;

        return options.leftKey.isPressed()
            || options.rightKey.isPressed()
            || options.jumpKey.isPressed()
            || options.sneakKey.isPressed();
    }

    /** True while Meteor's Anti AFK is running and this module has been told to look past it. */
    private boolean ignoringAntiAfk() {
        if (!idleIgnoreAntiAfk.get()) return false;

        AntiAFK antiAfk = Modules.get().get(AntiAFK.class);
        return antiAfk != null && antiAfk.isActive();
    }

    /** Says a line the way typing it would, so a client side command prefix still gets picked up. */
    private void say(String line) {
        if (line == null || line.isBlank() || mc.getNetworkHandler() == null) return;

        String trimmed = line.trim();
        if (trimmed.startsWith("/")) mc.getNetworkHandler().sendChatCommand(trimmed.substring(1));
        else mc.getNetworkHandler().sendChatMessage(trimmed);
    }

    /**
     * Every log goes through here.
     *
     * With a pre-log task set up, the block is clicked and the disconnect is held for the delay,
     * so the trapdoor actually shuts before the connection drops. Without one, this is the same
     * immediate disconnect as before.
     */
    private void requestLogout(Text reason, boolean force) {
        // Gathered now, not after the delay: by then you may have been hit again or the player who
        // was watching may have left render distance.
        captureLogDetails();

        if (!preLogTask.get()) {
            if (force) doForceKick(reason);
            else disconnect(reason);
            return;
        }

        // Already waiting; do not click twice or restart the countdown.
        if (pendingReason != null) return;

        pendingReason = reason;
        pendingForce = force;
        pendingTicks = preLogDelay.get();

        doPreLogInteract();
    }

    /**
     * Clicks the configured block.
     *
     * The packet is sent directly rather than going through a raycast, so a block in the way does
     * not stop it. Range still applies: the server rejects an interaction from too far off.
     */
    private void doPreLogInteract() {
        if (mc.player == null || mc.interactionManager == null) return;

        BlockPos pos = preLogPos.get();
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        Runnable action = () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (preLogRotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(Vec3d.ofCenter(pos)), 100, action);
        }
        else action.run();

        MsgUtil.updateModuleMsg("Clicked the block at §a§o" + pos.toShortString() + "§7, logging in §a§o"
            + preLogDelay.get() + " §7ticks.", this.name, "preLogTask".hashCode());
    }

    private void doForceKick(Text disconnectReason) {
        this.disconnectReason = withDetails(disconnectReason);
        JJUtil.illegalDisconnect(true, JJConfig.illegalDisconnectMethodSetting.get());
    }

    private void disconnect(Text reason) {
        if (mc.getNetworkHandler() == null) return;
        if (disableAutoReconnect.get()) JJUtil.disableAutoReconnect();
        mc.getNetworkHandler().onDisconnect(new DisconnectS2CPacket(withDetails(reason)));
        switch (autoLogToggle.get()) {
            case Module -> toggle();
            case Settings -> disableAutoLogSettings();
        }
    }

    private void disableAutoLogSettings() {
        yLevelAutoLog.set(false);
        timeoutAutoLog.set(false);
        antiTrapAutoLog.set(false);
        lowElytraAutoLog.set(false);
        lowRocketsAutoLog.set(false);
        healthAutoLog.set(false);
        totemAutoLog.set(false);
        proximityAutoLog.set(false);
        crystalAutoLog.set(false);
        coordsAutoLog.set(false);
        idleAutoLog.set(false);
    }

    /**
     * The server telling everyone it is about to go down.
     *
     * Matched on the text rather than a packet, because a restart warning is just a chat message
     * and the wording differs between servers. The phrases are a setting for the same reason.
     */
    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!restartAutoLog.get() || mc.player == null) return;
        if (JJUtil.isIn2b2tQueue()) return;

        String line = event.getMessage().getString().toLowerCase(java.util.Locale.ROOT);

        for (String phrase : restartPhrases.get()) {
            if (phrase.isBlank() || !line.contains(phrase.toLowerCase(java.util.Locale.ROOT))) continue;

            requestLogout(Text.literal("§8[§5Advanced Autolog§8] §7Disconnected you because the server announced a §crestart§7."),
                forceKick.get());
            return;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // The next queued line, once its delay is up.
        if (!queuedLines.isEmpty()) {
            if (queuedTicks > 0) queuedTicks--;
            else {
                say(queuedLines.poll());
                queuedTicks = idleLineDelay.get();
            }
        }

        // Pre-log countdown runs before anything else, so a log already in flight finishes.
        if (pendingReason != null) {
            if (pendingTicks > 0) {
                pendingTicks--;
                return;
            }

            Text reason = pendingReason;
            pendingReason = null;

            if (pendingForce) doForceKick(reason);
            else disconnect(reason);
            return;
        }

        if (JJUtil.isIn2b2tQueue()) return;
        if (mc.getNetworkHandler() == null) return;
        if (mc.player == null || mc.world == null) return;

        if (pullQuiet > 0) pullQuiet--;

        // A portal arrives as an ordinary position packet with nothing on it to say so, so the
        // only way to know is to notice the world you are in has changed.
        RegistryKey<World> dimension = mc.world.getRegistryKey();
        if (pullDimension != null && dimension != pullDimension) pullQuiet = SETTLE_TICKS;
        pullDimension = dimension;

        ++timer;
        ++ticksSinceWarned;
        trackDamage();
        handleDurabilityChecks();
        if (timeoutAutoLog.get() && logOutTimer != -69L) {
            long now = System.currentTimeMillis();
            if (now - timerTimestamp >= timeoutAutoLogTimer.get() * 1000) {
                timerTimestamp = now;
                Text reason = Text.literal("§8[§5Advanced Autolog§8] §7Disconnected you because your §3" + timeoutAutoLogTimer.get() + "§7-second timer has elapsed§a..!");
                requestLogout(reason, forceKick.get());
            }
        }

        if (yLevelAutoLog.get() && mc.player.getY() < yLevelThreshold.get()) {
            Text reason = Text.literal("§8[§4Advanced Autolog§8] §fDisconnected you because your Y level descended below §c"+ yLevelThreshold.get()+"§8!");
            requestLogout(reason, forceKick.get());
        }

        if (lowElytraAutoLog.get() && !hasEnoughElytras()) {
            Text reason = Text.literal("§8[§2Advanced Autolog§8] §fDisconnected you because you are running low on healthy elytras§c!");
            requestLogout(reason, forceKick.get());
        }

        if (lowRocketsAutoLog.get() && !hasEnoughRockets()) {
            Text reason = Text.literal("§8[§2Advanced Autolog§8] §fDisconnected you because you are running low on firework rockets§c!");
            requestLogout(reason, forceKick.get());
        }

        if (lowFoodAutoLog.get() && !hasEnoughFood()) {
            Text reason = Text.literal("§8[§2Advanced Autolog§8] §fDisconnected you because you are running low on food§c!");
            requestLogout(reason, forceKick.get());
        }

        popCheck();
        idleCheck();

        if (healthAutoLog.get() && mc.player.getHealth() <= healthThreshold.get()) {
            Text reason = Text.literal("§8[§cAdvanced Autolog§8] §fDisconnected you because your health dropped to §c"
                + Math.round(mc.player.getHealth()) + "§f of " + healthThreshold.get().intValue() + "§c!");
            requestLogout(reason, forceKick.get());
        }

        if (totemAutoLog.get() && totemPops >= totemPopLimit.get()) {
            Text reason = Text.literal("§8[§cAdvanced Autolog§8] §fDisconnected you after §c" + totemPops
                + "§f totem pop(s), §a" + countTotems() + "§f left§c!");
            requestLogout(reason, forceKick.get());
        }

        if (proximityAutoLog.get()) {
            PlayerEntity near = nearbyPlayer();
            if (near != null) {
                Text reason = Text.literal("§8[§cAdvanced Autolog§8] §fDisconnected you because §c"
                    + dev.jjstools.compat.Compat.name(near.getGameProfile()) + "§f came within §c"
                    + Math.round(mc.player.distanceTo(near)) + "§f blocks§c!");
                requestLogout(reason, forceKick.get());
            }
        }

        if (coordsAutoLog.get() && inTargetDimension()) {
            double dx = mc.player.getX() - coordsX.get();
            double dz = mc.player.getZ() - coordsZ.get();
            double range = coordsRange.get();

            if (dx * dx + dz * dz <= range * range) {
                String where = coordsInMessage.get()
                    ? " §8(§7" + coordsX.get() + ", " + coordsZ.get() + "§8)"
                    : "";
                requestLogout(Text.literal("§8[§2Advanced Autolog§8] §fDisconnected you because you reached your destination"
                    + where + "§2!"), forceKick.get());
            }
        }

        BlockPos newPos = mc.player.getBlockPos();

        if (lastPos == null) lastPos = newPos;
        else if (lastPos.getManhattanDistance(newPos) <= 1) {
            ++ticksNotMoved;
            lastPos = newPos;
        }

    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onEntityAddHighPriority(EntityAddedEvent event) {
        if (mc.player == null || mc.world == null) return;

        /*
         * A crystal is read as a threat the moment it appears, not when it goes off.
         *
         * Waiting for the explosion is waiting to be dead: the hit that sets one off and the
         * damage land together. Anyone placing one within arm's reach of you means it, so the
         * placement is the warning worth acting on.
         */
        if (crystalAutoLog.get() && event.entity.getType() == EntityType.END_CRYSTAL) {
            double distance = mc.player.distanceTo(event.entity);
            if (distance <= crystalRadius.get()) {
                requestLogout(Text.literal("§8[§dAdvanced Autolog§8] §fDisconnected you because an end crystal was placed §d"
                    + Math.round(distance) + "§f blocks away§c!"), forceKick.get());
                return;
            }
        }

        if (!antiTrapAutoLog.get() || event.entity.getType() != EntityType.TNT_MINECART) return;
        requestLogout(Text.literal("§8[§aAdvanced Autolog§8] §c§oDisconnected you to avoid a trap§8§o! §8§o(§c§oTNT minecarts §7§owere rendered§c§o!§8§o)"), forceKick.get());
    }

    /**
     * A join is the largest teleport there is, so the quiet period starts again on every one.
     *
     * The dimension baseline is cleared with it. Until the tick loop has set that, there is no
     * "world you were in" to compare against, and the pull check stays out of the way.
     */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        pullQuiet = SETTLE_TICKS;
        pullDimension = null;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        pullQuiet = SETTLE_TICKS;
        pullDimension = null;
    }

    /** Long enough for a join, a portal or your own pearl to finish moving you. */
    private static final int SETTLE_TICKS = 40;

    /**
     * A pearl you throw yourself starts the quiet period on the throw, not on the landing.
     *
     * The landing is the part that looks like a pull, and by then there is nothing left to tell
     * them apart, so the throw is what has to be caught.
     */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!pearlPullAutoLog.get() || mc.player == null) return;
        if (!(event.packet instanceof PlayerInteractItemC2SPacket)) return;

        if (mc.player.getMainHandStack().isOf(Items.ENDER_PEARL)
            || mc.player.getOffHandStack().isOf(Items.ENDER_PEARL)) {
            pullQuiet = Math.max(pullQuiet, SETTLE_TICKS);
        }
    }

    /**
     * A teleport that moved you a long way with nothing of yours behind it.
     *
     * Measured here rather than on the next tick, because by then the move has been applied and
     * where you were is gone.
     */
    private void checkPearlPull(PlayerPositionLookS2CPacket packet) {
        if (!pearlPullAutoLog.get() || mc.player == null || pullQuiet > 0) return;
        if (pendingReason != null) return;

        // Nothing to compare against until the tick loop has run at least once in this world,
        // which it has not during a queue or on the first frames after joining.
        if (pullDimension == null) return;

        Vec3d current = dev.jjstools.compat.Compat.pos(mc.player);

        Vec3d target;
        try {
            target = dev.jjstools.compat.Compat.teleportTarget(mc.player, packet);
        } catch (Throwable t) {
            target = packet.change().position();
        }

        double distance = current.distanceTo(target);
        if (distance < pearlPullDistance.get()) return;

        requestLogout(Text.literal("§8[§5Advanced Autolog§8] §fDisconnected you because something pulled you §5"
            + Math.round(distance) + "§f blocks§c!"), forceKick.get());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket position) {
            checkPearlPull(position);
            return;
        }

        if (!(event.packet instanceof DisconnectS2CPacket packet) || disconnectReason == null) return;
        ((DisconnectS2CPacketAccessor)(Object) packet).setReason(disconnectReason);
        switch (autoLogToggle.get()) {
            case Module -> toggle();
            case Settings -> disableAutoLogSettings();
        }
    }
}
