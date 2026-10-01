package dev.jjstools.modules;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.jjstools.JJsTools;
import dev.jjstools.mixin.BossBarHudAccessor;
import meteordevelopment.meteorclient.events.entity.player.FinishUsingItemEvent;
import meteordevelopment.meteorclient.events.entity.player.ItemUseCrosshairTargetEvent;
import meteordevelopment.meteorclient.events.entity.player.StoppedUsingItemEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.systems.modules.combat.BedAura;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.player.AutoGap;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.OminousBottleAmplifierComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drinks ominous bottles automatically. Modes and raid detection follow
 * ZenithProxy's AutoOmen: the omen effects are Bad Omen, Raid Omen and Trial
 * Omen, and a raid counts as active while a raid boss bar is present.
 *
 * Raid farm notes:
 *  - The raid bar is read from the client's boss bar list, which is filled from
 *    server packets. Hiding boss bars (Meteor NoRender, F1) only skips drawing,
 *    so detection still works with the bar hidden.
 *  - By default a Victory or Defeat bar still counts as a raid. The next bottle
 *    is only drunk once the server removes the bar and the cooldown passes, so a
 *    bottle is never spent into a raid that is winding down.
 *  - A drink in progress is cancelled if an omen or raid appears mid-drink, so
 *    no bottle is wasted when the next raid kicks off.
 *  - Bottles are swapped into your held slot with a slot-swap click, so your
 *    selected hotbar slot never changes, and swapped back a few ticks after the
 *    drink finishes. Swapping back the instant it finishes can land before the
 *    server finishes the drink and cancel it.
 */
public class AutoOmen extends Module {
    public enum Mode {
        EffectAndRaid("effectAndRaid"),
        Effect("effect"),
        Constant("constant");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum Order {
        HighestFirst("highest-first"),
        LowestFirst("lowest-first");

        private final String title;

        Order(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private enum State {
        Idle, Drinking, SwappingBack
    }

    private static final List<Class<? extends Module>> AURAS = List.of(KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBottles = settings.createGroup("Bottles");
    private final SettingGroup sgRaid = settings.createGroup("Raid");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("effectAndRaid: drink when no omen effect and no raid is active. effect: drink when no omen effect is active. constant: drink at a fixed interval.")
        .defaultValue(Mode.EffectAndRaid)
        .build()
    );

    private final Setting<Integer> constantDelay = sgGeneral.add(new IntSetting.Builder()
        .name("constant-delay")
        .description("Ticks between drinks in constant mode. Default matches the omen effect length: 100 seconds (2000 ticks).")
        .defaultValue(2000)
        .min(1)
        .sliderRange(20, 6000)
        .visible(() -> mode.get() == Mode.Constant)
        .build()
    );

    private final Setting<Integer> omenCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("omen-cooldown")
        .description("Ticks an omen effect has to be gone before drinking. 20 ticks matches ZenithProxy's default.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 200)
        .visible(() -> mode.get() != Mode.Constant)
        .build()
    );

    private final Setting<Integer> swapBackDelay = sgGeneral.add(new IntSetting.Builder()
        .name("swap-back-delay")
        .description("Ticks to wait after a drink before swapping the bottle stack back. Gives the server time to finish the drink.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> pauseAuras = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-auras")
        .description("Turn off KillAura, CrystalAura, AnchorAura and BedAura while drinking and turn them back on after.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Say in chat when a bottle is drunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> consumeFullOmenStack = sgBottles.add(new BoolSetting.Builder()
        .name("consume-full-omen-stack")
        .description("Off: a single bottle is left in every stack, so that slot stays reserved and bottles you pick up merge into it. Stacks are not combined.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Order> order = sgBottles.add(new EnumSetting.Builder<Order>()
        .name("order")
        .description("Which bottle level to drink first.")
        .defaultValue(Order.HighestFirst)
        .build()
    );

    private final Setting<Boolean> ignoreLevelOne = sgBottles.add(new BoolSetting.Builder()
        .name("ignore-level-1")
        .description("Never drink level I ominous bottles.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> searchInventory = sgBottles.add(new BoolSetting.Builder()
        .name("search-inventory")
        .description("Take bottles from the whole inventory, not only the hotbar. Either way they are silently swapped into your held slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> raidCooldown = sgRaid.add(new IntSetting.Builder()
        .name("raid-cooldown")
        .description("Ticks the raid bar has to be gone before drinking. 20 ticks matches ZenithProxy's default.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 400)
        .visible(() -> mode.get() == Mode.EffectAndRaid)
        .build()
    );

    private final Setting<Boolean> drinkOnFinishedRaid = sgRaid.add(new BoolSetting.Builder()
        .name("drink-on-finished-raid")
        .description("Treat a Victory or Defeat raid bar as no raid, so the next bottle is drunk while that bar is still showing. Faster, but off is the safe choice for farms.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.EffectAndRaid)
        .build()
    );

    private State state = State.Idle;
    private int stateTicks;
    private int heldSlot = -1;
    private int swappedSlot = -1;
    private int constantTimer;
    private int retryTimer;
    private int ticksSinceOmen = 1_000_000;
    private int ticksSinceRaid = 1_000_000;
    private final List<Class<? extends Module>> pausedAuras = new ArrayList<>();

    public AutoOmen() {
        super(JJsTools.CATEGORY, "auto-omen", "Automatically drinks ominous bottles.");
    }

    @Override
    public void onActivate() {
        state = State.Idle;
        stateTicks = 0;
        heldSlot = -1;
        swappedSlot = -1;
        constantTimer = 0;
        retryTimer = 0;
        ticksSinceOmen = 1_000_000;
        ticksSinceRaid = 1_000_000;
        pausedAuras.clear();
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.interactionManager != null) {
            if (state == State.Drinking) {
                state = State.SwappingBack;
                mc.options.useKey.setPressed(false);
                if (mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player);
            }
            if (swappedSlot != -1 && canClick()) InvUtils.quickSwap().fromId(heldSlot).to(swappedSlot);
        }

        swappedSlot = -1;
        state = State.Idle;
        resumeAuras();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (state == State.Drinking) mc.options.useKey.setPressed(false);
        state = State.Idle;
        swappedSlot = -1;
        heldSlot = -1;
        resumeAuras();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (hasOmenEffect()) ticksSinceOmen = 0;
        else if (ticksSinceOmen < 1_000_000) ticksSinceOmen++;

        if (raidActive()) ticksSinceRaid = 0;
        else if (ticksSinceRaid < 1_000_000) ticksSinceRaid++;

        if (constantTimer > 0) constantTimer--;
        if (retryTimer > 0) retryTimer--;

        switch (state) {
            case Drinking -> tickDrinking();
            case SwappingBack -> tickSwapBack();
            case Idle -> tryStart();
        }
    }

    /** Stops vanilla from right clicking the block you are looking at (a chest, a door) instead of drinking. */
    @EventHandler
    private void onItemUseCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        if (state == State.Drinking) event.target = null;
    }

    @EventHandler
    private void onFinishUsingItem(FinishUsingItemEvent event) {
        if (state == State.Drinking && event.itemStack.isOf(Items.OMINOUS_BOTTLE)) finish(false);
    }

    @EventHandler
    private void onStoppedUsingItem(StoppedUsingItemEvent event) {
        if (state == State.Drinking && event.itemStack.isOf(Items.OMINOUS_BOTTLE)) finish(true);
    }

    // Idle

    private void tryStart() {
        if (retryTimer > 0) return;
        if (!mc.player.isAlive() || mc.player.isCreative() || mc.player.isSpectator()) return;
        if (mc.player.isUsingItem() || !canClick()) return;
        if (otherModuleEating()) return;
        if (!wantsToDrink()) return;

        int slot = findBottle();
        if (slot == -1) return;

        ItemStack stack = slot == SlotUtils.OFFHAND ? mc.player.getOffHandStack() : mc.player.getInventory().getStack(slot);
        int level = amplifier(stack) + 1;

        heldSlot = mc.player.getInventory().getSelectedSlot();
        if (slot != heldSlot) {
            // Slot-swap click: the bottle stack and your held item trade places.
            // Your selected hotbar slot does not change.
            InvUtils.quickSwap().fromId(heldSlot).to(slot);
            swappedSlot = slot;
        } else {
            swappedSlot = -1;
        }

        pauseAuras();
        state = State.Drinking;
        stateTicks = 0;

        mc.options.useKey.setPressed(true);
        Utils.rightClick();

        if (chatFeedback.get()) info("Drinking a level " + level + " ominous bottle.");
    }

    private boolean wantsToDrink() {
        return switch (mode.get()) {
            case Constant -> constantTimer <= 0;
            case Effect -> ticksSinceOmen > omenCooldown.get();
            case EffectAndRaid -> ticksSinceOmen > omenCooldown.get() && ticksSinceRaid > raidCooldown.get();
        };
    }

    /** Inventory index of the bottle to drink, or -1. */
    private int findBottle() {
        int selected = mc.player.getInventory().getSelectedSlot();
        int end = searchInventory.get() ? SlotUtils.MAIN_END : SlotUtils.HOTBAR_END;

        int best = -1;
        int bestAmp = 0;
        int bestRank = Integer.MAX_VALUE;

        for (int i = 0; i <= end; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            int rank = i == selected ? 0 : (SlotUtils.isHotbar(i) ? 1 : 3);

            if (!usable(stack)) continue;
            int amp = amplifier(stack);

            if (best == -1 || better(amp, bestAmp) || (amp == bestAmp && rank < bestRank)) {
                best = i;
                bestAmp = amp;
                bestRank = rank;
            }
        }

        ItemStack offhand = mc.player.getOffHandStack();
        if (usable(offhand)) {
            int amp = amplifier(offhand);
            if (best == -1 || better(amp, bestAmp) || (amp == bestAmp && 2 < bestRank)) best = SlotUtils.OFFHAND;
        }

        return best;
    }

    private boolean usable(ItemStack stack) {
        if (!stack.isOf(Items.OMINOUS_BOTTLE)) return false;
        if (!consumeFullOmenStack.get() && stack.getCount() < 2) return false;
        return !(ignoreLevelOne.get() && amplifier(stack) == 0);
    }

    private boolean better(int amp, int bestAmp) {
        return order.get() == Order.HighestFirst ? amp > bestAmp : amp < bestAmp;
    }

    /**
     * Bottle amplifier, 0 to 4, shown in game as level I to V.
     *
     * The record's getter has no stable name in Yarn 1.21.11, so instead of
     * calling it this encodes the component with its own CODEC (a mapped field).
     * The component is stored as a plain int, the same number you would write in
     * ominous_bottle[ominous_bottle_amplifier=4], so the encoded value is the
     * amplifier. Anything unexpected falls back to 0 (level I).
     */
    private static int amplifier(ItemStack stack) {
        OminousBottleAmplifierComponent component = stack.get(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER);
        if (component == null) return 0;

        return OminousBottleAmplifierComponent.CODEC.encodeStart(JsonOps.INSTANCE, component)
            .result()
            .filter(JsonElement::isJsonPrimitive)
            .map(JsonElement::getAsInt)
            .orElse(0);
    }

    // Drinking

    private void tickDrinking() {
        stateTicks++;

        // Same as ZenithProxy: stop mid-drink if an omen or raid shows up.
        boolean blocked = switch (mode.get()) {
            case Constant -> false;
            case Effect -> ticksSinceOmen == 0;
            case EffectAndRaid -> ticksSinceOmen == 0 || ticksSinceRaid == 0;
        };

        if (blocked || !mc.player.isAlive() || mc.currentScreen != null || !mc.player.getMainHandStack().isOf(Items.OMINOUS_BOTTLE)) {
            finish(true);
            return;
        }

        mc.options.useKey.setPressed(true);

        if (!mc.player.isUsingItem()) {
            if (stateTicks > 10) {
                // The server never let the drink start.
                finish(true);
                return;
            }
            Utils.rightClick();
        }

        if (stateTicks > 100) finish(true);
    }

    private void finish(boolean aborted) {
        state = State.SwappingBack;
        stateTicks = 0;
        mc.options.useKey.setPressed(false);

        if (aborted) {
            if (mc.player != null && mc.interactionManager != null && mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player);
            retryTimer = 40;
        } else if (mode.get() == Mode.Constant) {
            constantTimer = constantDelay.get();
        }
    }

    // Swapping back

    private void tickSwapBack() {
        stateTicks++;
        if (stateTicks < swapBackDelay.get()) return;

        if (swappedSlot != -1 && mc.player.isAlive()) {
            if (!canClick()) {
                // Wait for any open screen to close, up to 10 seconds.
                if (stateTicks < 200) return;
            } else {
                InvUtils.quickSwap().fromId(heldSlot).to(swappedSlot);
            }
        }

        swappedSlot = -1;
        state = State.Idle;
        resumeAuras();
    }

    // Checks

    private boolean canClick() {
        return mc.currentScreen == null && mc.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    private boolean otherModuleEating() {
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        if (autoEat != null && autoEat.isActive() && autoEat.eating) return true;

        AutoGap autoGap = Modules.get().get(AutoGap.class);
        return autoGap != null && autoGap.isActive() && autoGap.isEating();
    }

    private boolean hasOmenEffect() {
        return mc.player.hasStatusEffect(StatusEffects.BAD_OMEN)
            || mc.player.hasStatusEffect(StatusEffects.RAID_OMEN)
            || mc.player.hasStatusEffect(StatusEffects.TRIAL_OMEN);
    }

    /**
     * Reads the boss bar list directly, so a hidden bar still counts. The raid
     * bar title is built from the event.minecraft.raid translation keys; the
     * finished titles use event.minecraft.raid.victory.full and
     * event.minecraft.raid.defeat.full. The whole text tree is walked, so this
     * works whether those keys are the top-level text or appended parts.
     */
    private boolean raidActive() {
        if (mc.inGameHud == null) return false;

        Map<UUID, ClientBossBar> bars = ((BossBarHudAccessor) mc.inGameHud.getBossBarHud()).jjstools$getBossBars();
        for (ClientBossBar bar : bars.values()) {
            boolean[] found = new boolean[2]; // [0] raid key seen, [1] victory or defeat key seen
            scanRaidText(bar.getName(), found, 0);
            if (found[0] && !(found[1] && drinkOnFinishedRaid.get())) return true;
        }

        return false;
    }

    private static void scanRaidText(Text text, boolean[] found, int depth) {
        if (text == null || depth > 8) return;

        if (text.getContent() instanceof TranslatableTextContent translatable) {
            String key = translatable.getKey();
            if (key.startsWith("event.minecraft.raid")) {
                found[0] = true;
                if (key.contains("victory") || key.contains("defeat")) found[1] = true;
            }

            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Text argText) scanRaidText(argText, found, depth + 1);
            }
        }

        for (Text sibling : text.getSiblings()) scanRaidText(sibling, found, depth + 1);
    }

    // Auras

    private void pauseAuras() {
        pausedAuras.clear();
        if (!pauseAuras.get()) return;

        for (Class<? extends Module> klass : AURAS) {
            Module module = Modules.get().get(klass);
            if (module != null && module.isActive()) {
                pausedAuras.add(klass);
                module.toggle();
            }
        }
    }

    private void resumeAuras() {
        for (Class<? extends Module> klass : pausedAuras) {
            Module module = Modules.get().get(klass);
            if (module != null && !module.isActive()) module.toggle();
        }
        pausedAuras.clear();
    }
}
