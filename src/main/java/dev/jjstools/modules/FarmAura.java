package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import dev.jjstools.util.Plantables;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Tills the ground around you and plants a chosen crop on every bit of empty farmland you can
 * reach.
 *
 * Tilling was its own module, Tiller, and the two were always run together, so it lives here now
 * as its own group. Dirt is ploughed with a hoe, the farmland that makes is planted the tick
 * after, and the two keep separate limits on how much they do a tick.
 *
 * Hoe and seeds are swapped in silently and each click turns to face the block through Rotations,
 * so the server sees a believable look direction rather than crops appearing behind your back.
 */
public class FarmAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTill = settings.createGroup("Till");

    private final Setting<List<Item>> crops = sgGeneral.add(new ItemListSetting.Builder()
        .name("crops")
        .description("What to plant. The first one you are carrying is used.")
        .defaultValue(Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS)
        // Only what will actually go in farmland. Saplings, sugar cane, nether wart and the rest
        // are plantable somewhere, just not here, so listing them only wastes your time.
        .filter(Plantables::isFarmlandCrop)
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

    // Till

    private final Setting<Boolean> till = sgTill.add(new BoolSetting.Builder()
        .name("till")
        .description("Till dirt and grass in reach into farmland with a hoe from your inventory, ready to plant.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> tillsPerTick = sgTill.add(new IntSetting.Builder()
        .name("tills-per-tick")
        .description("How many blocks to till each tick. High values send a lot of packets.")
        .defaultValue(2).min(1).sliderRange(1, 10)
        .visible(till::get)
        .build()
    );

    private final Setting<Integer> minDurability = sgTill.add(new IntSetting.Builder()
        .name("minimum-durability")
        .description("Stop tilling when the hoe has this much durability left, so it never breaks in your hand.")
        .defaultValue(10).min(0).sliderRange(0, 200)
        .visible(till::get)
        .build()
    );

    private final Setting<Boolean> skyOnly = sgTill.add(new BoolSetting.Builder()
        .name("open-to-sky-only")
        .description("Only till blocks with open sky above them, so you don't plough out your own base floor.")
        .defaultValue(true)
        .visible(till::get)
        .build()
    );

    /** So running out of hoes is said once, not every tick. */
    private boolean warnedNoHoe;

    public FarmAura() {
        super(JJsTools.CATEGORY, "farm-aura", "Tills the ground in reach and plants your chosen crop on every empty bit of farmland.");
    }

    @Override
    public void onActivate() {
        warnedNoHoe = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        int tilled = till.get() ? tillAround() : 0;

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

        if (done == 0 && tilled == 0 && disableWhenDone.get()) toggle();
    }

    // Tilling

    /** Tills what it can this tick. Returns how many blocks it tilled. */
    private int tillAround() {
        FindItemResult hoe = InvUtils.find(stack -> stack.getItem() instanceof HoeItem && hasDurability(stack));
        if (!hoe.found()) {
            if (!warnedNoHoe) {
                warning("No hoe with enough durability left, so only planting.");
                warnedNoHoe = true;
            }
            return 0;
        }
        warnedNoHoe = false;

        int done = 0;
        int reach = (int) Math.ceil(range.get());
        BlockPos origin = mc.player.getBlockPos();
        double rangeSq = range.get() * range.get();

        for (int x = -reach; x <= reach && done < tillsPerTick.get(); x++) {
            for (int z = -reach; z <= reach && done < tillsPerTick.get(); z++) {
                for (int y = -reach; y <= reach && done < tillsPerTick.get(); y++) {
                    BlockPos pos = origin.add(x, y, z);

                    if (!tillable(pos)) continue;
                    if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > rangeSq) continue;

                    click(pos, hoe);
                    done++;
                }
            }
        }
        return done;
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

    private void plant(BlockPos farmland, FindItemResult seed) {
        click(farmland, seed);
    }

    /** Right-clicks the top of a block with the item in that slot, swapped in and back silently. */
    private void click(BlockPos target, FindItemResult item) {
        Vec3d hit = Vec3d.ofCenter(target, 1.0);
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, target, false);

        Runnable action = () -> {
            InvUtils.swap(item.slot(), true);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, result);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(Vec3d.ofCenter(target)), 50, action);
        else action.run();
    }
}
