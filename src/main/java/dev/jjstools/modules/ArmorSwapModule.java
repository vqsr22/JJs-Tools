package dev.jjstools.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.ArrayDeque;

/**
 * Shared swapping logic for Auto Turtle Helmet and Auto Trousers: puts a specific armour piece on
 * from your inventory while wanted() is true, and optionally swaps your old piece back after.
 *
 * Placement rules:
 *  - Equipping: the piece you were wearing goes into the exact slot the new piece came from.
 *  - Swapping back: your old piece goes back on, and the swapped-in piece goes back to the slot it
 *    came from. If that slot is taken, it goes to the furthest-back empty slot (main inventory
 *    bottom-right first, hotbar last).
 */
public abstract class ArmorSwapModule extends Module {
    private final EquipmentSlot defaultEquipmentSlot;
    /** PlayerScreenHandler slot id of the armour slot: 5 head, 6 chest, 7 legs, 8 feet. */
    private final int defaultArmorSlotId;
    private final Item defaultItem;
    private final String itemName;

    protected final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> silentSwap;
    private final Setting<Integer> delay;
    private final Setting<Integer> cooldown;
    private final Setting<Integer> limitPerSecond;
    private final Setting<Boolean> preferMaxDurability;
    private final Setting<Boolean> ignoreBinding;
    private final Setting<Boolean> swapBack;

    // What this module changed, so it can be undone.
    private boolean swapped;
    private int home = -1;                      // inventory index the swapped-in piece came from
    private ItemStack oldPiece = ItemStack.EMPTY; // copy of what you were wearing (EMPTY if nothing)

    private int delayTimer;
    private int cooldownTimer;
    private final ArrayDeque<Long> recentSwaps = new ArrayDeque<>();

    /**
     * Which piece this module wears. Overridable so a subclass can let the user pick, as
     * Auto Wear Gold does. Everything below goes through these rather than the fields.
     */
    protected EquipmentSlot equipmentSlot() {
        return defaultEquipmentSlot;
    }

    /** PlayerScreenHandler slot id: 5 head, 6 chest, 7 legs, 8 feet. */
    protected int armorSlotId() {
        return defaultArmorSlotId;
    }

    protected Item item() {
        return defaultItem;
    }

    protected ArmorSwapModule(Category category, String name, String description,
                              EquipmentSlot equipmentSlot, int armorSlotId, Item item, String itemName) {
        super(category, name, description);
        this.defaultEquipmentSlot = equipmentSlot;
        this.defaultArmorSlotId = armorSlotId;
        this.defaultItem = item;
        this.itemName = itemName;

        // The module's own trigger settings go first, then the shared swap settings.
        addTriggerSettings(sgGeneral);

        silentSwap = sgGeneral.add(new BoolSetting.Builder()
            .name("silent-swap")
            .description("Swap through inventory packets without touching your held item. Off: the piece is swapped into your hand and right-clicked on, like doing it by hand.")
            .defaultValue(true)
            .build()
        );

        delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Ticks to wait before putting the " + itemName + " on once it is wanted.")
            .defaultValue(0)
            .min(0)
            .sliderRange(0, 100)
            .build()
        );

        cooldown = sgGeneral.add(new IntSetting.Builder()
            .name("cooldown")
            .description("Minimum ticks between swaps.")
            .defaultValue(10)
            .min(0)
            .sliderRange(0, 200)
            .build()
        );

        limitPerSecond = sgGeneral.add(new IntSetting.Builder()
            .name("limit-per-second")
            .description("Most swaps allowed in any one second. Equipping, swapping back and restoring each count as one.")
            .defaultValue(3)
            .range(1, 20)
            .sliderRange(1, 20)
            .build()
        );

        preferMaxDurability = sgGeneral.add(new BoolSetting.Builder()
            .name("prefer-max-durability")
            .description("Use the " + itemName + " with the most durability left. Off: use the first one found (hotbar first).")
            .defaultValue(true)
            .build()
        );

        ignoreBinding = sgGeneral.add(new BoolSetting.Builder()
            .name("ignore-curse-of-binding")
            .description("Never put on a " + itemName + " that has Curse of Binding, since it could not be taken off again to swap back.")
            .defaultValue(true)
            .build()
        );

        swapBack = sgGeneral.add(new BoolSetting.Builder()
            .name("swap-back")
            .description("Put your old piece back on when the " + itemName + " is no longer wanted, when it breaks, or when the module is turned off.")
            .defaultValue(true)
            .build()
        );
    }

    /**
     * True if one of these modules needs this armour slot right now (its piece is wanted or it has
     * swapped something that still needs swapping back). Meteor's Auto Armor leaves the slot alone
     * while this is true, see AutoArmorMixin.
     */
    public static boolean claims(EquipmentSlot slot) {
        Modules modules = Modules.get();
        if (modules == null) return false;
        for (Module module : modules.getAll()) {
            if (!(module instanceof ArmorSwapModule swap) || !swap.isActive() || swap.equipmentSlot() != slot) continue;
            if (swap.mc.player == null || swap.mc.world == null) continue;
            if (swap.swapped) return true;
            if (swap.wanted() && (swap.worn().isOf(swap.item()) || swap.findItem() != -1)) return true;
        }
        return false;
    }

    /** Add the module's own trigger settings. Runs inside the constructor, before the shared settings. */
    protected abstract void addTriggerSettings(SettingGroup group);

    /** True while the piece should be worn. */
    protected abstract boolean wanted();

    @Override
    public void onActivate() {
        clear();
        delayTimer = 0;
        cooldownTimer = 0;
        recentSwaps.clear();
    }

    @Override
    public void onDeactivate() {
        if (swapped && swapBack.get() && mc.player != null && mc.interactionManager != null && canClick()
            && worn().isOf(item())) {
            unequip();
        }
        clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clear();
        recentSwaps.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (cooldownTimer > 0) cooldownTimer--;
        if (!canClick()) return;

        ItemStack current = worn();
        boolean wearing = current.isOf(item());

        if (wanted()) {
            if (wearing) {
                delayTimer = 0;
                return;
            }

            int found = findItem();
            if (found == -1) {
                // Ours broke and there is no spare: put the old piece back on.
                if (swapped && current.isEmpty() && swapBack.get() && ready()) restoreOld();
                return;
            }

            if (hasBinding(current)) return;
            if (delayTimer < delay.get()) {
                delayTimer++;
                return;
            }
            if (!ready()) return;

            equip(found, current);
            return;
        }

        delayTimer = 0;
        if (!swapped) return;
        if (!swapBack.get()) {
            clear();
            return;
        }
        if (!ready()) return;

        if (wearing) unequip();
        else if (current.isEmpty()) restoreOld();
        else clear(); // you put something else on yourself, leave it alone
    }

    // Actions

    private void equip(int found, ItemStack current) {
        // If ours broke and we are putting on a spare, keep the original old piece.
        if (!swapped || !current.isEmpty()) oldPiece = current.copy();
        home = found;
        swapped = true;

        if (silentSwap.get()) {
            click(found);              // pick up the new piece
            clickArmor();              // put it on, old piece (if any) is now on the cursor
            if (!cursorEmpty()) click(found); // old piece into the new piece's slot
        } else {
            handEquip(found);          // old piece ends up in the new piece's slot
        }

        swapDone();
    }

    private void unequip() {
        int old = oldPiece.isEmpty() ? -1 : findOld();

        if (!oldPiece.isEmpty() && old == -1) {
            // Old piece is not in your inventory any more: keep ours on.
            clear();
            return;
        }

        if (!silentSwap.get() && old != -1) {
            handEquip(old); // old piece on, ours now in the old piece's slot
            if (old != home) {
                // Old piece was moved since the swap, so ours landed somewhere else.
                // Move it home, or further back if home is taken.
                int dest = destination();
                if (dest != -1 && (dest == home || rank(dest) > rank(old))) {
                    click(old);
                    click(dest);
                }
            }
        } else {
            if (old != -1) {
                click(old);   // pick up old piece
                clickArmor(); // old piece on, ours on the cursor
            } else {
                clickArmor(); // ours on the cursor, slot empty
            }

            int dest = destination();
            if (dest == -1) {
                clickArmor(); // nowhere to put it: wear it again
                clear();
                swapDone();
                return;
            }
            click(dest);
        }

        clear();
        swapDone();
    }

    /** Ours is gone (broke or moved away). Put the old piece back on if we still have it. */
    private void restoreOld() {
        int old = oldPiece.isEmpty() ? -1 : findOld();
        if (old != -1) {
            if (silentSwap.get()) {
                click(old);
                clickArmor();
            } else {
                handEquip(old);
            }
            swapDone();
        }
        clear();
    }

    /** Swaps the stack in an inventory slot into your hand, right-clicks it on, then puts everything back. */
    private void handEquip(int slot) {
        if (slot < 9) {
            InvUtils.swap(slot, true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        } else {
            int selected = mc.player.getInventory().getSelectedSlot();
            InvUtils.quickSwap().fromId(selected).to(slot);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.quickSwap().fromId(selected).to(slot);
        }
    }

    // Rate limiting

    /** Cooldown finished and under limit-per-second. */
    private boolean ready() {
        if (cooldownTimer > 0) return false;
        long now = System.currentTimeMillis();
        while (!recentSwaps.isEmpty() && now - recentSwaps.peekFirst() >= 1000) recentSwaps.pollFirst();
        return recentSwaps.size() < limitPerSecond.get();
    }

    private void swapDone() {
        cooldownTimer = cooldown.get();
        delayTimer = 0;
        recentSwaps.addLast(System.currentTimeMillis());
    }

    // Lookups

    private int findItem() {
        PlayerInventory inv = mc.player.getInventory();
        int best = -1;
        int bestLeft = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isOf(item())) continue;
            if (ignoreBinding.get() && hasBinding(stack)) continue;
            if (!preferMaxDurability.get()) return i;

            int left = stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
            if (left > bestLeft) {
                best = i;
                bestLeft = left;
            }
        }
        return best;
    }

    /** Where the old piece is now: the exact stack anywhere, else the same item in its home slot, else the same item anywhere. */
    private int findOld() {
        PlayerInventory inv = mc.player.getInventory();

        for (int i = 0; i < 36; i++) {
            if (ItemStack.areEqual(inv.getStack(i), oldPiece)) return i;
        }
        if (home != -1 && inv.getStack(home).isOf(oldPiece.getItem())) return home;
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isOf(oldPiece.getItem())) return i;
        }
        return -1;
    }

    /** The home slot if it is free, otherwise the furthest-back empty slot. */
    private int destination() {
        PlayerInventory inv = mc.player.getInventory();
        if (home != -1 && inv.getStack(home).isEmpty()) return home;

        for (int i = 35; i >= 9; i--) if (inv.getStack(i).isEmpty()) return i;
        for (int i = 8; i >= 0; i--) if (inv.getStack(i).isEmpty()) return i;
        return -1;
    }

    /** Higher is further back: main inventory 9..35, then hotbar below all of it. */
    private static int rank(int index) {
        return index >= 9 ? index : index - 100;
    }

    private static boolean hasBinding(ItemStack stack) {
        for (RegistryEntry<Enchantment> e : stack.getEnchantments().getEnchantments()) {
            if (e.matchesKey(Enchantments.BINDING_CURSE)) return true;
        }
        return false;
    }

    // Helpers

    protected ItemStack worn() {
        return mc.player.getEquippedStack(equipmentSlot());
    }

    private boolean canClick() {
        return mc.currentScreen == null
            && mc.player.currentScreenHandler == mc.player.playerScreenHandler
            && cursorEmpty()
            && !mc.player.isUsingItem();
    }

    private boolean cursorEmpty() {
        return mc.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    /** Clicks an inventory index (0-8 hotbar, 9-35 main). */
    private void click(int index) {
        int id = index < 9 ? 36 + index : index;
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, id, 0, SlotActionType.PICKUP, mc.player);
    }

    private void clickArmor() {
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, armorSlotId(), 0, SlotActionType.PICKUP, mc.player);
    }

    private void clear() {
        swapped = false;
        home = -1;
        oldPiece = ItemStack.EMPTY;
    }
}
