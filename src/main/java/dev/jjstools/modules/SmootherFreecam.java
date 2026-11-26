package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.lang.reflect.Field;

/**
 * Meteor's Freecam with creative flight's feel: the camera speeds up while a key is held and
 * glides to a stop after, rather than jumping a set distance the moment a key is touched.
 *
 * Freecam moves the camera once a game tick and the frames in between are filled in from that,
 * so any unevenness in the ticks showed as stutter. This moves the camera itself every frame
 * instead, by the real time since the last one, and holds Freecam's own position there. It reads
 * the movement keys itself too, so forward and back can follow where you look, up or down, up to
 * a set angle from level.
 *
 * Turning Freecam on can also float the camera out to where third person would put it, which any
 * movement key takes over from at once, keeping its speed.
 *
 * Turning this on turns Meteor's Freecam on with it (and off again after, if this is what turned
 * it on). Freecam's speed setting, scroll wheel included, is still the speed.
 */
public class SmootherFreecam extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStart = settings.createGroup("Start");
    private final SettingGroup sgDrone = settings.createGroup("Drone");

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Top speed, as a multiple of Freecam's own speed. 1 is Freecam's speed; 2 is twice as fast.")
        .defaultValue(1).min(0.05).max(20).sliderRange(0.1, 5)
        .build());

    private final Setting<Double> acceleration = sgGeneral.add(new DoubleSetting.Builder()
        .name("acceleration")
        .description("How quickly the camera gets up to speed while a key is held: the share of the way to full speed it closes each tick. Lower is floatier.")
        .defaultValue(0.12).min(0.01).max(1).sliderRange(0.02, 0.5)
        .build());

    private final Setting<Double> glide = sgGeneral.add(new DoubleSetting.Builder()
        .name("glide")
        .description("How quickly it slows down once no key is held: the share of its speed lost each tick. Lower glides further. Creative flight loses about 0.09.")
        .defaultValue(0.09).min(0.01).max(1).sliderRange(0.02, 0.5)
        .build());

    private final Setting<Double> pitchLimit = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-angle")
        .description("Forward and back follow where you look, up or down, up to this many degrees from level. 0 keeps them level, like Freecam.")
        .defaultValue(45).range(0, 90).sliderRange(0, 90)
        .build());

    private final Setting<Boolean> reloadChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("reload-chunks")
        .description("Reload the world's chunks when Freecam turns on and off, as Freecam's own setting does (so blocks hidden from where you stand show up). Off is quicker.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> startThirdPerson = sgStart.add(new BoolSetting.Builder()
        .name("start-in-third-person")
        .description("When Freecam turns on, float the camera out to where third person (F5) would put it. Any movement key takes over.")
        .defaultValue(true)
        .build());

    private final Setting<Double> startTime = sgStart.add(new DoubleSetting.Builder()
        .name("start-time")
        .description("Seconds the camera takes to float out to the third-person spot.")
        .defaultValue(0.45).min(0.05).max(5).sliderRange(0.1, 2)
        .visible(startThirdPerson::get)
        .build());

    private final Setting<Double> startDistance = sgStart.add(new DoubleSetting.Builder()
        .name("start-distance")
        .description("How far behind you the third-person spot is. Third person uses 4.")
        .defaultValue(4).min(0.5).max(32).sliderRange(1, 12)
        .visible(startThirdPerson::get)
        .build());

    private final Setting<Double> startHeight = sgStart.add(new DoubleSetting.Builder()
        .name("start-height")
        .description("How far above the third-person spot the camera ends up, in blocks, so it looks down over you rather than at the back of your head.")
        .defaultValue(1).min(-4).max(16).sliderRange(0, 6)
        .visible(startThirdPerson::get)
        .build());

    // Drone

    private final Setting<Boolean> drone = sgDrone.add(new BoolSetting.Builder()
        .name("drone-mode")
        .description("The camera tilts as it moves, like a drone: nose down going forward, banking into turns and strafes. Flip and roll keys do full flips.")
        .defaultValue(false)
        .build());

    private final Setting<Double> maxTilt = sgDrone.add(new DoubleSetting.Builder()
        .name("max-tilt")
        .description("How far the camera tilts at full speed, in degrees.")
        .defaultValue(22).range(0, 60).sliderRange(0, 45)
        .visible(drone::get)
        .build());

    private final Setting<Double> tiltResponse = sgDrone.add(new DoubleSetting.Builder()
        .name("tilt-response")
        .description("How quickly the tilt follows the movement. Lower is floatier.")
        .defaultValue(0.12).range(0.01, 1).sliderRange(0.02, 0.5)
        .visible(drone::get)
        .build());

    private final Setting<meteordevelopment.meteorclient.utils.misc.Keybind> flipKey = sgDrone.add(new meteordevelopment.meteorclient.settings.KeybindSetting.Builder()
        .name("flip-key")
        .description("Does a front flip.")
        .defaultValue(meteordevelopment.meteorclient.utils.misc.Keybind.none())
        .visible(drone::get)
        .build());

    private final Setting<meteordevelopment.meteorclient.utils.misc.Keybind> backFlipKey = sgDrone.add(new meteordevelopment.meteorclient.settings.KeybindSetting.Builder()
        .name("back-flip-key")
        .description("Does a back flip.")
        .defaultValue(meteordevelopment.meteorclient.utils.misc.Keybind.none())
        .visible(drone::get)
        .build());

    private final Setting<meteordevelopment.meteorclient.utils.misc.Keybind> rollLeftKey = sgDrone.add(new meteordevelopment.meteorclient.settings.KeybindSetting.Builder()
        .name("roll-left-key")
        .description("Does a barrel roll to the left.")
        .defaultValue(meteordevelopment.meteorclient.utils.misc.Keybind.none())
        .visible(drone::get)
        .build());

    private final Setting<meteordevelopment.meteorclient.utils.misc.Keybind> rollRightKey = sgDrone.add(new meteordevelopment.meteorclient.settings.KeybindSetting.Builder()
        .name("roll-right-key")
        .description("Does a barrel roll to the right.")
        .defaultValue(meteordevelopment.meteorclient.utils.misc.Keybind.none())
        .visible(drone::get)
        .build());

    private final Setting<Double> flipTime = sgDrone.add(new DoubleSetting.Builder()
        .name("flip-time")
        .description("Seconds a flip or roll takes.")
        .defaultValue(0.7).range(0.15, 3).sliderRange(0.2, 2)
        .visible(drone::get)
        .build());

    /** The drone's tilt now, in radians: nose down (pitch) and bank (roll). */
    private double tiltPitch, tiltRoll;
    /** A flip or roll under way: which axis, its way round, and when it began (nanos), or not running. */
    private int flipAxis, flipSign;
    private long flipStart = -1;
    private Boolean savedReload;

    private static Field speedValueField;
    private static boolean speedValueLooked;

    /** Where the camera is and how fast it moves, in blocks a tick. */
    private double x, y, z, vx, vy, vz;
    private boolean tracking;
    private long lastFrame;
    private boolean turnedFreecamOn;

    /** The float out to third person: from, to, and how far along (0 to 1), or not running. */
    private Vec3d introFrom, introTo;
    private double introT = -1;

    public SmootherFreecam() {
        super(JJsTools.CATEGORY, "smoother-freecam", "Freecam that speeds up and glides to a stop like creative flight, moves every frame rather than every tick, can fly up and down where you look, can float out to the third-person spot when turned on, and has a drone mode that tilts and flips.");
    }

    private static Freecam freecam() {
        Modules modules = Modules.get();
        return modules == null ? null : modules.get(Freecam.class);
    }

    @Override
    public void onActivate() {
        tracking = false;
        tiltPitch = tiltRoll = 0;
        flipStart = -1;
        Freecam freecam = freecam();
        turnedFreecamOn = freecam != null && !freecam.isActive();
        // Freecam reads its reload-chunks setting as it turns on and off, so this one stands in
        // for it for as long as this is on.
        setFreecamReload(freecam, reloadChunks.get());
        if (turnedFreecamOn) freecam.toggle();
    }

    @SuppressWarnings("unchecked")
    private void setFreecamReload(Freecam freecam, Boolean value) {
        if (freecam == null) return;
        Setting<?> setting = freecam.settings.get("reload-chunks");
        if (!(setting != null && setting.get() instanceof Boolean current)) return;
        if (value != null) {
            if (savedReload == null) savedReload = current;
            ((Setting<Boolean>) setting).set(value);
        } else if (savedReload != null) {
            ((Setting<Boolean>) setting).set(savedReload);
            savedReload = null;
        }
    }

    @Override
    public void onDeactivate() {
        Freecam freecam = freecam();
        if (turnedFreecamOn && freecam != null && freecam.isActive()) freecam.toggle();
        setFreecamReload(freecam, null);
        turnedFreecamOn = false;
        tracking = false;
        tiltPitch = tiltRoll = 0;
        flipStart = -1;
    }

    // Drone

    @meteordevelopment.orbit.EventHandler
    private void onKey(meteordevelopment.meteorclient.events.meteor.KeyEvent event) {
        if (!drone.get() || event.action != meteordevelopment.meteorclient.utils.misc.input.KeyAction.Press || mc.currentScreen != null) return;
        if (dev.jjstools.compat.Compat.matches(flipKey.get(), event)) startFlip(0, -1);
        else if (dev.jjstools.compat.Compat.matches(backFlipKey.get(), event)) startFlip(0, 1);
        else if (dev.jjstools.compat.Compat.matches(rollLeftKey.get(), event)) startFlip(1, 1);
        else if (dev.jjstools.compat.Compat.matches(rollRightKey.get(), event)) startFlip(1, -1);
    }

    private void startFlip(int axis, int sign) {
        if (!tracking || flipStart >= 0) return;
        flipAxis = axis;
        flipSign = sign;
        flipStart = System.nanoTime();
    }

    /** Eases the tilt toward what the movement asks for. dt in ticks. */
    private void updateTilt(Freecam freecam, double dt) {
        if (!drone.get()) {
            tiltPitch = tiltRoll = 0;
            return;
        }
        double yaw = Math.toRadians(freecam.yaw);
        double fwd = vx * -Math.sin(yaw) + vz * Math.cos(yaw);
        double right = vx * -Math.cos(yaw) + vz * -Math.sin(yaw);
        double top = Math.max(1e-3, 0.5 * freecamSpeed(freecam) * speed.get());
        double max = Math.toRadians(maxTilt.get());
        double wantPitch = -MathHelper.clamp(fwd / top, -1, 1) * max;
        double wantRoll = -MathHelper.clamp(right / top, -1, 1) * max;
        double k = 1 - Math.pow(1 - tiltResponse.get(), dt);
        tiltPitch += (wantPitch - tiltPitch) * k;
        tiltRoll += (wantRoll - tiltRoll) * k;
    }

    /**
     * The drone's turn of the camera on top of where it looks, in the camera's own frame, or
     * null for none (CameraDroneMixin).
     */
    public static org.joml.Quaternionf droneRotation() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        SmootherFreecam self = modules.get(SmootherFreecam.class);
        if (self == null || !self.isActive() || !self.drone.get() || !self.tracking) return null;
        return self.rotation();
    }

    private org.joml.Quaternionf rotation() {
        double flipPitch = 0, flipRoll = 0;
        if (flipStart >= 0) {
            double t = (System.nanoTime() - flipStart) / 1e9 / flipTime.get();
            if (t >= 1) {
                flipStart = -1;
            } else {
                double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
                double angle = flipSign * Math.PI * 2 * e;
                if (flipAxis == 0) flipPitch = angle;
                else flipRoll = angle;
            }
        }
        double pitch = tiltPitch + flipPitch, roll = tiltRoll + flipRoll;
        if (Math.abs(pitch) < 1e-5 && Math.abs(roll) < 1e-5) return null;
        return new org.joml.Quaternionf().rotateX((float) pitch).rotateZ((float) roll);
    }

    /** Called at the start of every frame, before the camera is placed (GameRendererOverlayMixin). */
    public static void frame() {
        Modules modules = Modules.get();
        if (modules == null) return;
        SmootherFreecam self = modules.get(SmootherFreecam.class);
        if (self != null && self.isActive()) self.onFrame();
    }

    private void onFrame() {
        Freecam freecam = freecam();
        long now = System.nanoTime();
        if (freecam == null || !freecam.isActive() || mc.player == null || mc.world == null) {
            tracking = false;
            return;
        }
        if (dev.jjstools.util.FreecamGuard.strayed(freecam)) {
            // A new world, or moved far away (2b2t's queue to the main server): the camera was
            // left where you were before, out of reach of the blocks around you, so everything
            // looked like void. It starts again at your player.
            dev.jjstools.util.FreecamGuard.recentre(freecam);
            tracking = false;
        }
        if (!tracking) {
            // Freecam has just come on: start from where it put the camera.
            tracking = true;
            lastFrame = now;
            x = freecam.pos.x;
            y = freecam.pos.y;
            z = freecam.pos.z;
            vx = vy = vz = 0;
            introT = -1;
            if (startThirdPerson.get()) beginIntro(freecam);
        }

        // In ticks, so the settings keep meaning what they say per tick. Capped so a hitch
        // does not throw the camera across the map.
        double dt = Math.min((now - lastFrame) / 1e9, 0.1) * 20;
        lastFrame = now;
        updateTilt(freecam, dt);

        Vec3d want = wanted(freecam);
        boolean held = want.lengthSquared() > 1e-10;

        if (introT >= 0) {
            if (held) {
                // Your own movement takes over, carrying on at the speed the float had.
                double step = introStep();
                Vec3d d = introTo.subtract(introFrom);
                vx = d.x * step;
                vy = d.y * step;
                vz = d.z * step;
                introT = -1;
            } else {
                introT = Math.min(1, introT + dt / 20 / startTime.get());
                double e = easeOut(introT);
                x = MathHelper.lerp(e, introFrom.x, introTo.x);
                y = MathHelper.lerp(e, introFrom.y, introTo.y);
                z = MathHelper.lerp(e, introFrom.z, introTo.z);
                if (introT >= 1) introT = -1;
                hold(freecam);
                return;
            }
        }

        if (held) {
            double k = 1 - Math.pow(1 - acceleration.get(), dt);
            vx += (want.x - vx) * k;
            vy += (want.y - vy) * k;
            vz += (want.z - vz) * k;
        } else {
            double keep = Math.pow(1 - glide.get(), dt);
            vx *= keep;
            vy *= keep;
            vz *= keep;
            if (vx * vx + vy * vy + vz * vz < 1e-8) vx = vy = vz = 0;
        }

        x += vx * dt;
        y += vy * dt;
        z += vz * dt;
        hold(freecam);
    }

    /** Pins Freecam to this camera, so its own tick and the frames between ticks leave it be. */
    private void hold(Freecam freecam) {
        freecam.pos.set(x, y, z);
        freecam.prevPos.set(x, y, z);
    }

    /** The speed and way the keys ask for, in blocks a tick. */
    private Vec3d wanted(Freecam freecam) {
        if (mc.currentScreen != null) return Vec3d.ZERO;
        double f = 0, s = 0, u = 0;
        if (Input.isPressed(mc.options.forwardKey)) f++;
        if (Input.isPressed(mc.options.backKey)) f--;
        if (Input.isPressed(mc.options.rightKey)) s++;
        if (Input.isPressed(mc.options.leftKey)) s--;
        if (Input.isPressed(mc.options.jumpKey)) u++;
        if (Input.isPressed(mc.options.sneakKey)) u--;
        if (f == 0 && s == 0 && u == 0) return Vec3d.ZERO;

        double yaw = Math.toRadians(freecam.yaw);
        double limit = pitchLimit.get();
        double pitch = Math.toRadians(MathHelper.clamp(freecam.pitch, -limit, limit));
        // Forward along where you look, tilted up or down no further than the limit.
        Vec3d fwd = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d dir = fwd.multiply(f).add(right.multiply(s)).add(0, u, 0);
        if (dir.lengthSquared() < 1e-10) return Vec3d.ZERO;

        double top = (Input.isPressed(mc.options.sprintKey) ? 1 : 0.5) * freecamSpeed(freecam) * speed.get();
        return dir.normalize().multiply(top);
    }

    /** Freecam's current speed, scroll wheel changes included. */
    private static double freecamSpeed(Freecam freecam) {
        if (!speedValueLooked) {
            speedValueLooked = true;
            try {
                speedValueField = Freecam.class.getDeclaredField("speedValue");
                speedValueField.setAccessible(true);
            } catch (Exception ignored) {
            }
        }
        if (speedValueField != null) {
            try {
                return speedValueField.getDouble(freecam);
            } catch (Exception ignored) {
            }
        }
        Object setting = freecam.settings.get("speed");
        return setting instanceof Setting<?> st && st.get() instanceof Double d ? d : 1;
    }

    /** Sets up the float from the camera out to where third person would put it, if it is in first person. */
    private void beginIntro(Freecam freecam) {
        Vec3d from = new Vec3d(x, y, z);
        Vec3d eye = mc.player.getCameraPosVec(1f);
        // Already somewhere else (third person, or moved): nothing to float out from.
        if (from.squaredDistanceTo(eye) > 0.25) return;

        double yaw = Math.toRadians(freecam.yaw), pitch = Math.toRadians(freecam.pitch);
        Vec3d look = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3d to = eye.subtract(look.multiply(startDistance.get())).add(0, startHeight.get(), 0);
        // Stop short of walls, as third person does.
        var hit = mc.world.raycast(new RaycastContext(eye, to, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3d dir = to.subtract(eye).normalize();
            double dist = Math.max(0, hit.getPos().distanceTo(eye) - 0.2);
            to = eye.add(dir.multiply(dist));
        }
        if (to.squaredDistanceTo(from) < 0.01) return;
        introFrom = from;
        introTo = to;
        introT = 0;
    }

    private static double easeOut(double t) {
        double r = 1 - t;
        return 1 - r * r * r;
    }

    /** How far the float moves a tick at its current point, as a share of its whole length. */
    private double introStep() {
        double r = 1 - introT;
        return 3 * r * r / (startTime.get() * 20);
    }
}
