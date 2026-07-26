package cronozx.cullinggames.common.sql;

import cronozx.cullinggames.common.GameState;
import cronozx.cullinggames.common.LiveMatch;
import cronozx.cullinggames.common.Protocol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Every coordination operation both runtime modules perform, implemented against SQL.
 *
 * <p>This is the SQL twin of the game server's {@code GameStore} and the proxy's {@code QueueService}
 * combined — one class rather than two because both sides read and write the same rows, and a single
 * implementation is the only way to guarantee they agree. Each side wraps it in a thin adapter that
 * exposes only the half it needs.
 *
 * <h2>Atomicity</h2>
 * The Redis implementation uses server-side Lua for the four operations that must not interleave —
 * the queue drain, the capacity-checked enqueue, lock acquisition, and stale-state cleanup. Here each
 * of those runs inside a single {@link SqlClient#transaction} instead, with {@code SELECT … FOR
 * UPDATE} where a read informs a later write. Everything else is a single statement and needs no
 * explicit transaction.
 *
 * <h2>Time</h2>
 * All expiry arithmetic uses the database server's {@code CURRENT_TIMESTAMP}, never a plugin host's
 * clock, so a proxy and a game server whose clocks disagree still expire locks identically.
 */
public final class SqlCoordination {

    /** SQL state class for an integrity-constraint violation (duplicate key). */
    private static final String SQLSTATE_INTEGRITY_VIOLATION = "23";

    private final SqlClient client;
    private final String ns;
    private final String matchNs;
    private final String queueTable;
    private final String aliveTable;
    private final String killsTable;
    private final String kvTable;
    private final String matchesTable;

    /**
     * Builds a coordination view over the namespace-global rows only — the pending queue and the
     * auto-start lock. This is what the proxy uses for queue work that belongs to no single round.
     *
     * @param client the SQL client (owns the connection and the I/O thread)
     */
    public SqlCoordination(SqlClient client) {
        this(client, Protocol.DEFAULT_MATCH_ID);
    }

    /**
     * Builds a coordination view scoped to one round.
     *
     * <p>Protocol v2 splits the rows in two. The queue table and the auto-start lock stay on the
     * plain namespace, because there is one queue per network however many rounds run. The roster,
     * kill counts, round lock and round state move to {@code <namespace>:m:<matchId>}, which is what
     * stops a second concurrent round from writing into the first one's roster.
     *
     * <p>The {@code ns} column already existed on every one of those tables, so scoping by match is
     * a change of value rather than of schema — an existing database needs no migration.
     *
     * @param client  the SQL client (owns the connection and the I/O thread)
     * @param matchId the round to scope to
     */
    public SqlCoordination(SqlClient client, String matchId) {
        this.client = client;
        SqlSettings settings = client.settings();
        this.ns = settings.namespace();
        this.matchNs = Protocol.matchKey(settings.namespace(), matchId);
        this.queueTable = settings.table(SqlSchema.QUEUE);
        this.aliveTable = settings.table(SqlSchema.ALIVE);
        this.killsTable = settings.table(SqlSchema.KILLS);
        this.kvTable = settings.table(SqlSchema.KV);
        this.matchesTable = settings.table(SqlSchema.MATCHES);
    }

    /**
     * Creates the tables if they do not exist.
     *
     * @return a future completing when the schema is in place, or failing if the user lacks CREATE
     */
    public CompletableFuture<Void> initSchema() {
        return client.async(connection -> {
            SqlSchema.apply(connection, client.settings());
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Queue
    // ------------------------------------------------------------------------------------------

    /** @return the pending queue in insertion order. */
    public CompletableFuture<List<UUID>> readQueue() {
        return client.async(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player FROM " + queueTable + " WHERE ns=? ORDER BY seq")) {
                select.setString(1, ns);
                return readUuidList(select);
            }
        });
    }

    /** @return the current queue length. */
    public CompletableFuture<Long> queueSize() {
        return client.async(connection -> countWhereNs(connection, ns, queueTable));
    }

    /**
     * Whether a player is queued.
     *
     * @param player the player
     * @return future that is {@code true} when the player is in the queue
     */
    public CompletableFuture<Boolean> queueContains(UUID player) {
        return client.async(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT 1 FROM " + queueTable + " WHERE ns=? AND player=?")) {
                select.setString(1, ns);
                select.setString(2, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next();
                }
            }
        });
    }

    /**
     * Atomically enqueues a player, honouring the open/closed state and the capacity limit.
     *
     * <p>Return codes match the Redis {@code ENQUEUE_SCRIPT} exactly so callers are backend-agnostic:
     * {@code -2} the queue is not OPEN, {@code -1} already queued, {@code 0} full, {@code >=1} the new
     * queue size.
     *
     * @param player the player
     * @param max    the authoritative capacity
     * @return future of the status code
     */
    public CompletableFuture<Long> enqueue(UUID player, int max) {
        return client.transaction(connection -> {
            // Same order of checks as the Lua: state, then duplicate, then capacity.
            if (!GameState.OPEN.wire().equals(readKv(connection, ns, SqlSchema.KEY_STATE))) {
                return -2L;
            }
            // FOR UPDATE serialises concurrent enqueues for this namespace, so the capacity check
            // below cannot be overtaken between the count and the insert.
            long queued;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT seq FROM " + queueTable + " WHERE ns=? FOR UPDATE")) {
                select.setString(1, ns);
                try (ResultSet rows = select.executeQuery()) {
                    long counted = 0;
                    while (rows.next()) {
                        counted++;
                    }
                    queued = counted;
                }
            }
            if (containsInTransaction(connection, player)) {
                return -1L;
            }
            if (queued >= max) {
                return 0L;
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + queueTable + " (ns, player) VALUES (?, ?)")) {
                insert.setString(1, ns);
                insert.setString(2, player.toString());
                insert.executeUpdate();
            } catch (SQLException duplicate) {
                if (isDuplicateKey(duplicate)) {
                    return -1L;
                }
                throw duplicate;
            }
            return queued + 1;
        });
    }

    private boolean containsInTransaction(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM " + queueTable + " WHERE ns=? AND player=?")) {
            select.setString(1, ns);
            select.setString(2, player.toString());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Removes a player from the queue.
     *
     * @param player the player
     * @return future of the number of rows removed ({@code >0} means they were queued)
     */
    public CompletableFuture<Long> leaveQueue(UUID player) {
        return client.async(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + queueTable + " WHERE ns=? AND player=?")) {
                delete.setString(1, ns);
                delete.setString(2, player.toString());
                return (long) delete.executeUpdate();
            }
        });
    }

    /** @return a future completing when the queue has been emptied. */
    public CompletableFuture<Void> clearQueue() {
        return client.async(connection -> {
            deleteAllForNs(connection, ns, queueTable);
            return null;
        });
    }

    /**
     * Atomically drains the queue into the living roster and empties the queue — the SQL counterpart
     * of the {@code DRAIN_SCRIPT} Lua.
     *
     * <p>The insert is expressed as {@code INSERT … SELECT … WHERE NOT EXISTS} rather than
     * {@code INSERT IGNORE} so it stays within portable SQL while still tolerating a player who is
     * somehow both queued and already alive.
     *
     * @return future of the drained roster in queue order
     */
    public CompletableFuture<List<UUID>> moveQueueToAlive() {
        return client.transaction(connection -> {
            List<UUID> drained;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player FROM " + queueTable + " WHERE ns=? ORDER BY seq FOR UPDATE")) {
                select.setString(1, ns);
                drained = readUuidList(select);
            }
            if (!drained.isEmpty()) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + aliveTable + " (ns, player) "
                                + "SELECT ?, q.player FROM " + queueTable + " q "
                                + "WHERE q.ns=? AND NOT EXISTS ("
                                + "SELECT 1 FROM " + aliveTable + " a WHERE a.ns=? AND a.player=q.player)")) {
                    insert.setString(1, matchNs);
                    insert.setString(2, ns);
                    insert.setString(3, matchNs);
                    insert.executeUpdate();
                }
                deleteAllForNs(connection, ns, queueTable);
            }
            return drained;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Living roster
    // ------------------------------------------------------------------------------------------

    /** @return the living roster. */
    public CompletableFuture<Set<UUID>> alive() {
        return client.async(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player FROM " + aliveTable + " WHERE ns=?")) {
                select.setString(1, matchNs);
                return new LinkedHashSet<>(readUuidList(select));
            }
        });
    }

    /** @return the number of living players. */
    public CompletableFuture<Long> aliveCount() {
        return client.async(connection -> countWhereNs(connection, matchNs, aliveTable));
    }

    /**
     * @param player the player
     * @return future that is {@code true} when the player is in the living roster
     */
    public CompletableFuture<Boolean> isAlive(UUID player) {
        return client.async(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT 1 FROM " + aliveTable + " WHERE ns=? AND player=?")) {
                select.setString(1, matchNs);
                select.setString(2, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next();
                }
            }
        });
    }

    /**
     * Removes a player from the living roster. Idempotent.
     *
     * @param player the player
     * @return future of the number of rows removed (0 or 1)
     */
    public CompletableFuture<Long> removeAlive(UUID player) {
        return client.async(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + aliveTable + " WHERE ns=? AND player=?")) {
                delete.setString(1, matchNs);
                delete.setString(2, player.toString());
                return (long) delete.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Kills
    // ------------------------------------------------------------------------------------------

    /**
     * Increments a player's kill count, creating the row on first kill — the SQL form of
     * {@code HINCRBY}.
     *
     * @param player the killer
     * @return future of the new kill total
     */
    public CompletableFuture<Long> incrementKill(UUID player) {
        return client.transaction(connection -> {
            int updated;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + killsTable + " SET kills = kills + 1 WHERE ns=? AND player=?")) {
                update.setString(1, matchNs);
                update.setString(2, player.toString());
                updated = update.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + killsTable + " (ns, player, kills) VALUES (?, ?, 1)")) {
                    insert.setString(1, matchNs);
                    insert.setString(2, player.toString());
                    insert.executeUpdate();
                }
                return 1L;
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT kills FROM " + killsTable + " WHERE ns=? AND player=?")) {
                select.setString(1, matchNs);
                select.setString(2, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Live-match registry (protocol v2)
    // ------------------------------------------------------------------------------------------

    /**
     * Records this round as live, so the proxy can enumerate what is running.
     *
     * <p>Idempotent: re-registering the same id refreshes the row rather than failing, which is what
     * lets a game server that reconnects mid-round re-announce itself safely.
     *
     * @param gameServer the game server running the round
     * @param arena      the arena id
     * @return a future completing when registered
     */
    public CompletableFuture<Void> registerMatch(String gameServer, String arena) {
        String matchId = matchIdOf(matchNs);
        return client.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + matchesTable + " SET game_server=?, arena=? WHERE ns=? AND match_id=?")) {
                update.setString(1, gameServer);
                update.setString(2, arena);
                update.setString(3, ns);
                update.setString(4, matchId);
                if (update.executeUpdate() > 0) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + matchesTable + " (ns, match_id, game_server, arena) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, ns);
                insert.setString(2, matchId);
                insert.setString(3, gameServer);
                insert.setString(4, arena);
                insert.executeUpdate();
            } catch (SQLException duplicate) {
                if (!isDuplicateKey(duplicate)) {
                    throw duplicate;
                }
            }
            return null;
        });
    }

    /**
     * Removes this round from the live registry.
     *
     * @return a future completing when removed
     */
    public CompletableFuture<Void> unregisterMatch() {
        String matchId = matchIdOf(matchNs);
        return client.async(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + matchesTable + " WHERE ns=? AND match_id=?")) {
                delete.setString(1, ns);
                delete.setString(2, matchId);
                delete.executeUpdate();
            }
            return null;
        });
    }

    /** @return every round currently registered as live in this namespace. */
    public CompletableFuture<List<LiveMatch>> liveMatches() {
        return client.async(connection -> {
            List<LiveMatch> out = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT match_id, game_server, arena FROM " + matchesTable
                            + " WHERE ns=? ORDER BY started_at, match_id")) {
                select.setString(1, ns);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        try {
                            out.add(new LiveMatch(rows.getString(1), rows.getString(2), rows.getString(3)));
                        } catch (IllegalArgumentException malformed) {
                            // Tolerate a stray row rather than failing the whole enumeration.
                        }
                    }
                }
            }
            return out;
        });
    }

    /**
     * Counts living players in a named round — the cross-match read the proxy needs, since its own
     * view is not scoped to any one round.
     *
     * @param matchId the round to count
     * @return future of the living-player count
     */
    public CompletableFuture<Long> aliveCountIn(String matchId) {
        String rowNs = Protocol.matchKey(ns, matchId);
        return client.async(connection -> countWhereNs(connection, rowNs, aliveTable));
    }

    /**
     * Removes a player from a named round's roster.
     *
     * @param matchId the round
     * @param player  the player
     * @return future of the number of rows removed
     */
    public CompletableFuture<Long> removeAliveFrom(String matchId, UUID player) {
        String rowNs = Protocol.matchKey(ns, matchId);
        return client.async(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + aliveTable + " WHERE ns=? AND player=?")) {
                delete.setString(1, rowNs);
                delete.setString(2, player.toString());
                return (long) delete.executeUpdate();
            }
        });
    }

    /**
     * Reads a named round's roster.
     *
     * @param matchId the round
     * @return future of the living players
     */
    public CompletableFuture<Set<UUID>> aliveIn(String matchId) {
        String rowNs = Protocol.matchKey(ns, matchId);
        return client.async(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player FROM " + aliveTable + " WHERE ns=?")) {
                select.setString(1, rowNs);
                return new LinkedHashSet<>(readUuidList(select));
            }
        });
    }

    /** Recovers the match id from a match namespace built by {@link Protocol#matchKey}. */
    private String matchIdOf(String matchNamespace) {
        int marker = matchNamespace.lastIndexOf(":m:");
        return (marker < 0) ? matchNamespace : matchNamespace.substring(marker + 3);
    }

    // ------------------------------------------------------------------------------------------
    // Advisory state
    // ------------------------------------------------------------------------------------------

    /**
     * Sets the queue-window state — whether the <em>queue</em> is open, closing or idle.
     *
     * <p>Distinct from {@link #setState}: the queue window belongs to the namespace, not to any one
     * round, so under protocol v2 the two live in different rows. {@link #enqueue} reads this one
     * when it decides whether the queue is accepting players.
     *
     * @param state      the new queue-window state
     * @param ttlSeconds TTL in seconds; {@code <= 0} means no expiry
     * @return a future completing when written
     */
    public CompletableFuture<Void> setQueueState(GameState state, int ttlSeconds) {
        return client.transaction(connection -> {
            writeKv(connection, ns, SqlSchema.KEY_STATE, state.wire(), ttlSeconds);
            return null;
        });
    }

    /** @return the queue-window state; a missing, blank, or expired row reads as {@link GameState#IDLE}. */
    public CompletableFuture<GameState> getQueueState() {
        return client.async(connection -> GameState.fromWire(readKv(connection, ns, SqlSchema.KEY_STATE)));
    }

    /**
     * Sets the advisory lifecycle state with no expiry.
     *
     * @param state the new state
     * @return a future completing when written
     */
    public CompletableFuture<Void> setState(GameState state) {
        return setState(state, 0);
    }

    /**
     * Sets the advisory lifecycle state, optionally with a TTL so a wedged transient state self-heals.
     *
     * @param state      the new state
     * @param ttlSeconds TTL in seconds; {@code <= 0} means no expiry
     * @return a future completing when written
     */
    public CompletableFuture<Void> setState(GameState state, int ttlSeconds) {
        return client.transaction(connection -> {
            writeKv(connection, matchNs, SqlSchema.KEY_STATE, state.wire(), ttlSeconds);
            return null;
        });
    }

    /** @return the advisory state; a missing, blank, or expired row reads as {@link GameState#IDLE}. */
    public CompletableFuture<GameState> getState() {
        return client.async(connection -> GameState.fromWire(readKv(connection, matchNs, SqlSchema.KEY_STATE)));
    }

    // ------------------------------------------------------------------------------------------
    // Locks (TTL emulation)
    // ------------------------------------------------------------------------------------------

    /**
     * Acquires the single-game lock — the SQL form of {@code SET NX EX}.
     *
     * <p>Expired rows for this namespace are swept first, inside the same transaction, so a lock left
     * behind by a crashed server is reclaimed exactly as a Redis TTL would have reclaimed it. The
     * insert then either succeeds (lock acquired) or violates the primary key (someone else holds it).
     *
     * @param owner      this server's identity, written as the row value
     * @param ttlSeconds crash-recovery TTL
     * @return future that is {@code true} when the lock was acquired
     */
    public CompletableFuture<Boolean> acquireLock(String owner, int ttlSeconds) {
        return acquireExpiringKey(matchNs, SqlSchema.KEY_LOCK, owner, ttlSeconds);
    }

    /**
     * Acquires the auto-start window lock, so exactly one proxy opens a given scheduled window.
     *
     * @param windowSeconds lock TTL (the window length)
     * @return future that is {@code true} when this proxy won the lock
     */
    public CompletableFuture<Boolean> acquireAutoStartLock(int windowSeconds) {
        return acquireExpiringKey(ns, SqlSchema.KEY_AUTOSTART_LOCK, "1", windowSeconds);
    }

    private CompletableFuture<Boolean> acquireExpiringKey(String rowNs, String key, String value,
            int ttlSeconds) {
        return client.transaction(connection -> {
            sweepExpired(connection, rowNs);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + kvTable + " (ns, k, v, expires_at) "
                            + "VALUES (?, ?, ?, TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP))")) {
                insert.setString(1, rowNs);
                insert.setString(2, key);
                insert.setString(3, value);
                insert.setInt(4, Math.max(1, ttlSeconds));
                insert.executeUpdate();
                return true;
            } catch (SQLException duplicate) {
                if (isDuplicateKey(duplicate)) {
                    return false;
                }
                throw duplicate;
            }
        });
    }

    /**
     * Refreshes the game lock's expiry, but only while this owner still holds it — the compare-and-
     * expire the Redis side does in Lua.
     *
     * @param owner      this server's identity
     * @param ttlSeconds the new TTL
     * @return future that is {@code true} when the row was refreshed
     */
    public CompletableFuture<Boolean> refreshLock(String owner, int ttlSeconds) {
        return client.async(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + kvTable + " SET expires_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP) "
                            + "WHERE ns=? AND k=? AND v=? "
                            + "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")) {
                update.setInt(1, Math.max(1, ttlSeconds));
                update.setString(2, matchNs);
                update.setString(3, SqlSchema.KEY_LOCK);
                update.setString(4, owner);
                return update.executeUpdate() == 1;
            }
        });
    }

    /**
     * Releases the game lock, but only while this owner still holds it (compare-and-delete).
     *
     * @param owner this server's identity
     * @return future that is {@code true} when the row was deleted
     */
    public CompletableFuture<Boolean> releaseLock(String owner) {
        return client.async(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + kvTable + " WHERE ns=? AND k=? AND v=?")) {
                delete.setString(1, matchNs);
                delete.setString(2, SqlSchema.KEY_LOCK);
                delete.setString(3, owner);
                return delete.executeUpdate() == 1;
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Round reset
    // ------------------------------------------------------------------------------------------

    /**
     * If no live game lock is held, clears transient round state and resets the state to IDLE —
     * self-healing a network whose game server crashed mid-round.
     *
     * @return future that is {@code true} when cleanup ran (i.e. no lock was held)
     */
    public CompletableFuture<Boolean> cleanupStaleGameIfUnlocked() {
        return client.transaction(connection -> {
            sweepExpired(connection, matchNs);
            if (readKv(connection, matchNs, SqlSchema.KEY_LOCK) != null) {
                return false;
            }
            resetRound(connection);
            return true;
        });
    }

    /**
     * Clears transient round state and resets the state to IDLE. Does not touch the lock — the caller
     * releases it separately so ownership is verified.
     *
     * @return a future completing when the reset is done
     */
    public CompletableFuture<Void> clearAll() {
        return client.transaction(connection -> {
            resetRound(connection);
            return null;
        });
    }

    private void resetRound(Connection connection) throws SQLException {
        deleteAllForNs(connection, matchNs, aliveTable);
        deleteAllForNs(connection, matchNs, killsTable);
        writeKv(connection, matchNs, SqlSchema.KEY_STATE, GameState.IDLE.wire(), 0);
    }

    // ------------------------------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Reads a key/value row, treating an expired row as absent. Reads always filter on
     * {@code expires_at} rather than trusting the sweeper, so expiry is exact even between sweeps.
     */
    private String readKv(Connection connection, String rowNs, String key) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT v FROM " + kvTable + " WHERE ns=? AND k=? "
                        + "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")) {
            select.setString(1, rowNs);
            select.setString(2, key);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /**
     * Upserts a key/value row. Expressed as UPDATE-then-INSERT rather than
     * {@code ON DUPLICATE KEY UPDATE} to stay inside portable SQL; callers already hold a transaction.
     */
    private void writeKv(Connection connection, String rowNs, String key, String value, int ttlSeconds)
            throws SQLException {
        String expiry = (ttlSeconds > 0) ? "TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP)" : "NULL";
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + kvTable + " SET v=?, expires_at=" + expiry + " WHERE ns=? AND k=?")) {
            int index = 1;
            update.setString(index++, value);
            if (ttlSeconds > 0) {
                update.setInt(index++, ttlSeconds);
            }
            update.setString(index++, rowNs);
            update.setString(index, key);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + kvTable + " (ns, k, v, expires_at) VALUES (?, ?, ?, " + expiry + ")")) {
            insert.setString(1, rowNs);
            insert.setString(2, key);
            insert.setString(3, value);
            if (ttlSeconds > 0) {
                insert.setInt(4, ttlSeconds);
            }
            insert.executeUpdate();
        }
    }

    /** Deletes every expired row in the given namespace. Also serves as the {@code _kv} janitor. */
    private void sweepExpired(Connection connection, String rowNs) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + kvTable
                        + " WHERE ns=? AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP")) {
            delete.setString(1, rowNs);
            delete.executeUpdate();
        }
    }

    private void deleteAllForNs(Connection connection, String rowNs, String table) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE ns=?")) {
            delete.setString(1, rowNs);
            delete.executeUpdate();
        }
    }

    private long countWhereNs(Connection connection, String rowNs, String table) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE ns=?")) {
            select.setString(1, rowNs);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /** Reads a single-column {@code player} result set, skipping anything that is not a UUID. */
    private static List<UUID> readUuidList(PreparedStatement statement) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String raw = rows.getString(1);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                try {
                    out.add(UUID.fromString(raw.trim()));
                } catch (IllegalArgumentException notAUuid) {
                    // Tolerate stray rows, exactly as the Redis implementation tolerates stray members.
                }
            }
        }
        return out;
    }

    /** @return whether the failure is a duplicate-key violation rather than a real error. */
    private static boolean isDuplicateKey(SQLException failure) {
        String state = failure.getSQLState();
        return state != null && state.startsWith(SQLSTATE_INTEGRITY_VIOLATION);
    }
}
