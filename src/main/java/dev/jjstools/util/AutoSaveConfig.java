package dev.jjstools.util;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.orbit.EventHandler;

/**
 * Writes Meteor's config to disk on a timer.
 *
 * Meteor only saves when you leave a world or shut the game down cleanly, so a crash, a kill, or
 * closing the launcher the quick way loses everything changed since you joined. That is the whole
 * reason settings keep coming back wrong: they were never written, not written and lost.
 *
 * The settings live in Meteor's own Config tab, under a JJ's Tools group, because that is where
 * someone looking for "why did my config not save" would look. They are added during add-on
 * initialisation, which runs after the systems are created and before they are loaded, so the
 * values come back off disk like any other Meteor setting.
 */
public class AutoSaveConfig {
    private static Setting<Boolean> enabled;
    private static Setting<Integer> minutes;

    private static int ticks = 0;

    private AutoSaveConfig() {}

    public static void init() {
        Config config = Config.get();
        if (config == null) return;

        SettingGroup sg = config.settings.createGroup("JJ's Tools");

        enabled = sg.add(new BoolSetting.Builder()
            .name("auto-save-config")
            .description("Write the config to disk on a timer, instead of only when you leave a world or close the game cleanly.")
            .defaultValue(true)
            .build()
        );

        minutes = sg.add(new IntSetting.Builder()
            .name("auto-save-minutes")
            .description("How long between saves.")
            .visible(() -> enabled.get())
            .defaultValue(5)
            .min(1)
            .max(60)
            .sliderRange(1, 30)
            .build()
        );

        MeteorClient.EVENT_BUS.subscribe(new AutoSaveConfig.Ticker());
    }

    private static class Ticker {
        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (enabled == null || !enabled.get()) {
                ticks = 0;
                return;
            }

            // Counted in ticks rather than against the clock so a paused or hitching game does
            // not fire a burst of saves when it catches up.
            if (++ticks < minutes.get() * 60 * 20) return;

            ticks = 0;
            Systems.save();
        }
    }
}
