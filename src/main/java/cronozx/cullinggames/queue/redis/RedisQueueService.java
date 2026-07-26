package cronozx.cullinggames.queue.redis;

import cronozx.cullinggames.queue.QueueService;
import cronozx.cullinggames.redis.RedisClient;
import cronozx.cullinggames.common.GameState;
import cronozx.cullinggames.common.LiveMatch;
import cronozx.cullinggames.common.Protocol;
import org.slf4j.Logger;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The Redis-backed {@link QueueService}: all queue, roster, lifecycle-state and lock operations the
 * proxy performs against Redis. Every
 * method is asynchronous (runs on {@link RedisClient}'s I/O thread) so the Velocity event threads
 * never block.
 *
 * <p>Key names are derived through {@link Protocol}, so the proxy and game server can never disagree
 * about the wire layout.
 */
public final class RedisQueueService implements QueueService {

    /**
     * Atomic enqueue (spec §5.4). One round-trip, race-free across any number of lobby servers or
     * proxies. Return codes: {@code -2} closed, {@code -1} already queued, {@code 0} full,
     * {@code >=1} the new queue size. Requires Redis 6.0.6+ for {@code LPOS}.
     */
    private static final String ENQUEUE_SCRIPT =
            "if redis.call('GET', KEYS[1]) ~= 'OPEN' then return -2 end\n"
            + "if redis.call('LPOS', KEYS[2], ARGV[1]) ~= false then return -1 end\n"
            + "if redis.call('LLEN', KEYS[2]) >= tonumber(ARGV[2]) then return 0 end\n"
            + "redis.call('RPUSH', KEYS[2], ARGV[1])\n"
            + "return redis.call('LLEN', KEYS[2])";

    private final RedisClient redis;
    private final Logger logger;

    private final String keyPrefix;
    private final String queueKey;
    private final String matchesKey;
    private final String stateKey;
    private final String autoStartLockKey;

    /**
     * @param redis     the Redis client
     * @param keyPrefix configured key prefix (namespaces every key)
     * @param logger    plugin logger
     */
    public RedisQueueService(RedisClient redis, String keyPrefix, Logger logger) {
        this.redis = redis;
        this.logger = logger;
        this.keyPrefix = keyPrefix;
        this.queueKey = Protocol.queueKey(keyPrefix);
        this.matchesKey = Protocol.matchesKey(keyPrefix);
        // The queue window's state, not any round's -- under v2 those are different keys.
        this.stateKey = Protocol.queueStateKey(keyPrefix);
        this.autoStartLockKey = Protocol.autoStartLockKey(keyPrefix);
    }

    /**
     * Atomically attempts to enqueue a player.
     *
     * @param player the player's UUID
     * @param max    the authoritative queue capacity
     * @return future of the status code (see {@link #ENQUEUE_SCRIPT})
     */
    @Override
    public CompletableFuture<Long> enqueue(UUID player, int max) {
        return redis.async(jedis -> {
            Object result = jedis.eval(
                    ENQUEUE_SCRIPT,
                    List.of(stateKey, queueKey),
                    List.of(player.toString(), Integer.toString(max)));
            return toLong(result);
        });
    }

    /**
     * Removes a player from the queue.
     *
     * @param player the player's UUID
     * @return future of the number of removed entries ({@code >0} means the player was queued)
     */
    @Override
    public CompletableFuture<Long> leave(UUID player) {
        return redis.async(jedis -> jedis.lrem(queueKey, 0, player.toString()));
    }

    /** @return future of the current queue length */
    @Override
    public CompletableFuture<Long> size() {
        return redis.async(jedis -> jedis.llen(queueKey));
    }

    /**
     * @param player the player's UUID
     * @return future that is {@code true} when the player is in the queue
     */
    @Override
    public CompletableFuture<Boolean> contains(UUID player) {
        return redis.async(jedis -> jedis.lpos(queueKey, player.toString()) != null);
    }

    /**
     * Deletes the queue key. Deletes when the key is a {@code list} or absent ({@code none}); stays
     * silent when absent and warns only on an unexpected type (fixes the legacy spurious "expected
     * string" error).
     *
     * @return future completing when the delete (or skip) is done
     */
    @Override
    public CompletableFuture<Void> clearQueue() {
        return redis.async(jedis -> {
            String type = jedis.type(queueKey);
            if ("list".equals(type) || "none".equals(type)) {
                jedis.del(queueKey);
            } else {
                logger.warn("Expected queue key '{}' to be a list but found '{}'; not clearing.", queueKey, type);
            }
            return null;
        });
    }

    /**
     * Reads the live-match registry ({@code <prefix>:matches}, a HASH of
     * {@code matchId -> server|arena}).
     *
     * <p>A malformed value is skipped rather than failing the enumeration: the registry is written
     * by game servers that may be running a different build, and one bad row must not blind the
     * proxy to every other round.
     */
    @Override
    public CompletableFuture<List<LiveMatch>> liveMatches() {
        return redis.async(jedis -> {
            List<LiveMatch> out = new ArrayList<>();
            for (Map.Entry<String, String> entry : jedis.hgetAll(matchesKey).entrySet()) {
                String value = entry.getValue();
                if (value == null) {
                    continue;
                }
                int split = value.indexOf('|');
                if (split <= 0 || split == value.length() - 1) {
                    continue;
                }
                try {
                    out.add(new LiveMatch(entry.getKey(),
                            value.substring(0, split), value.substring(split + 1)));
                } catch (IllegalArgumentException malformed) {
                    // Skip a stray row rather than failing the whole enumeration.
                }
            }
            out.sort(java.util.Comparator.comparing(LiveMatch::matchId));
            return out;
        });
    }

    /** @return future of one round's living-player roster (invalid members are skipped) */
    @Override
    public CompletableFuture<Set<UUID>> aliveIn(String matchId) {
        String aliveKey = Protocol.aliveKey(keyPrefix, matchId);
        return redis.async(jedis -> {
            Set<String> members = jedis.smembers(aliveKey);
            Set<UUID> parsed = new HashSet<>();
            for (String member : members) {
                if (member == null || member.isBlank()) {
                    continue;
                }
                try {
                    parsed.add(UUID.fromString(member.trim()));
                } catch (IllegalArgumentException ignored) {
                    // tolerate stray non-UUID members
                }
            }
            return parsed;
        });
    }

    /** @return future of one round's living-player count (SCARD) */
    @Override
    public CompletableFuture<Long> aliveCountIn(String matchId) {
        String aliveKey = Protocol.aliveKey(keyPrefix, matchId);
        return redis.async(jedis -> jedis.scard(aliveKey));
    }

    /**
     * Removes a player from one round's roster.
     *
     * @param matchId the round
     * @param player  the player's UUID
     * @return future of the number removed
     */
    @Override
    public CompletableFuture<Long> removeAliveFrom(String matchId, UUID player) {
        String aliveKey = Protocol.aliveKey(keyPrefix, matchId);
        return redis.async(jedis -> jedis.srem(aliveKey, player.toString()));
    }

    /**
     * Sets the advisory lifecycle state with no expiry.
     *
     * @param state the new state
     * @return future completing when written
     */
    @Override
    public CompletableFuture<Void> setState(GameState state) {
        return redis.async(jedis -> {
            jedis.set(stateKey, state.wire());
            return null;
        });
    }

    /**
     * Sets the advisory lifecycle state with a TTL, so a wedged transient state self-heals.
     *
     * @param state      the new state
     * @param ttlSeconds time-to-live in seconds
     * @return future completing when written
     */
    @Override
    public CompletableFuture<Void> setState(GameState state, int ttlSeconds) {
        return redis.async(jedis -> {
            jedis.setex(stateKey, ttlSeconds, state.wire());
            return null;
        });
    }

    /** @return future of the current lifecycle state (missing/blank/unknown → {@link GameState#IDLE}) */
    @Override
    public CompletableFuture<GameState> getState() {
        return redis.async(jedis -> GameState.fromWire(jedis.get(stateKey)));
    }

    /**
     * Attempts to acquire the auto-start window lock with {@code SET NX EX}, so exactly one proxy
     * opens a given scheduled window when several run.
     *
     * @param windowSeconds lock TTL (the window length)
     * @return future that is {@code true} when this proxy won the lock
     */
    @Override
    public CompletableFuture<Boolean> acquireAutoStartLock(int windowSeconds) {
        return redis.async(jedis -> {
            String result = jedis.set(autoStartLockKey, "1", SetParams.setParams().nx().ex(windowSeconds));
            return "OK".equals(result);
        });
    }

    private static long toLong(Object result) {
        if (result instanceof Long value) {
            return value;
        }
        if (result instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(result));
    }
}
