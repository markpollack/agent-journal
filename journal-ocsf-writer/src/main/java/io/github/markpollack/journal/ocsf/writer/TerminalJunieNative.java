package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static io.github.markpollack.journal.ocsf.writer.TerminalJunieProfile.require;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;

/**
 * Original Junie session-directory witness checks before terminal assembly; no dependency on the TCK. The summary
 * names the ACP session, the project directory and a completed lifecycle; the session events are whole lines, hold
 * the declared record count, one task start of the declared task and the main agent's uncancelled result. Usage is
 * read as reported: only a count that is not a non-negative integer or a cost that is not a non-negative number is refused.
 */
final class TerminalJunieNative {
    private TerminalJunieNative() { }

    static void validate(Path base, Jv m, Map<String, Jv> roles) throws IOException {
        Jv p = m.member("process");
        Jv summary = Jv.parse(new String(bytes(base, roles.get("summary")), StandardCharsets.UTF_8));
        equal(summary, "sessionId", text(p, "acp_session_id"));
        equal(summary, "projectDir", text(p, "cwd"));
        equal(summary, "lifecycle", "COMPLETED");
        Jv usage = summary.path("mainAgent", "modelUsage");
        for (Jv entry : usage == null ? List.<Jv>of() : array(summary.member("mainAgent"), "modelUsage")) usage(entry);
        List<Jv> events = lines(bytes(base, roles.get("session")), number(roles.get("session"), "records"));
        List<Jv> tasks = events.stream().filter(e -> "TaskStartedEvent".equals(optional(e, "kind"))).toList();
        require(tasks.size() == 1, "Junie session events need exactly one task start");
        equal(tasks.get(0), "taskId", text(array(m, "turns").get(0), "native_task_uid"));
        require(events.stream().anyMatch(TerminalJunieNative::result), "Junie main agent result absent or cancelled");
    }

    private static boolean result(Jv e) {
        Jv event = e.path("event", "agentEvent");
        return event != null && "ResultBlockUpdatedEvent".equals(optional(event, "kind")) && "MainAgent".equals(optional(event.member("agent"), "kind"))
                && event.member("cancelled") instanceof Jv.Bool cancelled && !cancelled.value();
    }

    private static void usage(Jv entry) {
        text(entry, "model");
        for (Map.Entry<String, Jv> member : ((Jv.Obj) entry).members().entrySet()) {
            Jv value = member.getValue();
            if (member.getKey().endsWith("Tokens")) require(value instanceof Jv.Num n && n.integral() && n.value().signum() >= 0, "Junie token count is not a non-negative integer");
            if ("cost".equals(member.getKey())) require(value instanceof Jv.Num n && n.value().compareTo(BigDecimal.ZERO) >= 0, "Junie cost is not a non-negative number");
        }
    }

    private static byte[] bytes(Path base, Jv source) throws IOException {
        byte[] bytes = Files.readAllBytes(base.resolve(relative(text(source, "path"))));
        require(bytes.length == number(source, "bytes") && SchemaPin.sha256(bytes).equals(text(source, "sha256")), "Junie original changed");
        return bytes;
    }

    private static List<Jv> lines(byte[] bytes, long records) {
        require(bytes.length > 0 && bytes[bytes.length - 1] == '\n', "Junie session event line incomplete");
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        require(lines.length - 1 == records, "Junie session record extent differs");
        List<Jv> rows = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            Jv row = Jv.parse(lines[i]);
            require(row instanceof Jv.Obj, "Junie session record is not an object");
            rows.add(row);
        }
        return rows;
    }

    private static String optional(Jv value, String key) {
        Jv member = value == null ? null : value.member(key);
        return member == null ? null : member.text();
    }
}
