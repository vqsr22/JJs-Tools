package dev.jjstools.modules;

import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import dev.jjstools.JJsTools;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.item.Items;
import dev.jjstools.util.MsgUtil;
import org.jetbrains.annotations.Nullable;
import net.minecraft.item.ItemStack;
import java.util.concurrent.TimeUnit;
import dev.jjstools.util.JJUtil;
import dev.jjstools.util.LogDetails;
import net.minecraft.sound.SoundEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import dev.jjstools.util.JJConfig;
import net.minecraft.entity.EquipmentSlot;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.component.DataComponentTypes;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.systems.modules.Module;
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

    private final SettingGroup sgETA = settings.createGroup("ETA Settings (Spoiler)", false);
    private final SettingGroup sgAutoLog = settings.createGroup("AutoLog Settings");
    private final SettingGroup sgNotify = settings.createGroup("Notification Settings");
    private final SettingGroup sgPreLog = settings.createGroup("Before Logging");

    private final Setting<Boolean> etaSetting = sgETA.add(
        new BoolSetting.Builder()
            .name("display-ETA")
            .description("Display an estimated time of arrival for your target coords.")
            .defaultValue(false)
            .build()
    );
    public final Setting<Integer> targetX = sgETA.add(
        new IntSetting.Builder()
            .name("target-X")
            .description("Not dimension-translated.")
            .range(-30000000, 30000000).noSlider().defaultValue(0)
            .onChanged(it -> this.bpsValues.clear())
            .build()
    );
    public final Setting<Integer> targetZ = sgETA.add(
        new IntSetting.Builder()
            .name("target-Z")
            .description("Not dimension-translated.")
            .range(-30000000, 30000000).noSlider().defaultValue(0)
            .onChanged(it -> this.bpsValues.clear())
            .build()
    );
    private final Setting<Boolean> speedUpdates = sgETA.add(
        new BoolSetting.Builder()
            .name("average-speed-updates")
            .description("Display your average speed over a period of time.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Boolean> averageETA = sgETA.add(
        new BoolSetting.Builder()
            .name("ETA-average")
            .description("Display ETA based on your average speed over X minutes.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> averageSpeedMinutes = sgETA.add(
        new IntSetting.Builder()
            .name("average-speed-over-X-minutes")
            .description("Display your average speed over a period of time.")
            .range(0, 3200).sliderRange(0, 60).defaultValue(10)
            .onChanged(it -> this.bufferSize = it * 1200)
            .build()
    );

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

    private final Setting<Boolean> antiTrapAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("anti-trap-autoLog")
            .description("Illegally disconnects you when any TNT minecarts are rendered, to avoid highway traps.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> etaAutoLog = sgAutoLog.add(
        new BoolSetting.Builder()
            .name("ETA-autoLog")
            .description("Logs you out on arrival at your destination.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Integer> etaAutoLogThreshold = sgAutoLog.add(
        new IntSetting.Builder()
            .name("ETA-autoLog-threshold")
            .description("Logs you out if you come within <threshold> blocks of your ETA.")
            .range(0, 25000).noSlider().defaultValue(150)
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
    private final Setting<Double> pingVolume = sgNotify.add(
        new DoubleSetting.Builder()
            .name("ping-volume")
            .range(0, 5).sliderMin(0).sliderMax(5).defaultValue(.5)
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
        .description("Turn toward the block through Meteor's silent rotation system, so the server sees a sane look direction while your view stays put.")
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
    private int ticksSinceWarned = 0;
    private long logOutTimer = -69L;
    private long timerTimestamp = 0L;
    private @Nullable BlockPos lastPos = null;
    private @Nullable Text disconnectReason = null;
    private int bufferSize = averageSpeedMinutes.get() * 1200;
    private final ArrayDeque<Double> bpsValues = new ArrayDeque<>();

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
        bpsValues.clear();
        ticksNotMoved = 0;
        logOutTimer = -69L;
        timerTimestamp = 0L;
        ticksSinceWarned = 0;
        bufferSize = averageSpeedMinutes.get() * 1200;
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
                mc.player.playSound(SoundEvents.ENTITY_ITEM_BREAK.value(), pingVolume.get().floatValue(), 1f);
                MsgUtil.updateModuleMsg("Elytra durability: §c" + percentDurability + "§7%", this.name, "roadTripElytraWarn".hashCode());
                reset = true;
            }
        }

        if (lagNotify.get() && ticksNotMoved >= 80) {
            reset = true;
            ticksNotMoved = 0;
            mc.player.playSound(SoundEvents.ENTITY_PHANTOM_SWOOP, pingVolume.get().floatValue(), 1f);
        }

        if (reset) ticksSinceWarned = 0;
    }

    private boolean hasEnoughElytras() {
        if (mc.player == null) return false;
        ArrayList<Integer> goodSlotsLeft = new ArrayList<>();
        for (int n = 0; n < mc.player.getInventory().getMainStacks().size(); n++) {
            ItemStack stack = mc.player.getInventory().getStack(n);

            if (stack.getItem() == Items.ELYTRA) {
                int max = stack.getMaxDamage();
                int curr = max - stack.getDamage();
                double percent = Math.floor((curr / (double) max) * 100);

                if (percent > 5) {
                    goodSlotsLeft.add(n);
                }
            }
        }
        return goodSlotsLeft.size() > elytraStock.get();
    }

    private boolean hasEnoughRockets() {
        if (mc.player == null) return false;
        int totalRocketsLeft = 0;
        for (int n = 0; n < mc.player.getInventory().getMainStacks().size(); n++) {
            ItemStack stack = mc.player.getInventory().getStack(n);
            if (stack.getItem() == Items.FIREWORK_ROCKET) {
                totalRocketsLeft += stack.getCount();
            }
        }
        return totalRocketsLeft > rocketStock.get();
    }

    private boolean hasEnoughFood() {
        if (mc.player == null) return false;
        int totalFoodLeft = 0;
        for (int n = 0; n < mc.player.getInventory().getMainStacks().size(); n++) {
            ItemStack stack = mc.player.getInventory().getStack(n);
            if (chosenFood.get().contains(stack.getItem())) {
                totalFoodLeft += stack.getCount();
            }
        }
        return totalFoodLeft > foodStock.get();
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
        JJUtil.disableAutoReconnect();
        mc.getNetworkHandler().onDisconnect(new DisconnectS2CPacket(withDetails(reason)));
        switch (autoLogToggle.get()) {
            case Module -> toggle();
            case Settings -> disableAutoLogSettings();
        }
    }

    private void disableAutoLogSettings() {
        etaAutoLog.set(false);
        yLevelAutoLog.set(false);
        timeoutAutoLog.set(false);
        antiTrapAutoLog.set(false);
        lowElytraAutoLog.set(false);
        lowRocketsAutoLog.set(false);
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

        BlockPos newPos = mc.player.getBlockPos();

        if (lastPos == null) lastPos = newPos;
        else if (lastPos.getManhattanDistance(newPos) <= 1) {
            ++ticksNotMoved;
            lastPos = newPos;
        }

        BlockPos destination = new BlockPos(targetX.get(), mc.player.getBlockY(), targetZ.get());

        int totalBlocksLeft = newPos.getManhattanDistance(destination);
        double blocksPerSecond = Utils.getPlayerSpeed().horizontalLength();

        if (etaAutoLog.get() && totalBlocksLeft <= etaAutoLogThreshold.get()) {
            if (mc.getNetworkHandler().getPlayerList().size() > 1) {
                Text reason = Text.literal("§8[§2Advanced Autolog§8] §fDisconnected you because you have reached your destination§2! §5:§3]");
                requestLogout(reason, forceKick.get());
            }
        }

        if (etaSetting.get()) {
            if (bpsValues.size() > bufferSize) {
                int diff = bpsValues.size() - bufferSize;
                for (int n = 0; n < diff; n++) {
                    if (!bpsValues.isEmpty()) bpsValues.removeFirst();
                }
            } else if (bpsValues.size() == bufferSize) {
                if (!bpsValues.isEmpty()) bpsValues.removeFirst();
                bpsValues.add(blocksPerSecond);
            } else bpsValues.add(blocksPerSecond);
            if (averageSpeedMinutes.get() > 0) {
                double average = 0;
                for (double d : bpsValues) {
                    average += d;
                }

                if (averageETA.get()) {
                    blocksPerSecond = average / bpsValues.size();
                }
                if (speedUpdates.get() && timer >= 20) MsgUtil.updateMsg(
                    "Average speed over " + averageSpeedMinutes.get() + " minute(s): "
                        + (Math.floor(average / bpsValues.size()) * 3600) / 1000 + "Km/h", "averageBPSUpdate".hashCode()
                );
            }

            if (timer >= 20) {
                timer = 0;
                if (blocksPerSecond == 0) {
                    double verticalBPS = Utils.getPlayerSpeed().y;
                    if (verticalBPS == 0) return;
                    MsgUtil.updateMsg("Vertical Speed: §6§o" + Math.floor(verticalBPS) + "§7§ob/s. §5§o" + (Math.floor(verticalBPS) * 3600) / 1000 + "§7§oKm/h", "verticalSpeedUpdate".hashCode());
                    return;
                }
                double totalSeconds = totalBlocksLeft / blocksPerSecond;

                long days = TimeUnit.SECONDS.toDays((long) totalSeconds);
                totalSeconds -= TimeUnit.DAYS.toSeconds(days);

                long hours = TimeUnit.SECONDS.toHours((long) totalSeconds);
                totalSeconds -= TimeUnit.HOURS.toSeconds(hours);

                long minutes = TimeUnit.SECONDS.toMinutes((long) totalSeconds);
                totalSeconds -= TimeUnit.MINUTES.toSeconds(minutes);

                long seconds = TimeUnit.SECONDS.toSeconds((long) totalSeconds);
                StringBuilder sb = new StringBuilder().append(JJUtil.rCC()).append("§8<§a§o✨§r§8> §7§oETA: §2§o");

                if (days == 0 && hours == 0 && minutes == 0 && seconds <= 3) {
                    if (totalBlocksLeft <= 69) {
                        sb.append("§2§oYou have arrived at your destination§8§o. §5:§3]");
                    } else {
                        sb.append("Imminent§8§o...");
                    }
                } else {
                    if (days != 0) sb.append(days).append(" §7§oDays, §2§o");
                    if (hours != 0) sb.append(hours).append(" §7§oHours, §2§o");
                    if (minutes != 0) sb.append(minutes).append(" §7§oMinutes, §2§o");
                    if (seconds != 0) sb.append(seconds).append(" §7§oSeconds.");
                }

                MsgUtil.updateMsg(sb.toString(), "AdvancedAutologETAUpdate".hashCode());
            }
            lastPos = newPos;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onEntityAddHighPriority(EntityAddedEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!antiTrapAutoLog.get() || event.entity.getType() != EntityType.TNT_MINECART) return;
        requestLogout(Text.literal("§8[§aAdvanced Autolog§8] §c§oDisconnected you to avoid a trap§8§o! §8§o(§c§oTNT minecarts §7§owere rendered§c§o!§8§o)"), forceKick.get());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof DisconnectS2CPacket packet) || disconnectReason == null) return;
        ((DisconnectS2CPacketAccessor)(Object) packet).setReason(disconnectReason);
        switch (autoLogToggle.get()) {
            case Module -> toggle();
            case Settings -> disableAutoLogSettings();
        }
    }
}
