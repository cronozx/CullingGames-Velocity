package cronozx.cullinggames;

import com.google.inject.Inject;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.cdimascio.dotenv.Dotenv;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Plugin(id = "cullinggames", name = "cullinggames", version = BuildConstants.VERSION, authors = {"cronozx"})
public class CullingGames {

    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from("cullinggames:main");
    private final ProxyServer server;
    private final JedisPool jedisPool;
    private boolean queueOpen = false;

    @Inject
    public CullingGames(ProxyServer server, Logger logger) {
        this.server = server;

        Dotenv dotenv = Dotenv.load();
        String redisHost = dotenv.get("CULLING_REDIS_HOST");
        int redisPort = Integer.parseInt(dotenv.get("CULLING_REDIS_PORT"));
        String redisPassword = dotenv.get("CULLING_REDIS_PASSWORD");

        this.jedisPool = new JedisPool(new JedisPoolConfig(), redisHost, redisPort, 2000, redisPassword);

        new Thread(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        if (message.startsWith("teleportTo:")) {
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
                        } else if (message.startsWith("queue:")) {
                            String playerUUID = message.split(":")[1];
                            if (queueOpen) {
                                queuePlayer(playerUUID);
                            } else {
                                Optional<Player> optionalPlayer = server.getPlayer(UUID.fromString(playerUUID));
                                if (optionalPlayer.isPresent()) {
                                    optionalPlayer.get().sendMessage(Component.newline().content("Culling Games >> The queue is not currently open").color(TextColor.color(255, 0, 0)));
                                } else {
                                    logger.warn("Attempted to send message to offline player: {}", playerUUID);
                                }
                            }
                        } else if (message.equals("forceStart")) {
                            queueOpen = true;
                            server.getAllServers().forEach(registeredServer -> registeredServer.sendMessage(Component.newline().content("Culling Games >> Culling Games event starting now do /queue to join.").color(TextColor.color(255, 0, 0))));
                            server.getScheduler().buildTask(CullingGames.this, () -> {
                                logger.info("Force Start executed");
                                queueOpen = false;
                                String forceChannel = "cullinggames:bukkit";
                                String forceStartMsg = "start";
                                sendMessage(forceChannel, forceStartMsg);
                            }).delay(1, TimeUnit.MINUTES).schedule();
                        } else if (message.startsWith("gameCanceled:")) {
                            String playerUUID = message.split(":")[1];
                            Player player = server.getPlayer(UUID.fromString(playerUUID)).get();
                            InetSocketAddress hubSocketAddress = server.getServer("hub").get().getServerInfo().getAddress();

                            player.transferToHost(hubSocketAddress);

                            player.sendMessage(Component.newline().content("Culling Games >> Not enough players to start."));
                        } else if (message.startsWith("timeout:")) {
                            String playerUUID = message.split(":")[1];
                            Player player = server.getPlayer(UUID.fromString(playerUUID)).get();

                            player.disconnect(Component.newline().content("Timed out"));
                        }
                    }
                }, "cullinggames:velocity");
            } catch (Exception e) {
                logger.error("Error subscribing to Redis channel: {}", e.getMessage());
            }
        }).start();

        new Thread(() -> server.getScheduler().buildTask(CullingGames.this, () -> {
            if (LocalTime.now().getMinute() == 0 && LocalTime.now().getSecond() == 0 && LocalTime.now().getHour() % 3 == 0) {
                clearQueue();
                this.queueOpen = true;
                for (RegisteredServer registeredServer: server.getAllServers()) {
                    registeredServer.sendMessage(Component.newline().content("Culling Games >> Culling Games event starting now do /queue to join.").color(TextColor.color(255, 0, 0)));
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
}
