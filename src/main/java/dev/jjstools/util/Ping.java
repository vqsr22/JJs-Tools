package dev.jjstools.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/** Alert sounds shared by the scanner modules, so both offer the same list. */
public enum Ping {
    Pling("Note block pling", SoundEvents.BLOCK_NOTE_BLOCK_PLING.value()),
    Bell("Note block bell", SoundEvents.BLOCK_NOTE_BLOCK_BELL.value()),
    Chime("Note block chime", SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value()),
    Bit("Note block bit", SoundEvents.BLOCK_NOTE_BLOCK_BIT.value()),
    Harp("Note block harp", SoundEvents.BLOCK_NOTE_BLOCK_HARP.value()),
    Xylophone("Note block xylophone", SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE.value()),
    Experience("Experience orb", SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP),
    LevelUp("Level up", SoundEvents.ENTITY_PLAYER_LEVELUP),
    Anvil("Anvil land", SoundEvents.BLOCK_ANVIL_LAND),
    Amethyst("Amethyst chime", SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME),
    Beacon("Beacon activate", SoundEvents.BLOCK_BEACON_ACTIVATE),
    Off("Off", null);

    private final String title;
    private final SoundEvent sound;

    Ping(String title, SoundEvent sound) {
        this.title = title;
        this.sound = sound;
    }

    @Override
    public String toString() {
        return title;
    }

    /**
     * Plays through the sound manager on the master category rather than
     * ClientPlayerEntity.playSound, which goes out on a category you may have turned down and is
     * positioned in the world. A master instance is always heard at the volume you asked for.
     */
    public void play(int semitones, double volume) {
        if (sound == null) return;

        float pitch = (float) Math.max(0.5, Math.min(2.0, Math.pow(2, semitones / 12.0)));
        MinecraftClient.getInstance().getSoundManager()
            .play(PositionedSoundInstance.master(sound, pitch, (float) volume));
    }
}
