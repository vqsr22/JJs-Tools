package dev.jjstools.panel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;

/**
 * Walks you to a container with Baritone and opens it.
 *
 * Baritone is reached by name rather than compiled against, so the Control Panel works without
 * it and simply says so. It is asked to get next to the container; once you are close enough to
 * reach it the walk is stopped and the container opened, the same click you would make. If
 * Baritone gives up, or two minutes pass, the walk is dropped.
 */
public final class StashWalker {
    private static BlockPos target;
    private static long startedAt;
    private static boolean walking;

    private StashWalker() {}

    public static boolean baritoneInstalled() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static BlockPos target() {
        return walking ? target : null;
    }

    /** Sets off for a container. Returns false, with a message, if Baritone is not there to do it. */
    public static boolean go(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;

        if (!baritoneInstalled()) {
            mc.player.sendMessage(Text.literal("§7[§dControl Panel§7] §fBaritone is not installed, so the stash can only be highlighted, not walked to."), false);
            return false;
        }
        try {
            Object goal = Class.forName("baritone.api.pathing.goals.GoalGetToBlock").getConstructor(BlockPos.class).newInstance(pos);
            Object process = customGoalProcess();
            Method setGoalAndPath = Class.forName("baritone.api.process.ICustomGoalProcess")
                .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"));
            setGoalAndPath.invoke(process, goal);
        } catch (Throwable e) {
            mc.player.sendMessage(Text.literal("§7[§dControl Panel§7] §fBaritone could not be asked to walk there: " + e.getClass().getSimpleName()), false);
            return false;
        }

        target = pos.toImmutable();
        startedAt = System.currentTimeMillis();
        walking = true;
        noBreaking();
        return true;
    }

    /** Baritone's own Allow Break, as it was before the walk, to put back after. Null when not changed. */
    private static Boolean savedAllowBreak;

    /**
     * Stops Baritone breaking blocks for the walk, so it never digs through your stash to reach
     * a chest. Put back how it was once the walk ends, however it ends.
     */
    private static void noBreaking() {
        try {
            Object setting = allowBreakSetting();
            java.lang.reflect.Field value = setting.getClass().getField("value");
            if (savedAllowBreak == null) savedAllowBreak = (Boolean) value.get(setting);
            value.set(setting, false);
        } catch (Throwable ignored) {
            // Baritone without the setting: nothing to change.
        }
    }

    private static void restoreBreaking() {
        if (savedAllowBreak == null) return;
        try {
            Object setting = allowBreakSetting();
            setting.getClass().getField("value").set(setting, savedAllowBreak);
        } catch (Throwable ignored) {
            // Nothing to put back.
        }
        savedAllowBreak = null;
    }

    private static Object allowBreakSetting() throws Exception {
        Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        return settings.getClass().getField("allowBreak").get(settings);
    }

    public static void stop() {
        if (!walking) return;
        walking = false;
        cancel();
    }

    public static void tick() {
        if (!walking) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            walking = false;
            return;
        }

        Vec3d centre = Vec3d.ofCenter(target);
        if (mc.player.getEyePos().squaredDistanceTo(centre) <= 4.3 * 4.3) {
            cancel();
            walking = false;
            open(mc);
            return;
        }

        long gone = System.currentTimeMillis() - startedAt;
        if (gone > 120_000 || (gone > 3000 && !pathing())) {
            walking = false;
            cancel();
            mc.player.sendMessage(Text.literal("§7[§dControl Panel§7] §fCould not reach the container at " + target.toShortString() + "."), false);
        }
    }

    /** Clicks the container on the face nearest you. */
    private static void open(MinecraftClient mc) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d d = eye.subtract(Vec3d.ofCenter(target));
        Direction face = Direction.getFacing(d.x, d.y, d.z);
        Vec3d hit = Vec3d.ofCenter(target).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, target, false));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private static Object baritone() throws Exception {
        Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
    }

    private static Object customGoalProcess() throws Exception {
        return Class.forName("baritone.api.IBaritone").getMethod("getCustomGoalProcess").invoke(baritone());
    }

    private static boolean pathing() {
        try {
            Object behaviour = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(baritone());
            boolean path = (boolean) Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("isPathing").invoke(behaviour);
            boolean active = (boolean) Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive").invoke(customGoalProcess());
            return path || active;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void cancel() {
        restoreBreaking();
        try {
            Object behaviour = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(baritone());
            Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("cancelEverything").invoke(behaviour);
        } catch (Throwable ignored) {
            // Nothing to stop.
        }
    }
}
