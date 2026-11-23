package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AnchorAura;
import meteordevelopment.meteorclient.systems.modules.combat.BedAura;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

/**
 * Eats when your health or hunger drops, as Mio's AutoEat does: an item list with a mode, health
 * and hunger thresholds, eating until full, and pausing Baritone. On top of that:
 *
 * - Stop on attack: hitting something yourself stops eating, and it waits a moment before
 *   starting again, so you can fight.
 * - Highest priority: while eating, nothing else gets in the way. The modules in the pause list
 *   are turned off, other modules' slot switches, attacks, block breaking and placing, item uses
 *   and inventory clicks are held back, and the food stays in your hand, until the eating is done;
 *   then everything is turned back on and carries on.
 * - Inventory: with no food in the hotbar, food is taken from the rest of the inventory into a
 *   hotbar slot, eaten, and what was there is put back.
 */
public class BetterAutoEat extends Module {
    public enum Mode { Any, Whitelist, Blacklist }
    public enum Priority { Saturation, Hunger }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPriority = settings.createGroup("Priority");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Foods for the whitelist or blacklist.")
        .defaultValue(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
            Items.CHICKEN, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE)
        .filter(item -> item.getComponents().get(DataComponentTypes.FOOD) != null)
        .build());

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Any: any food except the harmful ones (rotten flesh, spider eyes, pufferfish, poisonous potatoes, raw chicken, suspicious stew, chorus fruit). Whitelist: only the listed foods. Blacklist: any food but the listed ones.")
        .defaultValue(Mode.Any)
        .build());

    private final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("health")
        .description("Eat at or below this much health.")
        .defaultValue(16).range(0, 20).sliderRange(1, 20)
        .build());

    private final Setting<Double> hunger = sgGeneral.add(new DoubleSetting.Builder()
        .name("hunger")
        .description("Eat at or below this much hunger.")
        .defaultValue(17).range(0, 20).sliderRange(1, 20)
        .build());

    private final Setting<Boolean> untilFull = sgGeneral.add(new BoolSetting.Builder()
        .name("until-full")
        .description("Once started, keep eating until your hunger is full, not just until you are back over the thresholds.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> pauseBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pause Baritone while eating.")
        .defaultValue(true)
        .build());

    private final Setting<Priority> foodPriority = sgGeneral.add(new EnumSetting.Builder<Priority>()
        .name("food-priority")
        .description("Which food to pick when there is a choice: the one that fills saturation most, or hunger most.")
        .defaultValue(Priority.Saturation)
        .build());

    private final Setting<Boolean> stopOnAttack = sgPriority.add(new BoolSetting.Builder()
        .name("stop-on-attack")
        .description("Hitting something yourself (the attack key) stops eating, so you can fight.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> attackPause = sgPriority.add(new IntSetting.Builder()
        .name("attack-pause")
        .description("Ticks to wait after you attack before eating again.")
        .defaultValue(20).range(0, 200).sliderRange(0, 60)
        .visible(stopOnAttack::get)
        .build());

    private final Setting<Boolean> highestPriority = sgPriority.add(new BoolSetting.Builder()
        .name("highest-priority")
        .description("While eating, stop everything that could get in the way: turn off the modules below, hold back other modules' slot switches, attacks, mining, placing, item uses and inventory clicks, and keep the food in your hand. All of it carries on once you have eaten.")
        .defaultValue(false)
        .build());

    private final Setting<List<Module>> pauseModules = sgPriority.add(new ModuleListSetting.Builder()
        .name("pause-modules")
        .description("Modules turned off while eating (and back on after).")
        .defaultValue(KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class)
        .build());

    private final Setting<Boolean> inventory = sgInventory.add(new BoolSetting.Builder()
        .name("inventory")
        .description("With no food in your hotbar, take it from the rest of your inventory into a hotbar slot and eat it.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> inventorySlot = sgInventory.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("The hotbar slot food from the inventory goes into, when no hotbar slot is empty.")
        .defaultValue(9).range(1, 9).sliderRange(1, 9)
        .visible(inventory::get)
        .build());

    private final Setting<Boolean> swapBack = sgInventory.add(new BoolSetting.Builder()
        .name("put-back")
        .description("After eating, put back what was in that hotbar slot, and the rest of the food where it came from.")
        .defaultValue(true)
        .visible(inventory::get)
        .build());

    private static final List<Item> HARMFUL = List.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
        Items.PUFFERFISH, Items.CHICKEN, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT);

    private boolean eating;
    /** The slot being eaten from: a hotbar slot (0-8) or SlotUtils.OFFHAND. */
    private int slot = -1;
    private int prevSlot = -1;
    /** Food brought in from the inventory: from where, to which hotbar slot; -1 when none. */
    private int movedFrom = -1, movedTo = -1;
    private int cooldown;
    private boolean ownAction;
    private boolean pausedBaritone;
    private final List<Module> paused = new ArrayList<>();

    public BetterAutoEat() {
        super(JJsTools.CATEGORY, "better-auto-eat", "Eats at set health and hunger, Mio style, with stop-on-attack, a highest-priority mode that holds everything else back while you eat, and taking food from your inventory.");
    }

    @Override
    public void onDeactivate() {
        if (eating) stopEating();
        cooldown = 0;
    }

    // Deciding

    private boolean allowed(Item item) {
        return switch (mode.get()) {
            case Any -> !HARMFUL.contains(item);
            case Whitelist -> items.get().contains(item);
            case Blacklist -> !items.get().contains(item);
        };
    }

    private FoodComponent food(ItemStack stack) {
        if (stack.isEmpty() || !allowed(stack.getItem())) return null;
        return stack.get(DataComponentTypes.FOOD);
    }

    private boolean low() {
        return mc.player.getHealth() <= health.get() || mc.player.getHungerManager().getFoodLevel() <= hunger.get();
    }

    private boolean canEat(FoodComponent food) {
        return food != null && (mc.player.getHungerManager().isNotFull() || food.canAlwaysEat());
    }

    /** The best food slot: offhand, then hotbar, then (with inventory on) the rest; -1 if none. */
    private int findFood(boolean includeInventory) {
        if (canEat(food(mc.player.getOffHandStack()))) return SlotUtils.OFFHAND;
        int best = best(SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END);
        if (best != -1 || !includeInventory) return best;
        return best(SlotUtils.MAIN_START, SlotUtils.MAIN_END);
    }

    private int best(int from, int to) {
        int best = -1;
        double bestScore = -1;
        for (int i = from; i <= to; i++) {
            FoodComponent f = food(mc.player.getInventory().getStack(i));
            if (!canEat(f)) continue;
            double score = foodPriority.get() == Priority.Saturation ? f.saturation() : f.nutrition();
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    // Eating

    @EventHandler(priority = EventPriority.LOW)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (cooldown > 0) cooldown--;

        if (eating) {
            // Your own attack stops it, so you can fight.
            if (stopOnAttack.get() && mc.options.attackKey.isPressed()) {
                stopEating();
                cooldown = attackPause.get();
                return;
            }
            boolean full = !mc.player.getHungerManager().isNotFull();
            boolean done = untilFull.get() ? full : (!low() || full);
            ItemStack held = slot == SlotUtils.OFFHAND ? mc.player.getOffHandStack() : mc.player.getInventory().getStack(slot);
            if (done) {
                stopEating();
                return;
            }
            if (!canEat(food(held))) {
                // Ran out of that food: carry on with another, or stop.
                int next = findFood(inventory.get());
                if (next == -1) {
                    stopEating();
                    return;
                }
                if (!select(next)) {
                    stopEating();
                    return;
                }
            }
            keepEating();
            return;
        }

        if (cooldown > 0 || mc.currentScreen != null && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) return;
        if (!low()) return;
        if (stopOnAttack.get() && mc.options.attackKey.isPressed()) return;
        int found = findFood(inventory.get());
        if (found == -1) return;
        startEating(found);
    }

    /** At the end of every tick: with highest priority, the food goes back in hand whatever else moved it. */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onTickPost(TickEvent.Post event) {
        if (!eating || !highestPriority.get() || mc.player == null) return;
        if (slot != SlotUtils.OFFHAND && dev.jjstools.compat.Compat.selectedSlot(mc.player.getInventory()) != slot) {
            own(() -> InvUtils.swap(slot, false));
        }
        mc.options.useKey.setPressed(true);
    }

    private void startEating(int found) {
        prevSlot = dev.jjstools.compat.Compat.selectedSlot(mc.player.getInventory());
        if (!select(found)) return;
        eating = true;

        paused.clear();
        if (highestPriority.get()) {
            for (Module m : pauseModules.get()) {
                if (m != this && m.isActive()) {
                    paused.add(m);
                    m.toggle();
                }
            }
        }
        if (pauseBaritone.get()) {
            try {
                if (PathManagers.get().isPathing()) {
                    PathManagers.get().pause();
                    pausedBaritone = true;
                }
            } catch (Throwable ignored) {
            }
        }
        keepEating();
    }

    /** Puts the food at slot in hand: offhand as it is, hotbar selected, inventory brought to the hotbar. */
    private boolean select(int found) {
        if (found == SlotUtils.OFFHAND) {
            slot = SlotUtils.OFFHAND;
            return true;
        }
        if (SlotUtils.isHotbar(found)) {
            slot = found;
            own(() -> InvUtils.swap(found, false));
            return true;
        }
        // From the inventory: an empty hotbar slot if there is one, else the chosen one.
        int target = InvUtils.find(ItemStack::isEmpty, SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END).slot();
        if (target == -1) target = inventorySlot.get() - 1;
        int to = target;
        own(() -> InvUtils.move().from(found).toHotbar(to));
        if (movedFrom == -1) {
            movedFrom = found;
            movedTo = to;
        }
        slot = to;
        own(() -> InvUtils.swap(to, false));
        return true;
    }

    private void keepEating() {
        mc.options.useKey.setPressed(true);
        if (!mc.player.isUsingItem()) {
            Hand hand = slot == SlotUtils.OFFHAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
            own(() -> mc.interactionManager.interactItem(mc.player, hand));
        }
    }

    private void stopEating() {
        eating = false;
        mc.options.useKey.setPressed(false);
        if (mc.player != null && mc.player.isUsingItem()) own(() -> mc.interactionManager.stopUsingItem(mc.player));

        if (mc.player != null) {
            // What was taken from the inventory goes back, and what was in that slot with it.
            if (movedFrom != -1 && swapBack.get() && mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
                int from = movedFrom, to = movedTo;
                own(() -> InvUtils.move().from(to).to(from));
            }
            if (prevSlot >= 0 && prevSlot <= 8 && slot != SlotUtils.OFFHAND) {
                int back = prevSlot;
                own(() -> InvUtils.swap(back, false));
            }
        }
        movedFrom = movedTo = -1;
        slot = -1;

        for (Module m : paused) if (!m.isActive()) m.toggle();
        paused.clear();
        if (pausedBaritone) {
            try {
                PathManagers.get().resume();
            } catch (Throwable ignored) {
            }
            pausedBaritone = false;
        }
    }

    private void own(Runnable action) {
        ownAction = true;
        try {
            action.run();
        } finally {
            ownAction = false;
        }
    }

    // Highest priority: holding everything else back while eating

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onAttack(AttackEntityEvent event) {
        if (!eating) return;
        if (stopOnAttack.get() && mc.options.attackKey.isPressed()) {
            stopEating();
            cooldown = attackPause.get();
            return;
        }
        if (highestPriority.get()) event.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onBreak(StartBreakingBlockEvent event) {
        if (!eating) return;
        if (stopOnAttack.get() && mc.options.attackKey.isPressed()) {
            stopEating();
            cooldown = attackPause.get();
            return;
        }
        if (highestPriority.get()) event.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (!eating || !highestPriority.get() || ownAction) return;
        var p = event.packet;
        boolean blocks = p instanceof PlayerInteractEntityC2SPacket
            || p instanceof PlayerInteractBlockC2SPacket
            || p instanceof PlayerInteractItemC2SPacket
            || p instanceof ClickSlotC2SPacket
            || (p instanceof UpdateSelectedSlotC2SPacket u && slot != SlotUtils.OFFHAND && u.getSelectedSlot() != slot)
            || p instanceof PlayerActionC2SPacket;
        if (blocks) event.cancel();
    }
}
