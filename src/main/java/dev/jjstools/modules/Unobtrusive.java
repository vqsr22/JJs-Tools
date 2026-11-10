package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Gets the things that sit in the middle of your screen out of the way.
 *
 * Every one of these is something the game draws right where you are trying to look: fire
 * licking up over the crosshair, a shield taking up a quarter of the view, a rocket trail in
 * your face on every boost, experience bottles bursting on your own head while you mend. None
 * of it is information you need in that spot, so each can be moved, hidden or calmed down on
 * its own.
 *
 * All of it is drawing only. Nothing here changes what happens, only what you see of it.
 */
public class Unobtrusive extends Module {
    private final SettingGroup sgOverlays = settings.createGroup("Overlays");
    private final SettingGroup sgHands = settings.createGroup("Hands");
    private final SettingGroup sgSwing = settings.createGroup("Swing & Equip");
    private final SettingGroup sgParticles = settings.createGroup("Particles");
    private final SettingGroup sgThrown = settings.createGroup("Thrown Items");

    // Overlays

    private final Setting<Double> fireHeight = sgOverlays.add(new DoubleSetting.Builder()
        .name("fire-height")
        .description("Move the burning overlay up or down the screen. Negative lowers it out of your view; zero is vanilla.")
        .defaultValue(-0.3)
        .min(-1.0).max(1.0)
        .sliderRange(-1.0, 1.0)
        .build()
    );

    private final Setting<Boolean> hidePumpkin = sgOverlays.add(new BoolSetting.Builder()
        .name("hide-pumpkin-overlay")
        .description("Remove the blur you get while wearing a carved pumpkin.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hidePortal = sgOverlays.add(new BoolSetting.Builder()
        .name("hide-portal-overlay")
        .description("Remove the purple swirl while you are standing in a nether portal.")
        .defaultValue(true)
        .build()
    );

    // Hands

    private final Setting<Double> shieldHeight = sgHands.add(new DoubleSetting.Builder()
        .name("shield-height")
        .description("Move a held shield up or down. Negative lowers it, which frees up the side of the screen it normally covers; zero is vanilla.")
        .defaultValue(-0.15)
        .min(-1.0).max(1.0)
        .sliderRange(-1.0, 1.0)
        .build()
    );

    private final Setting<Integer> swingDistance = sgSwing.add(new IntSetting.Builder()
        .name("swing-cooldown-distance")
        .description("How far a held item dips and rises after a swing while the attack cooldown fills back up, as a share of vanilla's. 100 is vanilla; 0 keeps it still.")
        .defaultValue(100)
        .min(0).max(100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> scaleSwingTime = sgSwing.add(new BoolSetting.Builder()
        .name("scale-swing-cooldown-animation-time")
        .description("Shrink the swing dip to fit its distance, so the item moves more slowly over the shorter dip and takes as long as vanilla's. Off cuts it off at the distance instead, so the item drops at full speed, waits there, then rises.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> equipDistance = sgSwing.add(new IntSetting.Builder()
        .name("equip-cooldown-distance")
        .description("How far the held item drops when you switch to another item, and how far the new one rises from, as a share of vanilla's. Also covers your offhand. 100 is vanilla; 0 swaps in place.")
        .defaultValue(100)
        .min(0).max(100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Boolean> scaleEquipTime = sgSwing.add(new BoolSetting.Builder()
        .name("scale-equip-cooldown-animation-time")
        .description("Shrink the switch animation to fit its distance, so it moves more slowly and takes as long as vanilla's. Off cuts it off at the distance instead, so the item drops at full speed, waits there, then rises.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> centreFood = sgHands.add(new BoolSetting.Builder()
        .name("centre-food")
        .description("While eating or drinking, hold the item level in the middle of the screen instead of tipped over to one side. The bob and the lift to your mouth are kept.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> eatAngle = sgHands.add(new DoubleSetting.Builder()
        .name("eat-angle")
        .description("How far the item is turned while you eat, in degrees, so it still reads as something held rather than a picture stuck to the screen. Zero faces it straight at you.")
        .defaultValue(20.0)
        .min(0.0).max(60.0)
        .sliderRange(0.0, 45.0)
        .visible(centreFood::get)
        .build()
    );

    private final Setting<Double> eatOffsetX = sgHands.add(new DoubleSetting.Builder()
        .name("eat-offset-x")
        .description("Nudge the food left or right while you eat. Straight across the screen, whatever else is moving your hands.")
        .defaultValue(0.0)
        .min(-1.0).max(1.0)
        .sliderRange(-1.0, 1.0)
        .visible(centreFood::get)
        .build()
    );

    private final Setting<Double> eatOffsetY = sgHands.add(new DoubleSetting.Builder()
        .name("eat-offset-y")
        .description("Nudge the food up or down while you eat. Straight up the screen, whatever else is moving your hands.")
        .defaultValue(0.0)
        .min(-1.0).max(1.0)
        .sliderRange(-1.0, 1.0)
        .visible(centreFood::get)
        .build()
    );

    private final Setting<Double> eatBobSpeed = sgHands.add(new DoubleSetting.Builder()
        .name("eat-bob-speed")
        .description("How fast food and drink bob up and down while you eat. 1 is vanilla, 0.5 half as fast, 0 holds it still.")
        .defaultValue(1.0)
        .min(0.0).max(1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private final Setting<Double> eatBobHeight = sgHands.add(new DoubleSetting.Builder()
        .name("eat-bob-height")
        .description("How far food and drink bob up and down while you eat. 1 is vanilla, 0.5 half as far, 0 not at all.")
        .defaultValue(1.0)
        .min(0.0).max(1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    /**
     * Vanilla's eating bob is the size of a cosine of the time spent eating. Slowing the time it
     * is given by eat-bob-speed slows the bob, and scaling what comes back by eat-bob-height
     * makes it smaller, so the two are set apart.
     */
    public static float eatBob(double angle, java.util.function.DoubleUnaryOperator cos) {
        Unobtrusive m = active();
        if (m == null) return (float) cos.applyAsDouble(angle);
        double speed = m.eatBobSpeed.get();
        // At speed 0 it rests at the top of the bob, where vanilla starts, rather than wherever it froze.
        double value = speed <= 0 ? 1.0 : cos.applyAsDouble(angle * speed);
        return (float) (value * m.eatBobHeight.get());
    }

    // Particles

    private final Setting<Boolean> hideFireworks = sgParticles.add(new BoolSetting.Builder()
        .name("hide-fireworks")
        .description("Remove firework sparks and flashes, rocket trails included, which is most of what is in your face while flying.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideOwnPotions = sgParticles.add(new BoolSetting.Builder()
        .name("hide-own-potion-particles")
        .description("Remove the potion swirls coming off you in first person. Other players still show theirs, and so do you in third person.")
        .defaultValue(true)
        .build()
    );

    // Thrown items

    private final Setting<Boolean> hideCloseThrown = sgThrown.add(new BoolSetting.Builder()
        .name("hide-thrown-items-close-to-camera")
        .description("Do not draw thrown items while they are right next to the camera: experience bottles thrown at your own feet to mend, and pearls, potions, snowballs and eggs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> closeDistance = sgThrown.add(new DoubleSetting.Builder()
        .name("close-distance")
        .description("How near the camera a thrown item has to be before it is hidden, in blocks. Once it is further away it shows again, so you can still watch where it lands.")
        .defaultValue(0.5)
        .min(0.0).max(3.0)
        .sliderRange(0.0, 3.0)
        .visible(hideCloseThrown::get)
        .build()
    );

    public Unobtrusive() {
        super(JJsTools.CATEGORY, "unobtrusive",
            "Moves or hides the things the game draws in the middle of your screen: fire, shields, pumpkin and portal overlays, firework and potion particles, thrown items at the camera, and the tilt on food while you eat.");
    }

    private static Unobtrusive active() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        Unobtrusive module = modules.get(Unobtrusive.class);
        return module != null && module.isActive() ? module : null;
    }

    // Overlays

    /** How far to move the fire overlay, or zero for vanilla. */
    public static float fireOffset() {
        Unobtrusive m = active();
        return m == null ? 0.0F : m.fireHeight.get().floatValue();
    }

    public static boolean hidePortal() {
        Unobtrusive m = active();
        return m != null && m.hidePortal.get();
    }

    /**
     * Whether this full-screen overlay is the pumpkin blur.
     *
     * The game draws several overlays through the same call, powdered snow among them, so the
     * texture is what tells them apart.
     */
    public static boolean hideOverlay(Identifier texture) {
        Unobtrusive m = active();
        if (m == null || texture == null || !m.hidePumpkin.get()) return false;

        return texture.getPath().contains("pumpkinblur");
    }

    // Hands

    /** How far to move a held shield, or zero to leave it. */
    public static float shieldOffset(ItemStack stack) {
        Unobtrusive m = active();
        if (m == null || stack == null || !stack.isOf(Items.SHIELD)) return 0.0F;
        return m.shieldHeight.get().floatValue();
    }

    /**
     * The second half of the eating animation, done level and in the middle.
     *
     * Vanilla's first half is a bob, and it is kept as it is. Its second half lifts the item
     * toward your mouth and turns it three ways at once, and it is that turn that leaves food
     * tipped over to one side. Simply taking those turns out does not fix it, which is worth
     * knowing: the item's own model carries a quarter turn and a twenty-five degree roll for
     * the first-person hand, applied after all of this, and without the vanilla turns to offset
     * it the item ends up rolled the other way and dropped off the bottom of the screen.
     *
     * So the turn here is worked out backwards from where the item should end up. Start from
     * level and facing you, turned only by eat-angle so it keeps a little depth, then undo the
     * model's own rotation, and whatever is left is what to apply now. The position is solved
     * the same way: the spot vanilla lifts food to, with the side to side taken out, so it sits
     * dead centre at the same height it always did.
     *
     * Both are eased in by the same curve vanilla uses, so it still rises from your hand rather
     * than appearing in front of you.
     *
     * @return true if this has done the transform and vanilla's should be skipped
     */
    public static boolean centreEating(MatrixStack matrices, float tickDelta, Arm arm, ItemStack stack,
                                       PlayerEntity player) {
        Unobtrusive m = active();
        if (m == null || !m.centreFood.get() || matrices == null || stack == null || player == null) return false;

        int maxUse = stack.getMaxUseTime(player);
        if (maxUse <= 0) return false;

        // Vanilla's own progress and easing, so the timing is untouched.
        float used = player.getItemUseTimeLeft() - tickDelta + 1.0F;
        float progress = used / maxUse;
        float h = 1.0F - (float) Math.pow(progress, 27.0);
        h = Math.max(0.0F, Math.min(1.0F, h));

        int side = arm == Arm.RIGHT ? 1 : -1;

        // The rotation the item model applies for this hand, which this has to see past.
        Quaternionf model = new Quaternionf().rotationXYZ(0.0F,
            (float) Math.toRadians(side * -90.0), (float) Math.toRadians(side * 25.0));

        // Level, facing you, turned only as much as asked.
        Quaternionf wanted = new Quaternionf().rotationY((float) Math.toRadians(-side * m.eatAngle.get()));

        Quaternionf turn = new Quaternionf(wanted).mul(new Quaternionf(model).invert());

        // Where the item already is once the hand offset and the model's own offset are applied.
        Vector3f held = new Vector3f(side * (0.56F + 1.13F / 16.0F), -0.52F + 3.2F / 16.0F, -0.72F + 1.13F / 16.0F);
        Vector3f turned = turn.transform(new Vector3f(held));

        // The middle of the screen, at the height vanilla lifts food to.
        Vector3f shift = new Vector3f(0.0F, -0.35F, -0.70F).sub(turned);

        /*
         * The nudges go in with the move, before the turn, which makes them straight screen
         * directions. Anything nested inside, such as a hand position from another module, is
         * swung round by the turn the same way vanilla swings it, which is why an item can come
         * out off centre on one setup and dead centre on another. These are the way to pull it
         * back without caring what put it there.
         */
        float nudgeX = m.eatOffsetX.get().floatValue();
        float nudgeY = m.eatOffsetY.get().floatValue();

        matrices.translate((shift.x + nudgeX) * h, (shift.y + nudgeY) * h, shift.z * h);
        matrices.multiply(new Quaternionf().slerp(turn, h));
        return true;
    }

    /**
     * What the main hand's current dip is from: a swing, or switching items.
     *
     * Vanilla runs both through one number, so the cause has to be worked out separately. A new
     * slot or a different item means a switch: the old item drops, and the new one rises as the
     * attack cooldown the switch reset fills back up. The cooldown dropping back with the same
     * item still in hand means a swing. Whichever happened last owns the dip until the next.
     */
    private enum Dip { Swing, Equip }

    private Dip mainDip = Dip.Swing;
    private int lastSlot = -1;
    private Item lastItem;
    private float lastCooldown = 1.0F;

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        int slot = dev.jjstools.compat.Compat.selectedSlot(mc.player.getInventory());
        Item item = mc.player.getMainHandStack().getItem();
        float cooldown = mc.player.getAttackCooldownProgress(0.0F);

        if (slot != lastSlot || item != lastItem) mainDip = Dip.Equip;
        else if (cooldown < lastCooldown - 0.0001F) mainDip = Dip.Swing;

        lastSlot = slot;
        lastItem = item;
        lastCooldown = cooldown;
    }

    /**
     * A held item's dip, limited.
     *
     * Arrives as nought with the item fully up and one with it fully down, so capping it caps
     * how far down it can go on the way down and on the way back up. The offhand has no attack
     * cooldown, so its only dip is a switch.
     */
    public static float limitDip(float equipProgress, Hand hand) {
        Unobtrusive m = active();
        if (m == null) return equipProgress;

        boolean equip = hand == Hand.OFF_HAND || m.mainDip == Dip.Equip;
        float cap = (equip ? m.equipDistance.get() : m.swingDistance.get()) / 100.0F;
        boolean scaled = equip ? m.scaleEquipTime.get() : m.scaleSwingTime.get();

        /*
         * Scaled, the whole curve is squeezed into the shorter distance: the item reaches the
         * bottom at the same moment vanilla's would and comes back up on the same timing, only
         * covering less ground, so it moves more slowly to match. Cut off, it races down at
         * vanilla speed, stops dead at the limit, waits out the rest of vanilla's dip and then
         * rises.
         */
        if (scaled) return equipProgress * cap;
        return Math.min(equipProgress, cap);
    }

    // Particles

    /**
     * Whether to drop this particle before it is made.
     *
     * Told apart by registry id rather than by comparing against the particle type constants,
     * so a renamed constant in a later version cannot quietly break the comparison.
     */
    public static boolean hideParticle(ParticleEffect effect, double x, double y, double z) {
        Unobtrusive m = active();
        if (m == null || effect == null) return false;

        Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
        if (id == null) return false;
        String path = id.getPath();

        if (m.hideFireworks.get() && (path.equals("firework") || path.equals("flash"))) return true;

        if (m.hideOwnPotions.get() && path.equals("entity_effect")) return ownSwirl(x, y, z);

        return false;
    }

    /**
     * A potion particle coming off you, while you are looking out through your own eyes.
     *
     * Potion swirls spawn at random points inside the body they belong to, so anything
     * inside your own box, give or take half a block, is yours. Third person is left alone,
     * where they are something you might actually want to see.
     */
    private static boolean ownSwirl(double x, double y, double z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.options == null) return false;
        if (!mc.options.getPerspective().isFirstPerson()) return false;

        Box box = mc.player.getBoundingBox().expand(0.5);
        return box.contains(x, y, z);
    }

    // Thrown items

    /**
     * Whether a thrown item is too close to the camera to be worth drawing.
     *
     * Measured from the camera rather than from you, so in third person, where nothing is in
     * your face, they all show as normal.
     */
    public static boolean hideThrown(Entity entity, double camX, double camY, double camZ) {
        Unobtrusive m = active();
        if (m == null || entity == null) return false;

        // Experience bottles are thrown items too, so one check covers them and the rest.
        if (!m.hideCloseThrown.get() || !(entity instanceof ThrownItemEntity)) return false;

        double range = m.closeDistance.get();
        return entity.squaredDistanceTo(camX, camY, camZ) < range * range;
    }
}
