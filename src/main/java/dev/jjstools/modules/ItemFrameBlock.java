package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Stops you interacting with item frames (and glow item frames), so right-clicking a frame
 * never rotates the item in it or puts your held item into it. Optionally also stops left-clicks,
 * so you cannot knock items out or break frames by accident.
 *
 * It can also hand the click on to whatever the frame is covering, which is what you want when a
 * frame is being used as a label on a chest.
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

    private final Setting<Boolean> clickThrough = sgGeneral.add(new BoolSetting.Builder()
        .name("click-through")
        .description("Open what the frame is covering instead. Only blocks that hold something, so a frame on a plain wall still does nothing.")
        .visible(blockRotation::get)
        .defaultValue(true)
        .build()
    );

    /** Vanilla's own reach for blocks. */
    private static final double REACH = 4.5;

    /** Ticks before another pass-through click, matching vanilla's own gap between uses. */
    private static final int COOLDOWN = 4;

    private int cooldown = 0;

    public ItemFrameBlock() {
        super(JJsTools.CATEGORY, "anti-item-frame", "Stops you rotating or interacting with item frames.");
    }

    @Override
    public void onActivate() {
        cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (cooldown > 0) cooldown--;
    }

    @EventHandler
    private void onInteract(InteractEntityEvent event) {
        if (!blockRotation.get() || !shouldBlock(event.entity)) return;

        event.cancel();
        if (clickThrough.get()) reachBehind();
    }

    /**
     * Opens whatever the frame was sitting on.
     *
     * The click has to be made again rather than let through, because the game picks one target
     * and stops: once the ray has found the frame, nothing behind it is ever considered, so
     * cancelling the frame interaction leaves the chest unopened rather than opened. Firing the
     * ray again with entities left out finds the block the frame is hung on.
     *
     * Only blocks that hold something are opened. Anything else and a block in your hand would be
     * placed against the wall, which is a worse accident than the rotation this module exists to
     * stop.
     */
    private void reachBehind() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null || cooldown > 0) return;

        Vec3d from = mc.player.getEyePos();
        Vec3d to = from.add(mc.player.getRotationVec(1.0f).multiply(REACH));

        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            from, to, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
        if (mc.world.getBlockEntity(hit.getBlockPos()) == null) return;

        cooldown = COOLDOWN;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
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
