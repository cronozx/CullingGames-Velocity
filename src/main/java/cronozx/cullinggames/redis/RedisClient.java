package cronozx.cullinggames.redis;

import cronozx.cullinggames.common.RedisSettings;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Owns the {@link JedisPool} and all off-thread Redis I/O for the proxy.
 *
 * <p>Design (mirrors the game-server side):
 * <ul>
 *   <li>Construction never throws when Redis is unreachable — the pool is lazy and the health flag
 *       simply flips on the first failed operation.</li>
 *   <li>Every command runs on a single dedicated I/O thread via {@link #async(Function)}, returning
 *       a {@link CompletableFuture}; the Velocity event/main threads are never blocked on Redis.</li>
 *   <li>A {@link JedisConnectionException} marks the client unhealthy and starts a capped-backoff
 *       ping probe (2s → 30s); the first successful probe (or operation) restores health and logs
 *       once.</li>
 * </ul>
 *
 * <p>The {@link JedisPool} is built here from {@link RedisSettings} (the {@code common} module is
 * intentionally Jedis-free): a non-blank username authenticates as an ACL user with password, a
 * password alone authenticates with password only, and blank credentials connect without auth.
 */
public final class RedisClient {

    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final Logger logger;
    private final JedisPool pool;
    private final ExecutorService ioExecutor;
    private final ScheduledExecutorService reconnectExecutor;

    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicBoolean probeScheduled = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Builds the pool and starts the I/O and reconnect executors. Does not connect eagerly, so it
     * never throws if Redis is down.
     *
     * @param settings resolved Redis connection settings
     * @param logger   plugin logger
     */
    public RedisClient(RedisSettings settings, Logger logger) {
        this.logger = logger;
        this.pool = buildPool(settings);
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BattleRoyale-Redis-IO");
            thread.setDaemon(true);
            return thread;
        });
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BattleRoyale-Redis-Reconnect");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static JedisPool buildPool(RedisSettings settings) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(8);
        config.setMaxIdle(4);
        config.setMinIdle(1);
        config.setTestOnBorrow(true);

        String user = settings.username();
        String pass = settings.password();
        boolean hasUser = user != null && !user.isBlank();
        boolean hasPass = pass != null && !pass.isBlank();

        if (hasUser) {
            return new JedisPool(config, settings.host(), settings.port(), settings.timeoutMs(), user, pass);
        }
        if (hasPass) {
            return new JedisPool(config, settings.host(), settings.port(), settings.timeoutMs(), pass);
        }
        return new JedisPool(config, settings.host(), settings.port(), settings.timeoutMs());
    }

    /**
     * Runs {@code work} against a pooled connection on the dedicated I/O thread.
     *
     * @param work function applied to a borrowed {@link Jedis}; its result completes the future
     * @param <T>  result type
     * @return a future completing with the result, or exceptionally on failure
     */
    public <T> CompletableFuture<T> async(Function<Jedis, T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("RedisClient is closed"));
            return future;
        }
        ioExecutor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                T result = work.apply(jedis);
                markHealthy();
                future.complete(result);
            } catch (JedisConnectionException connectionEx) {
                markUnhealthy(connectionEx);
                future.completeExceptionally(connectionEx);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    /**
     * Fire-and-forget publish to a channel; failures are logged, never thrown.
     *
     * @param channel channel name
     * @param message message payload
     */
    public void publish(String channel, String message) {
        async(jedis -> {
            jedis.publish(channel, message);
            return null;
        }).exceptionally(ex -> {
            logger.warn("Failed to publish to Redis channel '{}': {}", channel, ex.getMessage());
            return null;
        });
    }

    /**
     * Borrows a raw connection from the pool. Used only by {@link RedisSubscriber} for the blocking
     * {@code subscribe} call; callers must close it (try-with-resources) to return it to the pool.
     *
     * @return a borrowed connection
     */
    public Jedis borrowConnection() {
        return pool.getResource();
    }

    /**
     * Synchronously pings Redis and updates the health flag. Used once for the startup diagnostics
     * line so it reports an accurate CONNECTED/RETRYING status.
     *
     * @return {@code true} if the ping succeeded
     */
    public boolean pingBlocking() {
        if (closed) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
            markHealthy();
            return true;
        } catch (Exception ex) {
            markUnhealthy(ex);
            return false;
        }
    }

    /** @return the last observed health state (advisory; updated by every operation and the probe) */
    public boolean isHealthy() {
        return healthy.get();
    }

    private void markHealthy() {
        if (healthy.compareAndSet(false, true)) {
            logger.info("Redis connection restored.");
        }
    }

    private void markUnhealthy(Throwable cause) {
        if (healthy.compareAndSet(true, false)) {
            logger.warn("Redis connection lost: {}", cause.getMessage());
        }
        scheduleProbe(INITIAL_BACKOFF_MS);
    }

    private void scheduleProbe(long delayMs) {
        if (closed) {
            return;
        }
        if (probeScheduled.compareAndSet(false, true)) {
            enqueueProbe(delayMs);
        }
    }

    private void enqueueProbe(long delayMs) {
        if (closed) {
            probeScheduled.set(false);
            return;
        }
        try {
            reconnectExecutor.schedule(() -> probe(delayMs), delayMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // executor shutting down; nothing more to do
            probeScheduled.set(false);
        }
    }

    private void probe(long previousDelayMs) {
        if (closed) {
            probeScheduled.set(false);
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
            markHealthy();
            probeScheduled.set(false);
        } catch (Exception ex) {
            long next = Math.min(previousDelayMs * 2, MAX_BACKOFF_MS);
            enqueueProbe(next);
        }
    }

    /** Shuts down executors and closes the pool. Idempotent. */
    public void close() {
        closed = true;
        ioExecutor.shutdownNow();
        reconnectExecutor.shutdownNow();
        try {
            pool.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
