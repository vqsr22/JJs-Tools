package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;

/**
 * Puts a turtle helmet on from your inventory. Swapping, swap back and rate limits are shared
 * with Auto Trousers in ArmorSwapModule.
 */
public class AutoTurtleHelmet extends ArmorSwapModule {
    public enum When { Always, InWater }

    private Setting<When> when;

    public AutoTurtleHelmet() {
        super(JJsTools.CATEGORY, "auto-turtle-helmet", "Automatically puts on a turtle helmet from your inventory.",
            EquipmentSlot.HEAD, 5, Items.TURTLE_HELMET, "turtle helmet");
    }

    @Override
    protected void addTriggerSettings(SettingGroup group) {
        when = group.add(new EnumSetting.Builder<When>()
            .name("when")
            .description("Always: keep a turtle helmet on. InWater: only while touching water (put it on before your head goes under, the effect only builds up while your head is above water).")
            .defaultValue(When.InWater)
            .build()
        );
    }

    @Override
    protected boolean wanted() {
        return switch (when.get()) {
            case Always -> true;
            case InWater -> mc.player.isTouchingWater();
        };
    }
}
