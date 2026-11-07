package dev.jjstools.panel;

import dev.jjstools.modules.ControlPanel;
import dev.jjstools.util.EncounterStore;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps a record of every player you come across: when you first and last saw them, how many
 * times, and where. Always on, as part of the Control Panel, whose Encounters panel shows the
 * list and whose module holds its settings (the Encounters group).
 *
 * The list lives in .minecraft/jjs-tools/encounters.json, outside Meteor's config, so updates
 * and profile wipes leave it alone. A player only counts again once they have left render
 * distance and come back, so standing next to someone does not run the counter up.
 */
public final class EncounterTracker {
    private static final EncounterTracker INSTANCE = new EncounterTracker();

    /** Players currently in render distance, so re-entering counts as a new encounter. */
    private final Set<String> present = new HashSet<>();
    private long lastSave;

    private EncounterTracker() {}

    public static void init() {
        EncounterStore.load();
        INSTANCE.lastSave = System.currentTimeMillis();
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
        // Written when the game closes too, so nothing since the last save is lost.
        Runtime.getRuntime().addShutdownHook(new Thread(EncounterStore::save, "JJ's Tools encounters"));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        EncounterStore.save();
        present.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;

        Set<String> nowPresent = new HashSet<>();
        String me = mc.player.getGameProfile().name();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            // Identity alone is not enough: some mods spawn a copy of you, so check the profile too.
            if (player.getUuid().equals(mc.player.getUuid())) continue;

            String name = player.getGameProfile().name();
            if (name == null || name.isBlank()) continue;
            if (name.equalsIgnoreCase(me)) continue;
            if (ControlPanel.encountersSkipFriends() && Friends.get().isFriend(player)) continue;

            nowPresent.add(name);
            if (present.contains(name)) continue;

            boolean firstEver = EncounterStore.all().stream()
                .noneMatch(e -> e.name != null && e.name.equalsIgnoreCase(name));

            EncounterStore.record(name, player.getUuid(),
                player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                mc.world.getRegistryKey().getValue().getPath());

            if (firstEver && ControlPanel.encountersChat()) {
                boolean hide = ControlPanel.encountersHideCoords() || Prefs.get().hideCoords;
                ChatUtils.info("%s", hide
                    ? "First encounter with " + name
                    : "First encounter with " + name + " at " + player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ());
            }
        }

        present.clear();
        present.addAll(nowPresent);

        if (System.currentTimeMillis() - lastSave > ControlPanel.encountersSaveInterval() * 1000L) {
            EncounterStore.save();
            lastSave = System.currentTimeMillis();
        }
    }
}
