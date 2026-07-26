package cronozx.cullinggames.common;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Advisory lifecycle state of the single BattleRoyale game, persisted as the {@code <prefix>:state}
 * Redis string and shared by both runtime modules.
 *
 * <p>The state is <strong>advisory</strong>: it drives {@code /br status} display and the
 * queue-window open guard, but the authoritative mutual-exclusion authority is the
 * {@code <prefix>:lock} key held by the game server. Because the value travels over Redis as a
 * plain string, {@link #fromWire(String)} tolerates missing, blank, and unrecognised values by
 * degrading to {@link #IDLE}.
 */
public enum GameState {

    /** Nothing is happening; no queue window is open and no round is running. */
    IDLE,

    /** A queue window is open and players may {@code /queue}. */
    OPEN,

    /** The queue window has closed and the round is being handed to the game server. */
    STARTING,

    /** A round is in progress on the game server. */
    RUNNING;

    /**
     * Legal forward transitions between states. Kept intentionally permissive because the value is
     * advisory and may be written by either side; it exists to document and lightly validate the
     * lifecycle, not to enforce it. A transition to the same state is always considered legal
     * (writes are idempotent).
     */
    private static final Map<GameState, Set<GameState>> LEGAL = Map.of(
            IDLE, EnumSet.of(OPEN, STARTING),
            OPEN, EnumSet.of(STARTING, IDLE),
            STARTING, EnumSet.of(RUNNING, IDLE),
            RUNNING, EnumSet.of(IDLE)
    );

    /**
     * Parses a state value read off the wire (the {@code <prefix>:state} Redis string).
     *
     * @param s the raw value; may be {@code null}
     * @return the matching state, or {@link #IDLE} when {@code s} is {@code null}, blank, or does
     *         not name a known state
     */
    public static GameState fromWire(String s) {
        if (s == null) {
            return IDLE;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return IDLE;
        }
        try {
            return valueOf(trimmed);
        } catch (IllegalArgumentException unknown) {
            return IDLE;
        }
    }

    /**
     * The wire form of this state, written to the {@code <prefix>:state} Redis string.
     *
     * @return the enum constant name
     */
    public String wire() {
        return name();
    }

    /**
     * Whether advancing from this state to {@code next} is a legal lifecycle transition. Advancing
     * to the same state is always legal.
     *
     * @param next the proposed next state
     * @return {@code true} if the transition (or self-transition) is legal
     */
    public boolean canTransitionTo(GameState next) {
        if (next == this) {
            return true;
        }
        return LEGAL.getOrDefault(this, Set.of()).contains(next);
    }
}
