package cronozx.cullinggames.util;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Small proxy-side messaging helpers. MiniMessage rendering lives in
 * {@link cronozx.cullinggames.config.Messages}; this holder only exposes audience
 * fan-out utilities that need the {@link ProxyServer}.
 */
public final class Text {

    private Text() {
    }

    /**
     * Broadcasts a rendered component to a set of named servers, or to every server when the list is
     * {@code null} or empty.
     *
     * <p>A {@link RegisteredServer} is itself an {@code Audience} that forwards to all players
     * connected to that backend, so this reaches every player on the targeted servers.
     *
     * @param server      the proxy
     * @param serverNames the target server names; {@code null}/empty means all servers
     * @param message     the pre-rendered component to send
     */
    public static void broadcast(ProxyServer server, List<String> serverNames, Component message) {
        if (serverNames == null || serverNames.isEmpty()) {
            for (RegisteredServer registered : server.getAllServers()) {
                registered.sendMessage(message);
            }
            return;
        }
        for (String name : serverNames) {
            server.getServer(name).ifPresent(registered -> registered.sendMessage(message));
        }
    }
}
