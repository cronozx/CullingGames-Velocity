package cronozx.cullinggames.common;

/**
 * Which coordination backend network mode runs on.
 *
 * <p>Both backends implement the same coordination contract — a queue, a living roster, kill counts,
 * an advisory lifecycle state, expiring locks, and a proxy&harr;game-server message channel. They are
 * chosen per-installation by {@code network.backend} and <strong>must match on both sides</strong>:
 * the proxy and the game server coordinate through the backend itself, so a proxy on Redis and a
 * game server on MySQL will simply never see each other.
 *
 * <p>{@link #REDIS} is the reference implementation and the better fit — it has native pub/sub and
 * native key expiry. {@link #MYSQL} exists for hosts that provide MySQL but not Redis; it emulates
 * pub/sub by polling a message table and emulates TTLs with an {@code expires_at} column, so START
 * and STOP signals arrive with up to one poll interval of extra latency.
 */
public enum Backend {

    /** Redis (or any Redis-protocol server such as Valkey/KeyDB). Native pub/sub and key expiry. */
    REDIS,

    /** MySQL or MariaDB. Polled message table; {@code expires_at} columns in place of key TTLs. */
    MYSQL;

    /**
     * Parses a configured backend name, case- and whitespace-insensitively.
     *
     * <p>Follows the config module's self-healing convention: an unrecognised value falls back
     * rather than aborting startup. {@code "mariadb"} and {@code "sql"} are accepted as aliases for
     * {@link #MYSQL} because both are what users reach for first.
     *
     * @param raw      the raw config value; may be {@code null} or blank
     * @param fallback the value to use when {@code raw} is absent or unrecognised
     * @return the parsed backend, or {@code fallback}
     */
    public static Backend parse(String raw, Backend fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "REDIS", "VALKEY", "KEYDB" -> REDIS;
            case "MYSQL", "MARIADB", "SQL" -> MYSQL;
            default -> fallback;
        };
    }
}
