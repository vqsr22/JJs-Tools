package dev.jjstools.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The timing and easing every animation in JJ's Tools shares, so they all move alike.
 *
 * - Things coming in ease out (fast, then settling); things leaving ease in; things moving
 *   from one place to another ease in and out. None of them bounce.
 * - Coming in takes {@link #ENTER_MS}; leaving is a fifth shorter ({@link #EXIT_MS}).
 * - Rows in a list come in one after another, {@link #STAGGER_MS} apart.
 * - Held down, a button sits at {@link #PRESS_SCALE} straight away, with no tween while held,
 *   and eases back when let go.
 * - Something turned on gives one short pulse to show it took.
 *
 * Hovering still swaps colours instantly, as monkeytype's do; none of this touches that.
 */
public final class Motion {
    private Motion() {}

    public static final double ENTER_MS = 200;
    public static final double EXIT_MS = ENTER_MS * 0.8;
    public static final double STAGGER_MS = 30;
    public static final double MOVE_MS = 220;
    public static final double PRESS_SCALE = 0.97;
    public static final double POPOVER_START = 0.95;
    private static final double RELEASE_MS = 120;
    private static final double PULSE_MS = 320;
    private static final double PULSE_SIZE = 0.05;

    private static double speed = 1;
    private static double speedCheckedAt = -1e9;

    /** The animation speed from the Control Panel's settings: 1 normal, 0 none. Looked up twice a second. */
    public static double speed() {
        double t = now();
        if (t - speedCheckedAt > 500) {
            speedCheckedAt = t;
            try {
                speed = dev.jjstools.modules.ControlPanel.animationSpeed();
            } catch (Throwable ignored) {
                speed = 1;
            }
        }
        return speed;
    }

    /** Whether animations are off. */
    public static boolean off() {
        return speed() <= 0;
    }

    /** A duration at the chosen speed; zero with animations off. */
    public static double ms(double base) {
        double s = speed();
        return s <= 0 ? 0 : base / s;
    }

    public static double now() {
        return System.nanoTime() / 1_000_000.0;
    }

    public static double clamp01(double t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    public static double easeOut(double t) {
        t = clamp01(t);
        double u = 1 - t;
        return 1 - u * u * u;
    }

    public static double easeIn(double t) {
        t = clamp01(t);
        return t * t * t;
    }

    public static double easeInOut(double t) {
        t = clamp01(t);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    /** How far in something is, 0 to 1, that began coming in at startMs after waiting delayMs. */
    public static double enter(double startMs, double delayMs) {
        if (off()) return 1;
        return easeOut((now() - startMs - ms(delayMs)) / ms(ENTER_MS));
    }

    /** How far in something still is, 1 to 0, that began leaving at startMs. */
    public static double exit(double startMs) {
        if (off()) return 0;
        return 1 - easeIn((now() - startMs) / ms(EXIT_MS));
    }

    /** Whether something that began leaving at startMs is still on its way out. */
    public static boolean leaving(double startMs) {
        return !off() && now() - startMs < ms(EXIT_MS);
    }

    /** How far along a move is, 0 to 1, eased in and out, that began at startMs after waiting delayMs. */
    public static double move(double startMs, double delayMs) {
        if (off()) return 1;
        return easeInOut((now() - startMs - ms(delayMs)) / ms(MOVE_MS));
    }

    /** As enter, for slots coming into place with their own start and delay. */
    public static double arrive(double startMs, double delayMs) {
        return enter(startMs, delayMs);
    }

    // Press and pulse, kept per thing (a widget, or a rectangle's place for the Control Panel's
    // buttons, which are drawn afresh each frame), with a limit so moved windows do not pile up.

    private static final Map<Object, Double> RELEASED = bounded();
    private static final Map<Object, Boolean> HELD = bounded();
    private static final Map<Object, Double> PULSED = bounded();
    private static final Map<Object, Boolean> WAS_ON = bounded();

    private static <K, V> Map<K, V> bounded() {
        return new LinkedHashMap<>(64, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > 512;
            }
        };
    }

    /** The size to draw something at that is pressable: smaller at once while held, easing back after. */
    public static double press(Object key, boolean held) {
        if (off()) return 1;
        if (held) {
            HELD.put(key, true);
            RELEASED.remove(key);
            return PRESS_SCALE;
        }
        if (HELD.remove(key) != null) RELEASED.put(key, now());
        Double at = RELEASED.get(key);
        if (at == null) return 1;
        double t = (now() - at) / ms(RELEASE_MS);
        if (t >= 1) {
            RELEASED.remove(key);
            return 1;
        }
        return PRESS_SCALE + (1 - PRESS_SCALE) * easeOut(t);
    }

    /** Starts a pulse, for something that just worked. */
    public static void pulse(Object key) {
        PULSED.put(key, now());
    }

    /** Pulses when on goes from off to on; the first look at something only notes how it is. */
    public static double pulseOnTurn(Object key, boolean on) {
        Boolean was = WAS_ON.put(key, on);
        if (was != null && !was && on) pulse(key);
        return pulseScale(key);
    }

    /** The size a pulse gives right now: up a touch quickly, then easing back, with no bounce. */
    public static double pulseScale(Object key) {
        Double at = PULSED.get(key);
        if (at == null) return 1;
        if (off()) {
            PULSED.remove(key);
            return 1;
        }
        double t = (now() - at) / ms(PULSE_MS);
        if (t >= 1) {
            PULSED.remove(key);
            return 1;
        }
        double up = 0.3;
        double k = t < up ? easeOut(t / up) : 1 - easeInOut((t - up) / (1 - up));
        return 1 + PULSE_SIZE * k;
    }

    private static final Map<Object, Double> OPENED = new java.util.WeakHashMap<>();

    /** When a screen was first drawn, for things on it coming in one after another. */
    public static double openedAt(Object screen) {
        if (screen == null) return 0;
        return OPENED.computeIfAbsent(screen, s -> now());
    }

    /** Whether anything is still moving, for code that only redraws when it must. */
    public static boolean busy() {
        return !RELEASED.isEmpty() || !PULSED.isEmpty();
    }
}
