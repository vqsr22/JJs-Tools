package dev.jjstools.util;

import java.util.Map;
import java.util.HashMap;
import dev.jjstools.JJsTools;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.systems.modules.Module;
import static meteordevelopment.meteorclient.MeteorClient.mc;
import meteordevelopment.meteorclient.mixininterface.IChatHud;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * Chat messages from the addon's modules, each with its own coloured prefix.
 *
 * Every module picks an accent colour once per launch, so the same module always speaks in the
 * same colour for the rest of the session.
 *
 * @author Tas [@0xTas] <root@0xTas.dev>
 **/
public class MsgUtil {
    private final static Map<String, String> modulePrefixes = new HashMap<>();

    public static String getRawPrefix() {
        return "[JJ's Tools]";
    }

    public static String getRawPrefix(String module) {
        return "[" + module + "]";
    }

    public static void initModulePrefixes() {
        for (Module module : Modules.get().getGroup(JJsTools.CATEGORY)) {
            modulePrefixes.put(module.name, JJUtil.rCC());
        }
    }

    public static String getModulePrefix(String module) {
        String colour = modulePrefixes.getOrDefault(module, null);
        if (colour == null) colour = JJUtil.rCC();

        return String.valueOf(Formatting.DARK_GRAY) + '[' + colour +
            Formatting.ITALIC + Utils.nameToTitle(module) + Formatting.DARK_GRAY + ']';
    }

    public static void sendModuleMsg(String msg, String module) {
        if (mc.player == null) return;

        try {
            mc.player.sendMessage(Text.literal(getModulePrefix(module) + ' ' + Formatting.GRAY + msg), false);
        } catch (Exception ignored) {}
    }

    /** Sends a message that replaces the last one sent with the same id, instead of piling up. */
    public static void updateModuleMsg(String msg, String module, int hashcode) {
        if (mc.player == null) return;

        try {
            ((IChatHud) mc.inGameHud.getChatHud()).meteor$add(
                Text.literal(getModulePrefix(module) + ' ' + Formatting.GRAY + msg), hashcode
            );
        } catch (Exception ignored) {}
    }
}
