package cronozx.cullinggames.common.sql;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable MySQL/MariaDB connection settings shared by both runtime modules, mirroring
 * {@link cronozx.cullinggames.common.RedisSettings} in shape and precedence rules.
 *
 * <p>Values originate from each module's {@code config.yml} and are then reconciled with process
 * environment variables via {@link #resolve}. Precedence, highest first: environment variable
 * ({@code BR_MYSQL_*}), then the config value, then the hardcoded default the caller supplied.
 *
 * <h2>Namespacing</h2>
 * Two independent knobs, and it matters which is which:
 * <ul>
 *   <li>{@link #tablePrefix()} selects the <em>tables</em> ({@code battleroyale_queue},
 *       {@code battleroyale_kv}, …). It is interpolated directly into SQL — it cannot be a bind
 *       parameter — so it is sanitised to {@code [A-Za-z0-9_]} by the compact constructor.</li>
 *   <li>{@link #namespace()} is the value of the {@code ns} column and is the direct analogue of the
 *       Redis {@code key-prefix}. It is always a bind parameter, never interpolated.</li>
 * </ul>
 * Several independent BattleRoyale networks can therefore share one set of tables by choosing
 * different namespaces, exactly as they would share one Redis by choosing different key prefixes.
 *
 * <p>This record is deliberately driver-free: it never references any JDBC driver class, only the
 * JDK's own {@code java.sql}. The driver is a runtime concern of {@link SqlClient}.
 *
 * @param host                     database host
 * @param port                     database port
 * @param database                 schema/database name
 * @param username                 login user
 * @param password                 login password; blank means no password
 * @param tablePrefix              sanitised prefix for every table this plugin owns
 * @param namespace                value of the {@code ns} column; must match on both sides
 * @param connectTimeoutMs         socket/connect timeout in milliseconds
 * @param pollIntervalMs           how often the message poller checks for new rows (pub/sub emulation)
 * @param messageRetentionSeconds  how long delivered messages are kept before the janitor deletes them
 * @param useSsl                   whether to request a TLS connection
 */
public record SqlSettings(String host, int port, String database, String username, String password,
                          String tablePrefix, String namespace, int connectTimeoutMs,
                          int pollIntervalMs, int messageRetentionSeconds, boolean useSsl) {

    /** Environment variable overriding {@link #host()}. */
    public static final String ENV_HOST = "BR_MYSQL_HOST";
    /** Environment variable overriding {@link #port()}. */
    public static final String ENV_PORT = "BR_MYSQL_PORT";
    /** Environment variable overriding {@link #database()}. */
    public static final String ENV_DATABASE = "BR_MYSQL_DATABASE";
    /** Environment variable overriding {@link #username()}. */
    public static final String ENV_USERNAME = "BR_MYSQL_USERNAME";
    /** Environment variable overriding {@link #password()}. */
    public static final String ENV_PASSWORD = "BR_MYSQL_PASSWORD";

    /** Used when {@code tablePrefix} sanitises down to nothing. */
    public static final String DEFAULT_TABLE_PREFIX = "cullinggames_";

    /** Lower bound on {@link #pollIntervalMs()}; below this the poller is pure overhead. */
    private static final int MIN_POLL_INTERVAL_MS = 50;

    /** Upper bound on {@link #pollIntervalMs()}; beyond this START/STOP latency becomes obvious. */
    private static final int MAX_POLL_INTERVAL_MS = 5_000;

    /**
     * Canonical constructor. Sanitises the table prefix and clamps the tuning knobs so a hand-edited
     * config can never produce invalid SQL or a pathological poll rate.
     */
    public SqlSettings {
        tablePrefix = sanitizeTablePrefix(tablePrefix);
        namespace = (namespace == null || namespace.isBlank()) ? "cullinggames" : namespace.trim();
        connectTimeoutMs = Math.max(250, connectTimeoutMs);
        pollIntervalMs = Math.min(MAX_POLL_INTERVAL_MS, Math.max(MIN_POLL_INTERVAL_MS, pollIntervalMs));
        messageRetentionSeconds = Math.max(10, messageRetentionSeconds);
    }

    /**
     * Resolves settings from config values, applying {@code BR_MYSQL_*} overrides read from the real
     * process environment.
     *
     * @param host                    config host
     * @param port                    config port
     * @param database                config database name
     * @param username                config username
     * @param password                config password (blank for none)
     * @param tablePrefix             config table prefix
     * @param namespace               config namespace (the {@code ns} column value)
     * @param connectTimeoutMs        config connect timeout
     * @param pollIntervalMs          config poll interval
     * @param messageRetentionSeconds config message retention
     * @param useSsl                  config TLS flag
     * @return the reconciled settings
     */
    public static SqlSettings resolve(String host, int port, String database, String username,
                                      String password, String tablePrefix, String namespace,
                                      int connectTimeoutMs, int pollIntervalMs,
                                      int messageRetentionSeconds, boolean useSsl) {
        return resolve(host, port, database, username, password, tablePrefix, namespace,
                connectTimeoutMs, pollIntervalMs, messageRetentionSeconds, useSsl, System.getenv());
    }

    /**
     * Resolves settings against an explicit environment map, keeping the override logic testable
     * without mutating real process environment variables.
     *
     * <p>A {@code BR_MYSQL_*} variable overrides its config counterpart only when present and
     * non-blank. An unparseable {@code BR_MYSQL_PORT} is ignored (the config port is kept) rather
     * than aborting startup.
     *
     * @param host                    config host
     * @param port                    config port
     * @param database                config database name
     * @param username                config username
     * @param password                config password (blank for none)
     * @param tablePrefix             config table prefix
     * @param namespace               config namespace (the {@code ns} column value)
     * @param connectTimeoutMs        config connect timeout
     * @param pollIntervalMs          config poll interval
     * @param messageRetentionSeconds config message retention
     * @param useSsl                  config TLS flag
     * @param env                     environment source; {@code null} is treated as an empty map
     * @return the reconciled settings
     */
    public static SqlSettings resolve(String host, int port, String database, String username,
                                      String password, String tablePrefix, String namespace,
                                      int connectTimeoutMs, int pollIntervalMs,
                                      int messageRetentionSeconds, boolean useSsl,
                                      Map<String, String> env) {
        Map<String, String> source = (env == null) ? Collections.emptyMap() : env;
        return new SqlSettings(
                override(source.get(ENV_HOST), host),
                overridePort(source.get(ENV_PORT), port),
                override(source.get(ENV_DATABASE), database),
                override(source.get(ENV_USERNAME), username),
                override(source.get(ENV_PASSWORD), password),
                tablePrefix, namespace, connectTimeoutMs, pollIntervalMs, messageRetentionSeconds,
                useSsl);
    }

    /**
     * Builds the JDBC URL for these settings.
     *
     * <p>{@code allowPublicKeyRetrieval} is required for MySQL 8's default {@code caching_sha2}
     * authentication over a non-TLS connection, which is the common case for a database on the same
     * host as the server.
     *
     * @return the JDBC URL
     */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl
                + "&allowPublicKeyRetrieval=" + !useSsl
                + "&connectTimeout=" + connectTimeoutMs
                + "&socketTimeout=" + Math.max(connectTimeoutMs, 10_000)
                + "&characterEncoding=UTF-8"
                + "&rewriteBatchedStatements=true";
    }

    /**
     * Fully-qualified name of one of this plugin's tables.
     *
     * @param table the unprefixed table name (a compile-time constant from {@link SqlSchema})
     * @return the prefixed table name, safe to interpolate into SQL
     */
    public String table(String table) {
        return tablePrefix + table;
    }

    /**
     * Strips everything outside {@code [A-Za-z0-9_]} from a configured table prefix, because the
     * prefix is interpolated into SQL and cannot be parameterised. Falls back to
     * {@link #DEFAULT_TABLE_PREFIX} when nothing usable survives.
     */
    private static String sanitizeTablePrefix(String raw) {
        if (raw == null) {
            return DEFAULT_TABLE_PREFIX;
        }
        StringBuilder clean = new StringBuilder(raw.length());
        for (char c : raw.trim().toCharArray()) {
            if (c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                clean.append(c);
            }
        }
        // A prefix starting with a digit would make `123_queue` an invalid identifier.
        while (clean.length() > 0 && Character.isDigit(clean.charAt(0))) {
            clean.deleteCharAt(0);
        }
        return clean.length() == 0 ? DEFAULT_TABLE_PREFIX : clean.toString();
    }

    private static String override(String envValue, String configValue) {
        return (envValue != null && !envValue.isBlank()) ? envValue : configValue;
    }

    private static int overridePort(String envValue, int configValue) {
        if (envValue == null || envValue.isBlank()) {
            return configValue;
        }
        try {
            return Integer.parseInt(envValue.trim());
        } catch (NumberFormatException notANumber) {
            return configValue;
        }
    }
}
