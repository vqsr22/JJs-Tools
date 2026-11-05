package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.mixin.accessor.HurtSoundInvoker;
import dev.jjstools.util.KitStore;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays the hit before the server confirms it.
 *
 * Vanilla waits for the server to say a hit landed before it flinches the target or makes a
 * sound, so everything you do arrives back at you one round trip late. This works out what the
 * server is going to say and says it immediately: the flinch and the sound happen on the click,
 * and the server's own copy is swallowed when it turns up so you do not get it twice.
 *
 * Nothing about the hit itself changes. The damage, the reach and the timing are all still the
 * server's to decide, and a hit that misses will still miss. Ported from Better Hitreg by
 * jasspir (Apache-2.0, github.com/jasspir/BetterHitreg); the prediction rules below are its
 * work, rewritten against Yarn.
 */
public class BetterHitreg extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> animation = sgGeneral.add(new BoolSetting.Builder()
        .name("animation")
        .description("Flinch the target the moment you hit, instead of a round trip later.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sounds = sgGeneral.add(new BoolSetting.Builder()
        .name("sounds")
        .description("Play the attack and hurt sound straight away.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> particles = sgGeneral.add(new BoolSetting.Builder()
        .name("particles")
        .description("Throw the crit and sharpness particles immediately as well.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swallowDuplicates = sgGeneral.add(new BoolSetting.Builder()
        .name("swallow-duplicates")
        .description("Drop the server's own copy of a hit you have already been shown. Off gives you an echo one ping behind every hit.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> playersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("players-only")
        .description("Only predict hits on players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait before playing the hit. Zero is the point of the module; raise it only if landing ahead of the server bothers you.")
        .defaultValue(0)
        .min(0)
        .max(10)
        .sliderRange(0, 5)
        .build()
    );

    /**
     * Which of the six things the server is about to say happened.
     *
     * The order matters and is vanilla's: knockback beats a crit, a crit beats a sweep, and
     * anything that lands beats the weak tap you get for swinging too early.
     */
    private enum HitType {
        TOO_EARLY("entity.player.attack.weak"),
        KNOCKBACK("entity.player.attack.knockback", true),
        CRITICAL("entity.player.attack.crit", true),
        SWEEP("entity.player.attack.sweep", true),
        FULL("entity.player.attack.strong", true),
        WEAK(null, true);

        final @Nullable String attackSound;
        final boolean hurts;

        HitType(String attackSound) {
            this(attackSound, false);
        }

        HitType(@Nullable String attackSound, boolean hurts) {
            this.attackSound = attackSound;
            this.hurts = hurts;
        }
    }

    /** Hitting again inside half a second does no damage. Trimmed by half a tick, as the mod does. */
    private static final long DAMAGE_INTERVAL_MS = 475L;

    /** How long after a prediction the server's own copy of it is still recognised. */
    private static final long SWALLOW_WINDOW_MS = 1000L;

    private static final Map<Integer, Long> PREDICTED = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();

    private long lastAttack = 0L;

    /** Set while this module is spawning its own particles, so they are not swallowed. */
    private static boolean ours = false;

    private record Pending(LivingEntity target, HitType type, int ticksLeft) {}

    public BetterHitreg() {
        super(JJsTools.CATEGORY, "better-hitreg",
            "Makes hits feel instant. Shows hits client side before the hit actually registers server side. Note that this does not improve latency.");
    }

    public static BetterHitreg get() {
        return Modules.get().get(BetterHitreg.class);
    }

    @Override
    public void onActivate() {
        lastAttack = 0L;
        pending.clear();
        PREDICTED.clear();
    }

    @Override
    public void onDeactivate() {
        pending.clear();
        PREDICTED.clear();
    }

    // Predicting

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!(event.entity instanceof LivingEntity target)) return;
        if (target instanceof ArmorStandEntity || !target.isAlive()) return;
        if (playersOnly.get() && !(target instanceof PlayerEntity)) return;

        long now = System.currentTimeMillis();
        long since = now - lastAttack;
        boolean tooEarlyForDamage = since < DAMAGE_INTERVAL_MS;

        // Below nine tenths of the cooldown the server gives you the weak version of whatever
        // you would otherwise have got, so none of the special cases can apply.
        boolean tooEarlyForSpecial = mc.player.getAttackCooldownProgress(0.5f) <= 0.9f;

        HitType type = classify(tooEarlyForDamage, tooEarlyForSpecial);

        if (!tooEarlyForDamage) {
            lastAttack = now;
            PREDICTED.put(target.getId(), now);
        }

        if (type == null) return;
        if (delay.get() <= 0) play(target, type);
        else pending.add(new Pending(target, type, delay.get()));
    }

    private @Nullable HitType classify(boolean tooEarlyForDamage, boolean tooEarlyForSpecial) {
        if (tooEarlyForDamage) return HitType.TOO_EARLY;

        /*
         * Sprinting knocks back, falling crits, standing still with a sword sweeps.
         *
         * Sprinting is read from the client rather than from what the server last heard, which
         * is the one place this is a guess: a landed knockback hit stops your sprint server
         * side without telling the client. It costs the odd knockback sound read as a strong
         * one, and nothing else.
         */
        if (!tooEarlyForSpecial && mc.player.isSprinting()) return HitType.KNOCKBACK;

        boolean crit = !tooEarlyForSpecial
            && mc.player.fallDistance > 0
            && !mc.player.isOnGround()
            && !mc.player.isClimbing()
            && !mc.player.isTouchingWater()
            && !mc.player.hasVehicle()
            && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS);
        if (crit) return HitType.CRITICAL;

        boolean sweep = !tooEarlyForSpecial
            && mc.player.getMainHandStack().isIn(ItemTags.SWORDS)
            && mc.player.isOnGround()
            && !movingFast()
            && !mc.options.forwardKey.isPressed();
        if (sweep) return HitType.SWEEP;

        return tooEarlyForSpecial ? HitType.WEAK : HitType.FULL;
    }

    /** Vanilla refuses to sweep while you are moving faster than two and a half times your speed. */
    private boolean movingFast() {
        return mc.player.getMovement().horizontalLengthSquared()
            >= MathHelper.square(mc.player.getMovementSpeed() * 2.5);
    }

    // Playing

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (pending.isEmpty()) return;

        List<Pending> due = new ArrayList<>();
        pending.replaceAll(p -> new Pending(p.target(), p.type(), p.ticksLeft() - 1));
        pending.removeIf(p -> {
            if (p.ticksLeft() > 0) return false;
            due.add(p);
            return true;
        });

        for (Pending p : due) play(p.target(), p.type());
    }

    private void play(LivingEntity target, HitType type) {
        if (mc.world == null || mc.player == null || !target.isAlive()) return;

        if (animation.get() && type.hurts) {
            // Exactly what the damage event does: ten ticks of flinch, counted down by the
            // entity's own tick. Setting it again when the server's copy lands just restarts it.
            target.maxHurtTime = 10;
            target.hurtTime = 10;
        }

        if (particles.get()) {
            if (type == HitType.CRITICAL) spray(ParticleTypes.CRIT, target);
            if (type.hurts && sharpness()) spray(ParticleTypes.ENCHANTED_HIT, target);
        }

        if (!sounds.get()) return;

        double x = target.getX();
        double y = target.getY() + target.getHeight() * 0.5;
        double z = target.getZ();

        if (type.attackSound != null) emit(type.attackSound, x, y, z);
        if (type.hurts) {
            SoundEvent hurt = ((HurtSoundInvoker) target).jjsTools$getHurtSound(target.getDamageSources().generic());
            if (hurt != null) emit(hurt, x, y, z);
        }
    }

    private void emit(String id, double x, double y, double z) {
        SoundEvent sound = Registries.SOUND_EVENT.getOptionalValue(Identifier.ofVanilla(id)).orElse(null);
        if (sound != null) emit(sound, x, y, z);
    }

    private void emit(SoundEvent sound, double x, double y, double z) {
        // Passing yourself as the source is what makes the client play it rather than wait for
        // a packet: the world only plays a sound locally when its source is the viewing player.
        mc.world.playSound(mc.player, x, y, z, sound, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    /** True if the weapon in hand carries Sharpness, which is what earns the extra particles. */
    private boolean sharpness() {
        return KitStore.enchantsOf(mc.player.getMainHandStack()).containsKey("minecraft:sharpness");
    }

    /**
     * Twenty particles thrown outward from the middle of the target, as vanilla does it.
     *
     * The flag is what stops the swallowing below from eating these on the way out: it cannot
     * tell our particles from the server's by looking at them, because they are the same
     * particles in the same place.
     */
    private void spray(ParticleEffect effect, LivingEntity target) {
        if (mc.particleManager == null) return;

        double cx = target.getX();
        double cy = target.getY() + target.getHeight() / 2.0;
        double cz = target.getZ();

        ours = true;
        try {
            for (int i = 0; i < 20; i++) {
                double dx = Math.random() - 0.5;
                double dy = Math.random() - 0.5;
                double dz = Math.random() - 0.5;
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length == 0) continue;

                mc.particleManager.addParticle(effect, cx + dx, cy + dy, cz + dz,
                    dx / length * 0.5, dy / length * 0.5, dz / length * 0.5);
            }
        } finally {
            ours = false;
        }
    }

    // Swallowing the server's copy

    /**
     * True if the server's damage event is one this module has already shown you.
     *
     * Read from the attacker rather than from a queue of hits, because the only thing that
     * matters is whether this flinch is the echo of a hit of yours; anything hitting them that
     * is not you still needs to come through.
     */
    public static boolean alreadyShown(Entity target, @Nullable Entity attacker) {
        BetterHitreg module = get();
        if (module == null || !module.isActive() || !module.swallowDuplicates.get()) return false;
        if (module.mc.player == null || attacker == null) return false;
        if (attacker.getId() != module.mc.player.getId()) return false;

        Long when = PREDICTED.get(target.getId());
        if (when == null) return false;

        if (System.currentTimeMillis() - when > SWALLOW_WINDOW_MS) {
            PREDICTED.remove(target.getId());
            return false;
        }

        return true;
    }

    /**
     * True if this crit or sharpness particle is the server catching up with one already thrown.
     *
     * Matched on where it is rather than on when, because the window alone would also eat the
     * particles from someone else's fight happening next to you. Anything that is not a hit
     * particle, and anything away from a target you just hit, goes through.
     */
    public static boolean swallowParticle(ParticleEffect effect, double x, double y, double z) {
        if (ours || effect == null) return false;

        BetterHitreg module = get();
        if (module == null || !module.isActive()) return false;
        if (!module.particles.get() || !module.swallowDuplicates.get()) return false;
        if (module.mc.world == null) return false;

        if (effect.getType() != ParticleTypes.CRIT && effect.getType() != ParticleTypes.ENCHANTED_HIT) return false;

        long now = System.currentTimeMillis();
        PREDICTED.entrySet().removeIf(e -> now - e.getValue() > SWALLOW_WINDOW_MS);

        for (Integer id : PREDICTED.keySet()) {
            Entity target = module.mc.world.getEntityById(id);
            if (target == null) continue;

            double cy = target.getY() + target.getHeight() / 2.0;
            double dx = x - target.getX(), dy = y - cy, dz = z - target.getZ();
            if (dx * dx + dy * dy + dz * dz <= 6.25) return true;
        }

        return false;
    }
}
