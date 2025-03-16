package cronozx.cullinggames;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.Scheduler;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.internal.DotenvReader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Plugin(id = "cullinggames", name = "cullinggames", version = BuildConstants.VERSION, authors = {"cronozx"})
public class CullingGames {

    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.from("cullinggames:main");
    private final Logger logger;
    private final ProxyServer server;
    private JedisPool jedisPool;

    @Inject
    public CullingGames(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;

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
                        }
                    }
                }, "cullinggames:velocity");
            } catch (Exception e) {
                logger.error("Error subscribing to Redis channel: " + e.getMessage());
            }
        }).start();

        new Thread(() -> {
          if (LocalTime.now().getMinute() == 0 && LocalTime.now().getSecond() == 0 && LocalTime.now().getHour() % 3 == 0) {
              for (RegisteredServer registeredServer: server.getAllServers()) {
                  registeredServer.sendMessage(Component.newline().content("Culling Games event starting now do /queue to join.").color(TextColor.color(255, 0, 0)));
                  if (registeredServer.getServerInfo().getName().equals("CullingGames")) {
                      server.getScheduler().buildTask(this, () -> {
                        String channel = "cullinggames:bukkit";
                        String startMsg = "cullinggames:start";
                        sendMessage(channel, startMsg);
                      }).delay(5, TimeUnit.MINUTES).schedule();
                  }

                  logger.info("Message sent");
              }
          }
        });
    }

    private void sendMessage(String channel, String message) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    @Subscribe
    public void onProxyDisable(ProxyShutdownEvent event) {
        jedisPool.close();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(IDENTIFIER);
    }
}
