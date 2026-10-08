package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;

/**
 * Says "Rubberband" in chat when the server sets you back (a rubberband / lagback), and
 * optionally how many ticks you were sent back and how far off that position was.
 *
 * Based on Lambda's Rubberband module (https://github.com/lambda-client/lambda, GPL-3.0):
 * the last 100 positions you sent are kept; when the server teleports you, the closest one is
 * found. Its age is the ticks, and its distance to where you were put is the deviation.
 */
public class RubberbandNotifier extends Module {
    private static final int HISTORY = 100;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColours = settings.createGroup("Colours");

    private final Setting<Boolean> showTicks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ticks")
        .description("Say how many ticks you were sent back.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showDeviation = sgGeneral.add(new BoolSetting.Builder()
        .name("show-deviation")
        .description("Say how far (in blocks) the set-back position is from the closest position you had sent.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> textColour = sgColours.add(new ColorSetting.Builder()
        .name("text-colour")
        .description("Colour of the message text.")
        .defaultValue(new SettingColor(170, 170, 170))
        .build()
    );

    private final Setting<SettingColor> ticksColour = sgColours.add(new ColorSetting.Builder()
        .name("ticks-colour")
        .description("Colour of the tick count.")
        .defaultValue(new SettingColor(255, 255, 85))
        .visible(showTicks::get)
        .build()
    );

    private final Setting<SettingColor> deviationColour = sgColours.add(new ColorSetting.Builder()
        .name("deviation-colour")
        .description("Colour of the deviation.")
        .defaultValue(new SettingColor(255, 255, 85))
        .visible(showDeviation::get)
        .build()
    );

    /** Positions you sent, newest last. */
    private final Setting<Boolean> ignoreExpected = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-expected-teleports")
        .description("Stay quiet for the teleports you asked for: joining a server, going through a portal, or an ender pearl landing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> settleTicks = sgGeneral.add(new IntSetting.Builder()
        .name("settle-ticks")
        .description("How long to stay quiet after one of those. 20 is one second.")
        .defaultValue(60).min(0).sliderRange(0, 200)
        .visible(ignoreExpected::get)
        .build()
    );

    /**
     * Ticks left in which a teleport is expected rather than a lagback.
     *
     * Joining, changing dimension and landing an ender pearl all produce a genuine position packet
     * that moves you a long way. Reporting those as rubberbands is noise, and the deviation figure
     * on a dimension change is meaningless anyway.
     */
    private int quietTicks = 0;
    private RegistryKey<World> lastDimension = null;

    private final ArrayDeque<Vec3d> sent = new ArrayDeque<>();

    public RubberbandNotifier() {
        super(JJsTools.CATEGORY, "rubberband-notifier", "Says \"Rubberband\" in chat when the server sets you back, with how many ticks and how far.");
    }

    @Override
    public void onActivate() {
        quietTicks = settleTicks.get();
        lastDimension = null;
        synchronized (sent) {
            sent.clear();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        synchronized (sent) {
            sent.clear();
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        quietTicks = settleTicks.get();
        lastDimension = null;
    }

    /**
     * Counts the quiet period down and watches for a dimension change.
     *
     * A portal arrives as an ordinary position packet with no marker on it, so the only way to
     * know is to notice the world you are in has changed.
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (quietTicks > 0) quietTicks--;
        if (mc.world == null) return;

        RegistryKey<World> now = mc.world.getRegistryKey();
        if (lastDimension != null && now != lastDimension) quietTicks = settleTicks.get();
        lastDimension = now;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        // A pearl you just threw is about to move you, so the quiet period starts on the throw
        // rather than on the landing, which is the part that looks like a lagback.
        if (ignoreExpected.get() && mc.player != null
            && event.packet instanceof PlayerInteractItemC2SPacket
            && (mc.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || mc.player.getOffHandStack().isOf(Items.ENDER_PEARL))) {
            quietTicks = Math.max(quietTicks, settleTicks.get());
        }

        if (!(event.packet instanceof PlayerMoveC2SPacket move) || !move.changesPosition()) return;
        Vec3d pos = new Vec3d(move.getX(0), move.getY(0), move.getZ(0));
        synchronized (sent) {
            sent.addLast(pos);
            while (sent.size() > HISTORY) sent.pollFirst();
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket packet)) return;
        if (mc.player == null) return;
        if (ignoreExpected.get() && quietTicks > 0) return;

        /*
         * Everything must be measured HERE, before the teleport is applied.
         *
         * This used to defer the whole calculation with mc.execute. By the time that ran, vanilla
         * had already moved the player and the client had already sent its confirmation position,
         * which landed in the history exactly equal to the set-back point. So the closest match was
         * always that confirmation: one entry old, zero blocks away. That is why it always said one
         * tick with no deviation.
         */
        Vec3d current = mc.player.getEntityPos();

        Vec3d target;
        try {
            target = EntityPosition.apply(EntityPosition.fromEntity(mc.player), packet.change(), packet.relatives()).position();
        } catch (Throwable t) {
            target = packet.change().position();
        }

        // A teleport that does not actually move you is not a rubberband.
        if (current.squaredDistanceTo(target) < 1.0E-6) return;

        int ticks = -1;
        double best = Double.MAX_VALUE;
        synchronized (sent) {
            int age = 0;
            Iterator<Vec3d> it = sent.descendingIterator();
            while (it.hasNext()) {
                age++;
                double d = it.next().squaredDistanceTo(target);
                if (d < best) {
                    best = d;
                    ticks = age;
                }
            }
        }

        /*
         * Deviation is how far the server moved you, not how close the set-back point was to some
         * position in the history. The old reading was always near zero because the server almost
         * always sets you back onto a position you had already sent, so the closest match matched
         * perfectly by definition.
         */
        double deviation = current.distanceTo(target);
        double matchError = best == Double.MAX_VALUE ? 0 : Math.sqrt(best);

        report(ticks, deviation, matchError);
    }

    private void report(int ticks, double deviation, double matchError) {

        MutableText msg = coloured("Rubberband", textColour.get());
        if (ticks > 0 && (showTicks.get() || showDeviation.get())) {
            msg.append(coloured(", set back", textColour.get()));
            if (showTicks.get()) {
                msg.append(coloured(" ", textColour.get()))
                    .append(coloured(String.valueOf(ticks), ticksColour.get()))
                    .append(coloured(ticks == 1 ? " tick" : " ticks", textColour.get()));
            }
            if (showDeviation.get()) {
                msg.append(coloured(" (deviation ", textColour.get()))
                    .append(coloured(String.format(Locale.ROOT, "%.3f", deviation), deviationColour.get()))
                    .append(coloured(" blocks)", textColour.get()));
            }
        }

        // Chat has to happen on the main thread; the numbers are already worked out.
        MutableText finished = msg;
        mc.execute(() -> info(finished));
    }

    private static MutableText coloured(String text, SettingColor colour) {
        int rgb = (colour.r << 16) | (colour.g << 8) | colour.b;
        return Text.literal(text).styled(style -> style.withColor(TextColor.fromRgb(rgb)));
    }
}
