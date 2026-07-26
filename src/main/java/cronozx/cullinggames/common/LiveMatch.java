package cronozx.cullinggames.common;

/**
 * One round currently registered as live in the shared backend.
 *
 * <p>Protocol v2's index of what is running. A round registers itself when it acquires its lock and
 * unregisters when it ends, so the proxy can answer "what is in flight, and where" without scanning
 * key space — and a proxy that restarts mid-round rediscovers rounds it never saw start.
 *
 * @param matchId    the round's id (see {@link Protocol#validateMatchId})
 * @param gameServer the game server running it, as the proxy knows it
 * @param arena      the arena id the round is being played in
 */
public record LiveMatch(String matchId, String gameServer, String arena) {

    /** Validates the id and rejects null components. */
    public LiveMatch {
        Protocol.validateMatchId(matchId);
        if (gameServer == null || gameServer.isBlank()) {
            throw new IllegalArgumentException("gameServer must not be null or blank");
        }
        if (arena == null || arena.isBlank()) {
            throw new IllegalArgumentException("arena must not be null or blank");
        }
    }
}
