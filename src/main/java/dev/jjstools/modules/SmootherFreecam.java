package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

/**
 * Meteor's Freecam with creative flight's feel: the camera speeds up while a key is held and
 * glides to a stop after, rather than jumping a set distance the moment a key is touched.
 *
 * Turning this on turns Meteor's Freecam on with it (and off again after, if this is what turned
 * it on). Freecam still reads the keys and its own speed setting, scroll wheel included, every
 * tick; this then takes the step it worked out as where you are heading, and moves the camera
 * only part of the way from its current speed toward that, so it builds up and eases off.
 */
public class SmootherFreecam extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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

    private double vx, vy, vz;
    private boolean turnedFreecamOn;

    public SmootherFreecam() {
        super(JJsTools.CATEGORY, "smoother-freecam", "Freecam that speeds up and glides to a stop like creative flight, instead of jumping the moment a key is touched. Uses Meteor's Freecam and its speed setting.");
    }

    private Freecam freecam() {
        return Modules.get().get(Freecam.class);
    }

    @Override
    public void onActivate() {
        vx = vy = vz = 0;
        Freecam freecam = freecam();
        turnedFreecamOn = freecam != null && !freecam.isActive();
        if (turnedFreecamOn) freecam.toggle();
    }

    @Override
    public void onDeactivate() {
        Freecam freecam = freecam();
        if (turnedFreecamOn && freecam != null && freecam.isActive()) freecam.toggle();
        turnedFreecamOn = false;
    }

    /** After Freecam's own tick: its step becomes the speed aimed for, not the move made. */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onTick(TickEvent.Post event) {
        Freecam freecam = freecam();
        if (freecam == null || !freecam.isActive()) {
            // Freecam was turned off by itself: nothing to smooth, and nothing left to start over.
            vx = vy = vz = 0;
            return;
        }
        double mul = speed.get();
        double wantX = (freecam.pos.x - freecam.prevPos.x) * mul;
        double wantY = (freecam.pos.y - freecam.prevPos.y) * mul;
        double wantZ = (freecam.pos.z - freecam.prevPos.z) * mul;
        boolean held = wantX * wantX + wantY * wantY + wantZ * wantZ > 1e-10;

        if (held) {
            double a = acceleration.get();
            vx += (wantX - vx) * a;
            vy += (wantY - vy) * a;
            vz += (wantZ - vz) * a;
        } else {
            double keep = 1 - glide.get();
            vx *= keep;
            vy *= keep;
            vz *= keep;
            if (vx * vx + vy * vy + vz * vz < 1e-8) vx = vy = vz = 0;
        }

        freecam.pos.set(freecam.prevPos.x + vx, freecam.prevPos.y + vy, freecam.prevPos.z + vz);
    }
}
