package dev.jjstools.util;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps JJ's Tools' module settings when the add-on is turned off and back on.
 *
 * Meteor saves every module by name in one file and only writes the modules it has loaded. Start
 * the game once without JJ's Tools and the next save drops all of its modules, so turning it back
 * on gave every module its defaults again. A copy of just JJ's Tools' modules is kept here, written
 * whenever Meteor saves, and any of them Meteor's file has no entry for is filled in from it.
 */
public final class ModuleConfigBackup {
    private ModuleConfigBackup() {}

    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("jjs-tools").resolve("module-settings.nbt");

    /** The names Meteor's saved file has an entry for, noted as it starts loading. */
    private static Set<String> saved = new HashSet<>();

    private static boolean ours(Module module) {
        return module.getClass().getName().startsWith("dev.jjstools.");
    }

    private static String name(NbtElement element) {
        return element instanceof NbtCompound c ? dev.jjstools.compat.Compat.nbtString(c, "name", "") : "";
    }

    private static NbtList list(NbtCompound tag) {
        return tag.get("modules") instanceof NbtList l ? l : new NbtList();
    }

    /** Meteor is about to load its saved modules. */
    public static void beforeLoad(NbtCompound tag) {
        saved = new HashSet<>();
        for (NbtElement e : list(tag)) saved.add(name(e));
    }

    /** Meteor has loaded its saved modules: fill in any of ours it had no entry for. */
    public static void afterLoad() {
        if (!Files.exists(FILE)) return;
        try {
            NbtCompound backup = NbtIo.read(FILE);
            if (backup == null) return;
            for (NbtElement e : list(backup)) {
                String name = name(e);
                if (name.isEmpty() || saved.contains(name)) continue;
                Module module = Modules.get().get(name);
                if (module != null && ours(module)) module.fromTag((NbtCompound) e);
            }
        } catch (Throwable t) {
            dev.jjstools.JJsTools.LOG.warn("Could not restore JJ's Tools module settings", t);
        }
    }

    /** Meteor has just put together what it saves: keep a copy of our modules. */
    public static void afterSave(NbtCompound tag) {
        NbtList ours = new NbtList();
        for (NbtElement e : list(tag)) {
            Module module = Modules.get().get(name(e));
            if (module != null && ours(module)) ours.add(e.copy());
        }
        if (ours.isEmpty()) return;
        NbtCompound backup = new NbtCompound();
        backup.put("modules", ours);
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            NbtIo.write(backup, tmp);
            Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            dev.jjstools.JJsTools.LOG.warn("Could not save JJ's Tools module settings", t);
        }
    }
}
