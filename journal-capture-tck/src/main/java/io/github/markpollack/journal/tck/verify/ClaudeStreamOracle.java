package io.github.markpollack.journal.tck.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The native oracle: one Claude Code {@code stream-json} file read with {@link NeutralJson} and indexed by owner and
 * occurrence. It keeps every line's exact text, the occurrence number Journal is expected to use (see
 * {@link ClaudeStreamContract}), and the per-owner sequences of blocks, tool uses, tool results and permission
 * decisions as lists in source order, never as maps, so duplicates stay visible.
 */
public final class ClaudeStreamOracle {

    /** One line of the stream. {@code owner} is {@code null} on the main thread. */
    public record Line(int lineNumber, int messageNumber, String text, Json json, String type, String subtype,
            String owner, boolean delivered) {
        public String timestamp() {
            return json.path("timestamp") == null ? null : json.path("timestamp").text();
        }
    }

    /** One content block of an assistant line, by position. */
    public record Block(int messageNumber, int blockIndex, String kind, Json block) {
        public String text() {
            return block.path("text") == null ? null : block.path("text").text();
        }

        public String thinking() {
            return block.path("thinking") == null ? null : block.path("thinking").text();
        }

        public String signature() {
            return block.path("signature") == null ? null : block.path("signature").text();
        }

        public String toolUseId() {
            return block.path("id") == null ? null : block.path("id").text();
        }

        public String toolName() {
            return block.path("name") == null ? null : block.path("name").text();
        }

        public Json toolInput() {
            return block.path("input");
        }
    }

    /** One tool use occurrence, with the id and timestamp of the message that carried it. */
    public record ToolUse(int messageNumber, int blockIndex, String id, String name, Json input, String messageId,
            String timestamp) {
    }

    /** One tool result occurrence. */
    public record ToolResult(int messageNumber, String toolUseId, Json content, boolean isError, String timestamp) {
    }

    /** One permission decision reported on a user line's {@code tool_result_meta}. */
    public record PermissionDecision(int messageNumber, String toolUseId, String decision, String source,
            String reasonType) {
    }

    /** Everything one owner (the main thread or one sub-agent) said and received, in source order. */
    public static final class Owner {
        public final String spawnToolUseId;
        public final List<Line> lines = new ArrayList<>();
        public final List<Line> assistantLines = new ArrayList<>();
        public final List<Line> userLines = new ArrayList<>();
        public final List<Block> blocks = new ArrayList<>();
        public final List<ToolUse> toolUses = new ArrayList<>();
        public final List<ToolResult> toolResults = new ArrayList<>();
        public final List<PermissionDecision> permissionDecisions = new ArrayList<>();

        Owner(String spawnToolUseId) {
            this.spawnToolUseId = spawnToolUseId;
        }

        public String label() {
            return spawnToolUseId == null ? "main" : "child:" + spawnToolUseId;
        }
    }

    private final Path file;
    private final String sha256;
    private final List<Line> lines = new ArrayList<>();
    private final Map<Integer, Line> byMessageNumber = new LinkedHashMap<>();
    private final Map<String, Owner> owners = new LinkedHashMap<>();
    private final Map<String, Integer> droppedByType = new LinkedHashMap<>();
    private boolean hasResultLine;

    private ClaudeStreamOracle(Path file) {
        this.file = file;
        this.sha256 = NeutralJson.sha256(file);
        int delivered = 0;
        for (NeutralJson.Line raw : NeutralJson.readLines(file)) {
            Json json = raw.json();
            String type = textOf(json, "type");
            String subtype = textOf(json, "subtype");
            boolean isDelivered = !ClaudeStreamContract.DROPPED_LINE_TYPES.contains(type);
            if (!isDelivered) {
                droppedByType.merge(type, 1, Integer::sum);
            }
            int messageNumber = isDelivered ? ++delivered : -1;
            Json ownerValue = json.path(ClaudeStreamContract.OWNER_FIELD);
            String owner = ownerValue == null || ownerValue.isNull() ? null : ownerValue.text();
            Line line = new Line(raw.number(), messageNumber, raw.text(), json, type, subtype, owner, isDelivered);
            lines.add(line);
            if (isDelivered) {
                byMessageNumber.put(messageNumber, line);
            }
            if ("result".equals(type)) {
                hasResultLine = true;
            }
            if (ClaudeStreamContract.SOURCE_RECORD_TYPES.contains(type)) {
                index(owners.computeIfAbsent(owner == null ? "" : owner, k -> new Owner(owner)), line);
            }
        }
        owners.computeIfAbsent("", k -> new Owner(null));
    }

    public static ClaudeStreamOracle read(Path streamFile) {
        return new ClaudeStreamOracle(streamFile);
    }

    private static void index(Owner owner, Line line) {
        owner.lines.add(line);
        Json message = line.json().path("message");
        Json content = message == null ? null : message.path("content");
        if ("assistant".equals(line.type())) {
            owner.assistantLines.add(line);
            String messageId = message == null ? null : textOf(message, "id");
            int index = 0;
            for (Json block : content == null ? List.<Json>of() : content.elements()) {
                String kind = textOf(block, "type");
                Block b = new Block(line.messageNumber(), index, kind, block);
                owner.blocks.add(b);
                if ("tool_use".equals(kind)) {
                    owner.toolUses.add(new ToolUse(line.messageNumber(), index, b.toolUseId(), b.toolName(),
                            b.toolInput(), messageId, line.timestamp()));
                }
                index++;
            }
        }
        else {
            owner.userLines.add(line);
            for (Json block : content == null ? List.<Json>of() : content.elements()) {
                if ("tool_result".equals(textOf(block, "type"))) {
                    Json isError = block.path("is_error");
                    owner.toolResults.add(new ToolResult(line.messageNumber(), textOf(block, "tool_use_id"),
                            block.path("content"), isError instanceof Json.JBool b && b.value(), line.timestamp()));
                }
            }
            Json meta = line.json().path("tool_result_meta");
            for (Json entry : meta == null ? List.<Json>of() : meta.elements()) {
                Json decision = entry.path("permission_decision");
                if (decision instanceof Json.JObject) {
                    owner.permissionDecisions.add(new PermissionDecision(line.messageNumber(), textOf(entry, "id"),
                            textOf(decision, "decision"), textOf(decision, "source"), textOf(decision, "reason_type")));
                }
            }
        }
    }

    static String textOf(Json json, String key) {
        Json value = json == null ? null : json.path(key);
        return value == null ? null : value.text();
    }

    public Path file() {
        return file;
    }

    public String sha256() {
        return sha256;
    }

    public List<Line> lines() {
        return lines;
    }

    /** The delivered line Journal numbers {@code messageNumber}, or {@code null}. */
    public Line line(int messageNumber) {
        return byMessageNumber.get(messageNumber);
    }

    public Owner main() {
        return owners.get("");
    }

    /** The sub-agent owners, by spawning tool-use id, in order of first appearance. */
    public Map<String, Owner> children() {
        Map<String, Owner> children = new LinkedHashMap<>();
        for (Map.Entry<String, Owner> e : owners.entrySet()) {
            if (!e.getKey().isEmpty()) {
                children.put(e.getKey(), e.getValue());
            }
        }
        return children;
    }

    public List<Owner> owners() {
        return new ArrayList<>(owners.values());
    }

    /** Delivered lines that are neither assistant nor user lines: system, result, and anything else. */
    public List<Line> otherDeliveredLines() {
        List<Line> other = new ArrayList<>();
        for (Line line : lines) {
            if (line.delivered() && !ClaudeStreamContract.SOURCE_RECORD_TYPES.contains(line.type())) {
                other.add(line);
            }
        }
        return other;
    }

    public int deliveredCount() {
        return byMessageNumber.size();
    }

    public Map<String, Integer> droppedByType() {
        return droppedByType;
    }

    public boolean hasResultLine() {
        return hasResultLine;
    }

    /** Every tool-use id of every owner, for ownership checks. */
    public Set<String> toolUseIdsOf(Owner owner) {
        Set<String> ids = new LinkedHashSet<>();
        for (ToolUse use : owner.toolUses) {
            ids.add(use.id());
        }
        return ids;
    }
}
