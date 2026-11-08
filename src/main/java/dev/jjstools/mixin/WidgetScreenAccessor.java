package dev.jjstools.mixin;

import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The root of a Meteor screen's widgets, for auto fit to lay the module columns out at a trial scale. */
@Mixin(value = WidgetScreen.class, remap = false)
public interface WidgetScreenAccessor {
    @Accessor(value = "root", remap = false)
    WContainer jjstools$getRoot();
}
