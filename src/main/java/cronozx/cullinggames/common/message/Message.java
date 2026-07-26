package cronozx.cullinggames.common.message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * An immutable decoded BattleRoyale wire message: a {@link MessageType} plus its ordered string
 * arguments.
 *
 * <h2>Wire format</h2>
 * A message is either the bare type name ({@code "STOP"}) or the type name followed by one or more
 * {@code |}-delimited arguments ({@code "START|battleroyale|5"}). A single logical field that is a
 * list of UUIDs is comma-joined with no spaces or brackets ({@code "a,b,c"}), so the two
 * delimiters never collide:
 * <ul>
 *   <li>UUIDs contain only {@code [0-9a-f-]};</li>
 *   <li>server names are constrained to {@code [A-Za-z0-9_.-]};</li>
 *   <li>enum names are {@code [A-Z_]}; integers are digits.</li>
 * </ul>
 * No field ever contains {@code |} or {@code ,} except the UUID list, which uses only {@code ,}.
 *
 * <h2>Defensive decoding</h2>
 * Every handler is expected to call {@link #requireArity(int)} before reading arguments, so
 * malformed input is logged and dropped by the caller instead of throwing
 * {@code ArrayIndexOutOfBoundsException} deep in a handler. {@link #parse(String)} preserves
 * trailing empty fields (e.g. an empty roster in {@code "PULL|"} or an empty winner in
 * {@code "ENDED|"}), so encode/parse round-trips even for empty list/optional payloads.
 *
 * @param type the message type
 * @param args the ordered, immutable arguments (never {@code null}; may be empty)
 */
public record Message(MessageType type, List<String> args) {

    /** Field delimiter in the wire form. */
    private static final String FIELD_SEPARATOR = "|";

    /** Element delimiter within a UUID-list field. */
    private static final String LIST_SEPARATOR = ",";

    /**
     * Canonical constructor; defensively copies {@code args} into an unmodifiable list and rejects
     * a {@code null} type.
     */
    public Message {
        Objects.requireNonNull(type, "type");
        args = (args == null) ? List.of() : List.copyOf(args);
    }

    /**
     * Encodes a message to its wire form.
     *
     * @param type the message type (required)
     * @param args the ordered arguments; each is appended verbatim after the type, {@code |}-joined
     * @return {@code "TYPE"} when no arguments are given, otherwise {@code "TYPE|arg|arg|…"}
     * @throws NullPointerException if {@code type} or any argument is {@code null}
     */
    public static String encode(MessageType type, String... args) {
        Objects.requireNonNull(type, "type");
        StringJoiner joiner = new StringJoiner(FIELD_SEPARATOR);
        joiner.add(type.name());
        if (args != null) {
            for (String arg : args) {
                joiner.add(Objects.requireNonNull(arg, "arg"));
            }
        }
        return joiner.toString();
    }

    /**
     * Parses a raw wire string into a {@link Message}.
     *
     * <p>Trailing empty fields are preserved (limit {@code -1} split), so {@code "PULL|"} decodes
     * to a single empty argument rather than none — this is what lets an empty UUID list or an
     * empty optional field survive a round-trip.
     *
     * @param raw the raw wire string
     * @return the decoded message
     * @throws IllegalArgumentException if {@code raw} is {@code null}, blank, or its leading token
     *         does not name a known {@link MessageType}
     */
    public static Message parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Cannot parse null/blank message");
        }
        String[] parts = raw.split("\\" + FIELD_SEPARATOR, -1);
        MessageType type;
        try {
            type = MessageType.valueOf(parts[0]);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown message type: '" + parts[0] + "'");
        }
        List<String> args = new ArrayList<>(parts.length - 1);
        for (int i = 1; i < parts.length; i++) {
            args.add(parts[i]);
        }
        return new Message(type, args);
    }

    /**
     * Returns the argument at index {@code i}, guaranteed present and non-blank.
     *
     * @param i zero-based argument index
     * @return the argument value
     * @throws IllegalArgumentException if the argument is absent or blank
     */
    public String arg(int i) {
        if (i < 0 || i >= args.size()) {
            throw new IllegalArgumentException(
                    "Missing argument " + i + " for " + type + " (have " + args.size() + ")");
        }
        String value = args.get(i);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Blank argument " + i + " for " + type);
        }
        return value;
    }

    /**
     * Guards that at least {@code n} arguments are present. Handlers call this before reading
     * arguments so malformed messages fail fast with a clear reason.
     *
     * @param n the minimum required argument count
     * @throws IllegalArgumentException if fewer than {@code n} arguments are present
     */
    public void requireArity(int n) {
        if (args.size() < n) {
            throw new IllegalArgumentException(
                    type + " requires " + n + " argument(s) but got " + args.size());
        }
    }

    /**
     * Encodes a collection of UUIDs into a single comma-joined field with no spaces or brackets.
     *
     * @param ids the UUIDs; may be {@code null} or empty
     * @return {@code "a,b,c"}, or the empty string when {@code ids} is {@code null} or empty
     */
    public static String encodeUuidList(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(LIST_SEPARATOR);
        for (UUID id : ids) {
            joiner.add(id.toString());
        }
        return joiner.toString();
    }

    /**
     * Parses a comma-joined UUID field back into a list. Tolerant of surrounding whitespace and of
     * empty entries (blank tokens are skipped), so {@code ""}, {@code "a, b , c"}, and
     * {@code "a,,b,"} all parse cleanly.
     *
     * @param csv the raw field; may be {@code null}
     * @return the parsed UUIDs in order (empty when {@code csv} is {@code null}, empty, or all blank)
     * @throws IllegalArgumentException if a non-blank token is not a valid UUID
     */
    public static List<UUID> parseUuidList(String csv) {
        List<UUID> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String token : csv.split(LIST_SEPARATOR)) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.add(UUID.fromString(trimmed));
        }
        return out;
    }
}
