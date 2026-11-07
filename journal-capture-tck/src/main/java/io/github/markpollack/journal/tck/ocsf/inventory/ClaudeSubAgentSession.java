package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.ClaudeOwnerScope;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Claude Code 2.1.316 sub-agent's own session file, bound to its child core from the native bytes alone. Claude Code
 * saves a sub-agent under {@code <session>/subagents/agent-<agentId>.jsonl}, beside an {@code .meta.json} naming the
 * spawning call ({@code toolUseId}). The binding holds when the meta file names a call the stream requests, the
 * stream's records owned by that call carry the same {@code agent_id}, every record of the session file is that
 * agent's ({@code agentId}) side chain of the run's session, and every record the stream reports for the sub-agent is
 * in the session file (a stale prefix of the file lacks the later ones). The qualifier and the verifier both call
 * {@link #bindings}: the verifier re-derives the binding from the native bytes and compares it with the recorded one,
 * which catches an altered declaration but cannot independently falsify this rule itself.
 */
final class ClaudeSubAgentSession {
    static final String ROLE = "child_transcript";
    private static final Pattern PATH = Pattern.compile("native/([^/]+)/subagents/agent-([A-Za-z0-9]+)\\.jsonl");

    private ClaudeSubAgentSession() { }

    /** The binding of each acquired sub-agent session file, by artifact; a file that does not bind is absent. */
    static Map<String, Json> bindings(Json manifest, Path base) {
        Map<String, Json> out = new LinkedHashMap<>();
        List<Json> sources = manifest.member("sources").elements();
        Json stream = sources.stream().filter(s -> "stream".equals(RawInventory.text(s, "role"))).findFirst().orElse(null);
        String session = RawInventory.text(manifest.member("run"), "session_uid");
        for (Json source : sources) {
            if (stream == null || !ROLE.equals(RawInventory.text(source, "role")) || !"acquired".equals(RawInventory.text(source, "state"))) { continue; }
            Matcher m = PATH.matcher(RawInventory.text(source, "path"));
            if (!m.matches() || !m.group(1).equals(session)) { continue; }
            String metaPath = "native/" + session + "/subagents/agent-" + m.group(2) + ".meta.json";
            Json meta = sources.stream().filter(s -> "sidecar".equals(RawInventory.text(s, "role")) && metaPath.equals(RawInventory.text(s, "path"))
                    && "acquired".equals(RawInventory.text(s, "state"))).findFirst().orElse(null);
            Json binding = meta == null ? null : bind(base, stream, source, meta, m.group(2), session);
            if (binding != null) {
                out.put(RawInventory.text(source, "artifact"), binding);
                out.put(RawInventory.text(meta, "artifact"), binding);
            }
        }
        return out;
    }

    /** The binding of one session file and its meta file, or {@code null} when the native bytes do not establish it. */
    private static Json bind(Path base, Json stream, Json source, Json meta, String agent, String session) {
        Json document = document(base, meta);
        String spawn = document == null ? null : RawInventory.text(document, "toolUseId");
        if (spawn == null) { return null; }
        NativeArtifact streamBytes = read(base, stream);
        NativeArtifact file = read(base, source);
        if (!ClaudeOwnerScope.spawned(streamBytes, spawn) || !streamAgent(streamBytes, spawn, agent) || !sideChain(file, agent, session)
                || !covers(file, streamBytes, spawn)) { return null; }
        Map<String, Json> binding = new LinkedHashMap<>();
        binding.put("spawn_tool_call_uid", new Json.JString(spawn));
        binding.put("agent_id", new Json.JString(agent));
        binding.put("meta_artifact", new Json.JString(RawInventory.text(meta, "artifact")));
        binding.put("basis", new Json.JString("the meta file names the spawning call, which the stream requests; the stream's records it owns carry this agent_id;"
                + " every record of the session file is this agentId's side chain of the run's session"));
        return new Json.JObject(binding);
    }

    /**
     * Whether the sub-agent of {@code binding} had finished when its files were acquired: Claude Code 2.1.316 writes
     * {@code "finished": true} into the meta file when the sub-agent ends. A background sub-agent still running when the
     * parent wrote its result has no such flag, and its session file is a prefix of what it went on to write.
     */
    static boolean finished(Json manifest, Path base, Json binding) {
        String meta = RawInventory.text(binding, "meta_artifact");
        Json source = manifest.member("sources").elements().stream().filter(s -> meta.equals(RawInventory.text(s, "artifact"))).findFirst().orElse(null);
        Json document = source == null ? null : document(base, source);
        return document != null && document.member("finished") instanceof Json.JBool b && b.value();
    }

    /** Whether the stream's records owned by {@code spawn} that state an agent id all state {@code agent}, and one does. */
    private static boolean streamAgent(NativeArtifact stream, String spawn, String agent) {
        int stated = 0;
        for (NativeArtifact.Record r : stream.records()) {
            if (!r.parsed() || !spawn.equals(ClaudeOwnerScope.owner(r.json()))) { continue; }
            String id = RawInventory.text(r.json(), "agent_id");
            if (id != null) {
                if (!agent.equals(id)) { return false; }
                stated++;
            }
        }
        return stated > 0;
    }

    /** Whether every stream record owned by {@code spawn} that states a uuid has a record of that uuid in the session file. */
    private static boolean covers(NativeArtifact file, NativeArtifact stream, String spawn) {
        java.util.Set<String> uuids = new java.util.HashSet<>();
        file.records().forEach(r -> { if (r.parsed() && RawInventory.text(r.json(), "uuid") != null) { uuids.add(RawInventory.text(r.json(), "uuid")); } });
        return stream.records().stream().filter(r -> r.parsed() && spawn.equals(ClaudeOwnerScope.owner(r.json())))
                .map(r -> RawInventory.text(r.json(), "uuid")).filter(java.util.Objects::nonNull).allMatch(uuids::contains);
    }

    /** Whether every record is parsed and states {@code agentId}, and any session identity or side-chain flag it states is this agent's. */
    private static boolean sideChain(NativeArtifact file, String agent, String session) {
        if (file.records().isEmpty()) { return false; }
        for (NativeArtifact.Record r : file.records()) {
            if (!r.parsed() || !agent.equals(RawInventory.text(r.json(), "agentId"))) { return false; }
            Json id = r.json().member("sessionId");
            Json side = r.json().member("isSidechain");
            if ((id != null && !session.equals(id.text())) || (side instanceof Json.JBool b && !b.value())) { return false; }
        }
        return true;
    }

    private static NativeArtifact read(Path base, Json source) {
        return NativeArtifact.read(base.resolve(RawInventory.text(source, "path")));
    }

    private static Json document(Path base, Json source) {
        try {
            return NeutralJson.parse(Files.readString(base.resolve(RawInventory.text(source, "path"))));
        }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
        catch (RuntimeException ex) { return null; }
    }
}
