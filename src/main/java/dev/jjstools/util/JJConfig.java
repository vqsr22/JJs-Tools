package dev.jjstools.util;

import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;

/** Addon-wide settings, shown in Meteor's Config tab under "JJ's Tools". */
public class JJConfig {
    public static Setting<JJUtil.IllegalDisconnectMethod> illegalDisconnectMethodSetting =
        new EnumSetting.Builder<JJUtil.IllegalDisconnectMethod>().defaultValue(JJUtil.IllegalDisconnectMethod.Chat).build();


    public static void initialize() {
        SettingGroup group = Config.get().settings.createGroup("JJ's Tools");

        illegalDisconnectMethodSetting = group.add(
            new EnumSetting.Builder<JJUtil.IllegalDisconnectMethod>()
                .name("illegal-disconnect-method")
                .description("The method to use to cause the server to kick you.")
                .defaultValue(JJUtil.IllegalDisconnectMethod.Chat)
                .build()
        );

    }
}
