package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;
import static io.github.markpollack.journal.tck.ocsf.RetainedSource.require;

/** The native text-turn model of the fresh profile {@value FreshRetainedGeneration#NATIVE_PROFILE}, derived from the frozen native
 * Claude Code 2.1.316 probe specimen of 2026-11-05 (two text turns, no tools). The frozen {@code /1} and ordinary {@code /2}
 * profiles keep {@link RetainedTurn} unchanged. Rule text:
 * <ol>
 * <li><b>Turn membership.</b> The turn's native records tile its prefix window exactly: sorted by offset they start at the
 * before-prefix, are contiguous and end at the after-prefix, so every record in the window belongs to exactly one turn.
 * Every record is of an admitted class: identity classes {@code user}, {@code assistant}, {@code attachment} (attachment
 * subtypes {@link #ATTACHMENTS}) or plain classes {@link #PLAIN}; an unknown class or attachment subtype is REFUSED, never
 * skipped. Every record carries the generation's native {@code sessionId}; identity rows carry {@code uuid} and
 * {@code version 2.1.316}. Exactly one {@code user} and one {@code assistant} per turn; the prompt equals STDIN's
 * message and the response text equals STDOUT's successful result.</li>
 * <li><b>Parent ancestry.</b> The assistant descends from its own turn's user through a {@code parentUuid} chain made only
 * of that turn's attachments; the chain must stay inside the turn's window, visit no record twice and reuse no UUID. Every
 * other identity row of the turn is a trailing attachment descending from the assistant. The first turn's user has a null
 * parent and no identity record precedes it; every later user's parent is the previous turn's assistant or one of its
 * trailing attachments.</li>
 * <li><b>Stream.</b> STDOUT records inside the turn window are {@code result} (exactly one, the declared result),
 * {@code assistant} (same uuid and session as the native response; the message members {@link #STREAMED_MESSAGE} equal the
 * native row's, while {@code stop_reason}, {@code stop_sequence} and {@code usage} are the in-flight stream values and are
 * not compared: a restatement of the {@code /1} rule, which compared the whole message) or {@code rate_limit_event}; an
 * unknown stream class is REFUSED.</li>
 * <li>Prefix, window and result rules are those of {@link RetainedTurn}; the transcript tail after the last turn is handled
 * by {@link RetainedGeneration} (plain classes only).</li>
 * </ol> */
final class NativeRetainedTurn {
    static final Set<String> IDENTITY = Set.of("user", "assistant", "attachment");
    static final Set<String> PLAIN = Set.of("queue-operation", "atis-latch", "api-request-shape", "api-request-blob", "api-request", "last-prompt", "ai-title", "cost-state");
    static final Set<String> ATTACHMENTS = Set.of("environment", "model", "total_tokens_reminder", "budget_usd", "instructions", "session_context", "date", "credential_org", "prompt_snapshot", "deferred_tools_delta", "mcp_instructions_delta");
    static final Set<String> STREAMED_MESSAGE = Set.of("id", "role", "model", "type", "content");
    private NativeRetainedTurn() { }

    /** What the next turn may descend from: the assistant and its trailing attachments. */
    record Tail(String assistant, Set<String> trailing) { }

    static Tail validate(Json generation, Json turn, Map<String, RetainedSource> sources, Tail previous, boolean absentBefore) {
        String session = string(generation, "nativeSessionId");
        Json[] envelope = RetainedTurn.envelope(generation, turn, sources); Json prompt = envelope[0], result = envelope[1];
        NativeWindow window = NativeWindow.of(turn, sources.get("TRANSCRIPT"), absentBefore);
        Turn members = members(generation, window, sources.get("TRANSCRIPT"), session);
        require(Json.sameValue(members.user.member("message"), prompt.member("message")), "native prompt differs");
        expect(result, "result", RetainedTurn.response(members.assistant));
        Set<String> chain = ancestry(members);
        if (previous == null) {
            require(members.user.member("parentUuid") != null && members.user.member("parentUuid").isNull(), "fresh native prompt has a parent");
            require(members.order.get(0).equals(string(members.user, "uuid")), "identity record precedes the first native prompt");
        }
        else require(previous.trailing().contains(string(members.user, "parentUuid")), "native prompt does not descend from the previous response");
        Set<String> trailing = trailing(members, chain);
        stream(turn, sources.get("STDOUT"), members.assistant, session);
        RetainedTurn.windows(turn, sources);
        return new Tail(string(members.assistant, "uuid"), trailing);
    }

    private record NativeWindow(long before, long after, List<Json> locators) {
        static NativeWindow of(Json turn, RetainedSource transcript, boolean absentBefore) {
            long before = absentBefore ? RetainedTurn.absent(turn.member("nativePrefixBefore")) : transcript.prefix(turn.member("nativePrefixBefore"));
            long after = transcript.prefix(turn.member("nativePrefixAfter"));
            require(before <= after, "native prefix regressed");
            List<Json> locators = new ArrayList<>(array(turn, "nativeRecords"));
            for (Json locator : locators) expect(locator, "source", "TRANSCRIPT");
            locators.sort(Comparator.comparingLong(l -> number(l, "start")));
            long end = before;
            for (Json locator : locators) {
                require(number(locator, "start") == end, "turn membership: native records do not tile the prefix window");
                end = number(locator, "end");
            }
            require(end == after, "turn membership: native records do not tile the prefix window");
            return new NativeWindow(before, after, locators);
        }
    }

    private record Turn(Json user, Json assistant, Map<String, Json> identity, List<String> order) { }

    private static Turn members(Json generation, NativeWindow window, RetainedSource transcript, String session) {
        Map<String, Json> identity = new LinkedHashMap<>(); List<String> order = new ArrayList<>();
        for (Json locator : window.locators()) {
            Json row = transcript.record(locator); String type = string(row, "type");
            expect(row, "sessionId", session);
            if (!IDENTITY.contains(type)) { require(PLAIN.contains(type), "unknown native record class: " + type); continue; }
            RetainedTurn.nativeIdentity(generation, row);
            String uuid = string(row, "uuid");
            require(identity.put(uuid, row) == null, "native UUID reused");
            order.add(uuid);
            if (type.equals("attachment")) attachment(row);
        }
        Json user = single(identity, "user", "prompt"), assistant = single(identity, "assistant", "response");
        expect(user.member("message"), "role", "user");
        return new Turn(user, assistant, identity, order);
    }

    private static void attachment(Json row) {
        require(row.member("attachment") instanceof Json.JObject, "attachment without body");
        require(ATTACHMENTS.contains(string(row.member("attachment"), "type")), "unknown native attachment class: " + string(row.member("attachment"), "type"));
    }

    /** Exactly one identity row of {@code type} per text turn. */
    private static Json single(Map<String, Json> identity, String type, String role) {
        Json found = null;
        for (Json row : identity.values()) {
            if (!type.equals(string(row, "type"))) continue;
            require(found == null, "text profile admits one native " + role + " per turn");
            found = row;
        }
        if (found == null) throw new IllegalArgumentException("text profile requires one native prompt and one native response");
        return found;
    }

    /** The response's parent chain, which must reach the prompt through this turn's attachments only. */
    private static Set<String> ancestry(Turn t) {
        Set<String> visited = new HashSet<>(); visited.add(string(t.assistant, "uuid"));
        String user = string(t.user, "uuid"); Json row = t.assistant;
        while (true) {
            String parent = parent(row, "native response does not descend from the native prompt");
            require(t.identity.containsKey(parent), "native parent outside the turn window");
            require(visited.add(parent), "native ancestry cycle");
            row = t.identity.get(parent);
            if (parent.equals(user)) return visited;
            expect(row, "type", "attachment");
        }
    }

    /** Every identity row off the prompt–response chain must be an attachment descending from the response. */
    private static Set<String> trailing(Turn t, Set<String> chain) {
        Set<String> trailing = new HashSet<>(); String assistant = string(t.assistant, "uuid"); trailing.add(assistant);
        for (String uuid : t.order) {
            if (chain.contains(uuid)) continue;
            Json row = t.identity.get(uuid); expect(row, "type", "attachment");
            Set<String> visited = new HashSet<>(); visited.add(uuid);
            while (true) {
                String parent = parent(row, "trailing native record does not descend from the response");
                require(t.identity.containsKey(parent), "native parent outside the turn window");
                require(visited.add(parent), "native ancestry cycle");
                if (parent.equals(assistant)) break;
                require(!chain.contains(parent), "trailing native record does not descend from the response");
                row = t.identity.get(parent);
            }
            trailing.add(uuid);
        }
        return trailing;
    }

    private static String parent(Json row, String reason) {
        Json parent = row.member("parentUuid");
        require(parent instanceof Json.JString s && !s.value().isEmpty(), reason);
        return ((Json.JString) parent).value();
    }

    private static void stream(Json turn, RetainedSource source, Json assistant, String session) {
        int results = 0;
        for (Json locator : array(turn, "stdoutRecords")) {
            expect(locator, "source", "STDOUT"); RetainedTurn.inWindow(turn, locator); Json row = source.record(locator);
            String type = string(row, "type"); expect(row, "session_id", session);
            switch (type) {
                case "result" -> { require(Json.sameValue(locator, turn.member("result")), "result locator differs"); results++; }
                case "assistant" -> {
                    expect(row, "uuid", string(assistant, "uuid"));
                    Json streamed = row.member("message"), nativeMessage = assistant.member("message");
                    require(streamed instanceof Json.JObject && nativeMessage instanceof Json.JObject, "stream/native response message missing");
                    for (String member : STREAMED_MESSAGE) {
                        Json a = streamed.member(member), b = nativeMessage.member(member);
                        require(a != null && b != null && Json.sameValue(a, b), "stream/native response differs: " + member);
                    }
                }
                case "rate_limit_event" -> { }
                default -> throw new IllegalArgumentException("unknown stream record class: " + type);
            }
        }
        require(results == 1, "retained turn needs exactly one result");
    }
}
