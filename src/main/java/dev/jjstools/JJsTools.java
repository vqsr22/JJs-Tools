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
    public static final Category CATEGORY = new Category("JJ's Tools", categoryIcon());

    /**
     * Netherite pickaxe with the enchantment glint forced on.
     *
     * The glint comes from the ENCHANTMENT_GLINT_OVERRIDE component rather than a real
     * enchantment, because this runs at class load, long before the enchantment registry exists.
     */
    private static ItemStack categoryIcon() {
        ItemStack stack = Items.NETHERITE_PICKAXE.getDefaultStack();
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    @Override
    public void onInitialize() {
        LOG.info("Initialising JJ's Tools");
        Modules.get().add(new ShulkerTint());
        Modules.get().add(new TabOptimizer());
        Modules.get().add(new AutoOmen());
        Modules.get().add(new AntiRocketPlace());
        Modules.get().add(new AutoDoor());

        Modules.get().add(new AdBlocker());
        Modules.get().add(new BattleCry());
        Modules.get().add(new BedMarker());
        Modules.get().add(new Grinder());
        Modules.get().add(new AutoMason());
        Modules.get().add(new AutoDyeShulkers());
        Modules.get().add(new AdvancedAutolog());
        Modules.get().add(new AutoSmith());
        Modules.get().add(new FastRename());
        Modules.get().add(new RareItemHighlighter());
        Modules.get().add(new BannerData());
        Modules.get().add(new ViaEnchantFix());
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
        Modules.get().add(new PortalPrintDetector());
        Modules.get().add(new PortalScraps());
        Modules.get().add(new Encounters());
        Modules.get().add(new ViaTextureFix());
        Modules.get().add(new IllegalDisconnect());
        Modules.get().add(new Incognito());
        Modules.get().add(new Tiller());
        Modules.get().add(new TeamTrees());
        Modules.get().add(new FarmAura());

        dev.jjstools.util.BlurExtras.init();
        dev.jjstools.util.JJConfig.initialize();
        dev.jjstools.util.MsgUtil.initModulePrefixes();
        dev.jjstools.util.EncounterStore.load();
        registerEncountersTab();
        dev.jjstools.util.CategoryIcons.applyAll();
        dev.jjstools.hud.JJsHud.init();
    }

    /** Slots the Encounters tab straight after Friends rather than on the end of the bar. */
    private static void registerEncountersTab() {
        var tabs = meteordevelopment.meteorclient.gui.tabs.Tabs.get();
        var tab = new dev.jjstools.gui.EncountersTab();

        int index = -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i) instanceof meteordevelopment.meteorclient.gui.tabs.builtin.FriendsTab) {
                index = i + 1;
                break;
            }
        }

        var icons = new dev.jjstools.gui.IconsTab();

        if (index == -1) {
            tabs.add(tab);
            tabs.add(icons);
        }
        else {
            tabs.add(index, icons);
            tabs.add(index, tab);
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
