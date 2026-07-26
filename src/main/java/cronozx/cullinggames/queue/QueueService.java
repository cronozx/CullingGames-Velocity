package cronozx.cullinggames.queue;

import cronozx.cullinggames.common.GameState;
import cronozx.cullinggames.common.LiveMatch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Every queue, roster, lifecycle-state and lock operation the proxy performs, independent of which
 * backend holds them.
 *
 * <p>The mirror image of the game server's {@code GameStore}: two implementations
 * ({@link cronozx.cullinggames.queue.redis.RedisQueueService} and
 * {@link cronozx.cullinggames.queue.sql.SqlQueueService}), one contract, and nothing
 * above this interface knows which is in play. Every method is asynchronous so the Velocity event
 * threads never block.
 *
 * <p>The proxy and the game server must be configured for the <strong>same</strong> backend and the
 * same namespace — they coordinate through the backend itself, so a mismatch is not an error anyone
 * can detect, it simply means the two never see each other.
 */
public interface QueueService {

    /**
     * Atomically enqueues a player, honouring the open/closed state and the capacity limit.
     *
     * <p>The status codes are part of the contract and identical on both backends: {@code -2} the
     * queue is not open, {@code -1} already queued, {@code 0} full, {@code >=1} the new queue size.
     *
     * @param player the player's UUID
     * @param max    the authoritative queue capacity
     * @return future of the status code
     */
    CompletableFuture<Long> enqueue(UUID player, int max);

    /**
     * Removes a player from the queue.
     *
     * @param player the player's UUID
     * @return future of the number of removed entries ({@code >0} means the player was queued)
     */
    CompletableFuture<Long> leave(UUID player);

    /** @return future of the current queue length. */
    CompletableFuture<Long> size();

    /**
     * @param player the player's UUID
     * @return future that is {@code true} when the player is in the queue
     */
    CompletableFuture<Boolean> contains(UUID player);

    /** @return future completing when the queue has been emptied. */
    CompletableFuture<Void> clearQueue();

    /**
     * Every round currently registered as live.
     *
     * <p>Protocol v2's index of what is running. The proxy is not scoped to any single round, so
     * every roster question it asks has to start here.
     *
     * @return future of the live rounds
     */
    CompletableFuture<List<LiveMatch>> liveMatches();

    /**
     * @param matchId the round
     * @return future of that round's living-player roster
     */
    CompletableFuture<Set<UUID>> aliveIn(String matchId);

    /**
     * @param matchId the round
     * @return future of that round's living-player count
     */
    CompletableFuture<Long> aliveCountIn(String matchId);

    /**
     * Removes a player from one round's roster.
     *
     * @param matchId the round
     * @param player  the player's UUID
     * @return future of the number removed
     */
    CompletableFuture<Long> removeAliveFrom(String matchId, UUID player);

    /**
     * The living roster across every live round.
     *
     * @return future of the union of all live rounds' rosters
     */
    default CompletableFuture<Set<UUID>> alive() {
        return liveMatches().thenCompose(matches -> {
            List<CompletableFuture<Set<UUID>>> reads = new ArrayList<>(matches.size());
            for (LiveMatch match : matches) {
                reads.add(aliveIn(match.matchId()));
            }
            return allOf(reads).thenApply(rosters -> {
                Set<UUID> union = new LinkedHashSet<>();
                for (Set<UUID> roster : rosters) {
                    union.addAll(roster);
                }
                return union;
            });
        });
    }

    /**
     * The number of living players across every live round.
     *
     * <p>This is what tells the auto-start scheduler whether anything is running at all, so it sums
     * rather than reading one round: with several rounds in flight, "is the network busy" is a
     * question about all of them.
     *
     * @return future of the total living-player count
     */
    default CompletableFuture<Long> aliveCount() {
        return liveMatches().thenCompose(matches -> {
            List<CompletableFuture<Long>> reads = new ArrayList<>(matches.size());
            for (LiveMatch match : matches) {
                reads.add(aliveCountIn(match.matchId()));
            }
            return allOf(reads).thenApply(counts -> {
                long total = 0;
                for (Long count : counts) {
                    total += (count == null) ? 0 : count;
                }
                return total;
            });
        });
    }

    /**
     * Removes a player from whichever live round holds them — what a disconnect means when the proxy
     * does not know which round the player was in.
     *
     * @param player the player's UUID
     * @return future of the total number removed across all rounds
     */
    default CompletableFuture<Long> removeAlive(UUID player) {
        return liveMatches().thenCompose(matches -> {
            List<CompletableFuture<Long>> removals = new ArrayList<>(matches.size());
            for (LiveMatch match : matches) {
                removals.add(removeAliveFrom(match.matchId(), player));
            }
            return allOf(removals).thenApply(results -> {
                long total = 0;
                for (Long removed : results) {
                    total += (removed == null) ? 0 : removed;
                }
                return total;
            });
        });
    }

    /** Collects a list of futures into a future of a list, preserving order. */
    private static <T> CompletableFuture<List<T>> allOf(List<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    List<T> out = new ArrayList<>(futures.size());
                    for (CompletableFuture<T> future : futures) {
                        out.add(future.join());
                    }
                    return out;
                });
    }

    /**
     * Sets the advisory lifecycle state with no expiry.
     *
     * @param state the new state
     * @return future completing when written
     */
    CompletableFuture<Void> setState(GameState state);

    /**
     * Sets the advisory lifecycle state with a TTL, so a wedged transient state self-heals.
     *
     * @param state      the new state
     * @param ttlSeconds time-to-live in seconds
     * @return future completing when written
     */
    CompletableFuture<Void> setState(GameState state, int ttlSeconds);

    /** @return future of the current lifecycle state; missing/blank/unknown reads as IDLE. */
    CompletableFuture<GameState> getState();

    /**
     * Attempts to acquire the auto-start window lock, so exactly one proxy opens a given scheduled
     * window when several run.
     *
     * @param windowSeconds lock TTL (the window length)
     * @return future that is {@code true} when this proxy won the lock
     */
    CompletableFuture<Boolean> acquireAutoStartLock(int windowSeconds);
}
