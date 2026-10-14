package dev.jjstools.modules;

import java.util.List;
import dev.jjstools.JJsTools;
import net.minecraft.item.Item;
import dev.jjstools.util.MsgUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.screen.AnvilScreenHandler;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.component.DataComponentTypes;
import dev.jjstools.mixin.accessor.AnvilScreenAccessor;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import dev.jjstools.mixin.accessor.AnvilScreenHandlerAccessor;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.EXPThrower;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Blocks;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * @author Tas [0xTas] <root@0xTas.dev>
 **/
public class FastRename extends Module {
    public FastRename() { super(JJsTools.CATEGORY, "fast-rename","Allows you to automatically rename items in bulk when using anvils."); }

    private final Setting<List<Item>> itemList = settings.getDefaultGroup().add(
        new ItemListSetting.Builder()
            .name("items")
            .description("Items to automatically rename (or exclude from being renamed, if blacklist mode is enabled.)")
            .build()
    );

    private final Setting<String> itemName = settings.getDefaultGroup().add(
        new StringSetting.Builder()
            .name("custom-name")
            .description("The name you want to give to qualifying items.")
            .defaultValue("")
            .onChanged(name -> {
                if (name.length() > AnvilScreenHandler.MAX_NAME_LENGTH) {
                    MsgUtil.sendModuleMsg("§4Custom name exceeds max accepted length§8..!", this.name);
                }
            })
            .build()
    );

    private final Setting<Boolean> blacklistMode = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("blacklist-mode")
            .description("Rename all items except the ones selected in the Items list.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> renameNamed = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("rename-prenamed")
            .description("Rename items which already have a different custom name.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> muteAnvils = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("mute-anvils")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> pingOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("sound-ping")
            .description("Play a sound cue when no more items can be renamed.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Double> pingVolume = settings.getDefaultGroup().add(
        new DoubleSetting.Builder()
            .name("ping-volume")
            .sliderMin(0.0)
            .sliderMax(5.0)
            .defaultValue(0.5)
            .build()
    );

    private final Setting<Boolean> disableOnDone = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("disable-on-done")
            .description("Automatically disable the module when no more items can be renamed.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> enableExpThrower = settings.getDefaultGroup().add(
        new BoolSetting.Builder()
            .name("enable-exp-thrower")
            .description("Automatically enable the Exp Thrower module when no more items can be renamed.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Integer> tickRate = settings.getDefaultGroup().add(
        new IntSetting.Builder()
            .name("tick-rate")
            .description("Increase this if the server is kicking you.")
            .min(0).max(20)
            .sliderRange(1, 20)
            .defaultValue(1)
            .build()
    );

    private final SettingGroup sgRecovery = settings.createGroup("Broken Anvil");

    private final Setting<Boolean> reopenAnvil = sgRecovery.add(
        new BoolSetting.Builder()
            .name("reopen-anvil")
            .description("When the anvil you are using breaks, open the one that drops into its place and carry on.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Integer> reopenDelay = sgRecovery.add(
        new IntSetting.Builder()
            .name("reopen-delay")
            .description("Ticks to wait after the anvil breaks before clicking the replacement. Long enough for the one above to finish falling.")
            .visible(reopenAnvil::get)
            .min(0).max(60)
            .sliderRange(0, 20)
            .defaultValue(4)
            .build()
    );
    private final Setting<Boolean> reopenRotate = sgRecovery.add(
        new BoolSetting.Builder()
            .name("silent-rotate")
            .description("Turn towards the anvil for the click without moving your view. Off sends the click as you are facing.")
            .visible(reopenAnvil::get)
            .defaultValue(true)
            .build()
    );

    /**
     * Size of the main inventory, hotbar included.
     *
     * Upstream reads PlayerInventory.main directly. That field was public on 1.21.4 and is private
     * now, so the number is written out instead.
     */
    private static final int MAIN_INVENTORY_SIZE = 36;

    private int timer = 0;
    private boolean notified = false;
    private static final int ANVIL_OFFSET = 3;

    /** How long to keep watching for a replacement before giving up, in ticks. */
    private static final int REOPEN_WINDOW = 100;

    private static BlockPos lastAnvilUsed;

    private BlockPos anvilPos;
    private boolean anvilBroke;
    private int sinceBroke;
    private int watchLeft;

    // See WorldMixin.java
    public boolean shouldMute() { return muteAnvils.get(); }

    private boolean hasValidItems(AnvilScreenHandler handler) {
        if (mc.player == null) return false;
        for (int n = 0; n < MAIN_INVENTORY_SIZE + ANVIL_OFFSET; n++) {
            if (n == AnvilScreenHandler.OUTPUT_ID) continue;
            ItemStack stack = handler.getSlot(n).getStack();
            if ((blacklistMode.get() && !itemList.get().contains(stack.getItem()))
                || (!blacklistMode.get() && itemList.get().contains(stack.getItem())))
            {
                if (itemName.get().isBlank() && stack.contains(DataComponentTypes.CUSTOM_NAME)) {
                    return renameNamed.get();
                } else if (!stack.getName().getString().equals(itemName.get())) {
                    return renameNamed.get() || !stack.contains(DataComponentTypes.CUSTOM_NAME);
                }
            }
        }
        return false;
    }

    private void noXP() {
        if (mc.player == null) return;
        if (!notified) {
            MsgUtil.sendModuleMsg("Not enough experience§c..!", this.name);
            if (pingOnDone.get()) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pingVolume.get().floatValue(), 1.0f);
        }
        notified = true;
        forgetAnvil();
        mc.player.closeHandledScreen();
        if (disableOnDone.get()) this.toggle();
        if (enableExpThrower.get() && !Modules.get().isActive(EXPThrower.class)) Modules.get().get(EXPThrower.class).toggle();
    }

    private void finished() {
        if (mc.player == null) return;
        if (!notified) {
            MsgUtil.sendModuleMsg("No more items to rename§a..!", this.name);
            if (pingOnDone.get()) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pingVolume.get().floatValue(), 1.0f);
        }
        notified = true;
        forgetAnvil();
        mc.player.closeHandledScreen();
        if (disableOnDone.get()) this.toggle();
    }

    @Override
    public void onDeactivate() {
        timer = 0;
        notified = false;
        forgetAnvil();
    }

    private void forgetAnvil() {
        anvilPos = null;
        anvilBroke = false;
        sinceBroke = 0;
        watchLeft = 0;
    }


    /**
     * Remembers the last anvil you right clicked, whether or not the module is on.
     *
     * Called from InteractBlockMixin rather than Meteor's event so the position is already known
     * when you switch the module on with the anvil screen in front of you, which is how it is
     * usually switched on.
     */
    public static void onBlockUsed(BlockPos pos) {
        if (isAnvilBlock(pos)) lastAnvilUsed = pos.toImmutable();
    }

    private static boolean isAnvilBlock(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return false;

        Block block = client.world.getBlockState(pos).getBlock();
        return block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL;
    }

    /**
     * Opens the anvil that lands where the last one broke.
     *
     * Anvils are stacked so the next one falls into the gap, which takes a moment, hence the wait.
     * The break is read from the block itself, never from the screen, because the screen is not a
     * reliable signal in either direction: closing it yourself leaves the anvil standing, and the
     * server leaves it open over a gap where the anvil used to be.
     */
    private void watchForReplacement() {
        if (!reopenAnvil.get() || mc.player == null || mc.world == null) return;

        // Pick up a different anvil if you opened one.
        if (mc.currentScreen instanceof AnvilScreen && lastAnvilUsed != null
            && !lastAnvilUsed.equals(anvilPos) && isAnvilBlock(lastAnvilUsed)) {
            anvilPos = lastAnvilUsed;
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
            /*
             * Nothing closes the screen when the anvil under it is destroyed, so it is closed here.
             * Left open, the renaming loop carries on shift clicking into a table that is not there
             * and the first sign anything is wrong is a screen full of items that will not move.
             */
            if (mc.currentScreen instanceof AnvilScreen) mc.player.closeHandledScreen();

            sinceBroke++;
            if (--watchLeft <= 0) {
                MsgUtil.sendModuleMsg("No anvil replaced the broken one§c..!", this.name);
                forgetAnvil();
            }
            return;
        }

        // An anvil is standing there again. With the screen up, we are back in business.
        if (mc.currentScreen instanceof AnvilScreen) {
            anvilBroke = false;
            return;
        }

        // Any other screen is yours, and clicking the anvil out from under it would be rude.
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

    /**
     * Clicks the anvil where it stands.
     *
     * The packet is sent straight out rather than through a raycast, so the click still lands when
     * something is in the way. Range is still the server's to enforce.
     */
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

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        watchForReplacement();

        if (mc.currentScreen == null) {
            notified = false;
            return;
        }
        if (mc.getNetworkHandler() == null) return;
        if (!(mc.currentScreen instanceof AnvilScreen anvilScreen)) return;
        if (!(mc.player.currentScreenHandler instanceof AnvilScreenHandler anvil)) return;

        if (timer < tickRate.get()) {
            timer++;
            return;
        } else {
            timer = 0;
        }

        ItemStack input1 = anvil.getSlot(AnvilScreenHandler.INPUT_1_ID).getStack();
        ItemStack input2 = anvil.getSlot(AnvilScreenHandler.INPUT_2_ID).getStack();
        ItemStack output = anvil.getSlot(AnvilScreenHandler.OUTPUT_ID).getStack();

        if (!hasValidItems(anvil)) finished();
        else if (input1.isEmpty() && input2.isEmpty()) {
            // fill input 1
            for (int n = ANVIL_OFFSET; n < MAIN_INVENTORY_SIZE + ANVIL_OFFSET; n++) {
                ItemStack stack = anvil.getSlot(n).getStack();
                if ((blacklistMode.get() && !itemList.get().contains(stack.getItem()))
                    || (!blacklistMode.get() && itemList.get().contains(stack.getItem())))
                {
                    if (stack.getName().getString().equals(itemName.get())) continue;
                    if (stack.contains(DataComponentTypes.CUSTOM_NAME) && !renameNamed.get()) continue;
                    if (itemName.get().isBlank() && !stack.contains(DataComponentTypes.CUSTOM_NAME)) continue;

                    InvUtils.shiftClick().slotId(n);
                    return;
                }
            }
            // no more valid items
            finished();
        } else if (!output.isEmpty() && itemList.get().contains(output.getItem())) {
            // take output
            if (output.getName().getString().equals(itemName.get()) || (itemName.get().isBlank() && input1.contains(DataComponentTypes.CUSTOM_NAME))) {
                int cost = ((AnvilScreenHandlerAccessor) anvil).getLevelCost().get();
                if (mc.player.experienceLevel >= cost) {
                    InvUtils.shiftClick().slotId(AnvilScreenHandler.OUTPUT_ID);
                } else noXP();
            }
        } else if (!input2.isEmpty()) {
            // input 2 shouldn't be filled but correct it if so
            InvUtils.shiftClick().slotId(AnvilScreenHandler.INPUT_2_ID);
            ((AnvilScreenAccessor) anvilScreen).getNameField().setText(itemName.get());
        } else if (output.isEmpty()) {
            /*
             * See AnvilScreenMixin.java
             * The server lets you rename multiple stacks from a single RenameItemC2SPacket,
             * as long as the AnvilScreen is prevented from sending additional rename packets when moving stacks.
             * Occasionally the output slot fails to update, so we refresh the slot manually here and just try again.
             **/
            if (((AnvilScreenAccessor) anvilScreen).getNameField().getText().equals(itemName.get())) {
                InvUtils.shiftClick().slotId(AnvilScreenHandler.INPUT_1_ID);
            } else {
                ((AnvilScreenAccessor) anvilScreen).getNameField().setText(itemName.get());
            }
        }
    }
}
