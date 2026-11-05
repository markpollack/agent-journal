package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration.Rejection;
import io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration.Verdict;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;

/** One turn's contiguous rollout region: lifecycle population, foreign identities, prompt membership and usage witnesses. */
final class CodexRetainedRegion {
    private final Json t; private final Map<String, byte[]> originals; private final String session, cwd, turn, prompt; private final boolean ordinary;
    private int started, completed, contexts, prompts; private Json usageRecord, lastTotal;

    /** {@code ordinary} applies the native-observed shapes of the /2 profile (codex-cli 0.166.0 probe, 2026-11-04); the frozen /1 profile admits what it admitted. */
    CodexRetainedRegion(Json t, Map<String, byte[]> originals, String session, String cwd, boolean ordinary) {
        this.t = t; this.originals = originals; this.session = session; this.cwd = cwd; this.ordinary = ordinary;
        turn = string(t, "native_turn_uid"); prompt = new String(originals.get("STDIN"), StandardCharsets.UTF_8);
        require(!prompt.isBlank(), Verdict.REFUSED, "blank prompt");
    }

    void read(byte[] region) {
        for (Json row : new String(region, StandardCharsets.UTF_8).lines().map(NeutralJson::parse).toList()) row(row);
        require(started == 1 && completed == 1 && contexts == 1, Verdict.REFUSED, "turn lifecycle population differs");
        require(prompts == 1, Verdict.REFUSED, "exact original prompt membership missing or ambiguous");
        if (usageRecord == null || lastTotal == null) throw new Rejection(Verdict.INCOMPLETE, "native usage witnesses missing", null);
        same(t.member("turn_token_usage"), usageRecord.member("turn_token_usage"), "turn usage differs from native record");
        same(t.member("thread_token_usage"), usageRecord.member("thread_token_usage"), "thread usage differs from native record");
        same(t.member("total_token_usage"), lastTotal, "total usage differs from native token_count");
        wire(t, originals.get("STDOUT"), session);
    }

    private void row(Json row) {
        Json p = row.member("payload"); require(p instanceof Json.JObject, Verdict.REFUSED, "native payload missing");
        identities(p);
        String type = string(row, "type");
        if ("event_msg".equals(type)) event(p);
        else if ("response_item".equals(type)) user(p);
        else context(type, p);
    }

    private void context(String type, Json p) {
        switch (type) {
            case "session_meta" -> throw new Rejection(Verdict.REFUSED, "session_meta inside a turn region", null);
            case "turn_context" -> { contexts++; expect(p, "turn_id", turn); expect(p, "cwd", cwd); }
            case "token_usage_record" -> { expect(p, "turn_id", turn); usageRecord = p; }
            default -> { }
        }
    }

    private void user(Json p) {
        if ("message".equals(optional(p, "type")) && "user".equals(optional(p, "role")) && prompt(p, prompt)) prompts++;
    }

    private void event(Json p) {
        switch (string(p, "type")) {
            case "task_started" -> { started++; expect(p, "turn_id", turn); }
            case "task_complete" -> { completed++; expect(p, "turn_id", turn); require(p.member("error") == null || p.member("error").isNull(), Verdict.REFUSED, "native completion error"); }
            case "token_count" -> lastTotal = p.path("info", "total_token_usage");
            case "thread_settings_applied" -> require(!ordinary || present(p, "thread_id"), Verdict.REFUSED, "ordinary profile requires thread_id on thread_settings_applied (codex-cli 0.166.0 reports it)");
            case "turn_aborted", "task_failed", "error" -> throw new Rejection(Verdict.REFUSED, "failed native turn", null);
            default -> { }
        }
    }

    private void identities(Json p) {
        for (String key : List.of("session_id", "thread_id")) if (present(p, key)) require(session.equals(string(p, key)), Verdict.REFUSED, "foreign thread in region");
        for (String key : List.of("turn_id", "root_turn_id")) if (present(p, key)) require(turn.equals(string(p, key)), Verdict.REFUSED, "foreign turn in region");
    }

    private static boolean present(Json p, String key) { return p.member(key) != null && !p.member(key).isNull(); }

    private static boolean prompt(Json p, String prompt) {
        List<Json> content = array(p, "content");
        boolean match = content.size() == 1 && "input_text".equals(optional(content.get(0), "type")) && prompt.equals(rawString(content.get(0), "text"));
        boolean context = content.stream().allMatch(b -> "input_text".equals(optional(b, "type")) && (rawString(b, "text").startsWith("<environment_context>") || rawString(b, "text").startsWith("# AGENTS.md instructions for ")));
        require(match || context, Verdict.REFUSED, "unassigned native user history"); return match;
    }

    private static void wire(Json t, byte[] stdout, String session) {
        List<Json> rows = new String(stdout, StandardCharsets.UTF_8).lines().map(NeutralJson::parse).toList();
        require(rows.size() >= 3, Verdict.INCOMPLETE, "stdout lifecycle incomplete");
        int index = 0;
        if ("thread.started".equals(optional(rows.get(0), "type"))) { expect(rows.get(0), "thread_id", session); index = 1; }
        else require(!"stdout".equals(string(t, "identity_source")), Verdict.REFUSED, "identity source claims stdout without thread.started");
        expect(rows.get(index), "type", "turn.started"); expect(rows.get(rows.size() - 1), "type", "turn.completed");
        for (Json row : rows) {
            require(!Set.of("error", "turn.failed").contains(optional(row, "type")), Verdict.REFUSED, "failed stdout turn");
            if (row.member("thread_id") != null) expect(row, "thread_id", session);
        }
        require(rows.stream().anyMatch(r -> "item.completed".equals(optional(r, "type")) && "agent_message".equals(optional(r.member("item"), "type"))), Verdict.REFUSED, "stdout text response absent");
        same(t.member("wire_usage"), rows.get(rows.size() - 1).member("usage"), "declared wire usage differs from stdout");
    }

    static void same(Json expected, Json actual, String reason) {
        require(expected instanceof Json.JObject && actual instanceof Json.JObject, Verdict.INCOMPLETE, reason + ": usage absent");
        for (String key : CodexRetainedGeneration.USAGE) require(number(expected, key) >= 0 && number(expected, key) == number(actual, key), Verdict.REFUSED, reason);
    }
    private static String optional(Json v, String key) { return CodexRetainedGeneration.optional(v, key); }
    private static void require(boolean pass, Verdict verdict, String reason) { CodexRetainedGeneration.require(pass, verdict, reason); }
}
