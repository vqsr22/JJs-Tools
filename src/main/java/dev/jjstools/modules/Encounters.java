package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.EncounterStore;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps a record of every player you come across: when you first and last saw them, how many
 * times, and where.
 *
 * The list lives in .minecraft/jjs-tools/encounters.json and is viewed from the Encounters tab in
 * the Meteor GUI. Because it is outside Meteor's config, updates and profile wipes leave it alone.
 *
 * A player only counts again once they have left render distance and come back, so standing next
 * to someone does not run the counter up.
 */
public class Encounters extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-message")
        .description("Say something in chat the first time you ever see a player.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-coords")
        .description("Leave coordinates out of the chat message, for streaming or screenshots.")
        .defaultValue(false)
        .visible(notify::get)
        .build()
    );

    private final Setting<Boolean> skipFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("skip-friends")
        .description("Don't record players on your Meteor friends list.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> saveInterval = sgGeneral.add(new IntSetting.Builder()
        .name("save-interval")
        .description("Seconds between writes to disk. The list is also written when you leave a world.")
        .defaultValue(30).min(5).sliderRange(5, 300)
        .build()
    );

    /** Players currently in render distance, so re-entering counts as a new encounter. */
    private final Set<String> present = new HashSet<>();
    private long lastSave;

    public Encounters() {
        super(JJsTools.CATEGORY, "encounters", "Records every player you meet. View the list in the Encounters tab.");
    }

    @Override
    public void onActivate() {
        EncounterStore.load();
        present.clear();
        lastSave = System.currentTimeMillis();
    }

    @Override
    public void onDeactivate() {
        EncounterStore.save();
        present.clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        EncounterStore.save();
        present.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        Set<String> nowPresent = new HashSet<>();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            // Identity alone is not enough: some mods spawn a copy of you, so check the profile too.
            if (player.getUuid().equals(mc.player.getUuid())) continue;

            String name = player.getGameProfile().name();
            if (name == null || name.isBlank()) continue;
            if (name.equalsIgnoreCase(mc.player.getGameProfile().name())) continue;
            if (skipFriends.get() && Friends.get().isFriend(player)) continue;

            nowPresent.add(name);
            if (present.contains(name)) continue;

            boolean firstEver = EncounterStore.all().stream()
                .noneMatch(e -> e.name != null && e.name.equalsIgnoreCase(name));

            EncounterStore.record(name, player.getUuid(),
                player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                mc.world.getRegistryKey().getValue().getPath());

            if (firstEver && notify.get()) {
                info(Text.literal(hideCoords.get()
                    ? "First encounter with %s".formatted(name)
                    : "First encounter with %s at %d, %d, %d".formatted(
                        name, player.getBlockX(), player.getBlockY(), player.getBlockZ())));
            }
        }

        present.clear();
        present.addAll(nowPresent);

        if (System.currentTimeMillis() - lastSave > saveInterval.get() * 1000L) {
            EncounterStore.save();
            lastSave = System.currentTimeMillis();
        }
    }

    @Override
    public String getInfoString() {
        return String.valueOf(EncounterStore.size());
    }
}
