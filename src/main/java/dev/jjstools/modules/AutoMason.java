package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.MsgUtil;
import dev.jjstools.util.StonecutterUtil;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.recipe.display.CuttingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.context.ContextParameterMap;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Feeds the stonecutter and takes what it makes, until there is nothing left to cut.
 *
 * One step at a time, each one looking at what the screen actually shows now: put a stack in,
 * pick the recipe, take the result. Every click is the game's own, the same as doing it by hand,
 * so the server and anything between it and you, ViaFabricPlus included, see exactly what they
 * would from a person.
 *
 * There used to be a Packet mode as well, which worked the whole inventory out in advance and
 * fired the clicks off from a guess of how each would turn out. Behind a translated connection
 * those guesses do not hold, so it did not work, and it sent clicks fast enough to be kicked for.
 *
 * @author Tas [0xTas] <root@0xTas.dev>
 **/
public class AutoMason extends Module {
    public AutoMason() { super(JJsTools.CATEGORY, "auto-mason", "Automates masonry interactions with the stonecutter."); }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> tickRate = sgGeneral.add(new IntSetting.Builder()
        .name("tick-rate")
        .description("Ticks to wait between steps. Each step is one click. Raise it if the server is kicking you.")
        .min(0).max(100)
        .sliderRange(0, 20)
        .defaultValue(2)
        .build()
    );

    private final Setting<Integer> maxPerSecond = sgGeneral.add(new IntSetting.Builder()
        .name("max-clicks-per-second")
        .description("A hard limit on how many clicks this sends in any one second, whatever tick-rate says. Servers kick for sending too many inventory packets too quickly; 2b2t is strict about it. Lower this if you still get kicked.")
        .min(1).max(40)
        .sliderRange(1, 20)
        .defaultValue(8)
        .build()
    );

    private final Setting<List<Item>> itemList = sgGeneral.add(new ItemListSetting.Builder()
        .name("target-items")
        .description("Which target items you wish to craft in the Stonecutter.")
        .filter(item -> StonecutterUtil.STONECUTTER_BLOCKS.values().stream().anyMatch(v -> v.contains(item)))
        .build()
    );

    private final Setting<Boolean> muteCutter = sgGeneral.add(new BoolSetting.Builder()
        .name("mute-stonecutter")
        .description("Mute the stonecutter sounds.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> closeOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("close-stonecutter")
        .description("Automatically close the stonecutter screen when no more blocks can be crafted.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> disableOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-done")
        .description("Automatically disable the module when no more blocks can be crafted.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pingOnDone = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-ping")
        .description("Play a sound cue when there are no more blocks to be crafted.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> pingVolume = sgGeneral.add(new IntSetting.Builder()
        .name("ping-sound-volume")
        .description("How loud the sound is, from 0 to 100.")
        .defaultValue(50).min(0).max(100).sliderRange(0, 100)
        .visible(pingOnDone::get)
        .build()
    );

    private int timer;
    private int ticks;
    private boolean notified;

    /** When each click in the last second went out, in ticks. Every click has to fit in it. */
    private final ArrayDeque<Integer> sentAt = new ArrayDeque<>();

    @Override
    public void onDeactivate() {
        timer = 0;
        notified = false;
        sentAt.clear();
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (event.screen instanceof StonecutterScreen) notified = false;
    }

    @EventHandler
    private void onSoundPlay(PlaySoundEvent event) {
        if (muteCutter.get() && event.sound.getId().equals(SoundEvents.UI_STONECUTTER_TAKE_RESULT.id())) {
            event.cancel();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ticks++;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (!(mc.player.currentScreenHandler instanceof StonecutterScreenHandler cutter)) return;
        if (notified) return;

        if (timer < tickRate.get()) {
            timer++;
            return;
        }

        if (itemList.get().isEmpty()) {
            MsgUtil.sendModuleMsg("No target items selected§c..!", this.name);
            finished();
            return;
        }

        ItemStack input = cutter.getSlot(StonecutterScreenHandler.INPUT_ID).getStack();
        ItemStack output = cutter.getSlot(StonecutterScreenHandler.OUTPUT_ID).getStack();

        if (!hasValidItems(cutter) && output.isEmpty()) {
            finished();
            return;
        }

        // Over the limit for this second: try again next tick, without resetting the wait.
        if (!spend()) return;
        timer = 0;

        if (!output.isEmpty()) {
            InvUtils.shiftClick().slotId(StonecutterScreenHandler.OUTPUT_ID);
        }
        else if (input.isEmpty()) {
            // One stack in at a time.
            for (int n = 2; n < mc.player.getInventory().getMainStacks().size() + 2; n++) {
                if (!isValidItem(cutter.getSlot(n).getStack())) continue;
                InvUtils.shiftClick().slotId(n);
                break;
            }
        }
        else {
            pickRecipe(cutter, input);
        }
    }

    /** Presses the button for the first wanted result this input can make, as clicking it would. */
    private void pickRecipe(StonecutterScreenHandler cutter, ItemStack input) {
        CuttingRecipeDisplay.Grouping<StonecuttingRecipe> available = ViaStonecutterFix.order(
            mc.world.getRecipeManager().getStonecutterRecipes().filter(input));
        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);

        for (int n = 0; n < available.entries().size(); n++) {
            ItemStack result = available.entries().get(n).recipe().optionDisplay().getFirst(context);
            if (result.isEmpty() || !itemList.get().contains(result.getItem())) continue;

            if (cutter.onButtonClick(mc.player, n)) mc.interactionManager.clickButton(cutter.syncId, n);
            return;
        }

        MsgUtil.sendModuleMsg("Desired recipe not found§c..!", this.name);
        finished();
    }

    /** True if one more click fits in the last second's allowance. Counts it if so. */
    private boolean spend() {
        while (!sentAt.isEmpty() && ticks - sentAt.peekFirst() >= 20) sentAt.removeFirst();
        if (sentAt.size() >= maxPerSecond.get()) return false;
        sentAt.addLast(ticks);
        return true;
    }

    private void finished() {
        if (mc.player == null) return;
        if (!notified) {
            if (chatFeedback) MsgUtil.sendModuleMsg("No more items to craft§a..!", this.name);
            if (pingOnDone.get()) {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, pingVolume.get() / 100.0F,
                    ThreadLocalRandom.current().nextFloat(0.69f, 1.337f));
            }
        }
        notified = true;
        if (closeOnDone.get()) mc.player.closeHandledScreen();
        if (disableOnDone.get()) toggle();
    }

    private boolean hasValidItems(StonecutterScreenHandler handler) {
        for (int n = 0; n < mc.player.getInventory().getMainStacks().size() + 2; n++) {
            if (n == StonecutterScreenHandler.OUTPUT_ID) continue;
            if (isValidItem(handler.getSlot(n).getStack())) return true;
        }
        return false;
    }

    private boolean isValidItem(ItemStack stack) {
        if (itemList.get().isEmpty() || stack.isEmpty()) return false;
        if (!StonecutterUtil.STONECUTTER_BLOCKS.containsKey(stack.getItem())) return false;

        return StonecutterUtil.STONECUTTER_BLOCKS.get(stack.getItem()).stream().anyMatch(item -> itemList.get().contains(item));
    }
}
