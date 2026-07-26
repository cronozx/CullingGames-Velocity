package cronozx.cullinggames.net.sql;

import cronozx.cullinggames.common.sql.SqlClient;
import cronozx.cullinggames.common.sql.SqlMessageBus;
import cronozx.cullinggames.common.sql.SqlSettings;
import cronozx.cullinggames.net.Transport;

import java.util.function.Consumer;

/**
 * SQL {@link Transport} for the proxy: polls the message table for the proxy channel and appends to
 * the game channel.
 *
 * <p>Owns the {@link SqlClient}, which the queue service shares — one connection serves both, which
 * is what keeps published ids monotonic and lets the subscriber use a simple cursor.
 */
public final class SqlTransport implements Transport {

    private final SqlClient client;
    private final SqlMessageBus bus;
    private final String inboundChannel;
    private final String outboundChannel;

    /**
     * @param client          the SQL client (ownership transfers to this transport)
     * @param bus             the polled message bus built over that client
     * @param inboundChannel  channel the proxy polls
     * @param outboundChannel channel the proxy appends to
     */
    public SqlTransport(SqlClient client, SqlMessageBus bus, String inboundChannel,
                        String outboundChannel) {
        this.client = client;
        this.bus = bus;
        this.inboundChannel = inboundChannel;
        this.outboundChannel = outboundChannel;
    }

    @Override
    public void subscribe(Consumer<String> handler) {
        bus.subscribe(inboundChannel, handler);
    }

    @Override
    public void publish(String message) {
        bus.publish(outboundChannel, message);
    }

    @Override
    public boolean isHealthy() {
        return client.isHealthy();
    }

    @Override
    public boolean pingBlocking() {
        return client.pingBlocking();
    }

    @Override
    public String describe() {
        SqlSettings settings = client.settings();
        return "MySQL " + settings.host() + ":" + settings.port() + "/" + settings.database()
                + " (poll " + settings.pollIntervalMs() + "ms)";
    }

    @Override
    public void close() {
        try {
            bus.shutdown();
        } catch (RuntimeException ignored) {
            // Already torn down.
        }
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // Best effort.
        }
    }
}
