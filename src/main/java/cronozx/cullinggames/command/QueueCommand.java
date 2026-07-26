package cronozx.cullinggames.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import cronozx.cullinggames.config.Messages;
import cronozx.cullinggames.config.ProxyConfig;
import cronozx.cullinggames.queue.QueueService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

/**
 * {@code /queue} — the player-facing join command (also run by the clickable start broadcast). No
 * permission is required. The enqueue is atomic (single Lua script on the proxy) and the reply is
 * driven entirely by the script's return code, so the displayed size is always the real post-enqueue
 * length.
 */
public final class QueueCommand implements SimpleCommand {

    private final QueueService queue;
    private final ProxyConfig config;
    private final Messages messages;
    private final Logger logger;

    public QueueCommand(QueueService queue, ProxyConfig config, Messages messages, Logger logger) {
        this.queue = queue;
        this.config = config;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            // /queue is player-only; console has no queue identity. Nothing to do.
            return;
        }
        int max = config.maxPlayers();
        queue.enqueue(player.getUniqueId(), max)
                .thenAccept(code -> reply(player, code, max))
                .exceptionally(ex -> {
                    logger.warn("Failed to enqueue {}: {}", player.getUsername(), ex.getMessage());
                    return null;
                });
    }

    private void reply(Player player, long code, int max) {
        if (code == -2) {
            messages.send(player, "queue-closed");
        } else if (code == -1) {
            messages.send(player, "queue-already");
        } else if (code == 0) {
            messages.send(player, "queue-full");
        } else {
            messages.send(player, "queue-joined",
                    Placeholder.unparsed("count", Long.toString(code)),
                    Placeholder.unparsed("max", Integer.toString(max)));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
