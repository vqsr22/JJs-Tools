package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Tills everything around you into farmland.
 *
 * Swaps to a hoe silently, turns to each block with Rotations so the server sees a sane look
 * direction, and stops before the hoe breaks.
 */
public class Tiller extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far to reach. Over about 4.5 the server will refuse the interaction.")
        .defaultValue(4.0).min(1).sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to till each tick. High values send a lot of packets.")
        .defaultValue(2).min(1).sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> minDurability = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-durability")
        .description("Stop when the hoe has this much durability left, so it never breaks in your hand.")
        .defaultValue(10).min(0).sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> skyOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("open-to-sky-only")
        .description("Only till blocks with open sky above them, so you don't plough out your own base floor.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Face each block as you till it. Leave on: silent instant tills are what anticheats look for.")
        .defaultValue(true)
        .build()
    );

    public Tiller() {
        super(JJsTools.CATEGORY, "tiller", "Tills all nearby dirt and grass into farmland with a hoe from your inventory.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        FindItemResult hoe = InvUtils.find(stack -> stack.getItem() instanceof HoeItem && hasDurability(stack));
        if (!hoe.found()) {
            error("No hoe with enough durability left.");
            toggle();
            return;
        }

        int done = 0;
        int reach = (int) Math.ceil(range.get());
        BlockPos origin = mc.player.getBlockPos();
        double rangeSq = range.get() * range.get();

        for (int x = -reach; x <= reach && done < blocksPerTick.get(); x++) {
            for (int z = -reach; z <= reach && done < blocksPerTick.get(); z++) {
                for (int y = -reach; y <= reach && done < blocksPerTick.get(); y++) {
                    BlockPos pos = origin.add(x, y, z);

                    if (!tillable(pos)) continue;
                    if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > rangeSq) continue;

                    till(pos, hoe);
                    done++;
                }
            }
        }
    }

    private boolean hasDurability(ItemStack stack) {
        if (!stack.isDamageable()) return true;
        return stack.getMaxDamage() - stack.getDamage() > minDurability.get();
    }

    private boolean tillable(BlockPos pos) {
        Block block = mc.world.getBlockState(pos).getBlock();

        boolean right = block == Blocks.DIRT
            || block == Blocks.GRASS_BLOCK
            || block == Blocks.COARSE_DIRT
            || block == Blocks.ROOTED_DIRT
            || block == Blocks.DIRT_PATH;
        if (!right) return false;

        // A hoe needs the top face clear, same as doing it by hand.
        if (!mc.world.getBlockState(pos.up()).isAir()) return false;

        return !skyOnly.get() || mc.world.isSkyVisible(pos.up());
    }

    private void till(BlockPos pos, FindItemResult hoe) {
        Vec3d hit = Vec3d.ofCenter(pos, 1.0);
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, pos, false);

        Runnable action = () -> {
            InvUtils.swap(hoe.slot(), true);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, result);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(Vec3d.ofCenter(pos)), 50, action);
        else action.run();
    }
}
