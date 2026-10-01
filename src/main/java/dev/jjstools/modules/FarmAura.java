package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Plants a chosen crop on every bit of empty farmland you can reach.
 *
 * Pairs with Tiller: run that first, then this. Seeds are swapped in silently and each placement
 * turns to face the block through Rotations, so the server sees a believable look direction rather
 * than crops appearing behind your back.
 */
public class FarmAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Item>> crops = sgGeneral.add(new ItemListSetting.Builder()
        .name("crops")
        .description("What to plant. The first one you are carrying is used.")
        .defaultValue(Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far to reach. Over about 4.5 the server will refuse the placement.")
        .defaultValue(4.0).min(1).sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> perTick = sgGeneral.add(new IntSetting.Builder()
        .name("plants-per-tick")
        .description("How many to plant each tick. High values send a lot of packets.")
        .defaultValue(2).min(1).sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Face each block as you plant. Leave on: silent instant placements are what anticheats look for.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableWhenDone = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-done")
        .description("Switch off once there is nothing left to plant or you run out of seeds.")
        .defaultValue(false)
        .build()
    );

    public FarmAura() {
        super(JJsTools.CATEGORY, "farm-aura", "Plants your chosen crop on every empty bit of farmland in reach.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        FindItemResult seed = InvUtils.find(stack -> crops.get().contains(stack.getItem()));
        if (!seed.found()) {
            if (disableWhenDone.get()) {
                error("Out of seeds.");
                toggle();
            }
            return;
        }

        int done = 0;
        int reach = (int) Math.ceil(range.get());
        BlockPos origin = mc.player.getBlockPos();
        double rangeSq = range.get() * range.get();

        for (int x = -reach; x <= reach && done < perTick.get(); x++) {
            for (int z = -reach; z <= reach && done < perTick.get(); z++) {
                for (int y = -reach; y <= reach && done < perTick.get(); y++) {
                    BlockPos farmland = origin.add(x, y, z);

                    if (mc.world.getBlockState(farmland).getBlock() != Blocks.FARMLAND) continue;
                    // The crop goes in the air above the farmland, so that has to be free.
                    if (!mc.world.getBlockState(farmland.up()).isAir()) continue;
                    if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(farmland)) > rangeSq) continue;

                    plant(farmland, seed);
                    done++;
                }
            }
        }

        if (done == 0 && disableWhenDone.get()) toggle();
    }

    private void plant(BlockPos farmland, FindItemResult seed) {
        Vec3d hit = Vec3d.ofCenter(farmland, 1.0);
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, farmland, false);

        Runnable action = () -> {
            InvUtils.swap(seed.slot(), true);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, result);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(farmland), Rotations.getPitch(Vec3d.ofCenter(farmland)), 50, action);
        else action.run();
    }
}
