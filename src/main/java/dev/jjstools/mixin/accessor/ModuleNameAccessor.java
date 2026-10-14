package dev.jjstools.mixin.accessor;

import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets a module's name be changed, which it otherwise cannot be.
 *
 * The name is final because an addon is expected to pick one and keep it. That holds right up
 * until two addons pick the same one, at which point the only way to tell them apart is to change
 * one, and nothing else about the module needs touching to do it.
 */
@Mixin(value = Module.class, remap = false)
public interface ModuleNameAccessor {
    @Mutable
    @Accessor("name")
    void jjsTools$setName(String name);
}
