package cronozx.cullinggames.common.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Creates the five tables that stand in for the Redis key space. Idempotent — every statement is
 * {@code CREATE TABLE IF NOT EXISTS}, so it is safe to run on every startup from every server.
 *
 * <p>Round <em>coordination</em> only. Persistent player data (lifetime stats, and later ELO) has
 * different consistency needs and a different portability target — it must also work on SQLite,
 * which has no {@code AUTO_INCREMENT} — so it lives behind the game server's storage layer instead.
 * See {@code bukkit/storage/StatsSchema}.
 *
 * <h2>Mapping from the Redis key space</h2>
 * <table>
 *   <caption>Redis structure to SQL table</caption>
 *   <tr><th>Redis</th><th>SQL</th></tr>
 *   <tr><td>{@code <prefix>:queue} (LIST)</td>
 *       <td>{@code _queue} — ordered by an {@code AUTO_INCREMENT} {@code seq};
 *           {@code UNIQUE(ns, player)} gives the {@code LPOS} duplicate check for free</td></tr>
 *   <tr><td>{@code <prefix>:alive} (SET)</td>
 *       <td>{@code _alive} — {@code PRIMARY KEY(ns, player)} is the set semantics</td></tr>
 *   <tr><td>{@code <prefix>:kills} (HASH)</td>
 *       <td>{@code _kills} — one row per member; {@code HINCRBY} becomes an upsert</td></tr>
 *   <tr><td>{@code <prefix>:state}, {@code :lock}, {@code :autostart-lock} (STRING + TTL)</td>
 *       <td>{@code _kv} — one row per key with a nullable {@code expires_at}</td></tr>
 *   <tr><td>{@code <prefix>:game} / {@code :proxy} (PUB/SUB)</td>
 *       <td>{@code _messages} — append-only; subscribers poll with an {@code id} cursor</td></tr>
 * </table>
 *
 * <h2>Portability</h2>
 * The DDL sticks to constructs MySQL 5.7+, MariaDB 10.2+, and H2's MySQL compatibility mode all
 * accept, so the store logic can be exercised against an in-memory database in tests. Time is always
 * taken from the <em>database server</em> ({@code CURRENT_TIMESTAMP}), never from a plugin host, so
 * expiry is immune to clock skew between the proxy and the game server.
 */
public final class SqlSchema {

    /** Unprefixed name of the pending-queue table. */
    public static final String QUEUE = "queue";
    /** Unprefixed name of the living-roster table. */
    public static final String ALIVE = "alive";
    /** Unprefixed name of the kill-count table. */
    public static final String KILLS = "kills";
    /** Unprefixed name of the key/value table that carries state and both locks. */
    public static final String KV = "kv";
    /** Unprefixed name of the append-only message table. */
    public static final String MESSAGES = "messages";
    /** Unprefixed name of the live-match registry table (protocol v2). */
    public static final String MATCHES = "matches";

    /** {@code _kv} row key holding the advisory lifecycle state. */
    public static final String KEY_STATE = "state";
    /** {@code _kv} row key holding the single-game lock. */
    public static final String KEY_LOCK = "lock";
    /** {@code _kv} row key holding the auto-start window lock. */
    public static final String KEY_AUTOSTART_LOCK = "autostart-lock";

    private SqlSchema() {
    }

    /**
     * Builds the {@code CREATE TABLE IF NOT EXISTS} statements for a given table prefix.
     *
     * @param settings the settings supplying the sanitised table prefix
     * @return the DDL statements, in dependency order (there are no foreign keys, so any order works)
     */
    public static List<String> ddl(SqlSettings settings) {
        return List.of(
                // Ordered queue. `seq` is the list order; UNIQUE(ns, player) enforces "already queued".
                "CREATE TABLE IF NOT EXISTS " + settings.table(QUEUE) + " ("
                        + "seq BIGINT NOT NULL AUTO_INCREMENT,"
                        + "ns VARCHAR(64) NOT NULL,"
                        + "player CHAR(36) NOT NULL,"
                        + "PRIMARY KEY (seq),"
                        + "CONSTRAINT uq_" + settings.tablePrefix() + QUEUE + " UNIQUE (ns, player)"
                        + ")",

                // Living roster. The composite primary key *is* the set.
                "CREATE TABLE IF NOT EXISTS " + settings.table(ALIVE) + " ("
                        + "ns VARCHAR(64) NOT NULL,"
                        + "player CHAR(36) NOT NULL,"
                        + "PRIMARY KEY (ns, player)"
                        + ")",

                // Kill counts (the Redis HASH).
                "CREATE TABLE IF NOT EXISTS " + settings.table(KILLS) + " ("
                        + "ns VARCHAR(64) NOT NULL,"
                        + "player CHAR(36) NOT NULL,"
                        + "kills INT NOT NULL DEFAULT 0,"
                        + "PRIMARY KEY (ns, player)"
                        + ")",

                // State + locks. A NULL expires_at means "no TTL"; a past one means the row is dead
                // but not yet swept, so every read filters on it rather than trusting the sweeper.
                "CREATE TABLE IF NOT EXISTS " + settings.table(KV) + " ("
                        + "ns VARCHAR(64) NOT NULL,"
                        + "k VARCHAR(64) NOT NULL,"
                        + "v VARCHAR(255) NOT NULL,"
                        + "expires_at TIMESTAMP NULL DEFAULT NULL,"
                        + "PRIMARY KEY (ns, k)"
                        + ")",

                // Live-match registry (protocol v2). The proxy's index of what is running: which
                // rounds exist, and on which game server. Purely additive — an existing database
                // gains this table on next startup and needs no migration, because every other
                // table was already scoped by its `ns` column.
                "CREATE TABLE IF NOT EXISTS " + settings.table(MATCHES) + " ("
                        + "ns VARCHAR(64) NOT NULL,"
                        + "match_id VARCHAR(64) NOT NULL,"
                        + "game_server VARCHAR(64) NOT NULL,"
                        + "arena VARCHAR(64) NOT NULL,"
                        + "started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (ns, match_id)"
                        + ")",

                // Pub/sub replacement. Append-only; a janitor trims rows past the retention window.
                "CREATE TABLE IF NOT EXISTS " + settings.table(MESSAGES) + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "ns VARCHAR(64) NOT NULL,"
                        + "channel VARCHAR(64) NOT NULL,"
                        + "payload VARCHAR(2048) NOT NULL,"
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (id)"
                        + ")");
    }

    /**
     * Secondary indexes, kept out of {@link #ddl} because {@code CREATE INDEX IF NOT EXISTS} is not
     * portable — MariaDB and H2 accept it, MySQL does not. {@link #apply} therefore runs these
     * separately and tolerates "already exists".
     *
     * @param settings the settings supplying the table prefix
     * @return the index statements
     */
    public static List<String> indexes(SqlSettings settings) {
        String messages = settings.table(MESSAGES);
        return List.of(
                // Covers the subscriber's `ns=? AND channel=? AND id>? ORDER BY id` poll.
                "CREATE INDEX idx_" + settings.tablePrefix() + "poll ON " + messages + " (ns, channel, id)",
                // Covers the janitor's retention sweep.
                "CREATE INDEX idx_" + settings.tablePrefix() + "created ON " + messages + " (created_at)");
    }

    /**
     * Applies the schema. Safe to call concurrently from several servers — {@code IF NOT EXISTS}
     * makes a lost race a no-op, and an index that already exists is ignored.
     *
     * @param connection an open connection
     * @param settings   the settings supplying the table prefix
     * @throws SQLException if a table statement fails (e.g. the user lacks CREATE)
     */
    public static void apply(Connection connection, SqlSettings settings) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : ddl(settings)) {
                statement.execute(sql);
            }
            for (String sql : indexes(settings)) {
                try {
                    statement.execute(sql);
                } catch (SQLException alreadyExists) {
                    // Expected on every startup after the first. Indexes are a performance detail;
                    // a genuine failure here must not stop the plugin from coordinating.
                }
            }
        }
    }
}
