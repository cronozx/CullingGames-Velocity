package cronozx.cullinggames;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import cronozx.cullinggames.commands.ReloadCommand;
import io.github.cdimascio.dotenv.Dotenv;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Plugin(id = "cullinggames", name = "cullinggames", authors = {"cronozx"})
public class CullingGames {

    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from("cullinggames:main");
    private final ProxyServer server;
    private final JedisPool jedisPool;
    private boolean queueOpen = false;
    private boolean isRunning = true;
    private final Thread redisSubscriberThread;
    private static final List<String> servers = new ArrayList<>();
    private static CommentedConfigurationNode node;

    @Inject
    public CullingGames(ProxyServer server, Logger logger, @DataDirectory Path dataPath) {
        this.server = server;

        Dotenv dotenv = Dotenv.load();
        String redisHost = dotenv.get("CULLING_REDIS_HOST");
        int redisPort = Integer.parseInt(dotenv.get("CULLING_REDIS_PORT"));
        String redisPassword = dotenv.get("CULLING_REDIS_PASSWORD");

        if (Files.notExists(dataPath)) {
            try {
                Files.createDirectory(dataPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Path configPath = dataPath.resolve("config.yml");
        if (Files.notExists(configPath)) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                Files.copy(stream, configPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        final YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(configPath).build();
        try {
            node = loader.load();
        } catch (ConfigurateException e) {
            throw new RuntimeException(e);
        }

        getWhitelistServers(logger);

        this.jedisPool = new JedisPool(new JedisPoolConfig(), redisHost, redisPort, 2000, redisPassword);

        redisSubscriberThread = new Thread(() -> {
            while (isRunning) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            try {
                                if (message == null) {
                                    return;
                                }

                                String messageType = message.contains(":") ?
                                        message.substring(0, message.indexOf(':')) :
                                        message;

                                switch (messageType) {
                                    case "teleportPlayersTo" -> {
                                        String[] parts = message.split(":");

                                        String serverName = parts[1];
                                        List<String> playerNames = Arrays.stream(parts[2].split(","))
                                                .map(name -> name.replace("[", "").replace("]", ""))
                                                .toList();
                                        Optional<RegisteredServer> registeredServer = server.getServer(serverName);

                                        if (registeredServer.isPresent()) {
                                            for (String playerName : playerNames) {
                                                Optional<Player> player = server.getPlayer(playerName);

                                                player.ifPresent(value -> value.createConnectionRequest(registeredServer.get()).fireAndForget());
                                            }
                                        }
                                    }
                                    case "teleportTo" -> {
                                        String[] parts = message.split(":");
                                        if (parts.length < 3) {
                                            logger.warn("Invalid message format. Message: {}", message);
                                            return;
                                        }

                                        String serverName = parts[1];
                                        String playerName = parts[2];
                                        Optional<Player> player = server.getPlayer(playerName);
                                        Optional<RegisteredServer> targetServer = server.getServer(serverName);

                                        if (player.isPresent() && targetServer.isPresent()) {
                                            player.get().createConnectionRequest(targetServer.get()).fireAndForget();
                                        }
                                    }
                                    case "queue" -> {
                                        String playerUUID = message.split(":")[1];
                                        if (queueOpen) {
                                            queuePlayer(playerUUID);
                                            sendMessage("cullinggames:bukkit", "confirmQueue:" + playerUUID);
                                        } else {
                                            Optional<Player> optionalPlayer = server.getPlayer(UUID.fromString(playerUUID));
                                            if (optionalPlayer.isPresent()) {
                                                optionalPlayer.get().sendMessage(Component.newline().content("§4§lCulling Games §8§l>> §r§7The queue is not currently open"));
                                            } else {
                                                logger.warn("Attempted to send message to offline player: {}", playerUUID);
                                            }
                                        }
                                    }
                                    case "forceStart" -> {
                                        queueOpen = true;
                                        server.getAllServers().forEach(registeredServer -> {
                                                    System.out.println(registeredServer.getServerInfo().getName());
                                                    if (servers.contains(registeredServer.getServerInfo().getName())) {
                                                        registeredServer.sendMessage(Component.newline().content("§4§lCulling Games §8§l>> §r§7A Culling Games event is starting now. ").append(
                                                                        Component.text("§f§nClick here§r")
                                                                                .hoverEvent(HoverEvent.showText(Component.text("§fClick to join queue")))
                                                                                .clickEvent(ClickEvent.runCommand("/cullinggames:queue")).asComponent())
                                                                .append(Component.text(" to join.")).asComponent());
                                                    }
                                            }
                                        );

                                        server.getScheduler().buildTask(CullingGames.this, () -> {
                                            logger.info("Force Start executed");
                                            queueOpen = false;
                                            String forceChannel = "cullinggames:bukkit";
                                            String forceStartMsg = "start";
                                            sendMessage(forceChannel, forceStartMsg);
                                        }).delay(1, TimeUnit.MINUTES).schedule();
                                    }
                                    case "gameCanceled" -> {
                                        Optional<RegisteredServer> hubServer = server.getServer("hub");
                                        Optional<RegisteredServer> cullingServer = server.getServer("CullingGames");

                                        if (hubServer.isPresent() && cullingServer.isPresent()) {
                                            for (Player player: cullingServer.get().getPlayersConnected()) {
                                                player.createConnectionRequest(hubServer.get()).fireAndForget();
                                                player.sendMessage(Component.newline().content("§4§lCulling Games §8§l>> §r§7Not enough players to start."));
                                            }
                                        }
                                    }
                                    case "gameCanceledEarly" -> {
                                        for (RegisteredServer registeredServer: server.getAllServers()) {
                                            registeredServer.sendMessage(Component.newline().content("§4§lCulling Games §8§l>> §r§7Not enough players to start."));
                                        }
                                    }
                                    case "timeout" -> {
                                        String playerUUID = message.split(":")[1];
                                        Optional<Player> player = server.getPlayer(UUID.fromString(playerUUID));
                                        player.ifPresent(value -> value.disconnect(Component.newline().content("Timed out")));
                                    }
                                    default -> logger.warn("Unknown message type: {}", message);
                                }
                            } catch (Exception e) {
                                logger.error("Error processing Redis message: {}", e.getMessage(), e);
                            }
                        }
                    }, "cullinggames:velocity");
                } catch (Exception e) {
                    logger.error("Error subscribing to Redis channel: {}", e.getMessage());

                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        isRunning = false;
                    }
                }
            }
        });

        redisSubscriberThread.setName("Redis-Subscriber");
        redisSubscriberThread.setDaemon(true);
        redisSubscriberThread.start();

        new Thread(() -> server.getScheduler().buildTask(CullingGames.this, () -> {
            if (LocalTime.now().getMinute() == 0 && LocalTime.now().getSecond() == 0 && LocalTime.now().getHour() % 3 == 0 && playersInGame() <= 0) {
                clearQueue();
                this.queueOpen = true;
                TextComponent message = Component.newline().content("§4§lCulling Games §8§l>> §r§7A Culling Games event is starting now. ").append(
                        Component.text("§f§nClick here§r")
                                .hoverEvent(HoverEvent.showText(Component.text("§fClick to join queue§r")))
                                .clickEvent(ClickEvent.runCommand("/cullinggames:queue")).asComponent()).append(Component.text(" to join.")).asComponent();

                for (RegisteredServer registeredServer : server.getAllServers()) {
                    if (servers.contains(registeredServer.getServerInfo().getName())) {
                        registeredServer.sendMessage(message);
                        if (registeredServer.getServerInfo().getName().equals("CullingGames")) {
                            server.getScheduler().buildTask(this, () -> {
                                this.queueOpen = false;
                                String channel = "cullinggames:bukkit";
                                String startMsg = "start";
                                sendMessage(channel, startMsg);
                            }).delay(5, TimeUnit.MINUTES).schedule();
                        }
                    }
                }
            }
        }).repeat(1, TimeUnit.SECONDS).schedule()).start();
    }

    private void sendMessage(String channel, String message) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    @Subscribe
    public void onProxyDisable(ProxyShutdownEvent event) {
        clearQueue();
        jedisPool.close();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(IDENTIFIER);

        CommandManager commandManager = server.getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder("reload").plugin(this).aliases("cullingreload").build();

        commandManager.register(commandMeta, new ReloadCommand());
    }

    @Subscribe
    public void onPlayerLeave(DisconnectEvent event) {
        Player player = event.getPlayer();

        if (playerInQueue(player)) {
            removePlayerFromQueue(player);
        }

        if (isPlayerInGames(player)) {
            removePlayerFromGames(player);
        }
    }

    public void clearQueue() {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "playerQueue";
            String keyType = jedis.type(key);

            if ("list".equals(keyType)) {
                jedis.del(key);
            } else {
                System.err.println("Expected 'playerQueue' to be a string but found: " + keyType);
            }
        }
    }

    public void queuePlayer(String playerUUID) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.rpush("playerQueue", playerUUID);
        }
    }

    public boolean playerInQueue(Player player) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.lrange("playerQueue", 0, -1).contains(player.getUniqueId().toString());
        }
    }

    public void removePlayerFromQueue(Player player) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lrem("playerQueue", 0, player.getUniqueId().toString());
        }
    }

    public boolean isPlayerInGames(Player player) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hexists("playerPoints", player.getUniqueId().toString());
        }
    }

    public void removePlayerFromGames(Player player) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hdel("playerPoints", player.getUniqueId().toString());
        }
    }

    public int playersInGame() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hkeys("playerPoints").size();
        }
    }

    public static void getWhitelistServers(Logger logger) {
        try {
            List<String> configServers = node.node("Server-Whitelist").getList(String.class);
            if (configServers != null) {
                servers.addAll(configServers);
            }
        } catch (SerializationException e) {
            logger.error("Failed to load server whitelist", e);
        }
    }
}