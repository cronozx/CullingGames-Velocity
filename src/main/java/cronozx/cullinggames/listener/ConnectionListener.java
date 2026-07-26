package cronozx.cullinggames.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import cronozx.cullinggames.queue.QueueService;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Cleans up a disconnecting player's queue and roster membership.
 *
 * <p>The work is dispatched to {@link QueueService}'s I/O executor and never blocks the Velocity
 * event thread. Removing the player from the {@code <prefix>:alive} set mid-round lets the game
 * server detect a win on its next {@code SCARD}; the queue removal is a belt-and-suspenders idempotent
 * {@code LREM}.
 */
public final class ConnectionListener {

    private final QueueService queue;
    private final Logger logger;

    public ConnectionListener(QueueService queue, Logger logger) {
        this.queue = queue;
        this.logger = logger;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        queue.leave(uuid).exceptionally(ex -> {
            logger.debug("Queue cleanup on disconnect failed for {}: {}", uuid, ex.getMessage());
            return null;
        });
        queue.removeAlive(uuid).exceptionally(ex -> {
            logger.debug("Roster cleanup on disconnect failed for {}: {}", uuid, ex.getMessage());
            return null;
        });
    }
}
