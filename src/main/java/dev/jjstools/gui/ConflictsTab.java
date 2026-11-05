package dev.jjstools.gui;

import dev.jjstools.util.ModuleRenames;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Map;

/**
 * Modules from different add-ons that wanted the same name.
 *
 * Meteor files modules by name, and a second module taking a name that is already spoken for does
 * not sit beside the first: it replaces it. The clash is settled as they register, so by the time
 * this is open both sides exist and the only thing left to decide is what they are called.
 *
 * One row per module rather than a settings panel each. Thirteen clashes is twenty six panels, and
 * a list that long is only readable if a row is a row.
 */
public class ConflictsTab extends Tab {
    public ConflictsTab() {
        super("Conflicts");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new ConflictsScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof ConflictsScreen;
    }

    private static class ConflictsScreen extends WindowTabScreen {
        public ConflictsScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            add(theme.label("Modules from different add-ons that wanted the same name.")).expandX();

            WTable credit = add(theme.table()).widget();
            credit.add(theme.label("From ").color(theme.textSecondaryColor()));
            credit.add(theme.label("JJ's Tools").color(Credit.PURPLE));

            add(theme.horizontalSeparator()).padVertical(6).expandX();

            Map<String, List<Module>> conflicts = ModuleRenames.conflicts();

            if (conflicts.isEmpty()) {
                add(theme.label("No clashes. Every module you have asked for a name of its own.")).expandX();
                renamedList();
                return;
            }

            add(theme.label("Settled as they registered, so both sides survived. Type a name and")).expandX();
            add(theme.label("press Set if you would rather call one of them something else.")).expandX();
            add(theme.horizontalSeparator()).padVertical(6).expandX();

            WTable table = add(theme.table()).expandX().widget();

            table.add(theme.label("Wanted", true)).padRight(10);
            table.add(theme.label("From", true)).padRight(10);
            table.add(theme.label("Called", true)).padRight(6);
            table.add(theme.label(""));
            table.row();

            for (Map.Entry<String, List<Module>> entry : conflicts.entrySet()) {
                for (Module module : entry.getValue()) {
                    table.add(theme.label(ModuleRenames.originalName(module))).padRight(10).centerY();
                    table.add(theme.label(ModuleRenames.sourceOf(module))
                        .color(theme.textSecondaryColor())).padRight(10).centerY();

                    // Two different things, and the box needs both. expandCellX gives the cell the
                    // width left over in the row; expandX makes the box fill the cell it was given.
                    // With only the first, the cell is wide and the box inside it stays as narrow
                    // as the text it happens to hold.
                    WTextBox box = table.add(theme.textBox(module.name))
                        .padRight(6).expandCellX().expandX().widget();

                    WButton set = table.add(theme.button("Set")).right().centerY().widget();
                    set.action = () -> {
                        ModuleRenames.rename(module, box.get());
                        reload();
                    };

                    table.row();
                }
            }

            renamedList();
        }

        /** What has been renamed already, so a rename can be found and undone later. */
        private void renamedList() {
            List<Module> renamed = ModuleRenames.allModules().stream()
                .filter(m -> ModuleRenames.isRenamed(m) || ModuleRenames.isAutoRenamed(m))
                .toList();

            if (renamed.isEmpty()) return;

            add(theme.horizontalSeparator()).padVertical(6).expandX();
            add(theme.label("Renamed")).expandX();

            WTable table = add(theme.table()).expandX().widget();

            for (Module module : renamed) {
                table.add(theme.label(ModuleRenames.originalName(module))).padRight(10).centerY();
                table.add(theme.label("to")).padRight(10).centerY();
                table.add(theme.label(module.name)).padRight(14).centerY();
                table.add(theme.label(ModuleRenames.sourceOf(module))
                    .color(theme.textSecondaryColor())).padRight(14).centerY();

                WButton undo = table.add(theme.button("Undo")).expandCellX().right().centerY().widget();
                undo.action = () -> {
                    ModuleRenames.rename(module, "");
                    reload();
                };

                table.row();
            }
        }

        @Override
        public void reload() {
            clear();
            initWidgets();
        }
    }
}
