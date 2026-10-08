package dev.jjstools.tint;

import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.DyeColor;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public final class BoxColorTracker {
    /** How long a right-click stays valid while waiting for the server to open the GUI. */
    private static final long CLICK_VALID_MS = 10_000;

    private static BlockPos lastClicked;
    private static long lastClickTime;

    private BoxColorTracker() {}

    public static void onBlockUsed(BlockPos pos) {
        lastClicked = pos.toImmutable();
        lastClickTime = System.currentTimeMillis();
    }

    /** found = false means we couldn't tell which box was opened. color == null means undyed. */
    public record Detected(boolean found, @Nullable DyeColor color) {
        static final Detected UNKNOWN = new Detected(false, null);
    }

    /**
     * Works out the colour, preferring the most trustworthy source available.
     *
     * The title is checked before the crosshair. A box with its vanilla name says its colour
     * outright, and that is right even when you opened it from a hopper minecart, through a
     * preview, or a long way from the block. Falling straight to the crosshair is what tinted
     * boxes with whichever shulker you happened to be looking at.
     */
    public static Detected detect(String title) {
        Detected fromTitle = fromTitle(title);
        if (fromTitle.found()) return fromTitle;

        return detect();
    }

    private static Detected fromTitle(String title) {
        if (title == null || title.isBlank()) return Detected.UNKNOWN;

        String lower = title.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("shulker box")) return Detected.UNKNOWN;

        DyeColor best = null;
        int bestLength = -1;

        for (DyeColor dye : DyeColor.values()) {
            String name = Colors.prettyName(dye).toLowerCase(java.util.Locale.ROOT);

            // Longest match wins, so "Light Blue" is not read as "Blue".
            if (lower.contains(name) && name.length() > bestLength) {
                best = dye;
                bestLength = name.length();
            }
        }

        // A plain "Shulker Box" is the undyed one, which is a real answer, not a failure.
        if (best == null) return new Detected(true, null);
        return new Detected(true, best);
    }

    public static Detected detect() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return Detected.UNKNOWN;

        if (lastClicked != null && System.currentTimeMillis() - lastClickTime < CLICK_VALID_MS) {
            Detected d = checkPos(client, lastClicked);
            lastClicked = null;
            if (d.found()) return d;
        }

        // Fallback: whatever the crosshair is on right now
        if (client.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            return checkPos(client, bhr.getBlockPos());
        }
        return Detected.UNKNOWN;
    }

    private static Detected checkPos(MinecraftClient client, BlockPos pos) {
        Block block = client.world.getBlockState(pos).getBlock();
        if (block instanceof ShulkerBoxBlock shulker) {
            return new Detected(true, shulker.getColor());
        }
        return Detected.UNKNOWN;
    }
}
