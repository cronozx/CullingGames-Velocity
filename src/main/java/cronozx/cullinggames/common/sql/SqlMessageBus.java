package cronozx.cullinggames.common.sql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Polled stand-in for Redis pub/sub: publishers append to a message table and each subscriber walks
 * it forward with an {@code id} cursor.
 *
 * <h2>Latency</h2>
 * A message is visible after at most one poll interval (default 250&nbsp;ms). Every signal this
 * carries — START, STOP, PULL, ELIMINATED, ENDED — is a human-scale event, so the added delay is not
 * perceptible in play; it is nevertheless the honest cost of not having pub/sub, and the reason
 * {@link cronozx.cullinggames.common.Backend#REDIS} remains the better backend where available.
 *
 * <h2>Why an {@code id > cursor} cursor is safe here</h2>
 * The classic hazard with an {@code AUTO_INCREMENT} cursor is that a transaction which reserved a low
 * id can commit <em>after</em> one that reserved a higher id, so a reader that has already advanced
 * past the higher id never sees the lower one. That cannot happen in this design: each side publishes
 * through {@link SqlClient}, which serialises every statement onto a single connection and a single
 * I/O thread, and each channel has exactly one publishing side (the game server writes only to the
 * proxy channel, the proxy only to the game channel). Ids on a given channel are therefore assigned
 * and committed in the same order.
 *
 * <p>A subscriber starts at the current maximum id, so a server joining mid-round replays nothing —
 * matching Redis, where a late subscriber simply misses earlier publishes.
 *
 * <h2>Retention</h2>
 * Rows are never deleted on delivery, because several subscribers may read the same channel. A
 * janitor instead trims anything older than {@link SqlSettings#messageRetentionSeconds()}, which runs
 * on the poll thread at a low multiple of the poll interval.
 */
public final class SqlMessageBus {

    /** Janitor runs every Nth poll, keeping cleanup cheap without a second timer. */
    private static final int JANITOR_EVERY_N_POLLS = 120;

    private final SqlClient client;
    private final String ns;
    private final String table;
    private final int pollIntervalMs;
    private final int retentionSeconds;
    private final Consumer<String> warn;
    private final Consumer<String> info;

    private ScheduledExecutorService poller;
    private volatile boolean running = false;

    /**
     * @param client the SQL client (owns the connection and the I/O thread)
     * @param warn   sink for warnings
     * @param info   sink for informational messages
     */
    public SqlMessageBus(SqlClient client, Consumer<String> warn, Consumer<String> info) {
        this.client = client;
        SqlSettings settings = client.settings();
        this.ns = settings.namespace();
        this.table = settings.table(SqlSchema.MESSAGES);
        this.pollIntervalMs = settings.pollIntervalMs();
        this.retentionSeconds = settings.messageRetentionSeconds();
        this.warn = warn;
        this.info = info;
    }

    /**
     * Appends a message to a channel, fire-and-forget. Failures are logged, never thrown — the same
     * contract as the Redis {@code publish}.
     *
     * @param channel the logical channel name
     * @param payload the encoded wire message
     */
    public void publish(String channel, String payload) {
        client.async(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + table + " (ns, channel, payload) VALUES (?, ?, ?)")) {
                insert.setString(1, ns);
                insert.setString(2, channel);
                insert.setString(3, payload);
                insert.executeUpdate();
            }
            return null;
        }).exceptionally(failure -> {
            warn.accept("Failed to publish to '" + channel + "': " + failure.getMessage());
            return null;
        });
    }

    /**
     * Starts polling {@code channel} and delivering new messages to {@code handler}.
     *
     * <p>{@code handler} runs on the poll thread and must not touch the Paper or Velocity API
     * directly; both callers marshal to their own main thread, exactly as they do for the Redis
     * subscriber thread.
     *
     * @param channel the logical channel name
     * @param handler receives each new message in id order
     */
    public void subscribe(String channel, Consumer<String> handler) {
        if (running) {
            return;
        }
        running = true;
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BattleRoyale-SQL-Poll");
            thread.setDaemon(true);
            return thread;
        });

        // Skip everything already in the table so a restart does not replay an old round's signals.
        client.async(connection -> currentMaxId(connection, channel))
                .whenComplete((startingId, error) -> {
                    long cursor = (error != null || startingId == null) ? 0L : startingId;
                    if (error != null) {
                        warn.accept("Could not read the message cursor (starting from 0): "
                                + error.getMessage());
                    } else {
                        info.accept("Polling MySQL channel '" + channel + "' every "
                                + pollIntervalMs + "ms.");
                    }
                    schedulePolling(channel, handler, cursor);
                });
    }

    private void schedulePolling(String channel, Consumer<String> handler, long startingCursor) {
        if (!running || poller == null) {
            return;
        }
        Cursor cursor = new Cursor(startingCursor);
        try {
            poller.scheduleWithFixedDelay(() -> pollOnce(channel, handler, cursor),
                    pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            running = false;
        }
    }

    private void pollOnce(String channel, Consumer<String> handler, Cursor cursor) {
        if (!running) {
            return;
        }
        try {
            List<Delivery> batch = client.async(connection -> {
                List<Delivery> found = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id, payload FROM " + table
                                + " WHERE ns=? AND channel=? AND id>? ORDER BY id")) {
                    select.setString(1, ns);
                    select.setString(2, channel);
                    select.setLong(3, cursor.value);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            found.add(new Delivery(rows.getLong(1), rows.getString(2)));
                        }
                    }
                }
                return found;
            }).get();

            for (Delivery delivery : batch) {
                cursor.value = delivery.id();
                dispatch(handler, delivery.payload());
            }

            if (cursor.tick()) {
                runJanitor();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        } catch (Exception failure) {
            // The client already logs connection loss and manages its own backoff; polling simply
            // keeps trying at its fixed rate and resumes when the database comes back.
            warn.accept("Message poll on '" + channel + "' failed: " + failure.getMessage());
        }
    }

    private void dispatch(Consumer<String> handler, String payload) {
        try {
            handler.accept(payload);
        } catch (RuntimeException handlerFailure) {
            warn.accept("Error handling message '" + payload + "': " + handlerFailure.getMessage());
        }
    }

    private void runJanitor() {
        client.async(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE ns=? AND created_at < "
                            + "TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP)")) {
                delete.setString(1, ns);
                delete.setInt(2, -retentionSeconds);
                delete.executeUpdate();
            }
            return null;
        }).exceptionally(failure -> null);
    }

    private long currentMaxId(java.sql.Connection connection, String channel) throws java.sql.SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COALESCE(MAX(id), 0) FROM " + table + " WHERE ns=? AND channel=?")) {
            select.setString(1, ns);
            select.setString(2, channel);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /** Stops polling. Idempotent. */
    public void shutdown() {
        running = false;
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
    }

    /** Mutable cursor plus the janitor tick counter; confined to the single poll thread. */
    private static final class Cursor {
        private long value;
        private int polls;

        private Cursor(long value) {
            this.value = value;
        }

        /** @return {@code true} when this poll should also run the janitor. */
        private boolean tick() {
            polls++;
            if (polls >= JANITOR_EVERY_N_POLLS) {
                polls = 0;
                return true;
            }
            return false;
        }
    }

    /** One row read from the message table. */
    private record Delivery(long id, String payload) {
    }
}
