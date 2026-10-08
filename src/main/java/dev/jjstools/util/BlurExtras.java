package dev.jjstools.util;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Blur;

/** Adds an "Extras" group to Meteor's Blur with a tab-list option. See BlurMixin. */
public final class BlurExtras {
    private BlurExtras() {
    }

    private static Setting<Boolean> tabList;

    public static void init() {
        Blur blur = Modules.get().get(Blur.class);
        if (blur == null) return;

        SettingGroup extras = blur.settings.createGroup("Extras");
        tabList = extras.add(new BoolSetting.Builder()
            .name("tab-list")
            .description("Blur the background while the tab list is open. (Added by JJ's Tools)")
            .defaultValue(true)
            .build()
        );
    }

    public static boolean tabList() {
        return tabList != null && tabList.get();
    }
}
