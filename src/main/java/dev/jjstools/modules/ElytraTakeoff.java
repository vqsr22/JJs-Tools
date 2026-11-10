package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
//#if MC >= 1.21.9
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
//#else
//$$ import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
//#endif
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Press a key to jump, open your elytra and fire a rocket in one go.
 *
 * Port of Lambda's BetterFirework (https://github.com/lambda-client/lambda, GPL-3.0), rewritten
 * for Meteor. Same flow as Lambda:
 *  - On the ground: jump this tick, then next tick start gliding and use a firework.
 *  - Already falling or gliding: start gliding (if needed) and use a firework straight away.
 *  - Right-clicking a firework in the air on the ground does the same takeoff.
 * Lambda's elytra auto-swap and ElytraFly hooks are not included.
 */
public class ElytraTakeoff extends Module {
    private enum State { Idle, Settling, Jumping, StartFlying }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDoubleJump = settings.createGroup("Double Jump");
    private final SettingGroup sgRightClick = settings.createGroup("Right Click");
    private final SettingGroup sgSwap = settings.createGroup("Elytra Swap");

    // General

    private final Setting<Keybind> takeoffKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("takeoff-key")
        .description("Jump, open the elytra and fire a rocket. In the air or already gliding, it just fires a rocket.")
        .defaultValue(Keybind.fromButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE))
        .action(this::onTakeoffKey)
        .build()
    );

    private final Setting<Boolean> cancelPickBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("cancel-pick-block")
        .description("Stop middle mouse also picking the block you are looking at. Off, a middle click on a block picks it and does not take off.")
        .defaultValue(false)
        .visible(() -> isMiddleMouse(takeoffKey.get()))
        .build()
    );

    private final Setting<Boolean> inventory = sgGeneral.add(new BoolSetting.Builder()
        .name("fireworks-from-inventory")
        .description("Use fireworks from your main inventory when there are none in your hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Show your hand swing when a rocket is fired. Off still sends the swing, it just is not drawn.")
        .defaultValue(true)
        .build()
    );

    // Double jump

    private final Setting<Boolean> doubleJump = sgDoubleJump.add(new BoolSetting.Builder()
        .name("double-jump-deploy")
        .description("Tap jump twice quickly to open the elytra. The first tap is an ordinary jump and the second deploys it under you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> doubleJumpRocket = sgDoubleJump.add(new BoolSetting.Builder()
        .name("fire-rocket")
        .description("Fire a rocket as well when a double jump opens the elytra. Off just opens it and lets you glide, and needs no fireworks.")
        .defaultValue(false)
        .visible(() -> doubleJump.get() || this.midairJump.get())
        .build()
    );

    private final Setting<Integer> doubleJumpWindow = sgDoubleJump.add(new IntSetting.Builder()
        .name("tap-window")
        .description("How long the second tap has to land, in ticks. Twenty is a second. Lower it if an ordinary jump ever takes off.")
        .defaultValue(8)
        .min(2).max(20)
        .sliderRange(4, 14)
        .visible(doubleJump::get)
        .build()
    );

    private final Setting<Boolean> midairJump = sgDoubleJump.add(new BoolSetting.Builder()
        .name("one-jump-in-midair")
        .description("While you are falling wearing a chestplate, a single tap of jump swaps the elytra on and deploys it, instead of needing two taps. Handy for stepping off a ledge. Only once you have fallen a few blocks, so jumping while you run never sets it off. Uses fire-rocket the same as the double jump does.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> midairFall = sgDoubleJump.add(new DoubleSetting.Builder()
        .name("midair-fall-distance")
        .description("How far you have to have fallen, in blocks, before one tap deploys. A sprint jump only falls about one block, so keep it above that.")
        .defaultValue(3.0).min(1.5).max(20.0).sliderRange(1.5, 10.0)
        .visible(midairJump::get)
        .build()
    );

    // Right click

    private final Setting<Boolean> rightClickFly = sgRightClick.add(new BoolSetting.Builder()
        .name("right-click-takeoff")
        .description("Right-clicking a firework on the ground or while falling takes off instead of wasting it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rightClickCancel = sgRightClick.add(new BoolSetting.Builder()
        .name("on-blocks")
        .description("Take off when right-clicking a block with fireworks too, instead of placing the rocket on it.")
        .defaultValue(false)
        .visible(rightClickFly::get)
        .build()
    );

    // Elytra swap

    private final Setting<Boolean> autoEquip = sgSwap.add(new BoolSetting.Builder()
        .name("auto-equip")
        .description("Put an elytra on first if you are not wearing one, then take off in the same press.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chestplateOnLanding = sgSwap.add(new BoolSetting.Builder()
        .name("chestplate-on-landing")
        .description("Put your chestplate back on once you are down. The elytra goes back to the slot the chestplate came out of, so neither wanders around your inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> landingDelay = sgSwap.add(new IntSetting.Builder()
        .name("landing-delay")
        .description("Ticks to wait after landing before swapping back. Twenty is a second; zero swaps the moment you touch down.")
        .defaultValue(0).min(0).sliderRange(0, 100)
        .visible(chestplateOnLanding::get)
        .build()
    );

    private State state = State.Idle;
    private boolean usingOwn;

    /** Whether the takeoff now under way should fire a rocket once the elytra is open. */
    private boolean rocketOnTakeoff = true;

    /*
     * A takeoff that is going to fire a rocket and has not yet.
     *
     * Chestplate on landing goes by the ground and by whether you are gliding, and both of those
     * flicker during a takeoff: you are still on the ground the tick you jump, and a glide the
     * client starts can be undone by the server a moment later. Either could read as a landing
     * and put the chestplate back on in the middle of taking off, so the rocket that was meant
     * to follow had no elytra to fire with. So nothing to do with landing happens until the
     * rocket has gone, or the takeoff is plainly not going to happen.
     */
    private boolean rocketPending;
    private int rocketPendingSince;

    /** How long a takeoff may wait for the elytra to open before it is given up on. */
    private static final int OPEN_WAIT_TICKS = 10;

    /** And how long chestplate on landing is held off for at the most, so a failed takeoff never leaves you stuck in the elytra. */
    private static final int ROCKET_HOLD_TICKS = 60;

    private int flyingTicks;

    /** For spotting the second tap of a double jump. */
    private int ticks;
    private boolean jumpWasDown;
    private int lastJumpTick = -1000;

    public ElytraTakeoff() {
        super(JJsTools.CATEGORY, "elytra-takeoff", "Jump, open your elytra and fire a rocket with one key, or deploy it with a double jump.");
    }

    @Override
    public void onActivate() {
        state = State.Idle;
        rocketPending = false;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        state = State.Idle;
        swappedOn = false;
        rocketPending = false;
    }

    // Keys

    private void onTakeoffKey() {
        if (mc.player == null || mc.currentScreen != null) return;

        // Middle mouse on a block is pick block, unless cancel-pick-block says otherwise.
        if (isMiddleMouse(takeoffKey.get()) && !cancelPickBlock.get()
            && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) return;

        takeoff(true);
    }

    /**
     * Opens the elytra, and fires a rocket if asked to.
     *
     * Shared by the key and the double jump, which differ only in whether a rocket goes with
     * it. Fireworks are only demanded when one is actually going to be fired, so deploying on
     * a double jump works with an empty inventory.
     */
    private void takeoff(boolean rocket) {
        if (mc.player == null || mc.currentScreen != null) return;

        if (rocket && !hasFireworks()) {
            warning("You need fireworks in your inventory to use Elytra Takeoff.");
            return;
        }
        if (state != State.Idle) return;

        if (!mc.player.isGliding() && !ensureGlider()) {
            warning(autoEquip.get()
                ? "No elytra to wear and none equipped."
                : "You aren't wearing an elytra. Turn on auto-equip to put one on automatically.");
            return;
        }

        rocketOnTakeoff = rocket;
        if (canStartGliding() || mc.player.isGliding()) begin(State.StartFlying);
        else if (canTakeoff()) begin(State.Jumping);
    }

    /** Starts a takeoff, holding chestplate on landing off if it is one that fires a rocket. */
    /*
     * Waiting for the server to have the elytra on before using it.
     *
     * Swapping the elytra on and opening it a tick or two later works stood still, but on the
     * move the server, and an anticheat checking your movement, can still have you in the
     * chestplate when the glide starts. It refuses the glide, lets you have it for a split second,
     * and pulls you back down. So when the elytra had to be swapped on just now and you are
     * moving, the takeoff waits about one round trip to the server first. Already wearing the
     * elytra, or stood still, it goes straight away as before.
     */
    private int settleUntil;

    private boolean needsSettling() {
        if (!swappedOn || swappedAt != ticks) return false;
        double vx = mc.player.getVelocity().x, vz = mc.player.getVelocity().z;
        return vx * vx + vz * vz > 0.0009 || !mc.player.isOnGround();
    }

    private int settleTicks() {
        int ping = 0;
        if (mc.getNetworkHandler() != null) {
            var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) ping = entry.getLatency();
        }
        return Math.max(2, Math.min(10, (int) Math.ceil(ping / 50.0) + 1));
    }

    private void begin(State next) {
        if (needsSettling()) {
            settleUntil = ticks + settleTicks();
            next = State.Settling;
        }

        state = next;
        flyingTicks = 0;
        if (rocketOnTakeoff) {
            rocketPending = true;
            rocketPendingSince = ticks;
        }
    }

    /** Stops the middle click from also doing pick block, when asked to. */
    @EventHandler
    //#if MC >= 1.21.9
    private void onMouse(MouseClickEvent event) {
    //#else
    //$$ private void onMouse(MouseButtonEvent event) {
    //#endif
        if (!cancelPickBlock.get() || mc.currentScreen != null) return;
        if (event.action == KeyAction.Press && dev.jjstools.compat.Compat.matches(takeoffKey.get(), event) && isMiddleMouse(takeoffKey.get())) {
            event.cancel();
        }
    }

    // Right click

    @EventHandler
    private void onInteractItem(InteractItemEvent event) {
        if (usingOwn || !rightClickFly.get() || mc.player == null) return;
        if (!holdingFirework(event.hand) || mc.player.isGliding()) return;
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() != HitResult.Type.MISS) return;

        if (startTakeoff()) event.toReturn = ActionResult.FAIL;
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (usingOwn || !rightClickFly.get() || !rightClickCancel.get() || mc.player == null) return;
        if (!holdingFirework(event.hand) || mc.player.isGliding()) return;

        startTakeoff();
        event.cancel();
    }

    /** Queues a takeoff if one is possible. Returns true if the normal right click should be stopped. */
    private static final int EQUIP_COOLDOWN_TICKS = 10;
    private int equipCooldown;

    /**
     * Where the elytra came from, so the chestplate goes back to exactly that slot and the two
     * keep swapping through one place in your inventory instead of wandering.
     */
    private int swapSlot = -1;

    private boolean wasGliding;
    private int landingTimer = -1;

    /*
     * Set when this module put the elytra on, so the chestplate comes back even if no glide
     * ever happened. Without it a takeoff that came to nothing left you in the elytra: a double
     * jump close to the ground lands you before the server confirms the glide, and the only
     * trigger for swapping back was going from gliding to standing.
     */
    private boolean swappedOn;
    private int swappedAt;

    /** Long enough to get off the ground and into a glide before a failed takeoff is assumed. */
    private static final int SWAP_GRACE = 10;

    @Override
    public void onDeactivate() {
        equipCooldown = 0;
        landingTimer = -1;
        wasGliding = false;
        swappedOn = false;
    }

    private boolean startTakeoff() {
        if (!ensureGlider()) return false;

        if (canTakeoff()) {
            if (state == State.Idle) {
                rocketOnTakeoff = true;
                begin(State.Jumping);
            }
            return true;
        }
        if (canStartGliding()) {
            if (state == State.Idle) {
                rocketOnTakeoff = true;
                begin(State.StartFlying);
            }
            return true;
        }
        return false;
    }

    // Tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (equipCooldown > 0) equipCooldown--;
        watchDoubleJump();
        trackLanding();
        if (mc.player == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            state = State.Idle;
            return;
        }

        switch (state) {
            case Idle -> {
            }
            case Settling -> {
                if (ticks < settleUntil) return;

                // Things may have moved on while waiting: you may have walked off an edge, say.
                if (canStartGliding() || mc.player.isGliding()) state = State.StartFlying;
                else if (canTakeoff()) state = State.Jumping;
                else finish();
            }
            case Jumping -> {
                mc.player.jump();
                state = State.StartFlying;
            }
            case StartFlying -> {
                if (canStartGliding() && mc.player.checkGliding()) {
                    mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }

                /*
                 * Only fire once the elytra is open, otherwise the rocket does nothing. It does
                 * not always open on the first try, so it is given a few ticks rather than one:
                 * a takeoff that missed by a tick used to just end there, with no rocket.
                 */
                if (mc.player.isGliding()) {
                    if (rocketOnTakeoff) useFirework();
                    finish();
                }
                else if (++flyingTicks > OPEN_WAIT_TICKS || mc.player.isOnGround() && flyingTicks > 2) {
                    finish();
                }
            }
        }
    }

    /** The takeoff is over, whether or not it got off the ground. */
    private void finish() {
        rocketOnTakeoff = true;
        rocketPending = false;
        state = State.Idle;
    }

    /**
     * Takes off on a second tap of jump.
     *
     * Read as the moment the key goes down rather than while it is held, so holding jump to
     * climb or swim is one press and never two. The first tap is left alone and does what it
     * always did, which is jump: by the time the second one lands you are off the ground and
     * already falling, which is exactly the state the elytra wants.
     *
     * Not while you are already gliding. A tap of jump in the air is a common thing to do by
     * accident and firing a rocket for it would cost you one; the activate key does that on
     * purpose.
     */
    private void watchDoubleJump() {
        if ((!doubleJump.get() && !midairJump.get()) || mc.player == null || mc.currentScreen != null || mc.options == null) {
            jumpWasDown = false;
            return;
        }

        boolean down = mc.options.jumpKey.isPressed();
        boolean pressed = down && !jumpWasDown;
        jumpWasDown = down;

        if (!pressed || mc.player.isGliding()) return;

        /*
         * One tap is enough when already in the air with a chestplate on. Wearing an elytra,
         * vanilla opens it on a tap in the air by itself; with a chestplate there is nothing to
         * open, which is what the second tap of a double jump was standing in for. Not in water,
         * on a ladder, riding or flying, where a tap of jump means something else and the elytra
         * would only be swapped on for nothing.
         */
        if (midairJump.get() && !mc.player.isOnGround() && wearingChestplate()
            && mc.player.fallDistance >= midairFall.get() && mc.player.getVelocity().y < -0.3
            && !mc.player.isTouchingWater() && !mc.player.isClimbing() && !mc.player.hasVehicle()
            && !mc.player.getAbilities().flying && !mc.player.hasStatusEffect(StatusEffects.LEVITATION)) {
            lastJumpTick = -1000;
            takeoff(doubleJumpRocket.get());
            return;
        }

        if (!doubleJump.get()) return;

        if (ticks - lastJumpTick <= doubleJumpWindow.get()) {
            // Spent, so a third tap starts a fresh pair rather than firing again at once.
            lastJumpTick = -1000;
            takeoff(doubleJumpRocket.get());
            return;
        }

        lastJumpTick = ticks;
    }

    // Firework use

    private void useFirework() {
        usingOwn = true;
        try {
            if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
                use(Hand.OFF_HAND);
                return;
            }

            int hotbar = find(0, 9);
            if (hotbar != -1) {
                InvUtils.swap(hotbar, true);
                use(Hand.MAIN_HAND);
                InvUtils.swapBack();
                return;
            }

            if (!inventory.get()) return;
            int slot = find(9, 36);
            if (slot == -1) return;

            int target = 8;
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) {
                    target = i;
                    break;
                }
            }

            InvUtils.quickSwap().fromId(target).to(slot);
            InvUtils.swap(target, true);
            use(Hand.MAIN_HAND);
            InvUtils.swapBack();
            InvUtils.quickSwap().fromId(target).to(slot);
        } finally {
            usingOwn = false;
        }
    }

    private void use(Hand hand) {
        mc.interactionManager.interactItem(mc.player, hand);
        if (swing.get()) mc.player.swingHand(hand);
        else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
    }

    private int find(int from, int to) {
        for (int i = from; i < to; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) return i;
        }
        return -1;
    }

    // Checks (same as Lambda's canStartGliding / canTakeoff)

    private boolean hasFireworks() {
        return find(0, 36) != -1 || mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET);
    }

    private boolean holdingFirework(Hand hand) {
        ItemStack stack = hand == Hand.OFF_HAND ? mc.player.getOffHandStack() : mc.player.getMainHandStack();
        return stack.isOf(Items.FIREWORK_ROCKET);
    }

    /**
     * Makes sure a glider is on, equipping one from the inventory if allowed.
     *
     * InvUtils.move() applies client side straight away, so hasGlider() is true immediately
     * afterwards and the takeoff can carry on in the same key press. No extra tick is spent
     * waiting for the server, which is what made a separate "equip then press again" feel slow.
     *
     * Anything already in the chest slot is swapped into the elytra's old slot, same as Meteor's
     * own ChestSwap.
     */
    /**
     * Watches for the moment you stop gliding and puts the chestplate back.
     *
     * The landing is the transition, not the state: checking "on the ground" alone would fire
     * every tick you stood still. The timer starts on that transition so the delay is measured
     * from touchdown.
     */
    private void trackLanding() {
        if (mc.player == null) return;

        boolean gliding = mc.player.isGliding();
        boolean grounded = mc.player.isOnGround();

        // Back in the air before a delayed swap fired: the chestplate must not go on mid-glide.
        if (gliding) landingTimer = -1;

        // Mid takeoff, rocket still to come: nothing here counts as a landing yet.
        if (rocketPending && ticks - rocketPendingSince > ROCKET_HOLD_TICKS) rocketPending = false;
        if (rocketPending) {
            landingTimer = -1;
            wasGliding = gliding;
            if (swappedOn) swappedAt = ticks;
            return;
        }

        if (wasGliding && !gliding && grounded) {
            startLanding();
        }
        // A takeoff that never became a glide: the elytra went on and you came back down
        // without it ever opening. Given a moment first, so a takeoff from the ground is not
        // mistaken for one before it has left the ground.
        else if (swappedOn && !gliding && grounded && landingTimer < 0 && ticks - swappedAt > SWAP_GRACE) {
            startLanding();
        }
        wasGliding = gliding;

        if (landingTimer < 0) return;
        if (landingTimer-- > 0) return;

        landingTimer = -1;
        equipChestplate();
    }

    private void startLanding() {
        landingTimer = chestplateOnLanding.get() ? landingDelay.get() : -1;
        swappedOn = false;
    }

    /**
     * Swaps a chestplate onto your chest.
     *
     * The slot the elytra came from is tried first. Taking from there puts the elytra straight
     * back where the chestplate was, so the pair keeps trading places rather than drifting.
     */
    private void equipChestplate() {
        if (mc.player == null) return;
        if (!hasGlider()) return;

        int slot = -1;

        if (swapSlot >= 0 && swapSlot < 36 && isChestplate(mc.player.getInventory().getStack(swapSlot))) {
            slot = swapSlot;
        }
        else {
            for (int i = 0; i < 36; i++) {
                if (isChestplate(mc.player.getInventory().getStack(i))) {
                    slot = i;
                    break;
                }
            }
        }

        if (slot == -1) return;

        InvUtils.move().from(slot).toArmor(2);
        swapSlot = slot;
    }

    private boolean isChestplate(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.contains(DataComponentTypes.GLIDER)) return false;

        var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
    }

    private boolean ensureGlider() {
        if (hasGlider()) {
            equipCooldown = 0;
            return true;
        }
        if (!autoEquip.get()) return false;

        /*
         * This is reached from the right click handler, which fires every tick the button is held.
         * Without a cooldown a failed or slow swap meant an inventory packet every single tick,
         * which is a packet flood and gets you dropped on 2b2t the moment you try to take off.
         *
         * One attempt, then wait for the server to answer before trying again.
         */
        if (equipCooldown > 0) return false;

        int slot = findGlider();
        if (slot == -1) return false;

        // Remembered now: after the swap this slot holds whatever was on your chest.
        swapSlot = slot;
        InvUtils.move().from(slot).toArmor(2);
        equipCooldown = EQUIP_COOLDOWN_TICKS;

        swappedOn = true;
        swappedAt = ticks;

        // Do not trust the client to have updated already; let the next tick confirm it.
        return hasGlider();
    }

    /** First usable elytra (or other glider) in the inventory, or -1. */
    private int findGlider() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.contains(DataComponentTypes.GLIDER)) continue;
            if (stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1) continue;
            return i;
        }
        return -1;
    }

    /** Something other than a glider on your chest, such as a chestplate. */
    private boolean wearingChestplate() {
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        return !chest.isEmpty() && !chest.contains(DataComponentTypes.GLIDER);
    }

    /** Wearing a glider (elytra) that is not about to break. */
    private boolean hasGlider() {
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.contains(DataComponentTypes.GLIDER)) return false;
        return !chest.isDamageable() || chest.getDamage() < chest.getMaxDamage() - 1;
    }

    /**
     * Can the elytra be opened right now. Needs to be in the air: on the ground vanilla refuses
     * to start gliding, which is why the key used to just fire a wasted rocket with a hand swing.
     * (Lambda gets the on-ground check from vanilla's canGlide(), which this mirrors.)
     */
    private boolean canStartGliding() {
        return !mc.player.isOnGround()
            && !mc.player.isGliding()
            && !mc.player.isClimbing()
            && !mc.player.isTouchingWater()
            && !mc.player.hasVehicle()
            && !mc.player.hasStatusEffect(StatusEffects.LEVITATION)
            && hasGlider();
    }

    private boolean canTakeoff() {
        return (mc.player.isOnGround() || !mc.player.isGliding())
            && !mc.player.getAbilities().flying
            && !mc.player.isClimbing()
            && !mc.player.isTouchingWater()
            && !mc.player.hasVehicle()
            && !mc.player.hasStatusEffect(StatusEffects.LEVITATION)
            && hasGlider()
            && hasFireworks();
    }

    private static boolean isMiddleMouse(Keybind bind) {
        return !bind.isKey() && bind.getValue() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
    }
}
