package dev.jjstools.gui;

import dev.jjstools.util.EncounterStore;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPlus;
import meteordevelopment.meteorclient.systems.friends.Friend;
import dev.jjstools.modules.Encounters;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.gui.screen.Screen;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The Encounters list, as a tab in the Meteor GUI.
 *
 * Everything is drawn with the theme's own widgets, so it picks up whatever Meteor theme and
 * colours are in use rather than hardcoding any of its own.
 *
 * One row per player, columns for head, name, friend, counts, dates and coordinates. The
 * date, count and coordinate columns are toggles because all of them at once is a very wide table.
 */
public class EncountersTab extends Tab {
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yy");

    private static boolean hideFriends = false;
    private static boolean showDates = true;
    private static boolean showCount = true;
    private static boolean showCoords = true;
    private static boolean hideCoordinates = false;

    public EncountersTab() {
        super("Encounters");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new EncountersScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof EncountersScreen;
    }

    private static class EncountersScreen extends WindowTabScreen {
        public EncountersScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            add(theme.label("Requires the encounters module to be on to record anything.")).expandX();
            add(theme.label("From JJ's Tools").color(theme.textSecondaryColor())).expandX();

            // The tab only shows what the module has recorded, so say so rather than letting an
            // empty list look broken.
            if (!Modules.get().isActive(Encounters.class)) {
                add(theme.label("The encounters module is off, so nothing new is being recorded."))
                    .padBottom(6).expandX();
            }

            WTable options = add(theme.table()).expandX().widget();

            options.add(theme.label("Hide friends")).padRight(4);
            WCheckbox hide = options.add(theme.checkbox(hideFriends)).padRight(14).widget();
            hide.action = () -> { hideFriends = hide.checked; reload(); };

            options.add(theme.label("Dates")).padRight(4);
            WCheckbox dates = options.add(theme.checkbox(showDates)).padRight(14).widget();
            dates.action = () -> { showDates = dates.checked; reload(); };

            options.add(theme.label("Count")).padRight(4);
            WCheckbox count = options.add(theme.checkbox(showCount)).padRight(14).widget();
            count.action = () -> { showCount = count.checked; reload(); };

            options.add(theme.label("Coords")).padRight(4);
            WCheckbox coords = options.add(theme.checkbox(showCoords)).padRight(14).widget();
            coords.action = () -> { showCoords = coords.checked; reload(); };

            options.add(theme.label("Hide coords")).padRight(4);
            WCheckbox hideCoords = options.add(theme.checkbox(hideCoordinates)).widget();
            hideCoords.action = () -> { hideCoordinates = hideCoords.checked; reload(); };

            options.row();

            add(theme.horizontalSeparator()).padVertical(6).expandX();

            WTable table = add(theme.table()).expandX().widget();
            fill(table);
        }

        @Override
        public void reload() {
            clear();
            initWidgets();
        }

        private void fill(WTable table) {
            table.clear();

            List<EncounterStore.Encounter> all = EncounterStore.all();
            if (all.isEmpty()) {
                table.add(theme.label("Nobody yet. Turn on the encounters module."));
                return;
            }

            header(table);

            for (EncounterStore.Encounter e : all) {
                Friend friend = Friends.get().get(e.name);
                if (hideFriends && friend != null) continue;

                // Meteor's head textures live on Friend, so a temporary one is used purely to
                // fetch and draw the skin for players who are not on the friends list.
                Friend head = friend != null ? friend : new Friend(e.name, e.id());
                if (head.headTextureNeedsUpdate()) MeteorExecutor.execute(head::updateInfo);

                table.add(theme.texture(24, 24, head.getHead().needsRotate() ? 90 : 0, head.getHead()))
                    .padRight(6).padVertical(3);
                table.add(theme.label(e.name)).padRight(14).centerY();

                // Friend
                if (friend == null) {
                    WPlus addFriend = table.add(theme.plus()).widget();
                    addFriend.action = () -> {
                        Friend f = new Friend(e.name, e.id());
                        if (Friends.get().add(f)) {
                            MeteorExecutor.execute(f::updateInfo);
                            reload();
                        }
                    };
                }
                else {
                    WMinus removeFriend = table.add(theme.minus()).widget();
                    removeFriend.action = () -> { Friends.get().remove(friend); reload(); };
                }

                if (showCount) table.add(theme.label(String.valueOf(e.count))).padHorizontal(10).centerY();
                if (showDates) {
                    table.add(theme.label(DATE.format(new Date(e.firstSeen)))).padRight(10).centerY();
                    table.add(theme.label(DATE.format(new Date(e.lastSeen)))).padRight(14).centerY();
                }
                if (showCoords) {
                    String coords = hideCoordinates
                        ? "hidden"
                        : "%d, %d, %d".formatted(e.x, e.y, e.z);
                    table.add(theme.label(coords)).padRight(10).centerY();
                    table.add(theme.label(e.dimension == null ? "?" : e.dimension)).padRight(14).centerY();
                }

                WButton forget = table.add(theme.button("Forget")).expandCellX().right().centerY().widget();
                forget.action = () -> { EncounterStore.remove(e); reload(); };

                table.row();
            }
        }

        private void header(WTable table) {
            table.add(theme.label("")).padRight(6);
            table.add(theme.label("Player", true)).padRight(14);
            table.add(theme.label("Friend", true)).padRight(14);
            if (showCount) table.add(theme.label("Seen", true)).padHorizontal(10);
            if (showDates) {
                table.add(theme.label("First", true)).padRight(10);
                table.add(theme.label("Last", true)).padRight(14);
            }
            if (showCoords) {
                table.add(theme.label("Coords", true)).padRight(10);
                table.add(theme.label("Dim", true)).padRight(14);
            }
            table.add(theme.label(""));
            table.row();
        }
    }
}
