package dev.jjstools.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The state of the world at the moment you logged.
 *
 * Built as a block of chat lines rather than one string, so the parts that matter stand out when
 * you are reading back through chat after the fact.
 *
 * Gathered BEFORE the disconnect: once the connection drops the world is gone and none of this can
 * be read any more.
 */
public class LogDetails {
    /** Local clock, to the second, because "when did that happen" is the first thing you ask. */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private LogDetails() {}

    /**
     * The same report as one Text, for the disconnect screen.
     *
     * The screen takes a single component, so the lines are joined with newlines rather than sent
     * separately. Reads as a block under the reason.
     */
    public static Text asBlock(String lastDamage) {
        List<Text> lines = gather(lastDamage);
        if (lines.isEmpty()) return Text.empty();

        var out = Text.literal("");
        for (Text line : lines) out.append("\n").append(line);
        return out;
    }

    public static List<Text> gather(String lastDamage) {
        List<Text> lines = new ArrayList<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return lines;

        lines.add(header("Log details"));
        lines.add(line("Time", LocalDateTime.now().format(CLOCK)));

        lines.add(line("Position", "%d, %d, %d in %s".formatted(
            mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(),
            mc.world.getRegistryKey().getValue().getPath())));

        lines.add(line("Health", "%.1f / %.1f, absorption %.1f".formatted(
            mc.player.getHealth(), mc.player.getMaxHealth(), mc.player.getAbsorptionAmount())));

        lines.add(line("Totems", String.valueOf(countTotems(mc.player))));
        lines.add(line("Last damage", lastDamage == null ? "none this session" : lastDamage));
        lines.add(line("Armour", armour(mc.player)));

        String effects = effects(mc.player);
        if (effects != null) lines.add(line("Effects", effects));

        String players = nearbyPlayers(mc);
        lines.add(players == null
            ? line("Players nearby", "none")
            : Text.literal("  Players nearby: ").formatted(Formatting.GRAY)
                .append(Text.literal(players).formatted(Formatting.RED)));

        String hostiles = nearbyHostiles(mc);
        lines.add(line("Hostiles", hostiles == null ? "none" : hostiles));

        return lines;
    }

    private static Text header(String text) {
        return Text.literal("── " + text + " ──").formatted(Formatting.DARK_GRAY);
    }

    private static Text line(String label, String value) {
        return Text.literal("  " + label + ": ").formatted(Formatting.GRAY)
            .append(Text.literal(value).formatted(Formatting.WHITE));
    }

    private static int countTotems(PlayerEntity player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) total += stack.getCount();
        }
        if (player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            // The offhand is already in the inventory list on this version; guard against
            // double counting rather than assuming either way.
        }
        return total;
    }

    /** Each piece as a percentage, since raw damage values mean nothing at a glance. */
    private static String armour(PlayerEntity player) {
        StringBuilder sb = new StringBuilder();

        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getEquippedStack(slot);
            if (stack.isEmpty()) continue;

            if (!sb.isEmpty()) sb.append(", ");
            sb.append(stack.getItem().getName().getString());

            if (stack.isDamageable()) {
                int max = stack.getMaxDamage();
                int left = max - stack.getDamage();
                sb.append(' ').append(Math.round(left * 100f / max)).append('%');
            }
        }

        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static String effects(LivingEntity player) {
        StringBuilder sb = new StringBuilder();

        for (StatusEffectInstance effect : player.getStatusEffects()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(effect.getEffectType().value().getName().getString())
                .append(' ').append(effect.getAmplifier() + 1)
                .append(" (").append(effect.getDuration() / 20).append("s)");
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    /** Names matter here: "someone was watching" is far less useful than knowing who. */
    private static String nearbyPlayers(MinecraftClient mc) {
        StringBuilder sb = new StringBuilder();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;

            if (!sb.isEmpty()) sb.append(", ");
            sb.append(player.getGameProfile().name())
                .append(" (").append(Math.round(mc.player.distanceTo(player))).append("m)");
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    private static String nearbyHostiles(MinecraftClient mc) {
        StringBuilder sb = new StringBuilder();
        int counted = 0;

        for (Entity entity : mc.world.getEntities()) {
            // Monster is an interface here, not an Entity subclass, so the Entity reference is
            // what everything below has to use.
            if (!(entity instanceof Monster) || !entity.isAlive()) continue;
            if (mc.player.squaredDistanceTo(entity) > 32 * 32) continue;

            if (counted++ >= 8) {
                sb.append(", and more");
                break;
            }
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(entity.getType().getName().getString())
                .append(" (").append(Math.round(mc.player.distanceTo(entity))).append("m)");
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    /** A readable name for whatever last hit you. */
    public static String describe(DamageSource source) {
        if (source == null) return null;

        String type = source.getType().msgId();
        Entity attacker = source.getAttacker();

        if (attacker instanceof PlayerEntity player) {
            return type + " from " + player.getGameProfile().name();
        }
        if (attacker != null) {
            return type + " from " + attacker.getType().getName().getString();
        }
        return type;
    }
}
