package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;

/**
 * Stops you interacting with item frames (and glow item frames), so right-clicking a frame
 * never rotates the item in it or puts your held item into it. Optionally also stops left-clicks,
 * so you cannot knock items out or break frames by accident.
 */
public class ItemFrameBlock extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> blockRotation = sgGeneral.add(new BoolSetting.Builder()
        .name("block-right-click")
        .description("Stop right-clicks on item frames: no rotating the item and no putting items in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blockLeftClick = sgGeneral.add(new BoolSetting.Builder()
        .name("block-left-click")
        .description("Also stop left-clicks on item frames, so items are not knocked out and frames are not broken.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyFilled = sgGeneral.add(new BoolSetting.Builder()
        .name("only-filled-frames")
        .description("Only block frames that already hold an item. Empty frames work normally, so you can still fill them.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sneakBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("sneak-to-allow")
        .description("Hold sneak to interact with item frames normally.")
        .defaultValue(false)
        .build()
    );

    public ItemFrameBlock() {
        super(JJsTools.CATEGORY, "anti-item-frame", "Stops you rotating or interacting with item frames.");
    }

    @EventHandler
    private void onInteract(InteractEntityEvent event) {
        if (blockRotation.get() && shouldBlock(event.entity)) event.cancel();
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (blockLeftClick.get() && shouldBlock(event.entity)) event.cancel();
    }

    private boolean shouldBlock(Entity entity) {
        if (!(entity instanceof ItemFrameEntity frame)) return false; // glow item frames extend this
        if (sneakBypass.get() && mc.player != null && mc.player.isSneaking()) return false;
        return !onlyFilled.get() || !frame.getHeldItemStack().isEmpty();
    }
}
