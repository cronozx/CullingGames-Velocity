package cronozx.cullinggames.redis;

import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.function.Consumer;

/**
 * A reconnecting subscriber for a single Redis pub/sub channel (the proxy consumes
 * {@code <prefix>:proxy}).
 *
 * <p>The subscribe call is blocking, so it runs on a dedicated daemon thread. The loop reconnects
 * with capped backoff on any failure while {@code running} is {@code true}. {@link #shutdown()} sets
 * {@code running=false} <em>before</em> unsubscribing so the blocking {@code subscribe} returns and
 * the loop exits cleanly rather than immediately reconnecting.
 */
public final class RedisSubscriber {

    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final RedisClient redis;
    private final String channel;
    private final Consumer<String> handler;
    private final Logger logger;
    private final Thread thread;
    private final JedisPubSub pubSub;

    private volatile boolean running = true;
    private volatile Jedis activeConnection;

    /**
     * @param redis   client providing pooled connections
     * @param channel channel to subscribe to
     * @param handler invoked with each received message body (exceptions are caught and logged)
     * @param logger  plugin logger
     */
    public RedisSubscriber(RedisClient redis, String channel, Consumer<String> handler, Logger logger) {
        this.redis = redis;
        this.channel = channel;
        this.handler = handler;
        this.logger = logger;
        this.pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String subscribedChannel, String message) {
                if (message == null) {
                    return;
                }
                try {
                    handler.accept(message);
                } catch (Exception ex) {
                    logger.error("Error handling Redis message on '{}': {}", subscribedChannel, ex.getMessage(), ex);
                }
            }
        };
        this.thread = new Thread(this::run, "BattleRoyale-Redis-Subscriber");
        this.thread.setDaemon(true);
    }

    /** Starts the subscriber thread. */
    public void start() {
        thread.start();
    }

    private void run() {
        long backoff = INITIAL_BACKOFF_MS;
        while (running) {
            try (Jedis jedis = redis.borrowConnection()) {
                backoff = INITIAL_BACKOFF_MS;
                this.activeConnection = jedis;
                logger.info("Subscribed to Redis channel '{}'.", channel);
                jedis.subscribe(pubSub, channel);
                // subscribe() returns only when unsubscribed (shutdown) — loop guard handles exit.
            } catch (Exception ex) {
                if (!running) {
                    break;
                }
                logger.warn("Redis subscriber on '{}' disconnected: {} (retrying in {} ms)",
                        channel, ex.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    /**
     * Stops the subscriber: clears the running flag first, then unsubscribes so the blocking
     * {@code subscribe} returns and the thread finishes. Idempotent.
     */
    public void shutdown() {
        running = false;
        try {
            if (pubSub.isSubscribed()) {
                pubSub.unsubscribe();
            }
        } catch (Exception ignored) {
            // already torn down
        }
        // Close the borrowed connection too, so the blocking subscribe() unblocks even if
        // unsubscribe() raced ahead of onSubscribe and the thread never sees the flag otherwise.
        Jedis connection = this.activeConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // already closed / returned to pool
            }
        }
        thread.interrupt();
    }
}
