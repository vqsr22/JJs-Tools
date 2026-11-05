package dev.jjstools.mixin.accessor;

import net.minecraft.screen.Property;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.screen.AnvilScreenHandler;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AnvilScreenHandler.class)
public interface AnvilScreenHandlerAccessor {
    @Accessor
    Property getLevelCost();

    /**
     * The name this anvil has actually been told about.
     *
     * Set only by setNewItemName, which is also the thing that decides whether a rename packet
     * goes out, so it is a faithful record of what the server was told. The text in the name box
     * is not: it can read correctly while the server knows nothing, which is how renaming used to
     * get stuck after an anvil was reopened.
     */
    @Accessor
    String getNewItemName();
}
