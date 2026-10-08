package dev.jjstools.util;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.Set;

/**
 * What can actually go on tilled farmland.
 *
 * Deliberately short. Plenty of things are plantable somewhere without being plantable here: sugar
 * cane and cactus want sand, nether wart wants soul sand, mushrooms and fungi want a dark floor or
 * nylium, berries grow on a bush, and saplings want plain dirt or grass rather than farmland.
 * Offering any of those in a farmland planter is just a longer list to scroll past.
 *
 * Written out by hand rather than tested against a block class, since those class names move
 * between versions and a wrong match would be silent.
 */
public class Plantables {
    public static final Set<Item> FARMLAND_CROPS = Set.of(
        Items.WHEAT_SEEDS,
        Items.CARROT,
        Items.POTATO,
        Items.BEETROOT_SEEDS,
        Items.MELON_SEEDS,
        Items.PUMPKIN_SEEDS,
        Items.TORCHFLOWER_SEEDS,
        Items.PITCHER_POD
    );

    private Plantables() {}

    public static boolean isFarmlandCrop(Item item) {
        return FARMLAND_CROPS.contains(item);
    }
}
