package cronozx.cullinggames.common.sql;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Off-thread MySQL access, the SQL counterpart to each module's {@code RedisClient}.
 *
 * <h2>Why one connection and not a pool</h2>
 * Every operation runs on a single dedicated I/O thread, so at most one statement is ever in flight
 * and a pool would hold idle connections for no benefit. One connection also makes the message
 * table's {@code AUTO_INCREMENT} ids strictly monotonic per publisher, which is what lets the
 * subscriber use a simple {@code id > cursor} cursor without missing rows — see {@link SqlMessageBus}.
 * This mirrors the Redis client, which likewise serialises everything onto one I/O thread.
 *
 * <h2>Failure behaviour</h2>
 * Construction <strong>never throws</strong> when the database is down: the connection is opened
 * lazily on first use. A failed operation closes the bad connection, flips {@link #isHealthy()} to
 * {@code false}, and schedules a validation probe with 2s&rarr;30s capped backoff that flips it back
 * and logs once on recovery. The next operation after a drop transparently reconnects.
 *
 * <p>The driver is resolved by class name and invoked directly rather than through
 * {@link DriverManager}'s registry, because {@code DriverManager} only sees drivers visible to the
 * <em>calling</em> class loader — and a shaded driver inside a plugin jar generally is not, on either
 * Paper or Velocity. {@link DriverManager} is still tried as a fallback for platforms that provide
 * their own driver.
 */
public final class SqlClient {

    /** Driver class shipped in the plugin jar (rewritten by the shadow relocation at build time). */
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    /** MariaDB's driver, accepted as a fallback when a platform provides that one instead. */
    private static final String MARIADB_DRIVER = "org.mariadb.jdbc.Driver";

    private static final long PROBE_INITIAL_MS = 2_000L;
    private static final long PROBE_MAX_MS = 30_000L;
    private static final int VALIDATION_TIMEOUT_SECONDS = 3;

    /** A unit of work against a live connection. */
    @FunctionalInterface
    public interface SqlWork<T> {
        /**
         * @param connection a validated, open connection owned by the client
         * @return the operation's result
         * @throws SQLException propagated to the returned future
         */
        T apply(Connection connection) throws SQLException;
    }

    /** Opens a new connection; injectable so tests can substitute an in-memory database. */
    @FunctionalInterface
    public interface ConnectionFactory {
        /**
         * @return a freshly opened connection
         * @throws SQLException if the database is unreachable
         */
        Connection open() throws SQLException;
    }

    private final SqlSettings settings;
    private final Consumer<String> warn;
    private final Consumer<String> info;
    private final ConnectionFactory factory;
    private final ExecutorService executor;
    private final ScheduledExecutorService probeScheduler;
    private final AtomicBoolean probeScheduled = new AtomicBoolean(false);

    /** Only ever touched on the I/O thread, except for the close path. */
    private Connection connection;

    private volatile boolean healthy = true;
    private volatile boolean closed = false;

    /**
     * Builds a client that connects to a real MySQL server using the bundled driver.
     *
     * @param settings resolved connection settings (env overrides already applied)
     * @param warn     sink for warnings (each module adapts its own logger)
     * @param info     sink for informational messages
     */
    public SqlClient(SqlSettings settings, Consumer<String> warn, Consumer<String> info) {
        this(settings, warn, info, null);
    }

    /**
     * Builds a client with an explicit connection factory.
     *
     * @param settings resolved connection settings
     * @param warn     sink for warnings
     * @param info     sink for informational messages
     * @param factory  connection factory; {@code null} uses the bundled MySQL driver
     */
    public SqlClient(SqlSettings settings, Consumer<String> warn, Consumer<String> info,
                     ConnectionFactory factory) {
        this.settings = settings;
        this.warn = warn;
        this.info = info;
        this.factory = (factory != null) ? factory : this::openWithDriver;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BattleRoyale-SQL-IO");
            thread.setDaemon(true);
            return thread;
        });
        this.probeScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BattleRoyale-SQL-Probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** @return the settings this client was built with. */
    public SqlSettings settings() {
        return settings;
    }

    /**
     * Runs {@code work} on the I/O thread against a validated connection, in autocommit mode.
     *
     * @param work the operation
     * @param <T>  the result type
     * @return a future completing with the result, or exceptionally on failure
     */
    public <T> CompletableFuture<T> async(SqlWork<T> work) {
        return submit(() -> {
            Connection live = connection();
            T result = work.apply(live);
            markHealthy();
            return result;
        });
    }

    /**
     * Runs {@code work} on the I/O thread inside an explicit transaction, committing on success and
     * rolling back on any failure.
     *
     * <p>Used for the multi-statement operations that replace Redis's server-side Lua scripts — the
     * queue drain, the capacity-checked enqueue, lock acquisition, and stale-state cleanup — so each
     * is as atomic as its Lua counterpart.
     *
     * @param work the operation
     * @param <T>  the result type
     * @return a future completing with the result, or exceptionally on failure
     */
    public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return submit(() -> {
            Connection live = connection();
            boolean previousAutoCommit = live.getAutoCommit();
            live.setAutoCommit(false);
            try {
                T result = work.apply(live);
                live.commit();
                markHealthy();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    live.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                try {
                    live.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Connection is already broken; the next operation will reopen it.
                }
            }
        });
    }

    /** An action already bound to its connection; runs on the I/O thread. */
    @FunctionalInterface
    private interface Action<T> {
        T run() throws SQLException;
    }

    private <T> CompletableFuture<T> submit(Action<T> action) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("SqlClient is closed"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(action.run());
                } catch (SQLException | RuntimeException failure) {
                    handleFailure(failure);
                    future.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            future.completeExceptionally(shuttingDown);
        }
        return future;
    }

    /**
     * Returns the live connection, opening or reopening it if necessary. Only called on the I/O
     * thread (and the probe thread, which never runs concurrently with it — see {@link #runProbe}).
     */
    private Connection connection() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                    return connection;
                }
            } catch (SQLException stale) {
                // Fall through to reopen.
            }
            closeQuietly(connection);
            connection = null;
        }
        connection = factory.open();
        return connection;
    }

    private Connection openWithDriver() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        if (settings.password() != null && !settings.password().isBlank()) {
            properties.setProperty("password", settings.password());
        }
        String url = settings.jdbcUrl();

        Driver driver = loadDriver();
        if (driver != null) {
            Connection opened = driver.connect(url, properties);
            if (opened != null) {
                return opened;
            }
        }
        // Fallback: a driver the platform itself registered.
        return DriverManager.getConnection(url, properties);
    }

    private Driver loadDriver() {
        for (String candidate : new String[]{MYSQL_DRIVER, MARIADB_DRIVER}) {
            try {
                Class<?> type = Class.forName(candidate, true, SqlClient.class.getClassLoader());
                return (Driver) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError notPresent) {
                // Try the next candidate.
            }
        }
        return null;
    }

    /** @return whether the last observed database interaction succeeded. */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Synchronously validates the connection and updates the health flag. Used once for the startup
     * diagnostics line so it reports an accurate CONNECTED/RETRYING status.
     *
     * @return {@code true} if the database answered
     */
    public boolean pingBlocking() {
        if (closed) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(async(live -> live.isValid(VALIDATION_TIMEOUT_SECONDS))
                    .get(Math.max(1000, settings.connectTimeoutMs()) * 2L, TimeUnit.MILLISECONDS));
        } catch (Exception unreachable) {
            return false;
        }
    }

    private void markHealthy() {
        if (!healthy) {
            healthy = true;
            info.accept("MySQL connection restored.");
        }
        probeScheduled.set(false);
    }

    private void handleFailure(Throwable failure) {
        if (healthy) {
            healthy = false;
            warn.accept("MySQL connection lost: " + rootMessage(failure) + " (retrying in background).");
        }
        // Drop the connection so the next operation reopens rather than reusing a broken socket.
        closeQuietly(connection);
        connection = null;
        scheduleProbe(PROBE_INITIAL_MS);
    }

    private void scheduleProbe(long delayMs) {
        if (closed || !probeScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            probeScheduler.schedule(() -> runProbe(delayMs), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            probeScheduled.set(false);
        }
    }

    /**
     * Re-validates on the I/O thread (never on the probe thread itself), so the connection field is
     * only ever touched from one thread.
     */
    private void runProbe(long previousDelayMs) {
        if (closed) {
            return;
        }
        probeScheduled.set(false);
        async(live -> live.isValid(VALIDATION_TIMEOUT_SECONDS))
                .exceptionally(stillDown -> {
                    scheduleProbe(Math.min(previousDelayMs * 2, PROBE_MAX_MS));
                    return false;
                });
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private static void closeQuietly(Connection target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (SQLException ignored) {
            // Best effort.
        }
    }

    /** Shuts down the executors and closes the connection. Idempotent. */
    public void close() {
        closed = true;
        executor.shutdownNow();
        probeScheduler.shutdownNow();
        closeQuietly(connection);
        connection = null;
    }
}
