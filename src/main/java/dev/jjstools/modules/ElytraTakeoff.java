package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
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
    private enum State { Idle, Jumping, StartFlying }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> activateKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("activate-key")
        .description("Jump, start gliding and fire a rocket. Mid-air or while gliding it just fires a rocket.")
        .defaultValue(Keybind.fromButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE))
        .action(this::onActivateKey)
        .build()
    );

    private final Setting<Keybind> midFlightKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("mid-flight-key")
        .description("Fire a rocket, only while you are already gliding.")
        .defaultValue(Keybind.none())
        .action(this::onMidFlightKey)
        .build()
    );

    private final Setting<Boolean> middleClickCancel = sgGeneral.add(new BoolSetting.Builder()
        .name("middle-click-cancel")
        .description("When activate-key is middle mouse, stop it also doing pick block. Off: looking at a block, middle click picks the block instead of taking off.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rightClickFly = sgGeneral.add(new BoolSetting.Builder()
        .name("right-click-fly")
        .description("Right-clicking a firework on the ground (or while falling) takes off instead of wasting it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rightClickCancel = sgGeneral.add(new BoolSetting.Builder()
        .name("right-click-cancel")
        .description("Also take off when right-clicking a block while holding fireworks, instead of placing the rocket on the block.")
        .defaultValue(false)
        .visible(rightClickFly::get)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your hand on screen. Off: only the swing packet is sent.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chestplateOnLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("chestplate-on-landing")
        .description("Put your chestplate back on once you are down. Pairs with auto-equip: the elytra goes back to the slot the chestplate came out of, so neither moves around your inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> landingDelay = sgGeneral.add(new IntSetting.Builder()
        .name("landing-delay")
        .description("Ticks to wait after landing before swapping back. 20 is one second. 0 swaps the moment you touch down.")
        .defaultValue(0).min(0).sliderRange(0, 100)
        .visible(chestplateOnLanding::get)
        .build()
    );

    private final Setting<Boolean> autoEquip = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-equip")
        .description("Put an elytra on first if you aren't wearing one, then take off in the same key press.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inventory = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory")
        .description("Use fireworks from your main inventory when there are none in the hotbar.")
        .defaultValue(true)
        .build()
    );

    private State state = State.Idle;
    private boolean usingOwn;

    public ElytraTakeoff() {
        super(JJsTools.CATEGORY, "elytra-takeoff", "Jump, open your elytra and fire a rocket with one key.");
    }

    @Override
    public void onActivate() {
        state = State.Idle;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        state = State.Idle;
    }

    // Keys

    private void onActivateKey() {
        if (mc.player == null || mc.currentScreen != null) return;

        // Middle mouse on a block is pick block, unless middle-click-cancel says otherwise.
        if (isMiddleMouse(activateKey.get()) && !middleClickCancel.get()
            && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) return;

        if (!hasFireworks()) {
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

        if (canStartGliding() || mc.player.isGliding()) state = State.StartFlying;
        else if (canTakeoff()) state = State.Jumping;
    }

    private void onMidFlightKey() {
        if (mc.player == null || mc.currentScreen != null) return;
        if (state == State.Idle && mc.player.isGliding()) state = State.StartFlying;
    }

    /** Stops the middle click from also doing pick block, when asked to. */
    @EventHandler
    private void onMouse(MouseClickEvent event) {
        if (!middleClickCancel.get() || mc.currentScreen != null) return;
        if (event.action == KeyAction.Press && activateKey.get().matches(event.input) && isMiddleMouse(activateKey.get())) {
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

    @Override
    public void onDeactivate() {
        equipCooldown = 0;
        landingTimer = -1;
        wasGliding = false;
    }

    private boolean startTakeoff() {
        if (!ensureGlider()) return false;

        if (canTakeoff()) {
            if (state == State.Idle) state = State.Jumping;
            return true;
        }
        if (canStartGliding()) {
            if (state == State.Idle) state = State.StartFlying;
            return true;
        }
        return false;
    }

    // Tick

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (equipCooldown > 0) equipCooldown--;
        trackLanding();
        if (mc.player == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            state = State.Idle;
            return;
        }

        switch (state) {
            case Idle -> {
            }
            case Jumping -> {
                mc.player.jump();
                state = State.StartFlying;
            }
            case StartFlying -> {
                if (canStartGliding() && mc.player.checkGliding()) {
                    mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
                // Only fire once the elytra is open, otherwise the rocket does nothing.
                if (mc.player.isGliding()) useFirework();
                state = State.Idle;
            }
        }
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

        if (wasGliding && !gliding && mc.player.isOnGround()) {
            landingTimer = chestplateOnLanding.get() ? landingDelay.get() : -1;
        }
        wasGliding = gliding;

        if (landingTimer < 0) return;
        if (landingTimer-- > 0) return;

        landingTimer = -1;
        equipChestplate();
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
