package cronozx.cullinggames.net;

import cronozx.cullinggames.common.Backend;
import cronozx.cullinggames.common.Protocol;
import cronozx.cullinggames.common.RedisSettings;
import cronozx.cullinggames.common.sql.SqlClient;
import cronozx.cullinggames.common.sql.SqlCoordination;
import cronozx.cullinggames.common.sql.SqlMessageBus;
import cronozx.cullinggames.common.sql.SqlSettings;
import cronozx.cullinggames.config.ProxyConfig;
import cronozx.cullinggames.net.redis.RedisTransport;
import cronozx.cullinggames.net.sql.SqlTransport;
import cronozx.cullinggames.queue.QueueService;
import cronozx.cullinggames.queue.redis.RedisQueueService;
import cronozx.cullinggames.queue.sql.SqlQueueService;
import cronozx.cullinggames.redis.RedisClient;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * The proxy's coordination backend: a {@link QueueService} and a {@link Transport}, built together
 * because they must share one connection.
 *
 * <p>{@link #create} is the only place in the proxy that branches on {@link Backend}; everything
 * downstream sees interfaces. Neither path connects eagerly, so an unreachable backend cannot fail
 * proxy startup.
 *
 * @param queue     the queue/roster/state operations
 * @param transport the message channel
 * @param bootstrap one-off backend preparation (table creation), or {@code null} if none is needed
 * @param namespace the identity that must match the game server's
 */
public record Coordination(QueueService queue, Transport transport,
                           java.util.function.Supplier<CompletableFuture<Void>> bootstrap,
                           String namespace) {

    /**
     * Builds the coordination layer for the configured backend.
     *
     * @param config the proxy config
     * @param logger plugin logger
     * @return the assembled coordination layer
     */
    public static Coordination create(ProxyConfig config, Logger logger) {
        if (config.backend() == Backend.MYSQL) {
            return createSql(config, logger);
        }
        return createRedis(config, logger);
    }

    private static Coordination createRedis(ProxyConfig config, Logger logger) {
        RedisSettings settings = config.redisSettings();
        RedisClient redis = new RedisClient(settings, logger);
        QueueService queue = new RedisQueueService(redis, settings.keyPrefix(), logger);
        Transport transport = new RedisTransport(redis, settings,
                Protocol.proxyChannel(settings.keyPrefix()),
                Protocol.gameChannel(settings.keyPrefix()),
                logger);
        return new Coordination(queue, transport, null, settings.keyPrefix());
    }

    private static Coordination createSql(ProxyConfig config, Logger logger) {
        SqlSettings settings = config.sqlSettings();
        SqlClient client = new SqlClient(settings, logger::warn, logger::info);
        SqlCoordination store = new SqlCoordination(client);
        SqlMessageBus bus = new SqlMessageBus(client, logger::warn, logger::info);
        // Channel names still come from Protocol, so the wire protocol is byte-identical on both
        // backends and neither side needs to know which the other is using at the message level.
        Transport transport = new SqlTransport(client, bus,
                Protocol.proxyChannel(settings.namespace()),
                Protocol.gameChannel(settings.namespace()));
        return new Coordination(new SqlQueueService(store), transport, store::initSchema,
                settings.namespace());
    }
}
