package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Puts a piece of gold armour on while a piglin is near, so piglins stay neutral, and puts your
 * own piece back once they are gone.
 *
 * Any of the four slots will do: piglins only check whether you are wearing gold anywhere, not
 * which slot. Leggings by default because they are usually the cheapest piece to give up.
 *
 * Swapping, swap back and rate limits are shared with Auto Turtle Helmet in ArmorSwapModule.
 *
 * Only piglins count: piglin brutes attack whatever you wear, and zombified piglins do not care
 * about gold.
 */
public class AutoWearGold extends ArmorSwapModule {
    public enum Piece {
        Helmet(EquipmentSlot.HEAD, 5, Items.GOLDEN_HELMET),
        Chestplate(EquipmentSlot.CHEST, 6, Items.GOLDEN_CHESTPLATE),
        Leggings(EquipmentSlot.LEGS, 7, Items.GOLDEN_LEGGINGS),
        Boots(EquipmentSlot.FEET, 8, Items.GOLDEN_BOOTS);

        public final EquipmentSlot slot;
        /** PlayerScreenHandler slot id. */
        public final int slotId;
        public final Item item;

        Piece(EquipmentSlot slot, int slotId, Item item) {
            this.slot = slot;
            this.slotId = slotId;
            this.item = item;
        }
    }

    private Setting<Piece> piece;
    private Setting<Integer> range;
    private Setting<Boolean> skipIfWearingGold;

    public AutoWearGold() {
        super(JJsTools.CATEGORY, "auto-wear-gold", "Puts gold armour on when piglins are near, and your own piece back after.",
            EquipmentSlot.LEGS, 7, Items.GOLDEN_LEGGINGS, "gold piece");
    }

    @Override
    protected void addTriggerSettings(SettingGroup group) {
        piece = group.add(new EnumSetting.Builder<Piece>()
            .name("piece")
            .description("Which slot to put gold in. Piglins only check that you are wearing gold somewhere, so this is purely which piece you would rather give up.")
            .defaultValue(Piece.Leggings)
            .build()
        );

        range = group.add(new IntSetting.Builder()
            .name("range")
            .description("Put the gold on when a piglin is within this many blocks.")
            .defaultValue(16)
            .range(1, 50)
            .sliderRange(1, 50)
            .build()
        );

        skipIfWearingGold = group.add(new BoolSetting.Builder()
            .name("skip-if-wearing-gold")
            .description("Don't swap if you already wear gold in another slot, since one piece is enough for piglins.")
            .defaultValue(true)
            .build()
        );
    }

    // The chosen piece drives the base class, so changing the setting changes the slot live.

    @Override
    protected EquipmentSlot equipmentSlot() {
        return piece == null ? EquipmentSlot.LEGS : piece.get().slot;
    }

    @Override
    protected int armorSlotId() {
        return piece == null ? 7 : piece.get().slotId;
    }

    @Override
    protected Item item() {
        return piece == null ? Items.GOLDEN_LEGGINGS : piece.get().item;
    }

    @Override
    protected boolean wanted() {
        if (skipIfWearingGold.get() && wearingOtherGold()) return false;

        double max = range.get() * (double) range.get();
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PiglinEntity && entity.isAlive() && mc.player.squaredDistanceTo(entity) <= max) return true;
        }
        return false;
    }

    /** Gold in any slot other than the one this module manages. */
    private boolean wearingOtherGold() {
        EquipmentSlot mine = equipmentSlot();

        for (Piece other : Piece.values()) {
            if (other.slot == mine) continue;
            if (mc.player.getEquippedStack(other.slot).isOf(other.item)) return true;
        }
        return false;
    }
}
