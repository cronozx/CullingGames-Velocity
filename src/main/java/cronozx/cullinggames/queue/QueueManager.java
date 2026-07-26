package cronozx.cullinggames.queue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import cronozx.cullinggames.common.GameState;
import cronozx.cullinggames.common.Protocol;
import cronozx.cullinggames.common.message.Message;
import cronozx.cullinggames.common.message.MessageType;
import cronozx.cullinggames.CullingGames;
import cronozx.cullinggames.config.Messages;
import cronozx.cullinggames.config.ProxyConfig;
import cronozx.cullinggames.net.Transport;
import cronozx.cullinggames.util.Text;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Owns the queue-window lifecycle on the proxy: opening a window (auto or forced), broadcasting the
 * clickable invite, and closing the window to hand off to the game server.
 *
 * <p>"Queue open" is derived from the Redis {@code <prefix>:state} key rather than an in-memory flag,
 * so it survives a proxy restart and is consistent across multiple proxies. The single close task is
 * held as a {@link ScheduledTask} handle so it can be cancelled on shutdown.
 */
public final class QueueManager {

    private final CullingGames plugin;
    private final ProxyServer server;
    private final Transport transport;
    private final QueueService queue;
    private final ProxyConfig config;
    private final Messages messages;
    private final Logger logger;

    private volatile ScheduledTask closeTask;

    public QueueManager(CullingGames plugin, ProxyServer server, Transport transport,
                        QueueService queue, ProxyConfig config, Messages messages, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.transport = transport;
        this.queue = queue;
        this.config = config;
        this.messages = messages;
        this.logger = logger;
    }

    /**
     * Opens a queue window without command feedback (used by the auto-start scheduler).
     *
     * @param forced        whether this is a forced (admin) open
     * @param windowSeconds how long the window stays open
     */
    public void openWindow(boolean forced, int windowSeconds) {
        openWindow(forced, windowSeconds, null);
    }

    /**
     * Opens a queue window.
     *
     * <p>Guards on the Redis state: if a game or window is already active ({@code state != IDLE})
     * the open is refused and, when a feedback source is present, replies {@code forcestart-running}.
     * Otherwise it clears the queue (both auto and forced), sets state {@code OPEN} with a TTL,
     * broadcasts {@code event-starting} to the announce servers, and schedules a single delayed close.
     *
     * @param forced        whether this is a forced (admin) open
     * @param windowSeconds how long the window stays open
     * @param feedback      command source to reply to, or {@code null} for auto-start
     */
    public void openWindow(boolean forced, int windowSeconds, CommandSource feedback) {
        queue.getState().thenAccept(state -> {
            if (state != GameState.IDLE) {
                if (feedback != null) {
                    messages.send(feedback, "forcestart-running");
                }
                return;
            }
            queue.clearQueue()
                    .thenCompose(ignored -> queue.setState(GameState.OPEN, windowSeconds + 60))
                    .thenRun(() -> {
                        broadcast("event-starting");
                        if (feedback != null) {
                            messages.send(feedback, "forcestart-done");
                        }
                        scheduleClose(windowSeconds);
                        logger.info("Queue window opened for {}s (forced={}).", windowSeconds, forced);
                    })
                    .exceptionally(ex -> {
                        logger.warn("Failed to open queue window: {}", ex.getMessage());
                        return null;
                    });
        }).exceptionally(ex -> {
            logger.warn("Failed to read state before opening window: {}", ex.getMessage());
            return null;
        });
    }

    private void scheduleClose(int windowSeconds) {
        cancelCloseTask();
        this.closeTask = server.getScheduler()
                .buildTask(plugin, this::closeWindow)
                .delay(Duration.ofSeconds(windowSeconds))
                .schedule();
    }

    /**
     * Closes the queue window: marks state {@code STARTING}, and if enough players are queued publishes
     * {@code START|<gameServer>|<min>} to the game channel; otherwise resets to {@code IDLE} and
     * broadcasts {@code not-enough-players}.
     */
    public void closeWindow() {
        int windowSeconds = config.windowSeconds();
        int min = config.minPlayers();
        queue.setState(GameState.STARTING, windowSeconds + 60)
                .thenCompose(ignored -> queue.size())
                .thenAccept(count -> {
                    if (count < min) {
                        queue.setState(GameState.IDLE);
                        broadcast("not-enough-players");
                        logger.info("Queue window closed with {} player(s) (< {}); not starting.", count, min);
                    } else {
                        // v2: START|<matchId>|<gameServer>|<arenaId>|<minPlayers>. Both halves
                        // derive the id through Protocol so they cannot disagree about it.
                        String matchId = Protocol.mainMatchId(config.gameServer());
                        String start = Message.encode(MessageType.START, matchId,
                                config.gameServer(), Protocol.MAIN_ARENA, Integer.toString(min));
                        transport.publish(start);
                        logger.info("Queue window closed with {} player(s); START sent to '{}' as match '{}'.",
                                count, config.gameServer(), matchId);
                    }
                })
                .exceptionally(ex -> {
                    logger.warn("Failed to close queue window: {}", ex.getMessage());
                    return null;
                });
    }

    private void broadcast(String messageKey) {
        Text.broadcast(server, config.announceServers(), messages.component(messageKey));
    }

    private void cancelCloseTask() {
        ScheduledTask task = this.closeTask;
        if (task != null) {
            task.cancel();
            this.closeTask = null;
        }
    }

    /** Cancels the pending close task, if any (shutdown). */
    public void cancelTasks() {
        cancelCloseTask();
    }
}
