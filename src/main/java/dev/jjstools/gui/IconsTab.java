package dev.jjstools.gui;

import dev.jjstools.util.CategoryIcons;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * Sets the icon shown next to every module category, from any addon, not just this one.
 *
 * Built as one Settings object with a group per category, so it renders exactly like every other
 * Meteor settings screen instead of a hand-drawn table.
 *
 * Choices are written to .minecraft/jjs-tools/category-icons.json and applied over whatever the
 * owning addon picked, so they survive that addon updating.
 */
public class IconsTab extends Tab {
    public IconsTab() {
        super("Icons");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new IconsScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof IconsScreen;
    }

    private static class IconsScreen extends WindowTabScreen {
        private record Row(Category category, Setting<Item> item, Setting<Boolean> glint) {}

        private final List<Row> rows = new ArrayList<>();

        public IconsScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            add(theme.label("Category icons, including other addons' categories.")).expandX();
            add(theme.label("From JJ's Tools").color(theme.textSecondaryColor())).expandX();
            add(theme.horizontalSeparator()).padVertical(6).expandX();

            rows.clear();
            Settings settings = new Settings();

            for (Category category : Modules.loopCategories()) {
                SettingGroup group = settings.createGroup(category.name);

                Item current = CategoryIcons.itemFor(category);
                boolean natural = CategoryIcons.glintsNaturally(current);

                // Applied the moment it changes, so there is no Apply button to forget.
                Setting<Item> item = group.add(new ItemSetting.Builder()
                    .name("icon")
                    .description("Any item or block. Applied straight away.")
                    .defaultValue(current)
                    .onChanged(value -> CategoryIcons.set(category, value, CategoryIcons.glintFor(category)))
                    .build()
                );

                Setting<Boolean> glint = group.add(new BoolSetting.Builder()
                    .name("glint")
                    .description(natural
                        ? "This item already glows on its own, so this does nothing."
                        : "Give the icon an enchantment shimmer.")
                    .defaultValue(CategoryIcons.glintFor(category))
                    .onChanged(value -> CategoryIcons.set(category, CategoryIcons.itemFor(category), value))
                    .build()
                );

                rows.add(new Row(category, item, glint));
            }

            add(theme.settings(settings)).expandX();
            add(theme.horizontalSeparator()).padVertical(6).expandX();

            WButton reset = add(theme.button("Reset all")).expandX().widget();
            reset.action = () -> {
                for (Row row : rows) CategoryIcons.reset(row.category());
                reload();
            };
        }

        @Override
        public void reload() {
            clear();
            initWidgets();
        }
    }
}
