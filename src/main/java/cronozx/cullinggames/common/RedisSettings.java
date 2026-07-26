package cronozx.cullinggames.common;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable Redis connection settings shared by both runtime modules.
 *
 * <p>Values originate from each module's {@code config.yml} and are then reconciled with process
 * environment variables via {@link #resolve}. Precedence, highest first:
 * <ol>
 *   <li>environment variable ({@code BR_REDIS_HOST}, {@code BR_REDIS_PORT},
 *       {@code BR_REDIS_USERNAME}, {@code BR_REDIS_PASSWORD});</li>
 *   <li>the config value passed to {@code resolve};</li>
 *   <li>the hardcoded default the caller supplied as that config value.</li>
 * </ol>
 *
 * <p>This record is deliberately dependency-free: it never references Jedis. Each runtime module
 * builds its own {@code JedisPool} from these settings (username non-blank → ACL user + password
 * auth; password only → password auth; both blank → no auth), keeping the {@code common} module
 * free of any third-party jar.
 *
 * @param host      Redis host
 * @param port      Redis port
 * @param username  ACL username; blank means legacy/no-user auth
 * @param password  password; blank means no password
 * @param keyPrefix namespaces every channel and key; must match on both sides
 * @param timeoutMs socket/connection timeout in milliseconds
 */
public record RedisSettings(String host, int port, String username, String password,
                            String keyPrefix, int timeoutMs) {

    /** Environment variable overriding {@link #host()}. */
    public static final String ENV_HOST = "BR_REDIS_HOST";
    /** Environment variable overriding {@link #port()}. */
    public static final String ENV_PORT = "BR_REDIS_PORT";
    /** Environment variable overriding {@link #username()}. */
    public static final String ENV_USERNAME = "BR_REDIS_USERNAME";
    /** Environment variable overriding {@link #password()}. */
    public static final String ENV_PASSWORD = "BR_REDIS_PASSWORD";

    /**
     * Resolves settings from config values, applying {@code BR_REDIS_*} overrides read from the real
     * process environment ({@link System#getenv()}).
     *
     * @param host      config host
     * @param port      config port
     * @param user      config username (blank for none)
     * @param pass      config password (blank for none)
     * @param prefix    config key prefix
     * @param timeoutMs config timeout in milliseconds
     * @return the reconciled settings
     */
    public static RedisSettings resolve(String host, int port, String user, String pass,
                                        String prefix, int timeoutMs) {
        return resolve(host, port, user, pass, prefix, timeoutMs, System.getenv());
    }

    /**
     * Resolves settings from config values against an explicit environment map. This overload keeps
     * environment-override logic unit-testable without mutating real process environment variables.
     *
     * <p>A {@code BR_REDIS_*} variable overrides its config counterpart only when present and
     * non-blank. An unparseable {@code BR_REDIS_PORT} is ignored (the config port is kept) rather
     * than aborting startup.
     *
     * @param host      config host
     * @param port      config port
     * @param user      config username (blank for none)
     * @param pass      config password (blank for none)
     * @param prefix    config key prefix
     * @param timeoutMs config timeout in milliseconds
     * @param env       environment source; {@code null} is treated as an empty map
     * @return the reconciled settings
     */
    public static RedisSettings resolve(String host, int port, String user, String pass,
                                        String prefix, int timeoutMs, Map<String, String> env) {
        Map<String, String> source = (env == null) ? Collections.emptyMap() : env;

        String resolvedHost = override(source.get(ENV_HOST), host);
        int resolvedPort = overridePort(source.get(ENV_PORT), port);
        String resolvedUser = override(source.get(ENV_USERNAME), user);
        String resolvedPass = override(source.get(ENV_PASSWORD), pass);

        return new RedisSettings(resolvedHost, resolvedPort, resolvedUser, resolvedPass,
                prefix, timeoutMs);
    }

    /**
     * Returns {@code envValue} when it is present and non-blank, otherwise {@code configValue}.
     */
    private static String override(String envValue, String configValue) {
        return (envValue != null && !envValue.isBlank()) ? envValue : configValue;
    }

    /**
     * Parses an overriding port from the environment, falling back to the config port when the
     * variable is absent, blank, or not a valid integer.
     */
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
