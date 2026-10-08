package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.JJUtil;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * Leaves the server by making it kick you, rather than by disconnecting.
 *
 * A clean disconnect tells the server you left, and anything watching the player list sees it.
 * Sending a packet the server cannot accept makes it drop the connection instead, which looks like
 * a crash or a timeout from the outside.
 *
 * The module turns itself off straight after, so it behaves like a button rather than a state.
 *
 * Through a proxy this does not do what you want. The malformed packet reaches the proxy, not the
 * server, so the proxy is what reacts to it, and on something like ZenithProxy that can take down
 * a session you meant to leave running. With "ignore-proxies" on it falls back to an ordinary
 * disconnect in that case.
 */
public class IllegalDisconnect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<JJUtil.IllegalDisconnectMethod> method = sgGeneral.add(new EnumSetting.Builder<JJUtil.IllegalDisconnectMethod>()
        .name("method")
        .description("Which packet to send. If one stops working, try another.")
        .defaultValue(JJUtil.IllegalDisconnectMethod.Slot)
        .build()
    );

    private final Setting<Boolean> disableAutoReconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-auto-reconnect")
        .description("Turn Meteor's Auto Reconnect off first, so it does not put you straight back in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreProxies = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-proxies")
        .description("On a proxy, disconnect normally instead. The bad packet would hit the proxy rather than the server, which is not what you are trying to do.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> proxyHosts = sgGeneral.add(new StringListSetting.Builder()
        .name("proxy-addresses")
        .description("Treated as a proxy on top of the local and private addresses already detected. Matched anywhere in the server address, ignoring case.")
        .defaultValue(List.of())
        .visible(ignoreProxies::get)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("say-which-was-used")
        .description("Print whether it went out as an illegal disconnect or a normal one.")
        .defaultValue(true)
        .build()
    );

    public IllegalDisconnect() {
        super(JJsTools.CATEGORY, "illegal-disconnect", "Leaves by making the server kick you, so it looks like a crash rather than a logout.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        if (ignoreProxies.get() && onProxy()) {
            if (chatFeedback.get()) {
                warning("Looks like a proxy, so disconnecting normally instead.");
            }
            if (disableAutoReconnect.get()) JJUtil.disableAutoReconnect();
            mc.getNetworkHandler().getConnection().disconnect(Text.literal("Disconnected"));
        }
        else {
            if (chatFeedback.get()) info("Illegal disconnect via " + method.get() + ".");
            JJUtil.illegalDisconnect(disableAutoReconnect.get(), method.get());
        }

        // A button, not a state: nothing to stay switched on for.
        toggle();
    }

    /**
     * Whether the address you are connected to looks like a proxy rather than a server.
     *
     * Local and private addresses cover the usual case of a proxy running on your own machine or
     * network. Anything else has to be listed by hand, because there is no way to tell a remote
     * proxy from a server by its address alone.
     */
    private boolean onProxy() {
        ServerInfo info = mc.getCurrentServerEntry();
        if (info == null) return true;

        String address = info.address == null ? "" : info.address.toLowerCase(Locale.ROOT);

        for (String host : proxyHosts.get()) {
            if (!host.isBlank() && address.contains(host.toLowerCase(Locale.ROOT))) return true;
        }

        return address.startsWith("localhost")
            || address.startsWith("127.")
            || address.startsWith("0.0.0.0")
            || address.startsWith("::1")
            || address.startsWith("192.168.")
            || address.startsWith("10.")
            || address.startsWith("172.16.")
            || address.startsWith("172.17.")
            || address.startsWith("172.18.")
            || address.startsWith("172.19.")
            || address.startsWith("172.2")
            || address.startsWith("172.30.")
            || address.startsWith("172.31.");
    }
}
