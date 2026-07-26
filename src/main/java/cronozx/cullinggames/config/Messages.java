package cronozx.cullinggames.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * MiniMessage resolver for proxy messages.
 *
 * <p>Reads its templates live from {@link ProxyConfig}, so after {@code config.reload()} the same
 * {@code Messages} instance immediately renders the updated strings — no rebuild or re-registration
 * needed. Every message is parsed with a {@code <prefix>} tag (itself a MiniMessage template parsed
 * into a component) plus any caller-supplied placeholder resolvers.
 */
public final class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ProxyConfig config;

    /**
     * @param config the live proxy configuration to read templates from
     */
    public Messages(ProxyConfig config) {
        this.config = config;
    }

    /**
     * Renders a message template to a component.
     *
     * @param key       message key under {@code messages:}
     * @param resolvers additional placeholder resolvers
     * @return the rendered component
     */
    public Component component(String key, TagResolver... resolvers) {
        String template = config.message(key);
        return MINI_MESSAGE.deserialize(template, withPrefix(resolvers));
    }

    /**
     * Renders and sends a message to an audience.
     *
     * @param audience  the recipient
     * @param key       message key under {@code messages:}
     * @param resolvers additional placeholder resolvers
     */
    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(component(key, resolvers));
    }

    private TagResolver[] withPrefix(TagResolver... resolvers) {
        Component prefix = MINI_MESSAGE.deserialize(config.prefix());
        TagResolver prefixResolver = Placeholder.component("prefix", prefix);
        if (resolvers == null || resolvers.length == 0) {
            return new TagResolver[]{prefixResolver};
        }
        TagResolver[] all = new TagResolver[resolvers.length + 1];
        all[0] = prefixResolver;
        System.arraycopy(resolvers, 0, all, 1, resolvers.length);
        return all;
    }
}
