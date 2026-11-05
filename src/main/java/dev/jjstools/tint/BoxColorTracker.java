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
     * Works out the colour, in order of how much each source can be trusted.
     *
     * The title is almost useless here and used to be checked first, which is why every box came
     * out undyed. A shulker's container is named container.shulkerBox whatever colour it is, so
     * the GUI says "Shulker Box" for all sixteen; only the item carries the colour in its name.
     * A plain title therefore looked like a definite answer of "undyed" and stopped the block from
     * ever being looked at.
     *
     * So: the block you clicked, which is both authoritative and the thing you actually opened. A
     * colour named in the title, which only happens on a renamed box but is worth having when
     * there is no block to read. Then the crosshair, last because it is the one that can be
     * pointing at a different box than the one that opened.
     */
    public static Detected detect(String title) {
        Detected fromClick = fromClick();
        if (fromClick.found()) return fromClick;

        Detected fromTitle = fromTitle(title);
        if (fromTitle.found() && fromTitle.color() != null) return fromTitle;

        Detected fromCrosshair = fromCrosshair();
        if (fromCrosshair.found()) return fromCrosshair;

        // Nothing left to read. A vanilla title at least confirms it is a shulker box, so undyed
        // is the best remaining guess rather than no tint at all.
        return fromTitle;
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

    /** The block you right clicked, if it was recent enough to be the one that opened. */
    private static Detected fromClick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || lastClicked == null) return Detected.UNKNOWN;
        if (System.currentTimeMillis() - lastClickTime >= CLICK_VALID_MS) return Detected.UNKNOWN;

        Detected d = checkPos(client, lastClicked);

        // Used up either way. A click that turned out not to be a shulker should not be sitting
        // there waiting to answer for the next box you open.
        lastClicked = null;
        return d;
    }

    /** Whatever the crosshair is on right now, which need not be the box that opened. */
    private static Detected fromCrosshair() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return Detected.UNKNOWN;

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
