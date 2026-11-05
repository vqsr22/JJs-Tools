package dev.jjstools.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

/**
 * A facing sent to the server for one tick, with the player's movement worked out to match.
 *
 * The server checks your movement against the facing you send with it: walking forward means
 * walking the way you face. Send one facing while your client moved by another and the two
 * disagree, and the server pulls you back, which is rubber banding. So for the tick a facing is
 * held, the movement itself is worked out facing that way too, and the keys you are pressing
 * are swapped for the ones that keep you going the way you meant to go. You still walk where you
 * were walking; the server sees a player who is facing one way and pressing the keys that walk
 * the other.
 *
 * Set at the start of a tick, before the player moves, and cleared at the end, so it only ever
 * covers the tick it was set for. Mixins on the movement packet, on travel and jumping, and on
 * the keyboard input read it.
 */
public final class HeldRotation {
    private static boolean active;
    private static float yaw;
    private static float pitch;

    /** The keys for each eighth of a turn to the right of facing: x is left, y is forward. */
    private static final Vec2f[] STEPS = {
        new Vec2f(0, 1), new Vec2f(-1, 1), new Vec2f(-1, 0), new Vec2f(-1, -1),
        new Vec2f(0, -1), new Vec2f(1, -1), new Vec2f(1, 0), new Vec2f(1, 1)
    };

    private HeldRotation() {}

    public static void set(float yaw, float pitch) {
        HeldRotation.yaw = yaw;
        HeldRotation.pitch = pitch;
        active = true;
    }

    public static void clear() {
        active = false;
    }

    public static boolean active() {
        return active && MinecraftClient.getInstance().player != null;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    /**
     * The keys to press, facing the held way, to keep going where the pressed keys go facing
     * realYaw: the nearest of the eight directions keys can give. Null when nothing is pressed.
     */
    public static Vec2f rewrite(Vec2f input, float realYaw) {
        if (Math.abs(input.x) < 1.0E-4F && Math.abs(input.y) < 1.0E-4F) return null;

        double meant = realYaw + Math.toDegrees(Math.atan2(-input.x, input.y));
        int step = Math.floorMod(Math.round(MathHelper.wrapDegrees((float) (meant - yaw)) / 45.0F), 8);
        return STEPS[step];
    }
}
