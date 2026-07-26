package cronozx.cullinggames.net;

import java.util.function.Consumer;

/**
 * The proxy&harr;game-server message channel, independent of which backend carries it. The mirror of
 * the game server's transport interface.
 *
 * <p>Redis provides this natively with pub/sub; MySQL emulates it by polling an append-only message
 * table, which costs up to one poll interval of extra latency on every signal. Nothing the proxy
 * sends or receives is tick-critical — START, STOP, PULL, ELIMINATED, ENDED — so the difference does
 * not show up in play.
 *
 * <p>{@link #subscribe} delivers on a background thread; handlers must treat player lookups as
 * fire-and-forget requests, exactly as they already do for the Redis subscriber thread.
 */
public interface Transport {

    /**
     * Starts delivering messages from the proxy channel.
     *
     * @param handler receives each raw wire message on a background thread
     */
    void subscribe(Consumer<String> handler);

    /**
     * Fire-and-forget publish to the game channel. Failures are logged, never thrown.
     *
     * @param message the encoded wire message
     */
    void publish(String message);

    /** @return whether the last observed backend interaction succeeded. */
    boolean isHealthy();

    /**
     * Blocking health check used once for the startup summary, so it reports an accurate status.
     *
     * @return {@code true} if the backend answered
     */
    boolean pingBlocking();

    /** @return a short human-readable backend description for the startup summary. */
    String describe();

    /** Stops delivery and releases the connection. Idempotent. */
    void close();
}
