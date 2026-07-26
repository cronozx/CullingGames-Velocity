package cronozx.cullinggames.queue.sql;

import cronozx.cullinggames.common.GameState;
import cronozx.cullinggames.common.LiveMatch;
import cronozx.cullinggames.common.sql.SqlCoordination;
import cronozx.cullinggames.queue.QueueService;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The SQL-backed {@link QueueService}. A thin adapter over
 * {@link cronozx.cullinggames.common.sql.SqlCoordination} — the same class the game server's
 * store adapts, so both sides genuinely operate on one implementation rather than two that have to be
 * kept in step by hand.
 */
public final class SqlQueueService implements QueueService {

    private final SqlCoordination store;

    /**
     * @param store the shared SQL coordination layer
     */
    public SqlQueueService(SqlCoordination store) {
        this.store = store;
    }

    @Override
    public CompletableFuture<Long> enqueue(UUID player, int max) {
        return store.enqueue(player, max);
    }

    @Override
    public CompletableFuture<Long> leave(UUID player) {
        return store.leaveQueue(player);
    }

    @Override
    public CompletableFuture<Long> size() {
        return store.queueSize();
    }

    @Override
    public CompletableFuture<Boolean> contains(UUID player) {
        return store.queueContains(player);
    }

    @Override
    public CompletableFuture<List<LiveMatch>> liveMatches() {
        return store.liveMatches();
    }

    @Override
    public CompletableFuture<Set<UUID>> aliveIn(String matchId) {
        return store.aliveIn(matchId);
    }

    @Override
    public CompletableFuture<Long> aliveCountIn(String matchId) {
        return store.aliveCountIn(matchId);
    }

    @Override
    public CompletableFuture<Long> removeAliveFrom(String matchId, UUID player) {
        return store.removeAliveFrom(matchId, player);
    }

    @Override
    public CompletableFuture<Void> clearQueue() {
        return store.clearQueue();
    }

    // alive(), aliveCount() and removeAlive() are deliberately NOT overridden: the interface's
    // default implementations fan out across every live round, which is what the proxy needs now
    // that a namespace can carry more than one. Delegating to the store here would silently answer
    // for a single round instead.

    /**
     * The proxy's state is the <em>queue window's</em>, not any round's, so it maps to the
     * namespace-global row rather than the match-scoped one.
     */
    @Override
    public CompletableFuture<Void> setState(GameState state) {
        return store.setQueueState(state, 0);
    }

    @Override
    public CompletableFuture<Void> setState(GameState state, int ttlSeconds) {
        return store.setQueueState(state, ttlSeconds);
    }

    @Override
    public CompletableFuture<GameState> getState() {
        return store.getQueueState();
    }

    @Override
    public CompletableFuture<Boolean> acquireAutoStartLock(int windowSeconds) {
        return store.acquireAutoStartLock(windowSeconds);
    }
}
