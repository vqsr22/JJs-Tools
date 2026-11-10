package dev.jjstools.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A sign's writing, swapped without the side effects of setText. Before 1.21.9 there is no render
 * state to change, so Sign to Item Frame blanks the sign's own text for the length of one draw,
 * and setText would schedule a chunk rebuild every frame.
 */
@Mixin(SignBlockEntity.class)
public interface SignBlockEntityAccessor {
    @Accessor("frontText")
    SignText jjstools$getFrontText();

    @Accessor("frontText")
    void jjstools$setFrontText(SignText text);

    @Accessor("backText")
    SignText jjstools$getBackText();

    @Accessor("backText")
    void jjstools$setBackText(SignText text);
}
