package dev.jjstools.modules;

import java.util.IdentityHashMap;
import java.util.Map;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import dev.jjstools.JJsTools;
import net.minecraft.screen.*;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.component.ComponentMap;
import net.minecraft.enchantment.Enchantments;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.component.DataComponentTypes;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.entity.passive.TropicalFishEntity;
import net.minecraft.util.DyeColor;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.client.network.ClientPlayerEntity;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * @author Tas [0xTas] <root@0xTas.dev>
 **/
public class RareItemHighlighter extends Module {
    public RareItemHighlighter() { super(JJsTools.CATEGORY, "rare-item-highlighter", "Slot highlighter for rare, unique, and anomalous items."); }

    private final SettingGroup sgRares = settings.createGroup("Rares Settings");
    private final SettingGroup sgUniques = settings.createGroup("Uniques Settings");

    private final Setting<Boolean> illegalEnchants = sgRares.add(
        new BoolSetting.Builder()
            .name("illegal-enchants")
            .description("Highlight items with illegal enchantments like Mending/Infinity, or stacked Protection.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> onlySilkyShears = sgRares.add(
        new BoolSetting.Builder()
            .name("exclusive-silky-shears")
            .description("Highlight silk touch shears only if they have no other enchants.")
            .defaultValue(true)
            .visible(illegalEnchants::get)
            .build()
    );

    private final Setting<Boolean> onlyInfinityMending = sgRares.add(
        new BoolSetting.Builder()
            .name("exclusive-mending/Infinity")
            .description("Highlight bows & books that have ONLY mending & infinity applied.")
            .defaultValue(true)
            .visible(illegalEnchants::get)
            .build()
    );

    private final Setting<Boolean> negativeDurability = sgRares.add(
        new BoolSetting.Builder()
            .name("negative-durability")
            .description("Highlight items with negative true durability (all negative durability items show in-game as 0 durability items in 1.21.)")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> petrifiedSlabs = sgRares.add(
        new BoolSetting.Builder()
            .name("alpha-slabs")
            .description("Highlight alpha slabs (now petrified oak slabs.)")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> lagRockets = sgRares.add(
        new BoolSetting.Builder()
            .name("lag-rockets")
            .description("Highlight lag rockets.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> illegalFish = sgRares.add(
        new BoolSetting.Builder()
            .name("illegal-fish")
            .description("Highlight illegal tropical fish with black as one of their colours. These are no longer obtainable as of 1.21.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> renamedItems = sgUniques.add(
        new BoolSetting.Builder()
            .name("renamed-items")
            .description("Highlight renamed items in GUIs.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> renamedShulks = sgUniques.add(
        new BoolSetting.Builder()
            .name("renamed-shulkers")
            .description("Highlight renamed shulker boxes, even if they contain no renamed items.")
            .defaultValue(false)
            .visible(renamedItems::get)
            .build()
    );

    private final Setting<Boolean> writtenBooks = sgUniques.add(
        new BoolSetting.Builder()
            .name("written-books")
            .description("Highlight written books.")
            .defaultValue(false)
            .build()
    );

    private final Setting<String> metadataSearch = settings.getDefaultGroup().add(
        new StringSetting.Builder()
            .name("metadata-search")
            .description("Fuzzy search for item NBT data. Notable usage examples: specific book authors, item names, or enchants.")
            .defaultValue("")
            .build()
    );

    private final Setting<Boolean> splitQueries = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("split-queries")
            .description("Split search queries into multiple items separated by commas. Disable to treat commas literally in the search instead.")
            .defaultValue(true)
            .build()
    );

    public final Setting<SettingColor> color = settings.getDefaultGroup().add(
        new ColorSetting.Builder()
            .name("highlight-colour")
            .defaultValue(new SettingColor(138, 71, 221, 69))
            .build()
    );

    private final Setting<Boolean> ownInventory = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("inventory-highlight")
            .description("Highlight items meeting the above criteria on the player inventory screen.")
            .defaultValue(false)
            .build()
    );

    private int enchantmentsCount(ItemStack stack) {
        int count = 0;
        if (!stack.isEmpty()) {
            count = stack.getItem() == Items.ENCHANTED_BOOK
                ? stack.get(DataComponentTypes.STORED_ENCHANTMENTS).getEnchantments().size()
                : stack.getEnchantments().getSize();
        }
        return count;
    }

    private boolean shouldIgnoreCurrentScreenHandler(ClientPlayerEntity player) {
        if (mc.currentScreen == null) return true;
        if (player.currentScreenHandler == null) return true;
        ScreenHandler handler = player.currentScreenHandler;
        if (handler instanceof PlayerScreenHandler) return !ownInventory.get();
        return !(handler instanceof AbstractFurnaceScreenHandler || handler instanceof GenericContainerScreenHandler
            || handler instanceof Generic3x3ContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler
            || handler instanceof HopperScreenHandler || handler instanceof HorseScreenHandler);
    }

    /*
     * Answers already worked out, per stack, so each is only checked once in a while.
     *
     * The check runs for every slot on every frame. For a shulker box it opens the box and checks
     * all 27 things inside, and with a search set it turned each item's whole data into text up
     * to four times over. A chest of shulkers was thousands of those a frame, which is the lag.
     * The answer for a stack hardly ever changes while you look at it, so it is kept and the
     * whole lot is thrown away every second and whenever a screen opens, which is soon enough
     * for a changed item or a changed setting to show.
     */
    private final Map<ItemStack, Boolean> answers = new IdentityHashMap<>();
    private int ticks;

    @Override
    public void onDeactivate() {
        answers.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (++ticks % 20 == 0) answers.clear();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        answers.clear();
    }

    // See HandledScreenRareItemMixin.java
    public boolean shouldHighlightSlot(ItemStack stack) {
        if (mc.player == null) return false;
        if (stack.isEmpty() || shouldIgnoreCurrentScreenHandler(mc.player)) return false;

        Boolean known = answers.get(stack);
        if (known != null) return known;

        if (answers.size() > 4096) answers.clear();
        boolean answer = check(stack);
        answers.put(stack, answer);
        return answer;
    }

    private boolean check(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        if (Utils.hasItems(stack)) {
            ItemStack[] stacks = new ItemStack[27];
            Utils.getItemsInContainerItem(stack, stacks);
            for (ItemStack s : stacks) {
                if (check(s)) return true;
            }
        }

        String search = metadataSearch.get().trim().toLowerCase();
        if (!search.isEmpty()) {
            // Each of these is worked out once, not once per query.
            ComponentMap metadata = stack.getComponents();
            String data = metadata == null ? "" : metadata.toString().toLowerCase();
            String name = stack.getName().getString().toLowerCase();
            String itemName = stack.getItem().getName().getString().toLowerCase();

            String[] queries = splitQueries.get() && search.contains(",") ? search.split(",") : new String[] { search };
            for (String raw : queries) {
                String q = raw.trim();
                if (q.isEmpty()) continue;
                if (data.contains(q) || data.contains(q.replace(" ", "_"))) return true;
                if (name.contains(q) || itemName.contains(q)) return true;
            }
        }

        if (!renamedShulks.get() && stack.contains(DataComponentTypes.CUSTOM_NAME)) {
            if (stack.getItem() == Items.SHULKER_BOX || AutoDyeShulkers.isColoredShulker(stack.getItem())) return false;
        }

        if (lagRockets.get() && stack.contains(DataComponentTypes.FIREWORKS)) {
            FireworksComponent firework = stack.get(DataComponentTypes.FIREWORKS);
            if (firework.explosions().size() == 7) return true;
        }

        if (illegalFish.get() && stack.isOf(Items.TROPICAL_FISH_BUCKET)) {
            // 1.21.5+: the bucket's fish variant is stored as three item components
            // instead of a BucketVariantTag inside the entity NBT.
            TropicalFishEntity.Pattern pattern = stack.get(DataComponentTypes.TROPICAL_FISH_PATTERN);
            DyeColor baseColor = stack.get(DataComponentTypes.TROPICAL_FISH_BASE_COLOR);
            DyeColor patternColor = stack.get(DataComponentTypes.TROPICAL_FISH_PATTERN_COLOR);
            if (pattern != null && baseColor != null && patternColor != null) {
                // Variant is a record, so its canonical constructor takes the three parts directly.
                TropicalFishEntity.Variant variant = new TropicalFishEntity.Variant(pattern, baseColor, patternColor);
                if (TropicalFishEntity.COMMON_VARIANTS.indexOf(variant) == -1
                    && (baseColor == DyeColor.BLACK || patternColor == DyeColor.BLACK)) {
                    return true;
                }
            }
        }

        if (writtenBooks.get() && stack.getItem() == Items.WRITTEN_BOOK) return true;
        if (petrifiedSlabs.get() && stack.getItem() == Items.PETRIFIED_OAK_SLAB) return true;
        if (renamedItems.get() && stack.contains(DataComponentTypes.CUSTOM_NAME)) return true;

        if (negativeDurability.get() && stack.isDamageable()) {
            if (stack.getOrDefault(DataComponentTypes.DAMAGE, stack.getDamage()) >= stack.getMaxDamage()) return true;
        }

        if (illegalEnchants.get() && (stack.getItem() == Items.ENCHANTED_BOOK || stack.hasEnchantments())) {
            int enchantmentsCount = enchantmentsCount(stack);
            if (stack.getItem() == Items.SHEARS && Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH)) {
                return enchantmentsCount == 1 || !onlySilkyShears.get();
            }

            if (stack.getItem() == Items.ENCHANTED_BOOK) {
                if (enchantmentsCount == 0 || enchantmentsCount > 7) return true;
            }

            boolean hasProtection = Utils.hasEnchantment(stack, Enchantments.PROTECTION);
            if (Utils.hasEnchantment(stack, Enchantments.FIRE_PROTECTION)) {
                if (!hasProtection) hasProtection = true;
                else return true;
            }
            if (Utils.hasEnchantment(stack, Enchantments.BLAST_PROTECTION)) {
                if (!hasProtection) hasProtection = true;
                else return true;
            }
            if (Utils.hasEnchantment(stack, Enchantments.PROJECTILE_PROTECTION)) {
                if (hasProtection) return true;
            }

            if (Utils.hasEnchantment(stack, Enchantments.INFINITY) && Utils.hasEnchantment(stack, Enchantments.MENDING)) {
                return enchantmentsCount == 2 || !onlyInfinityMending.get();
            }
        }

        return false;
    }
}
