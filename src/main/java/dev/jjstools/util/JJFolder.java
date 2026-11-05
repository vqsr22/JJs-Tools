package dev.jjstools.util;

import dev.jjstools.JJsTools;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Where per-account data lives.
 *
 * Files are kept under .minecraft/jjs-tools/accounts/<uuid>/ rather than one shared folder, so two
 * accounts on the same install do not overwrite each other's encounters or bed. That matters when
 * instances are symlinked together, and because accounts genuinely see different players.
 *
 * Still outside Meteor's config, so updates and profile wipes leave it alone.
 */
public class JJFolder {
    private static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("jjs-tools");
    private static final Path ACCOUNTS = ROOT.resolve("accounts");

    private JJFolder() {}

    /** The signed-in account's folder, or a shared "offline" one if there is somehow no session. */
    public static Path forAccount() {
        UUID id = null;
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc != null && mc.getSession() != null) {
            try {
                id = mc.getSession().getUuidOrNull();
            } catch (Throwable ignored) {
                // Some auth setups hand back a malformed uuid; the fallback below covers it.
            }
        }

        return ACCOUNTS.resolve(id == null ? "offline" : id.toString());
    }

    public static Path file(String name) {
        return forAccount().resolve(name);
    }

    /**
     * Moves a file from the old shared folder into this account's folder, once.
     *
     * Without this everything recorded before the split would look lost. The first account to sign
     * in after updating inherits it, which is the best guess available: the old file has no record
     * of who it belonged to.
     */
    public static void migrateIfNeeded(String name) {
        Path legacy = ROOT.resolve(name);
        Path target = file(name);

        if (!Files.exists(legacy) || Files.exists(target)) return;

        try {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target);
            JJsTools.LOG.info("Moved {} into this account's folder", name);
        } catch (IOException e) {
            JJsTools.LOG.error("Could not move {} into this account's folder", name, e);
        }
    }
}
