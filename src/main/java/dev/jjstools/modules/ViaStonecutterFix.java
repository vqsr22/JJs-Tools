package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.display.CuttingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.registry.Registries;
import net.minecraft.util.context.ContextParameterMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Puts the stonecutter's buttons in the order the server counts them, on ViaFabricPlus.
 *
 * Picking a stonecutter recipe sends the server nothing but the button's position in the list.
 * The server looks that position up in its own list, so the two have to be in the same order,
 * and behind a translated connection they are not. ViaVersion sorts the list it builds for this
 * client by the name of what each recipe makes, while the server goes by the recipe's own id.
 * Those mostly agree, but not where one result's name is the start of another's: polished
 * blackstone sorts before polished blackstone bricks by name, after it by id. From that point
 * every button gives you the one beside it.
 *
 * Vanilla names a stonecutter recipe after what it makes, then what it is made from, as in
 * polished_blackstone_bricks_from_blackstone_stonecutting. Every recipe on one input shares the
 * part from "_from_" on, so ordering by what each makes with "_from_" after it is ordering by id,
 * and the buttons line up with the server again.
 */
public class ViaStonecutterFix extends Module {
    public ViaStonecutterFix() {
        super(JJsTools.CATEGORY, "via-stonecutter-fix",
            "Fixes stonecutter buttons giving you the wrong block on ViaFabricPlus, by putting them in the order the server counts them.");
    }

    private static boolean on() {
        Modules modules = Modules.get();
        if (modules == null) return false;

        ViaStonecutterFix module = modules.get(ViaStonecutterFix.class);
        return module != null && module.isActive();
    }

    /** The recipes for one input, in the server's order if this is on, or as they came if not. */
    public static <T extends net.minecraft.recipe.Recipe<?>> CuttingRecipeDisplay.Grouping<T> order(CuttingRecipeDisplay.Grouping<T> recipes) {
        if (!on() || recipes == null || recipes.entries().size() < 2) return recipes;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return recipes;

        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);

        List<CuttingRecipeDisplay.GroupEntry<T>> sorted = new ArrayList<>(recipes.entries());
        // Stable, so anything that comes out with the same key keeps the order it had.
        sorted.sort(Comparator.comparing(entry -> key(entry, context)));
        return new CuttingRecipeDisplay.Grouping<>(sorted);
    }

    private static String key(CuttingRecipeDisplay.GroupEntry<?> entry, ContextParameterMap context) {
        ItemStack result = entry.recipe().optionDisplay().getFirst(context);
        if (result.isEmpty()) return "";
        return Registries.ITEM.getId(result.getItem()).getPath() + "_from_";
    }
}
