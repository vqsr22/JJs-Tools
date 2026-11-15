package xaero.lib.common.config.option;

/**
 * Compile-only stand-in for XaeroLib's ConfigOption, so XaeroConfigMixin can name it. It is never
 * put in the jar: with Xaero's mods installed the real class is used, and without them the mixin
 * is skipped.
 */
public abstract class ConfigOption<T> {
    public abstract String getId();
}
