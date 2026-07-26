package cronozx.cullinggames.common.message;

/**
 * Why a round was cancelled. Travels as the single argument of a {@link MessageType#CANCELLED}
 * message ({@code CANCELLED|<reason>}) and lets the proxy pick the correct broadcast.
 */
public enum CancelReason {

    /** Fewer than the required minimum players were queued when the window closed or drained. */
    NOT_ENOUGH_PLAYERS,

    /** An administrator issued {@code /br stop} (or the plugin disabled mid-round). */
    ADMIN_STOP,

    /** Too few pulled players arrived at the arena before the arrival timeout elapsed. */
    ARRIVAL_TIMEOUT
}
