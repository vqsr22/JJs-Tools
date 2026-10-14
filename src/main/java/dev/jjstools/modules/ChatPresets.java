package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Lines of chat on a key.
 *
 * Four presets, each with its own bind and its own list of lines. A preset with more than one line
 * sends them in order with a delay between, which is what makes it useful for anything a server
 * answers one step at a time: a set of commands, a report with its details, a greeting followed by
 * a question.
 *
 * The delay is the point rather than an afterthought. Servers drop messages sent in the same tick,
 * so a chain fired all at once arrives as one line and the rest vanish.
 */
public class ChatPresets extends Module {
    private static final int PRESETS = 4;

    private final List<Preset> presets = new ArrayList<>();

    public ChatPresets() {
        super(JJsTools.CATEGORY, "chat-presets", "Say a line, or a chain of lines, on a key.");

        /*
         * Built in a loop rather than written out four times.
         *
         * Settings are normally field initialisers, but they only have to exist before the module
         * is registered, and the constructor is earlier than that. Four copies of the same three
         * settings written by hand is four places to forget to change something.
         */
        for (int i = 0; i < PRESETS; i++) {
            final int slot = i;
            SettingGroup group = settings.createGroup("Preset " + (i + 1), i == 0);

            group.add(new KeybindSetting.Builder()
                .name("bind")
                .description("Key that sends this preset.")
                .defaultValue(Keybind.none())
                .action(() -> send(slot))
                .build()
            );

            Setting<List<String>> lines = group.add(new StringListSetting.Builder()
                .name("lines")
                .description("One entry per message, sent in order. A line starting with / is sent as a command.")
                .defaultValue(List.of())
                .build()
            );

            Setting<Integer> delay = group.add(new IntSetting.Builder()
                .name("delay")
                .description("Ticks between lines. 20 is one second. Too short and the server throws the later ones away.")
                .range(0, 400)
                .sliderRange(0, 100)
                .defaultValue(20)
                .build()
            );

            presets.add(new Preset(lines, delay));
        }
    }

    @Override
    public void onDeactivate() {
        for (Preset preset : presets) preset.stop();
    }

    /** Starts a preset from its first line. Pressing the same bind again restarts it. */
    private void send(int slot) {
        if (!isActive() || mc.player == null || mc.currentScreen != null) return;
        if (slot < 0 || slot >= presets.size()) return;

        presets.get(slot).start();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        for (Preset preset : presets) preset.tick();
    }

    /** Says a line the way typing it would, so a client side command prefix still gets picked up. */
    private void say(String line) {
        if (line == null || line.isBlank() || mc.getNetworkHandler() == null) return;

        String trimmed = line.trim();
        if (trimmed.startsWith("/")) mc.getNetworkHandler().sendChatCommand(trimmed.substring(1));
        else mc.getNetworkHandler().sendChatMessage(trimmed);
    }

    private class Preset {
        private final Setting<List<String>> lines;
        private final Setting<Integer> delay;

        /** Which line goes next, or -1 when this preset is not running. */
        private int next = -1;
        private int timer = 0;

        private Preset(Setting<List<String>> lines, Setting<Integer> delay) {
            this.lines = lines;
            this.delay = delay;
        }

        private void start() {
            if (lines.get().isEmpty()) return;

            next = 0;
            timer = 0;
        }

        private void stop() {
            next = -1;
            timer = 0;
        }

        private void tick() {
            if (next < 0) return;

            if (timer > 0) {
                timer--;
                return;
            }

            List<String> all = lines.get();
            if (next >= all.size()) {
                stop();
                return;
            }

            String line = all.get(next++);
            timer = delay.get();
            say(line);
        }
    }
}
