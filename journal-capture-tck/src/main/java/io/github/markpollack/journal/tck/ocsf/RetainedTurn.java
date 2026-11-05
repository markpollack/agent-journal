package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;
import static io.github.markpollack.journal.tck.ocsf.RetainedSource.require;

/** Source-grounded expectations for the bounded successful-text-turn profile. */
final class RetainedTurn {
    private RetainedTurn() { }

    static String validate(Json generation, Json turn, Map<String, RetainedSource> sources, String previous) { return validate(generation, turn, sources, previous, false); }

    /** {@code absentBefore} admits only the tagged fresh-start form; it is a membership bound, never a byte witness. */
    static String validate(Json generation, Json turn, Map<String, RetainedSource> sources, String previous, boolean absentBefore) {
        Json[] envelope = envelope(generation, turn, sources); Json prompt = envelope[0], result = envelope[1];
        List<Json> nativeLocators = array(turn, "nativeRecords");
        require(nativeLocators.size() == 2, "text profile requires one native prompt and response");
        Json user = sources.get("TRANSCRIPT").record(nativeLocators.get(0));
        Json assistant = sources.get("TRANSCRIPT").record(nativeLocators.get(1));
        nativeIdentity(generation, user); nativeIdentity(generation, assistant);
        expect(user, "type", "user"); expect(assistant, "type", "assistant");
        require(Json.sameValue(user.member("message"), prompt.member("message")), "native prompt differs");
        require(previous == null ? user.member("parentUuid") != null && user.member("parentUuid").isNull()
                : previous.equals(string(user, "parentUuid")), "native prompt parent differs");
        expect(assistant, "parentUuid", string(user, "uuid"));
        require(!string(user, "uuid").equals(string(assistant, "uuid")), "native UUID reused");
        expect(result, "result", response(assistant));
        prefixes(turn, sources.get("TRANSCRIPT"), nativeLocators, absentBefore);
        stream(turn, sources.get("STDOUT"), assistant);
        windows(turn, sources);
        return string(assistant, "uuid");
    }

    /** The turn's STDIN prompt and STDOUT result records after their shared checks (user prompt; successful result of the native session). */
    static Json[] envelope(Json generation, Json turn, Map<String, RetainedSource> sources) {
        Json prompt = sources.get("STDIN").record(turn.member("prompt"));
        Json result = sources.get("STDOUT").record(turn.member("result"));
        expect(turn.member("prompt"), "source", "STDIN"); expect(turn.member("result"), "source", "STDOUT");
        expect(prompt, "type", "user"); require(prompt.member("message") instanceof Json.JObject, "prompt message missing");
        expect(prompt.member("message"), "role", "user");
        expect(result, "type", "result"); expect(result, "subtype", "success");
        require(result.member("is_error") instanceof Json.JBool b && !b.value(), "retained result is not successful");
        expect(result, "session_id", string(generation, "nativeSessionId"));
        return new Json[] {prompt, result};
    }

    static void nativeIdentity(Json generation, Json row) {
        expect(row, "sessionId", string(generation, "nativeSessionId"));
        expect(row, "version", "2.1.316"); string(row, "uuid");
    }

    static String response(Json assistant) {
        Json message = assistant.member("message"); require(message instanceof Json.JObject, "native response message missing");
        expect(message, "role", "assistant");
        string(message, "id"); List<Json> blocks = array(message, "content");
        require(blocks.size() == 1, "text profile requires one response text block");
        expect(blocks.get(0), "type", "text"); return rawString(blocks.get(0), "text");
    }

    private static void prefixes(Json turn, RetainedSource source, List<Json> locators, boolean absentBefore) {
        long before = absentBefore ? absent(turn.member("nativePrefixBefore")) : source.prefix(turn.member("nativePrefixBefore"));
        long after = source.prefix(turn.member("nativePrefixAfter"));
        require(before <= after, "native prefix regressed");
        for (Json locator : locators) {
            expect(locator, "source", "TRANSCRIPT");
            require(number(locator, "start") >= before && number(locator, "end") <= after, "native member outside observed prefixes");
        }
    }

    static long absent(Json observation) {
        fields(observation, "state observation"); expect(observation, "state", "ABSENT"); expect(observation, "observation", "before-first-dispatch"); return 0;
    }

    private static void stream(Json turn, RetainedSource source, Json assistant) {
        int results = 0;
        for (Json locator : array(turn, "stdoutRecords")) {
            expect(locator, "source", "STDOUT"); inWindow(turn, locator); Json row = source.record(locator);
            if ("result".equals(string(row, "type"))) {
                require(Json.sameValue(locator, turn.member("result")), "result locator differs"); results++;
            }
            else {
                expect(row, "type", "assistant"); expect(row, "uuid", string(assistant, "uuid"));
                expect(row, "session_id", string(assistant, "sessionId"));
                require(Json.sameValue(row.member("message"), assistant.member("message")), "stream/native response differs");
            }
        }
        require(results == 1, "retained turn needs exactly one result");
    }

    static void windows(Json turn, Map<String, RetainedSource> sources) {
        for (String stream : Set.of("STDIN", "STDOUT", "STDERR")) {
            long start = number(turn.member("startOffsets"), stream), end = number(turn.member("endOffsets"), stream);
            require(start >= 0 && start <= end && end <= sources.get(stream).size(), "invalid observed stream window");
        }
        inWindow(turn, turn.member("prompt")); inWindow(turn, turn.member("result"));
    }

    static void inWindow(Json turn, Json locator) {
        String stream = string(locator, "source");
        require(number(locator, "start") >= number(turn.member("startOffsets"), stream)
                && number(locator, "end") <= number(turn.member("endOffsets"), stream), "prompt/result outside observation window");
    }
}
