package dev.jjstools;

import com.mojang.logging.LogUtils;
import dev.jjstools.modules.*;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class JJsTools extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    /**
     * An enchanted netherite helmet with a gold trim. The glint comes from the glint override
     * rather than a real enchantment, and the trim is added once a world is loaded, because both
     * of those registries only exist later than this runs.
     */
    public static final Category CATEGORY = new Category("JJ's Tools", dev.jjstools.util.AddonIcon.create());

    @Override
    public void onInitialize() {
        LOG.info("Initialising JJ's Tools");
        Modules.get().add(new StorageGuiTweaks());
        Modules.get().add(new TabOptimizer());
        Modules.get().add(new AutoOmen());
        Modules.get().add(new AntiRocketPlace());
        Modules.get().add(new AutoDoor());

        Modules.get().add(new AdBlocker());
        Modules.get().add(new BedMarker());
        Modules.get().add(new Grinder());
        Modules.get().add(new AutoMason());
        Modules.get().add(new AutoDyeShulkers());
        Modules.get().add(new AdvancedAutolog());
        Modules.get().add(new FastRename());
        Modules.get().add(new AutoEnchant());
        Modules.get().add(new RareItemHighlighter());
        Modules.get().add(new PickupLootStream());
        Modules.get().add(new PrinterGoBrrr());
        Modules.get().add(new ViaEnchantFix());
        Modules.get().add(new ViaRecipeFix());
        Modules.get().add(new ViaStonecutterFix());
        Modules.get().add(new AutoTorch());
        Modules.get().add(new AutoTurtleHelmet());
        Modules.get().add(new AutoWearGold());
        Modules.get().add(new ItemFrameBlock());
        Modules.get().add(new ElytraTakeoff());
        Modules.get().add(new ChatNotifications());
        Modules.get().add(new CustomTrims());
        Modules.get().add(new RubberbandNotifier());
        Modules.get().add(new LightLevels());
        Modules.get().add(new XCarry());
        Modules.get().add(new ChatTweaks());
        Modules.get().add(new ControlPanel());
        Modules.get().add(new PortalPrintDetector());
        Modules.get().add(new PortalScraps());
        Modules.get().add(new Encounters());
        Modules.get().add(new ViaTextureFix());
        Modules.get().add(new Incognito());
        Modules.get().add(new TeamTrees());
        Modules.get().add(new FarmAura());
        Modules.get().add(new KitCreator());
        Modules.get().add(new NoJolt());
        Modules.get().add(new InventoryLoadout());
        Modules.get().add(new SignToItemFrame());
        Modules.get().add(new Unobtrusive());
        Modules.get().add(new BetterHitreg());

        dev.jjstools.util.AutoSaveConfig.init();
        dev.jjstools.util.BlurExtras.init();
        dev.jjstools.util.JJConfig.initialize();
        dev.jjstools.util.MsgUtil.initModulePrefixes();
        dev.jjstools.util.EncounterStore.load();
        dev.jjstools.panel.PanelData.init();
        registerTabs();
        dev.jjstools.util.ModuleRenames.applyAll();
        dev.jjstools.util.CategoryIcons.applyAll();
        dev.jjstools.util.CategoryIcons.hook();
        dev.jjstools.hud.JJsHud.init();
    }

    /**
     * Slots JJ's Tools' tabs straight after Friends rather than on the end of the bar.
     * Encounters used to be one of them; it now lives in the Control Panel.
     */
    private static void registerTabs() {
        var tabs = meteordevelopment.meteorclient.gui.tabs.Tabs.get();

        int index = -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i) instanceof meteordevelopment.meteorclient.gui.tabs.builtin.FriendsTab) {
                index = i + 1;
                break;
            }
        }

        var icons = new dev.jjstools.gui.IconsTab();
        var conflicts = new dev.jjstools.gui.ConflictsTab();

        if (index == -1) {
            tabs.add(icons);
            tabs.add(conflicts);
        }
        else {
            // Added back to front, since each lands at the same index and pushes the last one down.
            tabs.add(index, conflicts);
            tabs.add(index, icons);
        }
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.jjstools";
    }
}
