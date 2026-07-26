package cronozx.cullinggames.common.message;

import cronozx.cullinggames.common.Protocol;

/**
 * Encoding, decoding and the compatibility verdict for the {@link MessageType#HELLO} handshake.
 *
 * <p>Both halves coordinate through a shared backend rather than a direct connection, so nothing
 * fails when they disagree about the wire layout — a v1 proxy writes {@code battleroyale:alive}
 * while a v2 game server reads {@code battleroyale:m:default:alive}, and the two simply never
 * observe each other. Before v2 that silent mismatch was indistinguishable from an idle network
 * (§4.1 of {@code docs/ARCHITECTURE.md}).
 *
 * <p>{@code HELLO} closes that hole. Each side announces its version on startup and on reconnect;
 * the receiver compares it against {@link Protocol#PROTOCOL_VERSION} and, on a mismatch, logs which
 * side is behind and what to upgrade. Detection is best-effort by design: it makes an existing
 * silent failure loud, and never blocks a round that is otherwise working.
 */
public final class Handshake {

    /** Role token announced by the Paper game server. */
    public static final String ROLE_GAME = "game";

    /** Role token announced by the Velocity proxy. */
    public static final String ROLE_PROXY = "proxy";

    private Handshake() {
    }

    /**
     * Encodes this side's announcement.
     *
     * @param role {@link #ROLE_GAME} or {@link #ROLE_PROXY}
     * @return the wire form, {@code HELLO|<version>|<role>}
     */
    public static String encode(String role) {
        return Message.encode(MessageType.HELLO,
                Integer.toString(Protocol.PROTOCOL_VERSION), role);
    }

    /**
     * Reads the version out of a received {@code HELLO}.
     *
     * @param message a decoded {@link MessageType#HELLO} message
     * @return the peer's announced protocol version
     * @throws IllegalArgumentException if the message is malformed or the version is not a number
     */
    public static int version(Message message) {
        message.requireArity(2);
        try {
            return Integer.parseInt(message.arg(0).trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "HELLO carried a non-numeric version '" + message.args().get(0) + "'");
        }
    }

    /**
     * Reads the role out of a received {@code HELLO}.
     *
     * @param message a decoded {@link MessageType#HELLO} message
     * @return the peer's announced role
     * @throws IllegalArgumentException if the message is malformed
     */
    public static String role(Message message) {
        message.requireArity(2);
        return message.arg(1);
    }

    /**
     * @param peerVersion the version a peer announced
     * @return {@code true} when this side can coordinate with that peer
     */
    public static boolean compatible(int peerVersion) {
        return peerVersion == Protocol.PROTOCOL_VERSION;
    }

    /**
     * Builds the operator-facing explanation of a mismatch — which side is behind, and what to do.
     *
     * @param peerVersion the version the peer announced
     * @param peerRole    the role the peer announced
     * @return a single-line message naming the versions and the required action
     */
    public static String mismatchMessage(int peerVersion, String peerRole) {
        String direction = peerVersion < Protocol.PROTOCOL_VERSION
                ? "The " + peerRole + " is running an OLDER BattleRoyale build"
                : "The " + peerRole + " is running a NEWER BattleRoyale build";
        return "Protocol version mismatch: this side speaks v" + Protocol.PROTOCOL_VERSION
                + ", the " + peerRole + " announced v" + peerVersion + ". " + direction
                + ". The two halves coordinate through key names that changed between versions, so"
                + " they will NOT see each other until both run the same build. Update the game"
                + " server and the proxy together.";
    }
}
