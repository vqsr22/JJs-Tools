package dev.jjstools.tint;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a container's contents add up to, for the icon and fill bar drawn on its item.
 *
 * The slots are gathered into groups of things that count as the same, according to how strict
 * the grouping asked for is, and each group remembers how many there are and where in the box it
 * first and last turned up. How full the box is comes out of the same pass.
 */
public final class StorageSummary {
    /** How alike two stacks have to be to count as one thing. */
    public enum Grouping {
        /** Same item, whatever is on it. */
        Item,
        /** Same item, and enchanted or not alike. A box of plain and enchanted books counts both. */
        Enchantments,
        /** Same item with exactly the same data: potions apart by effect, tools apart by damage. */
        Exact
    }

    /** How fullness is measured. */
    public enum Fill {
        /** Share of the slots with anything in them. */
        Slots,
        /** Share of the room for items: a slot holding 32 of 64 is half a slot. */
        Items
    }

    /** One kind of thing in the box: a stack to draw for it, how many in all, and where it sits. */
    public record Group(ItemStack icon, int total, int first, int last) {}

    public record Summary(List<Group> groups, double fill, boolean empty) {}

    private StorageSummary() {}

    public static Summary of(List<ItemStack> slots, int capacity, Grouping grouping, Fill fill) {
        Map<String, int[]> totals = new LinkedHashMap<>();
        Map<String, ItemStack> icons = new LinkedHashMap<>();

        double used = 0.0;
        boolean empty = true;

        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty()) continue;
            empty = false;

            used += fill == Fill.Slots ? 1.0 : stack.getCount() / (double) Math.max(1, stack.getMaxCount());

            String key = key(stack, grouping);
            int[] t = totals.get(key);
            if (t == null) {
                // total, first slot, last slot
                totals.put(key, new int[] { stack.getCount(), i, i });
                icons.put(key, stack.copyWithCount(1));
            }
            else {
                t[0] += stack.getCount();
                t[2] = i;
            }
        }

        List<Group> groups = new ArrayList<>(totals.size());
        for (Map.Entry<String, int[]> e : totals.entrySet()) {
            int[] t = e.getValue();
            groups.add(new Group(icons.get(e.getKey()), t[0], t[1], t[2]));
        }

        return new Summary(groups, Math.min(1.0, used / Math.max(1, capacity)), empty);
    }

    private static String key(ItemStack stack, Grouping grouping) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();

        return switch (grouping) {
            case Item -> id;
            case Enchantments -> id + (stack.hasGlint() || stack.contains(DataComponentTypes.STORED_ENCHANTMENTS) ? "|e" : "");
            // The components' own text form: equal for stacks that would stack together, apart from count.
            case Exact -> id + "|" + stack.getComponentChanges();
        };
    }

    /**
     * The group whose item a box's custom name points at, if any.
     *
     * Names are rarely exact, "[shop2b2t.com] Chorus" for a box of chorus fruit, so any word of
     * three letters or more in the name that turns up in an item's own name counts, and the
     * group matching the most words wins.
     */
    public static @Nullable Group byName(List<Group> groups, @Nullable String name) {
        if (name == null || name.isBlank()) return null;

        List<String> words = new ArrayList<>();
        for (String w : name.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 3) words.add(w);
        }
        if (words.isEmpty()) return null;

        Group best = null;
        int bestScore = 0;
        for (Group g : groups) {
            String itemName = g.icon().getItem().getName().getString().toLowerCase(java.util.Locale.ROOT);
            int score = 0;
            for (String w : words) if (itemName.contains(w)) score++;
            if (score > bestScore) {
                best = g;
                bestScore = score;
            }
        }
        return best;
    }
}
