package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.context.ContextParameterMap;
import dev.jjstools.util.VanillaTags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stops the recipe book claiming you can craft things you cannot, on ViaFabricPlus.
 *
 * Behind a translated connection the book ticks recipes as craftable with none of the
 * ingredients anywhere in your inventory: a crafting table, sticks and planks with not a scrap
 * of wood on you. The book decides that from a list of requirements the server sends with each
 * recipe, and somewhere between an older server and this client that list stops matching what
 * the recipe actually needs.
 *
 * So the book's answer is checked against the recipe itself. Every crafting recipe also carries
 * the slots it shows you, the cycling planks and the rest, and those are what you can see is
 * needed. Each slot that holds something has to be filled from what you are carrying, one item
 * per slot, or the recipe is not craftable whatever the requirements said.
 *
 * Only ever overrules a yes. A recipe the book already calls uncraftable is left alone, and
 * anything it cannot read, such as furnace or smithing recipes, keeps the book's own answer.
 * The check counts generously, any item of the right kind wherever it sits, so the only thing
 * it can do is take away a tick that should not have been there.
 */
public class ViaRecipeFix extends Module {
    public ViaRecipeFix() {
        super(JJsTools.CATEGORY, "via-recipe-fix",
            "Stops the recipe book ticking recipes as craftable when you do not have the ingredients, a ViaFabricPlus bug.");
    }

    private static ViaRecipeFix active() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        ViaRecipeFix module = modules.get(ViaRecipeFix.class);
        return module != null && module.isActive() ? module : null;
    }

    /** True if the book called this craftable and it plainly is not. Called from RecipeDisplayEntryMixin. */
    public static boolean vetoes(RecipeDisplayEntry entry) {
        if (active() == null || entry == null) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || mc.player == null) return false;

        List<SlotDisplay> slots = ingredientSlots(entry.display());
        if (slots == null) return false;

        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);

        /*
         * What each filled slot will take, as a set of items.
         *
         * A gap in a shaped recipe is skipped. A slot that should hold something but resolves to
         * nothing is not: that is the bug itself. Right-clicking planks on an affected connection
         * shows every alternative with an empty grid, because the slots are tags like any planks
         * and the client's copy of those tags came through empty. Skipping such a slot, as the
         * first version of this did, left nothing to check and so never overruled anything.
         */
        List<Set<Item>> needs = new ArrayList<>();
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.EmptySlotDisplay) continue;

            Set<Item> items = resolve(slot, context);

            // Must hold something, and nothing at all fits it: cannot be filled from anything.
            if (items.isEmpty()) {
                noticeOnce();
                return true;
            }
            needs.add(items);
        }

        if (needs.isEmpty()) return false;

        boolean fillable = fillable(needs, carried(mc));
        if (!fillable) noticeOnce();
        return !fillable;
    }

    /**
     * The items a slot accepts.
     *
     * Asked of the slot first. If that comes back empty and the slot is a tag, the tag is read
     * from the game's own vanilla data instead, which is right even when the client's copy is
     * not, so having the logs still counts as having the logs. A slot built from several others
     * is the union of what each of them resolves to, by the same rule.
     */
    private static Set<Item> resolve(SlotDisplay slot, ContextParameterMap context) {
        Set<Item> items = new HashSet<>();

        List<ItemStack> options = slot.getStacks(context);
        if (options != null) {
            for (ItemStack stack : options) {
                if (!stack.isEmpty()) items.add(stack.getItem());
            }
        }
        if (!items.isEmpty()) return items;

        if (slot instanceof SlotDisplay.TagSlotDisplay tag) {
            items.addAll(VanillaTags.items(tag.tag().id()));
        }
        else if (slot instanceof SlotDisplay.CompositeSlotDisplay composite) {
            for (SlotDisplay inner : composite.contents()) items.addAll(resolve(inner, context));
        }

        return items;
    }

    /** Confirms in chat, once a session, that the fix is reaching the recipe book and doing its job. */
    private static boolean announced;

    private static void noticeOnce() {
        if (announced) return;
        announced = true;

        ViaRecipeFix module = active();
        if (module != null) module.info("Hiding craftable ticks on recipes you do not have the ingredients for.");
    }

    /** The slots a crafting recipe shows, or null for anything else. */
    private static List<SlotDisplay> ingredientSlots(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) return shaped.ingredients();
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients();
        return null;
    }

    /**
     * Everything you could craft from: your inventory, your offhand, and whatever is already in
     * the crafting grid in front of you, which the book counts too.
     */
    private static Map<Item, Integer> carried(MinecraftClient mc) {
        Map<Item, Integer> counts = new HashMap<>();

        for (int i = 0; i < 36; i++) add(counts, mc.player.getInventory().getStack(i));
        add(counts, mc.player.getOffHandStack());

        if (mc.player.currentScreenHandler != null) {
            for (Slot slot : mc.player.currentScreenHandler.slots) {
                if (slot.inventory instanceof RecipeInputInventory) add(counts, slot.getStack());
            }
        }

        return counts;
    }

    private static void add(Map<Item, Integer> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    /**
     * Whether every slot can be filled, one item each, from what is carried.
     *
     * The pickiest slots are filled first. A slot that takes only one thing should get it before
     * a slot that takes anything, or the easy slot can use up the only item the picky one could
     * have had and a craftable recipe would be called uncraftable.
     */
    private static boolean fillable(List<Set<Item>> needs, Map<Item, Integer> carried) {
        List<Set<Item>> ordered = new ArrayList<>(needs);
        ordered.sort(Comparator.comparingInt(Set::size));

        Map<Item, Integer> left = new HashMap<>(carried);

        for (Set<Item> need : ordered) {
            Item best = null;
            int most = 0;

            // Taken from whichever accepted item there is most of, to leave the scarce ones.
            for (Item item : need) {
                int have = left.getOrDefault(item, 0);
                if (have > most) {
                    most = have;
                    best = item;
                }
            }

            if (best == null) return false;
            left.put(best, most - 1);
        }

        return true;
    }
}
