package cronozx.cullinggames.common;

/**
 * Central derivation of every Redis pub/sub channel name and every Redis key name used by the
 * BattleRoyale system.
 *
 * <p>Both runtime modules (the Paper game server and the Velocity proxy) derive their channel and
 * key names exclusively through this class, so the two halves can never disagree about the wire
 * layout. All names are namespaced by a configurable {@code prefix} (default {@code battleroyale}),
 * which lets several independent BattleRoyale networks share a single Redis instance simply by
 * choosing different prefixes.
 *
 * <p>Channel direction:
 * <ul>
 *   <li>{@link #gameChannel(String)} — proxy publishes, game server subscribes.</li>
 *   <li>{@link #proxyChannel(String)} — game server publishes, proxy subscribes.</li>
 * </ul>
 *
 * <h2>Protocol v2 — match scoping</h2>
 * v1 carried exactly one round per namespace: {@code :lock}, {@code :alive}, {@code :kills} and
 * {@code :state} were singletons, so a second concurrent round would have written into the first
 * one's roster. v2 scopes those four under a <em>match id</em> ({@link #matchKey}), which is what
 * lets one namespace carry several simultaneous rounds.
 *
 * <p>Keys split into two groups:
 * <ul>
 *   <li><strong>Namespace-global</strong> — {@link #queueKey}, {@link #queueStateKey},
 *       {@link #matchesKey}, {@link #autoStartLockKey}, {@link #versionKey}. There is one queue and
 *       one queue window per namespace no matter how many rounds run.</li>
 *   <li><strong>Match-scoped</strong> — {@link #aliveKey}, {@link #killsKey}, {@link #lockKey},
 *       {@link #stateKey}. One set per live round.</li>
 * </ul>
 *
 * <h2>Version negotiation</h2>
 * A v1 peer and a v2 peer would not crash — they would simply never see each other, because their
 * key names and message arities differ. That silent failure is the worst outcome, so both sides
 * publish {@link #PROTOCOL_VERSION} (via {@link #versionKey} and the
 * {@code cronozx.cullinggames.common.message.MessageType#HELLO} message) and refuse to
 * coordinate with a peer announcing a different major version, logging the mismatch loudly.
 *
 * <p>This is a stateless utility holder and cannot be instantiated.
 */
public final class Protocol {

    /**
     * The wire-protocol version both halves must agree on.
     *
     * <p>Bump this whenever key layout or message arity changes in a way an older peer cannot read.
     * v1 = single round per namespace; v2 = match-scoped keys and a match id on every round message.
     */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * Match id used when a namespace runs a single round, so a standalone-shaped network install
     * has a stable, readable key layout ({@code battleroyale:m:default:alive}) instead of an opaque
     * generated id.
     */
    public static final String DEFAULT_MATCH_ID = "default";

    private Protocol() {
    }

    /**
     * Channel the game server publishes on and the proxy subscribes to ({@code game → proxy}).
     *
     * @param prefix the configured key/channel prefix
     * @return {@code prefix + ":proxy"}
     */
    public static String proxyChannel(String prefix) {
        return prefix + ":proxy";
    }

    /**
     * Channel the proxy publishes on and the game server subscribes to ({@code proxy → game}).
     *
     * @param prefix the configured key/channel prefix
     * @return {@code prefix + ":game"}
     */
    public static String gameChannel(String prefix) {
        return prefix + ":game";
    }

    /**
     * Key for the pending-players queue, a Redis {@code LIST} of player UUIDs.
     *
     * <p>Namespace-global: players queue once and are assigned to whichever round starts next, so
     * this is not match-scoped even in v2.
     *
     * @param prefix the configured key/channel prefix
     * @return {@code prefix + ":queue"}
     */
    public static String queueKey(String prefix) {
        return prefix + ":queue";
    }

    /**
     * Key for the queue-window state, a Redis {@code STRING} holding a {@link GameState} name.
     *
     * <p>Namespace-global, and distinct from {@link #stateKey}: this one tracks whether the
     * <em>queue</em> is open, closing or idle, which is a property of the namespace rather than of
     * any single round. In v1 one key served both roles.
     *
     * @param prefix the configured key/channel prefix
     * @return {@code prefix + ":state"}
     */
    public static String queueStateKey(String prefix) {
        return prefix + ":state";
    }

    /**
     * Key for the set of live match ids, a Redis {@code SET}.
     *
     * <p>The proxy's index of what is running: it is how a restarted proxy rediscovers rounds that
     * were already in flight, and how {@code /br status} enumerates them without scanning keys.
     *
     * @param prefix the configured key/channel prefix
     * @return {@code prefix + ":matches"}
     */
    public static String matchesKey(String prefix) {
        return prefix + ":matches";
    }

    /**
     * Key for the auto-start window lock, a Redis {@code STRING} written with {@code SET NX EX}
     * so that exactly one proxy opens a given scheduled window when several proxies run.
     *
     * @param prefix the configured key/channel prefix
     * @return {@code prefix + ":autostart-lock"}
     */
    public static String autoStartLockKey(String prefix) {
        return prefix + ":autostart-lock";
    }

    /**
     * Key holding the {@link #PROTOCOL_VERSION} each side announces, a Redis {@code STRING}.
     *
     * <p>Written by both halves at startup and read by the other, so a version mismatch is detected
     * immediately rather than after a queue window silently fails to start anything.
     *
     * @param prefix the configured key/channel prefix
     * @param role   {@code "game"} or {@code "proxy"} — each side announces under its own key so
     *               neither overwrites the other's
     * @return {@code prefix + ":protocol:" + role}
     */
    public static String versionKey(String prefix, String role) {
        return prefix + ":protocol:" + role;
    }

    /**
     * Base of the match-scoped key group.
     *
     * @param prefix  the configured key/channel prefix
     * @param matchId the match id (see {@link #validateMatchId})
     * @return {@code prefix + ":m:" + matchId}
     */
    public static String matchKey(String prefix, String matchId) {
        return prefix + ":m:" + validateMatchId(matchId);
    }

    /**
     * Key for one round's roster of living players, a Redis {@code SET} of player UUIDs.
     *
     * @param prefix  the configured key/channel prefix
     * @param matchId the match id
     * @return {@code prefix + ":m:" + matchId + ":alive"}
     */
    public static String aliveKey(String prefix, String matchId) {
        return matchKey(prefix, matchId) + ":alive";
    }

    /**
     * Key for one round's per-player kill counts, a Redis {@code HASH} of {@code uuid → int}.
     *
     * @param prefix  the configured key/channel prefix
     * @param matchId the match id
     * @return {@code prefix + ":m:" + matchId + ":kills"}
     */
    public static String killsKey(String prefix, String matchId) {
        return matchKey(prefix, matchId) + ":kills";
    }

    /**
     * Key for one round's mutual-exclusion lock, a Redis {@code STRING} written with
     * {@code SET NX EX}. Its TTL provides crash recovery.
     *
     * <p>In v1 this was a namespace singleton and therefore also the cap of one round per network;
     * scoping it per match is the change that lifts that cap.
     *
     * @param prefix  the configured key/channel prefix
     * @param matchId the match id
     * @return {@code prefix + ":m:" + matchId + ":lock"}
     */
    public static String lockKey(String prefix, String matchId) {
        return matchKey(prefix, matchId) + ":lock";
    }

    /**
     * Key for one round's advisory lifecycle state, a Redis {@code STRING} holding a
     * {@link GameState} name.
     *
     * @param prefix  the configured key/channel prefix
     * @param matchId the match id
     * @return {@code prefix + ":m:" + matchId + ":state"}
     */
    public static String stateKey(String prefix, String matchId) {
        return matchKey(prefix, matchId) + ":state";
    }

    /**
     * Validates a match id.
     *
     * <p>A match id travels in two places that both have delimiters: Redis key names (separated by
     * {@code :}) and wire messages (separated by {@code |}, with {@code ,} inside list fields).
     * Restricting it to {@code [A-Za-z0-9_.-]} means it can never split a key or a field, so no
     * escaping is needed anywhere downstream.
     *
     * @param matchId the candidate id
     * @return the same id, unchanged
     * @throws IllegalArgumentException if it is {@code null}, blank, over 64 characters, or contains
     *         anything outside {@code [A-Za-z0-9_.-]}
     */
    public static String validateMatchId(String matchId) {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("Match id must not be null or blank");
        }
        if (matchId.length() > 64) {
            throw new IllegalArgumentException(
                    "Match id '" + matchId + "' exceeds 64 characters");
        }
        for (int i = 0; i < matchId.length(); i++) {
            char c = matchId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!ok) {
                throw new IllegalArgumentException("Match id '" + matchId
                        + "' contains an illegal character '" + c
                        + "'; allowed characters are A-Z a-z 0-9 _ . -");
            }
        }
        return matchId;
    }

    /**
     * Builds the conventional match id for a round of {@code arenaId} on {@code gameServer}.
     *
     * <p>Deriving the id from the two things that already identify a round — rather than generating
     * a random one — means a crashed game server reclaims its own keys on restart instead of
     * orphaning them, and makes the keys readable in {@code redis-cli}.
     *
     * @param gameServer the game server's configured name
     * @param arenaId    the arena id the round runs in
     * @return {@code gameServer + "." + arenaId}, sanitised to the legal character set
     */
    public static String matchId(String gameServer, String arenaId) {
        return validateMatchId(sanitise(gameServer) + "." + sanitise(arenaId));
    }

    /**
     * Arena id used by a game server that hosts a single round.
     *
     * <p>A placeholder rather than a real arena name: until a game server can host more than one
     * round at a time there is nothing to distinguish, and using a fixed token keeps the key layout
     * identical whether or not the operator has named their arenas.
     */
    public static final String MAIN_ARENA = "main";

    /**
     * The id of the one round a given game server hosts.
     *
     * <p>Both halves derive the id from this method rather than each building the string themselves,
     * because a disagreement about it would reproduce exactly the silent never-see-each-other failure
     * that {@link #versionKey} exists to prevent.
     *
     * @param gameServer the game server's configured name; blank falls back to
     *                   {@link #DEFAULT_MATCH_ID}
     * @return the match id for that server's round
     */
    public static String mainMatchId(String gameServer) {
        if (gameServer == null || gameServer.isBlank()) {
            return DEFAULT_MATCH_ID;
        }
        return matchId(gameServer, MAIN_ARENA);
    }

    private static String sanitise(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Match id component must not be null or blank");
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            out.append(ok ? c : '-');
        }
        return out.toString();
    }
}
