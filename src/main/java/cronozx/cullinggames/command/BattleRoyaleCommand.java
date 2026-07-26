package cronozx.cullinggames.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import cronozx.cullinggames.common.GameState;
import cronozx.cullinggames.common.Protocol;
import cronozx.cullinggames.common.message.Message;
import cronozx.cullinggames.common.message.MessageType;
import cronozx.cullinggames.config.Messages;
import cronozx.cullinggames.config.ProxyConfig;
import cronozx.cullinggames.queue.QueueManager;
import cronozx.cullinggames.queue.QueueService;
import cronozx.cullinggames.net.Transport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /br} admin command: {@code forcestart [seconds] | stop | status | reload}. Each subcommand
 * enforces its own permission node (see spec §9.2). Redis work runs asynchronously and feedback is
 * sent from the completion callback.
 */
public final class BattleRoyaleCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("forcestart", "stop", "status", "reload");

    private final Transport transport;
    private final QueueService queue;
    private final QueueManager queueManager;
    private final ProxyConfig config;
    private final Messages messages;
    private final Logger logger;

    public BattleRoyaleCommand(Transport transport, QueueService queue, QueueManager queueManager,
                               ProxyConfig config, Messages messages, Logger logger) {
        this.transport = transport;
        this.queue = queue;
        this.queueManager = queueManager;
        this.config = config;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendUsage(source);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "forcestart" -> forceStart(source, args);
            case "stop" -> stop(source);
            case "status" -> status(source);
            case "reload" -> reload(source);
            default -> sendUsage(source);
        }
    }

    private void forceStart(CommandSource source, String[] args) {
        if (!source.hasPermission("cullinggames.admin.forcestart")) {
            deny(source);
            return;
        }
        int window = config.windowSeconds();
        if (args.length >= 2) {
            try {
                int parsed = Integer.parseInt(args[1]);
                if (parsed > 0) {
                    window = parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall back to the configured window
            }
        }
        queueManager.openWindow(true, window, source);
    }

    private void stop(CommandSource source) {
        if (!source.hasPermission("cullinggames.admin.stop")) {
            deny(source);
            return;
        }
        // v2: STOP|<matchId>. Addressed rather than broadcast, so an admin stop cannot end
        // a round on a server the operator did not mean to touch.
        transport.publish(Message.encode(MessageType.STOP,
                Protocol.mainMatchId(config.gameServer())));
        // Manual unwedge: force-reset the advisory state locally so a new window can open.
        queue.setState(GameState.IDLE);
        messages.send(source, "stopped");
    }

    private void status(CommandSource source) {
        if (!source.hasPermission("cullinggames.admin.status")) {
            deny(source);
            return;
        }
        CompletableFuture<GameState> stateFuture = queue.getState();
        CompletableFuture<Long> queueFuture = queue.size();
        CompletableFuture<Long> aliveFuture = queue.aliveCount();
        CompletableFuture.allOf(stateFuture, queueFuture, aliveFuture).whenComplete((ignored, ex) -> {
            String state;
            String queueSize;
            String alive;
            boolean healthy;
            if (ex != null) {
                state = "UNKNOWN";
                queueSize = "?";
                alive = "?";
                healthy = false;
            } else {
                state = stateFuture.join().name();
                queueSize = Long.toString(queueFuture.join());
                alive = Long.toString(aliveFuture.join());
                healthy = transport.isHealthy();
            }
            String backendStatus = healthy ? "CONNECTED" : "RETRYING";
            messages.send(source, "status",
                    Placeholder.unparsed("state", state),
                    Placeholder.unparsed("queue", queueSize),
                    Placeholder.unparsed("alive", alive),
                    Placeholder.unparsed("backend", backendStatus),
                    // <redis> is the pre-MySQL name for the same value. Kept because config.yml is
                    // only written on first run, so an existing install still has it in its template.
                    Placeholder.unparsed("redis", backendStatus));
        });
    }

    private void reload(CommandSource source) {
        if (!source.hasPermission("cullinggames.admin.reload")) {
            deny(source);
            return;
        }
        boolean ok = config.reload();
        // Messages read templates live from the config, so no rebuild is required.
        messages.send(source, ok ? "reloaded" : "reload-failed");
    }

    private void sendUsage(CommandSource source) {
        source.sendMessage(Component.text("/br <forcestart [seconds]|stop|status|reload>", NamedTextColor.YELLOW));
    }

    private void deny(CommandSource source) {
        source.sendMessage(Component.text("You do not have permission to use that subcommand.", NamedTextColor.RED));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Per-subcommand permission checks happen in execute() so each node can be granted separately.
        return true;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return SUBCOMMANDS;
        }
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(partial)).toList();
        }
        return List.of();
    }
}
