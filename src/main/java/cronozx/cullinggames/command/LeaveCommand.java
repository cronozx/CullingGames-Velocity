package cronozx.cullinggames.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import cronozx.cullinggames.config.Messages;
import cronozx.cullinggames.queue.QueueService;
import org.slf4j.Logger;

/**
 * {@code /leave} — removes the player from the queue. No permission is required. The reply reflects
 * whether the player was actually in the queue (the {@code LREM} count).
 */
public final class LeaveCommand implements SimpleCommand {

    private final QueueService queue;
    private final Messages messages;
    private final Logger logger;

    public LeaveCommand(QueueService queue, Messages messages, Logger logger) {
        this.queue = queue;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            return;
        }
        queue.leave(player.getUniqueId())
                .thenAccept(removed -> {
                    if (removed != null && removed > 0) {
                        messages.send(player, "queue-left");
                    } else {
                        messages.send(player, "queue-not-in");
                    }
                })
                .exceptionally(ex -> {
                    logger.warn("Failed to remove {} from queue: {}", player.getUsername(), ex.getMessage());
                    return null;
                });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
