package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Copies Meteor's friends list into other clients by typing their own friend commands for you:
 * every friend gets each add command, with {name} swapped for theirs, and friends you remove get
 * each remove command. New friends are sent as they are added while this is on.
 *
 * The commands go out the way chat does, so the other client has to be the one that picks them
 * up. A line with no command prefix in front would be a chat message to everyone, so those are
 * never sent.
 */
public class FriendSync extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> addCommands = sgGeneral.add(new StringListSetting.Builder()
        .name("add-commands")
        .description("Each other client's command to add a friend, with {name} where the name goes, like *friend add {name}. Must start with that client's prefix.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<List<String>> removeCommands = sgGeneral.add(new StringListSetting.Builder()
        .name("remove-commands")
        .description("Each other client's command to remove a friend, like *friend del {name}. Leave empty to only ever add.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between commands, so a long list goes out steadily.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    /** Friends already sent to the other clients, lowercase, so each is only sent once. */
    private final Set<String> synced = new LinkedHashSet<>();
    /** Commands waiting to go out, one every few ticks. */
    private final Deque<String> queue = new ArrayDeque<>();
    private int wait;
    private int checkIn;

    public FriendSync() {
        super(JJsTools.CATEGORY, "friend-sync", "Copies your Meteor friends into other clients by running their friend commands for you.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        checkIn = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        // Once a second: anyone new to send, anyone gone to take off.
        if (--checkIn <= 0) {
            checkIn = 20;
            compare();
        }

        if (wait > 0) {
            wait--;
            return;
        }
        String next = queue.poll();
        if (next == null) return;
        ChatUtils.sendPlayerMsg(next, false);
        wait = delay.get();
    }

    private void compare() {
        Set<String> now = new LinkedHashSet<>();
        for (Friend friend : Friends.get()) now.add(friend.getName());

        for (String name : now) {
            if (synced.add(name.toLowerCase(Locale.ROOT))) queueAll(addCommands.get(), name);
        }
        for (String gone : new ArrayList<>(synced)) {
            boolean still = now.stream().anyMatch(n -> n.equalsIgnoreCase(gone));
            if (!still) {
                synced.remove(gone);
                queueAll(removeCommands.get(), gone);
            }
        }
    }

    private void queueAll(List<String> templates, String name) {
        for (String template : templates) {
            String line = template.trim();
            if (line.isEmpty()) continue;
            // Starting with a letter or number it would be read as chat and sent to everyone.
            if (Character.isLetterOrDigit(line.charAt(0))) {
                warning("Skipped \"%s\": it needs the other client's command prefix in front.", line);
                continue;
            }
            queue.add(line.replace("{name}", name));
        }
    }

    /** How to set it up, and a button to send every friend again, for a client that lost its list. */
    @Override
    public WWidget getWidget(GuiTheme theme) {
        var box = theme.verticalList();
        for (String line : new String[] {
            "1. In add-commands, add your other client's friend command with {name} where the name goes.",
            "   For example: *friend add {name}",
            "2. Optionally add its remove command to remove-commands, like *friend del {name}.",
            "3. Turn Friend Sync on. Every Meteor friend is sent once, and new ones as you add them.",
            "Test with one friend first: a command your other client does not catch goes to public chat.",
        }) {
            box.add(theme.label(line));
        }
        WHorizontalList row = box.add(theme.horizontalList()).widget();
        WButton again = row.add(theme.button("sync all again")).widget();
        again.action = () -> {
            synced.clear();
            checkIn = 0;
        };
        return box;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        if (tag == null) return null;
        tag.putString("synced", String.join("\n", synced));
        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        synced.clear();
        String saved = tag.getString("synced", "");
        if (!saved.isEmpty()) synced.addAll(List.of(saved.split("\n")));
        return this;
    }
}
