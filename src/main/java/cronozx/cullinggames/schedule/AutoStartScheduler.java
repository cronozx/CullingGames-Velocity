package cronozx.cullinggames.schedule;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import cronozx.cullinggames.CullingGames;
import cronozx.cullinggames.config.ProxyConfig;
import cronozx.cullinggames.queue.QueueManager;
import cronozx.cullinggames.queue.QueueService;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Schedules auto-start windows with no polling.
 *
 * <p>Rather than a per-second wall-clock loop, this computes the exact {@link Duration} until the
 * next fire — either {@code interval-minutes} from now, or the nearest upcoming {@code daily-times}
 * entry in the configured timezone — and schedules a single delayed task. After each fire it
 * recomputes and reschedules.
 *
 * <p>On fire, if a game is already running ({@code aliveCount > 0}) it skips and reschedules; else it
 * competes for the {@code <prefix>:autostart-lock} so exactly one proxy opens the window when several
 * run, then reschedules the next fire regardless of the outcome.
 */
public final class AutoStartScheduler {

    private final CullingGames plugin;
    private final ProxyServer server;
    private final ProxyConfig config;
    private final QueueService queue;
    private final QueueManager queueManager;
    private final Logger logger;

    private volatile ScheduledTask task;

    public AutoStartScheduler(CullingGames plugin, ProxyServer server, ProxyConfig config,
                              QueueService queue, QueueManager queueManager, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.queue = queue;
        this.queueManager = queueManager;
        this.logger = logger;
    }

    /** Starts scheduling if auto-start is enabled. */
    public void start() {
        if (!config.autoStartEnabled()) {
            logger.info("Auto-start is disabled.");
            return;
        }
        scheduleNext();
    }

    private void scheduleNext() {
        Duration delay = computeNextDelay();
        if (delay == null) {
            logger.warn("Auto-start is enabled but no valid daily-times or interval-minutes are "
                    + "configured; auto-start is inactive.");
            return;
        }
        if (delay.isNegative() || delay.isZero()) {
            delay = Duration.ofSeconds(1);
        }
        this.task = server.getScheduler()
                .buildTask(plugin, this::fire)
                .delay(delay)
                .schedule();
        logger.info("Next auto-start scheduled in {}.", formatDuration(delay));
    }

    private void fire() {
        int window = config.windowSeconds();
        queue.aliveCount().thenAccept(alive -> {
            if (alive > 0) {
                logger.info("Auto-start skipped: a game is already running ({} alive).", alive);
                scheduleNext();
                return;
            }
            queue.acquireAutoStartLock(window).thenAccept(won -> {
                if (won) {
                    queueManager.openWindow(false, window);
                } else {
                    logger.info("Auto-start window handled by another proxy; skipping.");
                }
                scheduleNext();
            }).exceptionally(ex -> {
                logger.warn("Auto-start lock acquisition failed: {}", ex.getMessage());
                scheduleNext();
                return null;
            });
        }).exceptionally(ex -> {
            logger.warn("Auto-start alive check failed: {}", ex.getMessage());
            scheduleNext();
            return null;
        });
    }

    /**
     * Computes the delay to the next fire, or {@code null} if neither schedule source is usable.
     */
    private Duration computeNextDelay() {
        int interval = config.intervalMinutes();
        if (interval > 0) {
            return Duration.ofMinutes(interval);
        }
        List<LocalTime> times = config.dailyTimes();
        if (times.isEmpty()) {
            return null;
        }
        ZoneId zone = config.timezone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime best = null;
        // Scan today and tomorrow so a candidate strictly after `now` always exists.
        for (int dayOffset = 0; dayOffset <= 1; dayOffset++) {
            LocalDate date = now.toLocalDate().plusDays(dayOffset);
            for (LocalTime time : times) {
                ZonedDateTime candidate = ZonedDateTime.of(date, time, zone);
                if (candidate.isAfter(now) && (best == null || candidate.isBefore(best))) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            return null;
        }
        return Duration.between(now, best);
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%dh %02dm %02ds", hours, minutes, seconds);
    }

    /** Cancels the pending task (shutdown). */
    public void cancel() {
        ScheduledTask current = this.task;
        if (current != null) {
            current.cancel();
            this.task = null;
        }
    }
}
