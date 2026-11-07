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
 * stream's records owned by that call carry the same {@code agent_id}, and every record of the session file is that
 * agent's ({@code agentId}) side chain of the run's session.
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
        if (!ClaudeOwnerScope.spawned(streamBytes, spawn) || !streamAgent(streamBytes, spawn, agent) || !sideChain(read(base, source), agent, session)) { return null; }
        Map<String, Json> binding = new LinkedHashMap<>();
        binding.put("spawn_tool_call_uid", new Json.JString(spawn));
        binding.put("agent_id", new Json.JString(agent));
        binding.put("meta_artifact", new Json.JString(RawInventory.text(meta, "artifact")));
        binding.put("basis", new Json.JString("the meta file names the spawning call, which the stream requests; the stream's records it owns carry this agent_id;"
                + " every record of the session file is this agentId's side chain of the run's session"));
        return new Json.JObject(binding);
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
