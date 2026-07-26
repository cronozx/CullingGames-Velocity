package cronozx.cullinggames.common.message;

/**
 * Every message type exchanged over the two BattleRoyale Redis channels. The type is the token
 * before the first {@code |} in the wire form (see {@link Message}).
 *
 * <h2>Protocol v2</h2>
 * Every round-scoped message carries its {@code <matchId>} as the <strong>first</strong> argument,
 * which is what lets one namespace carry several simultaneous rounds. {@link #HELLO} is the only
 * message that is not round-scoped, because it is what establishes whether the two sides can talk
 * at all.
 *
 * <p>Proxy → game ({@code <prefix>:game}):
 * <ul>
 *   <li>{@link #START} — {@code START|<matchId>|<gameServer>|<arenaId>|<minPlayers>}</li>
 *   <li>{@link #STOP} — {@code STOP|<matchId>}</li>
 * </ul>
 *
 * <p>Game → proxy ({@code <prefix>:proxy}):
 * <ul>
 *   <li>{@link #PULL} — {@code PULL|<matchId>|<uuid,uuid,…>}</li>
 *   <li>{@link #ELIMINATED} — {@code ELIMINATED|<matchId>|<uuid>}</li>
 *   <li>{@link #TIMEOUT} — {@code TIMEOUT|<matchId>|<uuid>}</li>
 *   <li>{@link #CANCELLED} — {@code CANCELLED|<matchId>|<reason>}</li>
 *   <li>{@link #ENDED} — {@code ENDED|<matchId>|<winnerUuid>} (winner may be empty)</li>
 * </ul>
 *
 * <p>Both directions:
 * <ul>
 *   <li>{@link #HELLO} — {@code HELLO|<protocolVersion>|<role>}</li>
 * </ul>
 */
public enum MessageType {

    /**
     * Proxy asks the game server to start a round of {@code arenaId} under {@code matchId}, with an
     * authoritative minimum player count.
     */
    START,

    /** Proxy asks the game server to force-stop the round identified by {@code matchId}. */
    STOP,

    /** Game server asks the proxy to connect a roster of players to the arena. */
    PULL,

    /** Game server reports a player was eliminated (routed to the lobby in {@code LOBBY} mode). */
    ELIMINATED,

    /** Game server reports a pulled player never arrived in time. */
    TIMEOUT,

    /** Game server reports the round was cancelled, carrying a {@link CancelReason}. */
    CANCELLED,

    /** Game server reports the round ended, carrying the (possibly empty) winner UUID. */
    ENDED,

    /**
     * Either side announces its protocol version and role, so a v1/v2 mismatch surfaces as a loud
     * log line rather than as two halves that silently never see each other.
     */
    HELLO
}
