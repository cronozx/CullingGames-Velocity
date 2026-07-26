package cronozx.cullinggames.config;

import cronozx.cullinggames.common.Backend;
import cronozx.cullinggames.common.RedisSettings;
import cronozx.cullinggames.common.sql.SqlSettings;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configurate-backed proxy configuration. On first run the bundled {@code config.yml} is copied from
 * the jar into the plugin data directory. {@link #reload()} re-reads the file from disk into a fresh
 * immutable snapshot, so mutable lists (like {@code announce.servers}) are rebuilt each time and
 * never accumulate duplicates.
 *
 * <p>The current snapshot is held behind a single {@code volatile} reference and swapped atomically,
 * so readers on other threads always observe a fully-parsed, self-consistent state.
 */
public final class ProxyConfig {

    private static final String DEFAULT_PREFIX =
            "<gradient:#f4c430:#ff8c00><bold>Battle Royale</bold></gradient> <dark_gray>»</dark_gray> ";

    private static final List<String> DEFAULT_DAILY_TIMES = List.of(
            "00:00", "03:00", "06:00", "09:00", "12:00", "15:00", "18:00", "21:00");

    private final Path dataDirectory;
    private final Logger logger;

    private volatile Data data;

    /**
     * @param dataDirectory the plugin data directory (from {@code @DataDirectory})
     * @param logger        plugin logger
     */
    public ProxyConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    /** Loads the config at startup, using safe defaults if the file cannot be read. */
    public void load() {
        try {
            this.data = readFromDisk();
        } catch (Exception ex) {
            logger.error("Failed to load proxy config; using defaults: {}", ex.getMessage());
            if (this.data == null) {
                this.data = Data.defaults();
            }
        }
    }

    /**
     * Re-reads the config from disk into a fresh snapshot.
     *
     * @return {@code true} if the reload succeeded (the previous snapshot is kept on failure)
     */
    public boolean reload() {
        try {
            this.data = readFromDisk();
            return true;
        } catch (Exception ex) {
            logger.error("Failed to reload proxy config; keeping previous configuration: {}", ex.getMessage());
            return false;
        }
    }

    private Data readFromDisk() throws IOException {
        ensureConfigPresent();
        Path configPath = dataDirectory.resolve("config.yml");
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(configPath).build();
        CommentedConfigurationNode root;
        try {
            root = loader.load();
        } catch (IOException ex) {
            throw new IOException("Failed to parse config.yml: " + ex.getMessage(), ex);
        }

        String host = root.node("redis", "host").getString("127.0.0.1");
        int port = root.node("redis", "port").getInt(6379);
        String username = root.node("redis", "username").getString("");
        String password = root.node("redis", "password").getString("");
        String keyPrefix = root.node("redis", "key-prefix").getString("cullinggames");
        int timeoutMs = root.node("redis", "timeout-ms").getInt(2000);
        RedisSettings redis = RedisSettings.resolve(host, port, username, password, keyPrefix, timeoutMs);

        String backendRaw = root.node("backend").getString("redis");
        Backend backend = Backend.parse(backendRaw, Backend.REDIS);
        if (Backend.parse(backendRaw, null) == null) {
            logger.warn("Unknown backend '{}'; using redis.", backendRaw);
        }

        // The namespace defaults to the Redis key-prefix so a network keeps one identity across a
        // backend switch, and a config that never mentions MySQL still yields valid settings.
        // Blank counts as absent, matching the "blank = inherit key-prefix" note in config.yml.
        String namespaceRaw = root.node("mysql", "namespace").getString("");
        String namespace = namespaceRaw.isBlank() ? keyPrefix : namespaceRaw.trim();
        SqlSettings mysql = SqlSettings.resolve(
                root.node("mysql", "host").getString("127.0.0.1"),
                root.node("mysql", "port").getInt(3306),
                root.node("mysql", "database").getString("cullinggames"),
                root.node("mysql", "username").getString("root"),
                root.node("mysql", "password").getString(""),
                root.node("mysql", "table-prefix").getString("cullinggames_"),
                namespace,
                root.node("mysql", "timeout-ms").getInt(2000),
                root.node("mysql", "poll-interval-ms").getInt(250),
                root.node("mysql", "message-retention-seconds").getInt(120),
                root.node("mysql", "use-ssl").getBoolean(false));

        String gameServer = root.node("servers", "game-server").getString("cullinggames");
        String lobbyServer = root.node("servers", "lobby-server").getString("lobby");

        int minPlayers = root.node("queue", "min-players").getInt(5);
        int maxPlayers = root.node("queue", "max-players").getInt(30);
        int windowSeconds = root.node("queue", "window-seconds").getInt(120);

        List<String> announceServers = getStringList(root.node("announce", "servers"), Collections.emptyList());

        boolean autoStartEnabled = root.node("auto-start", "enabled").getBoolean(true);
        String timezoneRaw = root.node("auto-start", "timezone").getString("UTC");
        ZoneId timezone = parseZone(timezoneRaw);
        List<String> dailyTimesRaw = getStringList(root.node("auto-start", "daily-times"), DEFAULT_DAILY_TIMES);
        List<LocalTime> dailyTimes = parseDailyTimes(dailyTimesRaw);
        int intervalMinutes = root.node("auto-start", "interval-minutes").getInt(0);

        Map<String, String> messages = readMessages(root.node("messages"));

        return new Data(backend, redis, mysql, gameServer, lobbyServer, minPlayers, maxPlayers, windowSeconds,
                List.copyOf(announceServers), autoStartEnabled, timezone, timezoneRaw,
                List.copyOf(dailyTimes), List.copyOf(dailyTimesRaw), intervalMinutes,
                Collections.unmodifiableMap(messages));
    }

    private void ensureConfigPresent() throws IOException {
        if (Files.notExists(dataDirectory)) {
            Files.createDirectories(dataDirectory);
        }
        Path configPath = dataDirectory.resolve("config.yml");
        if (Files.notExists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) {
                    throw new IOException("Bundled config.yml is missing from the plugin jar");
                }
                Files.copy(in, configPath);
            }
        }
    }

    private List<String> getStringList(ConfigurationNode node, List<String> fallback) {
        try {
            List<String> list = node.getList(String.class);
            if (list == null) {
                return new ArrayList<>(fallback);
            }
            return new ArrayList<>(list);
        } catch (SerializationException ex) {
            logger.warn("Failed to read list at '{}'; using default: {}", node.path(), ex.getMessage());
            return new ArrayList<>(fallback);
        }
    }

    private ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            logger.warn("Invalid timezone '{}'; falling back to UTC.", raw);
            return ZoneId.of("UTC");
        }
    }

    private List<LocalTime> parseDailyTimes(List<String> raw) {
        List<LocalTime> parsed = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                parsed.add(LocalTime.parse(entry.trim()));
            } catch (DateTimeParseException ex) {
                logger.warn("Invalid daily-time '{}'; skipping.", entry);
            }
        }
        Collections.sort(parsed);
        return parsed;
    }

    private Map<String, String> readMessages(ConfigurationNode messagesNode) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : messagesNode.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            String value = entry.getValue().getString();
            if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    /** @return resolved Redis settings (with {@code BR_REDIS_*} env overrides applied) */
    public Backend backend() {
        return data.backend();
    }

    /** @return the MySQL settings (used when {@link #backend()} is {@link Backend#MYSQL}). */
    public SqlSettings sqlSettings() {
        return data.mysql();
    }

    /**
     * @return the identity that must match the game server's — the Redis key prefix, or on MySQL the
     *         {@code ns} column value that plays the same role
     */
    public String namespace() {
        return (data.backend() == Backend.MYSQL)
                ? data.mysql().namespace()
                : data.redis().keyPrefix();
    }

    /** @return the Redis settings (used when {@link #backend()} is {@link Backend#REDIS}). */
    public RedisSettings redisSettings() {
        return data.redis();
    }

    /** @return the configured Redis key prefix */
    public String keyPrefix() {
        return data.redis().keyPrefix();
    }

    /** @return the arena backend server name */
    public String gameServer() {
        return data.gameServer();
    }

    /** @return the lobby backend server name */
    public String lobbyServer() {
        return data.lobbyServer();
    }

    /** @return the minimum players required to start */
    public int minPlayers() {
        return data.minPlayers();
    }

    /** @return the maximum queue capacity */
    public int maxPlayers() {
        return data.maxPlayers();
    }

    /** @return how long the queue window stays open, in seconds */
    public int windowSeconds() {
        return data.windowSeconds();
    }

    /** @return the servers that receive the start broadcast (empty means all servers) */
    public List<String> announceServers() {
        return data.announceServers();
    }

    /** @return whether scheduled auto-starts are enabled */
    public boolean autoStartEnabled() {
        return data.autoStartEnabled();
    }

    /** @return the auto-start timezone */
    public ZoneId timezone() {
        return data.timezone();
    }

    /** @return the raw timezone id string (for diagnostics) */
    public String timezoneRaw() {
        return data.timezoneRaw();
    }

    /** @return the parsed, sorted daily auto-start times */
    public List<LocalTime> dailyTimes() {
        return data.dailyTimes();
    }

    /** @return the raw daily-time strings (for diagnostics) */
    public List<String> dailyTimesRaw() {
        return data.dailyTimesRaw();
    }

    /** @return the auto-start interval in minutes ({@code >0} overrides daily-times) */
    public int intervalMinutes() {
        return data.intervalMinutes();
    }

    /** @return the MiniMessage prefix template */
    public String prefix() {
        return data.messages().getOrDefault("prefix", DEFAULT_PREFIX);
    }

    /**
     * Looks up a raw MiniMessage message template.
     *
     * @param key message key under {@code messages:}
     * @return the raw template, or a visible fallback if the key is missing
     */
    public String message(String key) {
        String value = data.messages().get(key);
        if (value == null) {
            logger.warn("Missing message key '{}' in proxy config.", key);
            return "<red>missing message: " + key + "</red>";
        }
        return value;
    }

    /**
     * Immutable parsed configuration snapshot.
     */
    private record Data(Backend backend, RedisSettings redis, SqlSettings mysql,
                        String gameServer, String lobbyServer,
                        int minPlayers, int maxPlayers, int windowSeconds,
                        List<String> announceServers, boolean autoStartEnabled,
                        ZoneId timezone, String timezoneRaw,
                        List<LocalTime> dailyTimes, List<String> dailyTimesRaw,
                        int intervalMinutes, Map<String, String> messages) {

        static Data defaults() {
            RedisSettings redis = RedisSettings.resolve(
                    "127.0.0.1", 6379, "", "", "cullinggames", 2000);
            SqlSettings mysql = SqlSettings.resolve("127.0.0.1", 3306, "cullinggames", "root", "",
                    "cullinggames_", "cullinggames", 2000, 250, 120, false);
            List<LocalTime> times = new ArrayList<>();
            for (String time : DEFAULT_DAILY_TIMES) {
                times.add(LocalTime.parse(time));
            }
            return new Data(Backend.REDIS, redis, mysql, "cullinggames", "lobby", 5, 30, 120,
                    List.of("lobby"), true, ZoneId.of("UTC"), "UTC",
                    List.copyOf(times), DEFAULT_DAILY_TIMES, 0,
                    Map.of("prefix", DEFAULT_PREFIX));
        }
    }
}
