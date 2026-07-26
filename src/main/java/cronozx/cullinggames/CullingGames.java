package cronozx.cullinggames;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import cronozx.cullinggames.common.GameState;
import cronozx.cullinggames.common.message.CancelReason;
import cronozx.cullinggames.common.LiveMatch;
import cronozx.cullinggames.common.message.Handshake;
import cronozx.cullinggames.common.message.Message;
import cronozx.cullinggames.command.BattleRoyaleCommand;
import cronozx.cullinggames.command.LeaveCommand;
import cronozx.cullinggames.command.QueueCommand;
import cronozx.cullinggames.config.Messages;
import cronozx.cullinggames.config.ProxyConfig;
import cronozx.cullinggames.listener.ConnectionListener;
import cronozx.cullinggames.net.Coordination;
import cronozx.cullinggames.net.Transport;
import cronozx.cullinggames.queue.QueueManager;
import cronozx.cullinggames.queue.QueueService;
import cronozx.cullinggames.schedule.AutoStartScheduler;
import cronozx.cullinggames.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BattleRoyale Velocity proxy plugin: owns the cross-server queue and orchestrates round hand-off to
 * the game server over Redis.
 *
 * <p>The injected constructor only stores references; all heavy initialisation happens in
 * {@link #onProxyInitialization(ProxyInitializeEvent)} so a Redis outage (or a bad config) can never
 * fail plugin construction. Inbound game-server messages arrive on {@code <prefix>:proxy} and are
 * dispatched by {@link #onRedisMessage(String)}; players are always resolved by UUID.
 */
@Plugin(id = "cullinggames", name = "CullingGames", version = BuildConstants.VERSION,
        description = "Cross-server battle royale queue & orchestration for Velocity.",
        authors = {"cronozx"})
public final class CullingGames {

    private final ProxyServer server;
    private final Logger logger;

    /** Peer versions already complained about, so a mismatch is logged once, not per message. */
    private final java.util.Set<Integer> warnedPeerVersions =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path dataDirectory;

    private ProxyConfig config;
    private Messages messages;
    private Coordination coordination;
    private Transport transport;
    private QueueService queueService;
    private QueueManager queueManager;
    private AutoStartScheduler autoStartScheduler;

    @Inject
    public CullingGames(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.config = new ProxyConfig(dataDirectory, logger);
        config.load();
        this.messages = new Messages(config);

        this.coordination = Coordination.create(config, logger);
        this.transport = coordination.transport();
        this.queueService = coordination.queue();
        this.queueManager = new QueueManager(this, server, transport, queueService, config, messages, logger);
        this.autoStartScheduler =
                new AutoStartScheduler(this, server, config, queueService, queueManager, logger);

        // MySQL needs its tables before anything reads them; Redis needs no preparation. Either way
        // the proxy finishes starting — a backend that is down is reported, never fatal.
        if (coordination.bootstrap() != null) {
            coordination.bootstrap().get().whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.error("Could not prepare the MySQL coordination tables: {}"
                            + " — check that the configured user has CREATE on the database.",
                            error.getMessage());
                } else {
                    logger.info("MySQL coordination tables are ready.");
                }
                transport.subscribe(this::onBackendMessage);
                announceVersion();
            });
        } else {
            transport.subscribe(this::onBackendMessage);
            announceVersion();
        }

        autoStartScheduler.start();
        registerCommands();
        server.getEventManager().register(this, new ConnectionListener(queueService, logger));

        printSummary();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (autoStartScheduler != null) {
            autoStartScheduler.cancel();
        }
        if (queueManager != null) {
            queueManager.cancelTasks();
        }
        if (transport != null) {
            transport.close();
        }
    }

    private void registerCommands() {
        CommandManager commandManager = server.getCommandManager();
        commandManager.register(
                commandManager.metaBuilder("queue").plugin(this).build(),
                new QueueCommand(queueService, config, messages, logger));
        commandManager.register(
                commandManager.metaBuilder("leave").plugin(this).build(),
                new LeaveCommand(queueService, messages, logger));
        commandManager.register(
                commandManager.metaBuilder("br").plugin(this).build(),
                new BattleRoyaleCommand(transport, queueService, queueManager, config, messages, logger));
    }

    /**
     * Dispatches an inbound game-server message (the proxy channel). Runs on the transport's
     * delivery thread; the actual player connections are fire-and-forget requests.
     *
     * @param raw the raw wire message
     */
    private void onBackendMessage(String raw) {
        Message message;
        try {
            message = Message.parse(raw);
        } catch (IllegalArgumentException ex) {
            logger.debug("Ignoring unparseable proxy message '{}': {}", raw, ex.getMessage());
            return;
        }
        try {
            switch (message.type()) {
                case PULL -> handlePull(message);
                case ELIMINATED -> handleEliminated(message);
                case TIMEOUT -> handleTimeout(message);
                case CANCELLED -> handleCancelled(message);
                case ENDED -> handleEnded(message);
                case HELLO -> checkPeerVersion(message, raw);
                default -> logger.debug("Ignoring unexpected message type {} on the proxy channel.",
                        message.type());
            }
        } catch (IllegalArgumentException ex) {
            logger.warn("Malformed {} message '{}': {}", message.type(), raw, ex.getMessage());
        }
    }

    /**
     * Compares a game server's announced protocol version against ours, complaining once per distinct
     * version. See {@link Handshake} for why a mismatch is otherwise invisible.
     */
    /** Announces this side's protocol version to the game servers. */
    private void announceVersion() {
        transport.publish(Handshake.encode(Handshake.ROLE_PROXY));
    }

    private void checkPeerVersion(Message message, String raw) {
        int peerVersion;
        String peerRole;
        try {
            peerVersion = Handshake.version(message);
            peerRole = Handshake.role(message);
        } catch (IllegalArgumentException malformed) {
            logger.debug("Ignoring malformed HELLO '{}'", raw);
            return;
        }
        if (Handshake.compatible(peerVersion) || !warnedPeerVersions.add(peerVersion)) {
            return;
        }
        logger.error(Handshake.mismatchMessage(peerVersion, peerRole));
    }

    private void handlePull(Message message) {
        // v2: PULL|<matchId>|<uuid,uuid,...>
        message.requireArity(2);
        String matchId = message.arg(0);
        List<UUID> roster = Message.parseUuidList(message.args().get(1));
        if (roster.isEmpty()) {
            return;
        }
        // Which server is running this round is a registry question, not a config one: with several
        // rounds in flight they need not all be on config.gameServer(). The configured name is the
        // fallback for a round that has not registered yet.
        queueService.liveMatches().whenComplete((matches, error) -> {
            String targetName = config.gameServer();
            if (error == null && matches != null) {
                for (LiveMatch match : matches) {
                    if (match.matchId().equals(matchId)) {
                        targetName = match.gameServer();
                        break;
                    }
                }
            }
            Optional<RegisteredServer> target = server.getServer(targetName);
            if (target.isEmpty()) {
                logger.warn("PULL for match '{}' but game-server '{}' is not registered on this proxy.",
                        matchId, targetName);
                return;
            }
            for (UUID id : roster) {
                server.getPlayer(id).ifPresent(player ->
                        player.createConnectionRequest(target.get()).fireAndForget());
            }
        });
    }

    private void handleEliminated(Message message) {
        // v2: ELIMINATED|<matchId>|<uuid>
        message.requireArity(2);
        UUID victim = UUID.fromString(message.arg(1));
        Optional<RegisteredServer> lobby = server.getServer(config.lobbyServer());
        if (lobby.isEmpty()) {
            logger.warn("ELIMINATED received but lobby-server '{}' is not registered on this proxy.",
                    config.lobbyServer());
            return;
        }
        server.getPlayer(victim).ifPresent(player ->
                player.createConnectionRequest(lobby.get()).fireAndForget());
    }

    private void handleTimeout(Message message) {
        // v2: TIMEOUT|<matchId>|<uuid>
        message.requireArity(2);
        UUID player = UUID.fromString(message.arg(1));
        server.getPlayer(player).ifPresent(target -> messages.send(target, "arrival-timeout"));
    }

    private void handleCancelled(Message message) {
        // v2: CANCELLED|<matchId>|<reason>
        message.requireArity(2);
        CancelReason reason = parseReason(message.arg(1));
        moveGameServerPlayersToLobby();
        String key = (reason == CancelReason.NOT_ENOUGH_PLAYERS) ? "not-enough-players" : "round-cancelled";
        Text.broadcast(server, config.announceServers(), messages.component(key));
        queueService.setState(GameState.IDLE);
    }

    private void handleEnded(Message message) {
        // v2: ENDED|<matchId>|<winnerUuid>, winner possibly empty.
        message.requireArity(1);
        String winnerRaw = (message.args().size() < 2) ? "" : message.args().get(1);
        if (winnerRaw != null && !winnerRaw.isBlank()) {
            String winnerName = resolveName(winnerRaw.trim());
            Text.broadcast(server, config.announceServers(),
                    messages.component("winner-broadcast", Placeholder.unparsed("winner", winnerName)));
        }
        moveGameServerPlayersToLobby();
        queueService.setState(GameState.IDLE);
    }

    private String resolveName(String uuidRaw) {
        try {
            UUID winner = UUID.fromString(uuidRaw);
            return server.getPlayer(winner).map(Player::getUsername).orElse(uuidRaw);
        } catch (IllegalArgumentException ex) {
            return uuidRaw;
        }
    }

    private CancelReason parseReason(String raw) {
        try {
            return CancelReason.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return CancelReason.ADMIN_STOP;
        }
    }

    private void moveGameServerPlayersToLobby() {
        Optional<RegisteredServer> game = server.getServer(config.gameServer());
        Optional<RegisteredServer> lobby = server.getServer(config.lobbyServer());
        if (game.isEmpty() || lobby.isEmpty()) {
            return;
        }
        for (Player player : game.get().getPlayersConnected()) {
            player.createConnectionRequest(lobby.get()).fireAndForget();
        }
    }

    private void printSummary() {
        boolean healthy = transport.pingBlocking();
        logger.info("========== BattleRoyale (proxy) ==========");
        logger.info("Version:      {}", BuildConstants.VERSION);
        logger.info("Backend:      {}  [{}]", transport.describe(),
                healthy ? "CONNECTED" : "RETRYING");
        logger.info("Namespace:    {}", coordination.namespace());
        logger.info("Game server:  {}", config.gameServer());
        logger.info("Lobby server: {}", config.lobbyServer());
        logger.info("Queue:        min={}  max={}  window={}s",
                config.minPlayers(), config.maxPlayers(), config.windowSeconds());
        if (config.autoStartEnabled()) {
            if (config.intervalMinutes() > 0) {
                logger.info("Auto-start:   every {} minute(s)", config.intervalMinutes());
            } else {
                logger.info("Auto-start:   daily {} ({})", config.dailyTimesRaw(), config.timezoneRaw());
            }
        } else {
            logger.info("Auto-start:   disabled");
        }
        logger.info("==========================================");
    }
}
