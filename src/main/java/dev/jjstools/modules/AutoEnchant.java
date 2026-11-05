package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.mixin.accessor.AnvilScreenHandlerAccessor;
import dev.jjstools.util.MsgUtil;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnchantmentListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Puts enchanted books onto your gear at an anvil, one book at a time, until there is nothing
 * left to add.
 *
 * You pick the items it is allowed to work on and the enchantments you want on them. It then
 * works through your inventory: an item goes in the left slot, a book that would improve it goes
 * in the right, the result is taken out, and the result goes back in to collect the next book.
 * That last part is why a sword comes out with five enchantments rather than one.
 *
 * What it will not do is guess at the anvil's rules. Whether a book fits an item, whether two
 * enchantments conflict, and what a combination costs are all decided by the game. This checks
 * the cheap and obvious things first to save a pointless click, then puts the book in and reads
 * the answer off the anvil. A book the anvil refuses is set aside for that item and the next one
 * is tried, so one bad pairing cannot stall the whole run.
 *
 * Every click is an ordinary shift click, the same as doing it by hand.
 */
public class AutoEnchant extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRecovery = settings.createGroup("Broken Anvil");

    private final Setting<List<Item>> itemList = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Which of your items to put books on. Only things that can hold an enchantment are listed.")
        .filter(AutoEnchant::enchantable)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> wanted = sgGeneral.add(new EnchantmentListSetting.Builder()
        .name("enchantments")
        .description("Which enchantments to put on. Leave this empty to use every book you are carrying.")
        .build()
    );

    private final Setting<Boolean> booksFromInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("use-all-books")
        .description("Take books from anywhere in your inventory. Off uses only the books in your hotbar, which is the easy way to hold back the ones you are saving.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxCost = sgGeneral.add(new IntSetting.Builder()
        .name("max-cost")
        .description("Skip a combination that would cost more levels than this. The anvil refuses anything over 39 by itself.")
        .defaultValue(39).min(1).max(39).sliderRange(1, 39)
        .build()
    );

    private final Setting<Integer> keepLevels = sgGeneral.add(new IntSetting.Builder()
        .name("keep-levels")
        .description("Stop before a combination would take you below this many levels.")
        .defaultValue(0).min(0).max(100).sliderRange(0, 50)
        .build()
    );

    private final Setting<Boolean> waitForXp = sgGeneral.add(new BoolSetting.Builder()
        .name("wait-for-xp")
        .description("Short of levels, sit at the open anvil and carry on once you have them, instead of stopping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> tickRate = sgGeneral.add(new IntSetting.Builder()
        .name("tick-rate")
        .description("Ticks between clicks. Raise this if the server is kicking you.")
        .defaultValue(2).min(0).max(20).sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> soundPing = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-ping")
        .description("Play a sound when there is nothing left to do.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pingVolume = sgGeneral.add(new IntSetting.Builder()
        .name("ping-sound-volume")
        .description("How loud the sound is, from 0 to 100.")
        .defaultValue(50).min(0).max(100).sliderRange(0, 100)
        .visible(soundPing::get)
        .build()
    );

    private final Setting<Boolean> disableOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-done")
        .description("Switch the module off once there is nothing left to do.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> reopenAnvil = sgRecovery.add(new BoolSetting.Builder()
        .name("reopen-anvil")
        .description("Anvils break fast when you are combining books. Open the one that drops into the gap and carry on.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> reopenDelay = sgRecovery.add(new IntSetting.Builder()
        .name("reopen-delay")
        .description("Ticks to wait after the anvil breaks before clicking the replacement. Long enough for the one above to finish falling.")
        .defaultValue(4).min(0).max(60).sliderRange(0, 20)
        .visible(reopenAnvil::get)
        .build()
    );

    private final Setting<Boolean> reopenRotate = sgRecovery.add(new BoolSetting.Builder()
        .name("silent-rotate")
        .description("Turn towards the anvil for the click without moving your view.")
        .defaultValue(true)
        .visible(reopenAnvil::get)
        .build()
    );

    /** Anvil handler slots: two inputs, the output, then your inventory. */
    private static final int INPUT_1 = 0;
    private static final int INPUT_2 = 1;
    private static final int OUTPUT = 2;
    private static final int INVENTORY_START = 3;
    private static final int INVENTORY_SIZE = 36;
    private static final int HOTBAR_START = INVENTORY_START + 27;

    /** Ticks to let the server work out an output before calling a pairing refused. */
    private static final int SETTLE = 12;

    /** How long to watch for a replacement anvil before giving up, in ticks. */
    private static final int REOPEN_WINDOW = 100;

    private static final int XP_MSG_ID = "anvilEnchanterXp".hashCode();

    private int timer;
    private int settle;
    private boolean notified;
    private boolean xpNotified;

    /** Books this anvil would not take for the item currently in the left slot. */
    private final Set<String> refused = new HashSet<>();

    /** Items nothing in the inventory can improve, so they are not picked up again. */
    private final Set<String> exhausted = new HashSet<>();

    private BlockPos anvilPos;
    private boolean anvilBroke;
    private int sinceBroke;
    private int watchLeft;

    public AutoEnchant() {
        super(JJsTools.CATEGORY, "auto-enchant",
            "Combines your enchanted books onto your gear at an anvil, book after book, until there is nothing left to add.");
    }

    /**
     * Whether an item is worth offering in the list.
     *
     * Anything with durability can take at least Unbreaking and Mending at an anvil, which is the
     * honest test and the one that keeps elytra and shields in the list: neither can be enchanted
     * at a table, so going by the enchanting component alone would have left both out. The
     * component is still checked for the handful of enchantable things that never wear down, and
     * books are in because combining two of them is a normal thing to do at an anvil.
     */
    private static boolean enchantable(Item item) {
        if (item == Items.ENCHANTED_BOOK) return true;

        ComponentMap components = item.getComponents();
        return components.get(DataComponentTypes.MAX_DAMAGE) != null
            || components.get(DataComponentTypes.ENCHANTABLE) != null;
    }

    @Override
    public void onActivate() {
        timer = 0;
        settle = 0;
        notified = false;
        xpNotified = false;
        refused.clear();
        exhausted.clear();
        forgetAnvil();
    }

    @Override
    public void onDeactivate() {
        onActivate();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        watchForReplacement();

        if (!(mc.currentScreen instanceof AnvilScreen)) {
            // A closed anvil is a clean slate: your inventory may be entirely different by the
            // time you open the next one.
            notified = false;
            xpNotified = false;
            settle = 0;
            refused.clear();
            exhausted.clear();
            return;
        }

        if (!(mc.player.currentScreenHandler instanceof AnvilScreenHandler anvil)) return;
        if (mc.getNetworkHandler() == null) return;

        if (timer < tickRate.get()) {
            timer++;
            return;
        }
        timer = 0;

        if (itemList.get().isEmpty()) {
            report("Pick some items to enchant first§c..!");
            return;
        }

        ItemStack input1 = anvil.getSlot(INPUT_1).getStack();
        ItemStack input2 = anvil.getSlot(INPUT_2).getStack();
        ItemStack output = anvil.getSlot(OUTPUT).getStack();

        if (!output.isEmpty()) {
            takeOutput(anvil, input2);
            return;
        }

        if (input1.isEmpty()) {
            // A book sitting on its own is left over from a pairing that was pulled apart.
            if (!input2.isEmpty()) {
                InvUtils.shiftClick().slotId(INPUT_2);
                return;
            }

            int slot = findTarget(anvil);
            if (slot < 0) {
                finished();
                return;
            }

            refused.clear();
            settle = 0;
            InvUtils.shiftClick().slotId(slot);
            return;
        }

        if (input2.isEmpty()) {
            int slot = findBook(anvil, input1);
            if (slot < 0) {
                // Nothing left that this one can take. Put it back and move on.
                exhausted.add(signature(input1));
                refused.clear();
                InvUtils.shiftClick().slotId(INPUT_1);
                return;
            }

            settle = 0;
            InvUtils.shiftClick().slotId(slot);
            return;
        }

        /*
         * Both slots filled and still no output.
         *
         * Either the server has not caught up yet or the anvil will not take this pairing at all,
         * and the two look identical from here, so it is given a moment before being called a
         * refusal. "Too Expensive" lands here as well, which is the behaviour you want: the book
         * is set aside and a cheaper one is tried instead of the run stopping.
         */
        if (++settle < SETTLE) return;

        settle = 0;
        refused.add(signature(input2));
        InvUtils.shiftClick().slotId(INPUT_2);
    }

    /** Takes the result, unless it costs more than you have or more than you allowed. */
    private void takeOutput(AnvilScreenHandler anvil, ItemStack book) {
        if (mc.player == null) return;

        int cost = ((AnvilScreenHandlerAccessor) anvil).getLevelCost().get();

        if (cost > maxCost.get()) {
            report("That pairing wants §f" + cost + "§c levels, skipping it..!");
            refused.add(signature(book));
            InvUtils.shiftClick().slotId(INPUT_2);
            return;
        }

        if (mc.player.experienceLevel - cost < keepLevels.get() || mc.player.experienceLevel < cost) {
            if (waitForXp.get()) {
                if (!xpNotified) {
                    MsgUtil.updateModuleMsg("Waiting for level §f" + cost + "§e..!", this.name, XP_MSG_ID);
                    xpNotified = true;
                }
                return;
            }

            report("Not enough experience§c..!");
            if (disableOnDone.get()) this.toggle();
            return;
        }

        xpNotified = false;
        notified = false;
        settle = 0;
        refused.clear();
        InvUtils.shiftClick().slotId(OUTPUT);
    }

    /**
     * The first item in your inventory that a book you are carrying would improve.
     *
     * A candidate is only chosen if a book for it exists, so the next step never has to deal with
     * having picked up something it cannot help.
     */
    private int findTarget(AnvilScreenHandler anvil) {
        for (int n = INVENTORY_START; n < INVENTORY_START + INVENTORY_SIZE; n++) {
            ItemStack stack = anvil.getSlot(n).getStack();
            if (stack.isEmpty() || !itemList.get().contains(stack.getItem())) continue;
            if (stack.getCount() != 1) continue;
            if (exhausted.contains(signature(stack))) continue;

            if (findBook(anvil, stack) >= 0) return n;
        }
        return -1;
    }

    /** The first book in your inventory that would add something to this item. */
    private int findBook(AnvilScreenHandler anvil, ItemStack target) {
        int from = booksFromInventory.get() ? INVENTORY_START : HOTBAR_START;

        for (int n = from; n < INVENTORY_START + INVENTORY_SIZE; n++) {
            ItemStack stack = anvil.getSlot(n).getStack();
            if (!stack.isOf(Items.ENCHANTED_BOOK)) continue;
            if (refused.contains(signature(stack))) continue;

            if (improves(stack, target)) return n;
        }
        return -1;
    }

    /**
     * Whether this book has anything to give this item.
     *
     * The three cheap questions, in order: is it an enchantment you asked for, would it beat what
     * the item already has, and will the game let the two go together at all. Anything past that
     * is the anvil's business.
     */
    private boolean improves(ItemStack book, ItemStack target) {
        Object2IntMap<RegistryEntry<Enchantment>> offered = new Object2IntArrayMap<>();
        Utils.getEnchantments(book, offered);
        if (offered.isEmpty()) return false;

        Object2IntMap<RegistryEntry<Enchantment>> already = new Object2IntArrayMap<>();
        Utils.getEnchantments(target, already);

        Set<RegistryKey<Enchantment>> keys = wanted.get();
        boolean anyEnchantment = keys == null || keys.isEmpty();
        boolean targetIsBook = target.isOf(Items.ENCHANTED_BOOK);

        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : offered.object2IntEntrySet()) {
            RegistryEntry<Enchantment> ench = entry.getKey();

            if (!anyEnchantment) {
                Optional<RegistryKey<Enchantment>> key = ench.getKey();
                if (key.isEmpty() || !keys.contains(key.get())) continue;
            }

            // Nothing to gain from a book the item already matches or beats.
            if (entry.getIntValue() <= already.getInt(ench)) continue;

            // A book takes any enchantment; anything else has to actually accept it.
            if (!targetIsBook && !ench.value().isAcceptableItem(target)) continue;

            if (conflicts(ench, already)) continue;

            return true;
        }

        return false;
    }

    /** Whether this enchantment is at odds with something the item already carries. */
    private static boolean conflicts(RegistryEntry<Enchantment> ench, Object2IntMap<RegistryEntry<Enchantment>> already) {
        for (RegistryEntry<Enchantment> existing : already.keySet()) {
            if (existing.equals(ench)) continue;
            if (!Enchantment.canBeCombined(ench, existing)) return true;
        }
        return false;
    }

    /**
     * A short description of what a stack is and what is on it.
     *
     * Used to set things aside for the rest of a run. Stacks are rebuilt every time the server
     * syncs a slot, so the object itself is no good as a marker, but what it is and what it
     * carries is stable and is exactly what the decision was made about. It also means an item
     * comes back into play the moment a book changes it, since changing it changes this.
     */
    private static String signature(ItemStack stack) {
        if (stack.isEmpty()) return "";

        Object2IntMap<RegistryEntry<Enchantment>> enchants = new Object2IntArrayMap<>();
        Utils.getEnchantments(stack, enchants);

        List<String> parts = new ArrayList<>(enchants.size());
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : enchants.object2IntEntrySet()) {
            parts.add(entry.getKey().getKey().map(key -> key.getValue().toString()).orElse("?")
                + ":" + entry.getIntValue());
        }
        parts.sort(null);

        return Registries.ITEM.getId(stack.getItem()) + "|" + String.join(",", parts);
    }

    private void finished() {
        report("Nothing left to enchant§a..!");
        if (disableOnDone.get()) this.toggle();
    }

    /** One message per stretch of the same situation, rather than one a tick. */
    private void report(String message) {
        if (notified) return;
        notified = true;

        MsgUtil.sendModuleMsg(message, this.name);
        if (soundPing.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pingVolume.get() / 100.0F, 1.0f);
        }
    }

    // Broken anvils

    /**
     * Opens whatever anvil lands where the last one broke.
     *
     * Combining books wears an anvil out quickly, so this matters more here than anywhere else.
     * The break is read from the block rather than from the screen, because the screen is not a
     * reliable signal in either direction: the server leaves it open over an empty space, and
     * closing it yourself leaves the anvil standing.
     */
    private void watchForReplacement() {
        if (!reopenAnvil.get() || mc.player == null || mc.world == null) return;

        BlockPos used = FastRename.lastAnvil();
        if (mc.currentScreen instanceof AnvilScreen && used != null
            && !used.equals(anvilPos) && isAnvilBlock(used)) {
            anvilPos = used;
            anvilBroke = false;
            sinceBroke = 0;
            watchLeft = 0;
        }

        if (anvilPos == null) return;

        boolean standing = isAnvilBlock(anvilPos);

        if (!anvilBroke) {
            if (standing) return;

            anvilBroke = true;
            sinceBroke = 0;
            watchLeft = REOPEN_WINDOW;
        }

        if (!standing) {
            // Nothing closes the screen when the anvil under it is destroyed, so it is closed
            // here rather than left clicking into a table that is not there.
            if (mc.currentScreen instanceof AnvilScreen) mc.player.closeHandledScreen();

            sinceBroke++;
            if (--watchLeft <= 0) {
                MsgUtil.sendModuleMsg("No anvil replaced the broken one§c..!", this.name);
                forgetAnvil();
            }
            return;
        }

        if (mc.currentScreen instanceof AnvilScreen) {
            anvilBroke = false;
            return;
        }

        // Any other screen is yours.
        if (mc.currentScreen != null) return;

        sinceBroke++;
        if (--watchLeft <= 0) {
            MsgUtil.sendModuleMsg("Could not open the replacement anvil§c..!", this.name);
            forgetAnvil();
            return;
        }

        if (sinceBroke < reopenDelay.get()) return;

        clickAnvil(anvilPos);
        sinceBroke = 0;
    }

    private void clickAnvil(BlockPos pos) {
        if (mc.player == null || mc.interactionManager == null) return;

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        Runnable action = () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (reopenRotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(Vec3d.ofCenter(pos)), 100, action);
        }
        else action.run();
    }

    private void forgetAnvil() {
        anvilPos = null;
        anvilBroke = false;
        sinceBroke = 0;
        watchLeft = 0;
    }

    private static boolean isAnvilBlock(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return false;

        Block block = client.world.getBlockState(pos).getBlock();
        return block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL;
    }
}
