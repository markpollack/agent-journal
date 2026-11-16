package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import static io.github.markpollack.journal.tck.ocsf.JunieAcquisitionReading.number;
import static io.github.markpollack.journal.tck.ocsf.JunieAcquisitionReading.require;

/**
 * The native records of one Junie ACP one-shot session, read with the TCK's neutral tokenizer: the summary's session
 * identity, project directory, lifecycle and reported usage, and the session events' framing, task and final result.
 * Usage is taken as reported: a cost of zero is an observation, and a token count the entry does not state stays
 * absent; only a count that is not a non-negative integer, or a cost that is not a non-negative number, is refused. Session
 * events that name a credential-shaped environment variable are refused.
 */
final class JunieSessionReading {
    private static final List<String> CREDENTIAL_WORDS = List.of("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH", "COOKIE", "SESSION_ID");

    private JunieSessionReading() { }

    static void summary(byte[] bytes, JunieAcquisitionReading.Identity identity) {
        Json summary = object(new String(bytes, StandardCharsets.UTF_8), "summary.json is not one JSON object");
        String session = text(summary, "sessionId");
        require(identity.session().equals(session), "identity mismatch: summary.json sessionId " + session + " differs from acp_session_id " + identity.session());
        String project = text(summary, "projectDir");
        require(identity.workingDirectory().equals(project), "identity mismatch: summary.json projectDir " + project + " differs from the working directory " + identity.workingDirectory());
        String lifecycle = text(summary, "lifecycle");
        require("COMPLETED".equals(lifecycle), "incomplete events: summary.json lifecycle is " + lifecycle + ", not COMPLETED");
        usage(summary.path("mainAgent", "modelUsage"));
    }

    static void events(JunieAcquisitionReading.Source session, JunieAcquisitionReading.Identity identity) {
        byte[] bytes = session.bytes();
        require(bytes.length > 0 && bytes[bytes.length - 1] == '\n', "incomplete events: the last line of events.jsonl is not newline-terminated");
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        List<Json> records = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            records.add(object(lines[i], "incomplete events: line " + (i + 1) + " of events.jsonl is not one JSON object"));
        }
        long declared = number(session.declaration(), "records");
        require(records.size() == declared, "incomplete events: events.jsonl holds " + records.size() + " records, declared " + declared);
        environment(records);
        List<Json> tasks = records.stream().filter(r -> "TaskStartedEvent".equals(text(r, "kind"))).toList();
        require(tasks.size() == 1, "incomplete events: " + tasks.size() + " TaskStartedEvent records, not exactly one");
        require(identity.task().equals(text(tasks.get(0), "taskId")), "incomplete events: the TaskStartedEvent taskId differs from native_task_uid");
        require(records.stream().anyMatch(JunieSessionReading::finalResult), "incomplete events: no main-agent ResultBlockUpdatedEvent with cancelled: false");
    }

    /**
     * Refuses an {@code EnvironmentVariablesUpdatedEvent} that names a credential-shaped variable, naming the variables and
     * never their values (Junie 3813.7 stores each value as ciphertext under a locally held key, so it stays recoverable).
     */
    private static void environment(List<Json> records) {
        Set<String> named = new TreeSet<>();
        for (Json record : records) {
            Json event = record.path("event", "agentEvent");
            if (event == null || !"EnvironmentVariablesUpdatedEvent".equals(text(event, "kind"))) {
                continue;
            }
            require(event.member("env") instanceof Json.JArray, "credential-shaped environment: an EnvironmentVariablesUpdatedEvent env is not a list");
            for (Json entry : event.member("env").elements()) {
                String name = text(entry, "key");
                require(name != null, "credential-shaped environment: an EnvironmentVariablesUpdatedEvent entry names no variable");
                if (credentialShaped(name)) {
                    named.add(name);
                }
            }
        }
        require(named.isEmpty(), "credential-shaped environment: events.jsonl names the variables " + named + " (values not shown)");
    }

    /** Agent Client's rule: upper-cased, the name contains a credential word; SSH_AUTH_SOCK and XAUTHORITY exactly are not credentials. */
    static boolean credentialShaped(String name) {
        if ("SSH_AUTH_SOCK".equals(name) || "XAUTHORITY".equals(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return CREDENTIAL_WORDS.stream().anyMatch(upper::contains);
    }

    /** A main agent's result block that was not cancelled. */
    private static boolean finalResult(Json record) {
        Json event = record.path("event", "agentEvent");
        return event != null && "ResultBlockUpdatedEvent".equals(text(event, "kind")) && "MainAgent".equals(text(event.member("agent"), "kind"))
                && event.member("cancelled") instanceof Json.JBool cancelled && !cancelled.value();
    }

    private static void usage(Json entries) {
        if (entries == null) {
            return;
        }
        require(entries instanceof Json.JArray, "usage: mainAgent.modelUsage is not an array");
        for (Json entry : entries.elements()) {
            require(entry instanceof Json.JObject, "usage: a mainAgent.modelUsage entry is not an object");
            String model = text(entry, "model");
            require(model != null && !model.isBlank(), "usage: a mainAgent.modelUsage entry names no model");
            for (Map.Entry<String, Json> member : ((Json.JObject) entry).members().entrySet()) {
                quantity(model, member.getKey(), member.getValue());
            }
        }
    }

    /** A member ending in {@code Tokens} is a count, {@code cost} a quantity; members the entry does not state are never added. */
    private static void quantity(String model, String name, Json value) {
        if (name.endsWith("Tokens")) {
            require(value instanceof Json.JNumber n && n.value().signum() >= 0 && n.value().stripTrailingZeros().scale() <= 0,
                    "usage: " + model + " " + name + " is not a non-negative integer");
        }
        else if ("cost".equals(name)) {
            require(value instanceof Json.JNumber n && n.value().compareTo(BigDecimal.ZERO) >= 0, "usage: " + model + " cost is not a non-negative number");
        }
    }

    private static Json object(String text, String reason) {
        try {
            Json value = NeutralJson.parse(text);
            require(value instanceof Json.JObject, reason);
            return value;
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(reason, e);
        }
    }

    private static String text(Json value, String key) {
        Json member = value == null ? null : value.member(key);
        return member == null ? null : member.text();
    }
}
