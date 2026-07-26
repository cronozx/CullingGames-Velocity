package cronozx.cullinggames.net.redis;

import cronozx.cullinggames.common.RedisSettings;
import cronozx.cullinggames.net.Transport;
import cronozx.cullinggames.redis.RedisClient;
import cronozx.cullinggames.redis.RedisSubscriber;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * Redis pub/sub {@link Transport} for the proxy: subscribes to the proxy channel and publishes to the
 * game channel.
 *
 * <p>Owns the {@link RedisClient} it is given — closing the transport closes the pool.
 */
public final class RedisTransport implements Transport {

    private final RedisClient redis;
    private final RedisSettings settings;
    private final String inboundChannel;
    private final String outboundChannel;
    private final Logger logger;

    private RedisSubscriber subscriber;

    /**
     * @param redis           the Redis client (ownership transfers to this transport)
     * @param settings        resolved Redis settings, used for the startup summary
     * @param inboundChannel  channel the proxy subscribes to
     * @param outboundChannel channel the proxy publishes to
     * @param logger          plugin logger
     */
    public RedisTransport(RedisClient redis, RedisSettings settings, String inboundChannel,
                          String outboundChannel, Logger logger) {
        this.redis = redis;
        this.settings = settings;
        this.inboundChannel = inboundChannel;
        this.outboundChannel = outboundChannel;
        this.logger = logger;
    }

    @Override
    public void subscribe(Consumer<String> handler) {
        subscriber = new RedisSubscriber(redis, inboundChannel, handler, logger);
        subscriber.start();
    }

    @Override
    public void publish(String message) {
        redis.publish(outboundChannel, message);
    }

    @Override
    public boolean isHealthy() {
        return redis.isHealthy();
    }

    @Override
    public boolean pingBlocking() {
        return redis.pingBlocking();
    }

    @Override
    public String describe() {
        return "Redis " + settings.host() + ":" + settings.port();
    }

    @Override
    public void close() {
        if (subscriber != null) {
            try {
                subscriber.shutdown();
            } catch (RuntimeException ignored) {
                // Already torn down.
            }
            subscriber = null;
        }
        try {
            redis.close();
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }
}
