package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one native artifact says, as a {@link SourceProfile} reads it directly from the native records: the expected
 * population of each fact family, each with its record occurrence and exact values. The oracle compares the saved
 * package against these; they are never produced from the mapping contract executor or a production parser.
 *
 * <p>Lists keep source order and duplicates. {@code null} values mean "not reported" and are distinct from JSON
 * {@code null}, which arrives as {@link Json.JNull}.
 */
public final class ExpectedFacts {

    /**
     * @param namespace the namespace the source reports beside the tool name (an MCP server), or {@code null}
     * @param identityBasisId the kind of identifier {@code id} is (2: stream-local item id), or {@code null} when the profile states none
     */
    public record Request(int recordIndex, String id, String name, Json arguments, String responseUid, String reportedState, String namespace, Integer identityBasisId) {
        public Request(int recordIndex, String id, String name, Json arguments, String responseUid, String reportedState) {
            this(recordIndex, id, name, arguments, responseUid, reportedState, null, null);
        }
    }

    /** {@code isError} is the native boolean when reported, else {@code null}; {@code content} is {@code null} when the source reports no result content. */
    public record Result(int recordIndex, String id, Json content, Json isError, Json structured, Decision decision, String reportedState, Integer identityBasisId) {
        public Result(int recordIndex, String id, Json content, Json isError, Json structured, Decision decision, String reportedState) {
            this(recordIndex, id, content, isError, structured, decision, reportedState, null);
        }
    }

    /**
     * {@code blocks} are the native block values in order, or {@code null} when the source carries no content;
     * {@code reasoningForm} is the reasoning_form_id every block must carry, or {@code null} when the profile states none.
     */
    public record Response(int recordIndex, String id, List<Json> blocks, String stopReason, boolean terminal, String model, Integer reasoningForm) {
        public Response(int recordIndex, String id, List<Json> blocks, String stopReason, boolean terminal, String model) {
            this(recordIndex, id, blocks, stopReason, terminal, model, null);
        }
    }

    public record Prompt(int recordIndex, String turnUid, List<String> texts) {
    }

    /**
     * @param eventRecordIndex the record whose event carries the observation
     * @param recordIndex the record the observation cites
     * @param components normalized component name to exact native number
     */
    public record Usage(int eventRecordIndex, int recordIndex, int scopeId, int statusId, Map<String, Json> components, String model, String provider, Integer measurementStatusId) {
        /** A usage whose record reports no provider (the provider must then be absent on the observation, never inferred). */
        public Usage(int eventRecordIndex, int recordIndex, int scopeId, int statusId, Map<String, Json> components, String model) {
            this(eventRecordIndex, recordIndex, scopeId, statusId, components, model, null, null);
        }

        public Usage(int eventRecordIndex, int recordIndex, int scopeId, int statusId, Map<String, Json> components, String model, String provider) {
            this(eventRecordIndex, recordIndex, scopeId, statusId, components, model, provider, null);
        }
    }

    /** @param basis the vendor's stated cost basis when the record reports one (single-model {@code costBasis}), else {@code null} */
    public record Cost(int recordIndex, Json amount, String currency, int scopeId, String basis) {
        public Cost(int recordIndex, Json amount, String currency, int scopeId) {
            this(recordIndex, amount, currency, scopeId, null);
        }
    }

    /** {@code ownerId} is the tool-call id the record itself names, when the decision is at tool-call scope. */
    public record Decision(int recordIndex, String reportedDecision, int decisionId, String source, String reasonType,
            int scopeId, int outcomeId, int methodId, List<String> candidates, String ownerId) {
    }

    /**
     * @param commandLine the command string the source reports, or {@code null}
     * @param identityBasisId the kind of identifier {@code uid} is, or {@code null} when the profile states none
     */
    public record Operation(int recordIndex, String uid, Json argv, Json exitCode, String reportedState, int statusId,
            int outcomeId, int methodId, List<String> candidates, String commandLine, Integer identityBasisId) {
        public Operation(int recordIndex, String uid, Json argv, Json exitCode, String reportedState, int statusId, int outcomeId, int methodId, List<String> candidates) {
            this(recordIndex, uid, argv, exitCode, reportedState, statusId, outcomeId, methodId, candidates, null, null);
        }
    }

    public record TurnCompleted(int recordIndex, int statusId, String stopReason) {
    }

    public record TurnStarted(int recordIndex, String turnUid) {
    }

    /**
     * One rendered content block of an injected-context record, read from the native record: the block type the
     * contract promises, the exact text the source renders ({@code null} when the native element carries no text)
     * and the native element itself, which the event must retain as {@code data}.
     */
    public record Block(String blockType, String text, Json data) {
    }

    /**
     * A settings / injected-context / accounting record (session_context). {@code settings} holds the exact native
     * values the record reports under their normalized names (absent when not reported); {@code blocks} are the
     * rendered content blocks in native order, or {@code null} when the source renders nothing.
     */
    public record Context(int recordIndex, int activityId, String contextKind, String turnUid, Map<String, Json> settings, String renderedRole, List<Block> blocks) {
    }

    public final String artifact;
    public final List<Request> requests = new ArrayList<>();
    public final List<Result> results = new ArrayList<>();
    public final List<Response> responses = new ArrayList<>();
    public final List<Prompt> prompts = new ArrayList<>();
    public final List<Usage> usages = new ArrayList<>();
    public final List<Cost> costs = new ArrayList<>();
    public final List<Decision> decisions = new ArrayList<>();
    public final List<Operation> operations = new ArrayList<>();
    public final List<TurnCompleted> completed = new ArrayList<>();
    public final List<TurnStarted> started = new ArrayList<>();
    public final List<Context> contexts = new ArrayList<>();
    /** Records this core owns and must retain (owner-scoped for a split core; admission-scoped for a bounded source). */
    public final Set<Integer> owned = new LinkedHashSet<>();
    /** The native session id each parsed record reports (or inherits, for a stream that states it once), by record index; empty when the profile cannot read one. */
    public final Map<Integer, String> sessionOf = new LinkedHashMap<>();
    /** The native record identifier each parsed record carries, by record index; empty when the source has none. */
    public final Map<Integer, String> nativeUid = new LinkedHashMap<>();
    /** Owned records that a normalization rule covers; every other owned record is expected as a whole-record fallback. */
    public final Set<Integer> normalized = new LinkedHashSet<>();
    /** Records excluded by a declared admission, by record index, with the declared family name. */
    public final Map<Integer, String> excluded = new LinkedHashMap<>();
    /** Records whose timestamp text the event must carry verbatim as {@code metadata.original_time}. */
    public final Map<Integer, String> nativeTime = new LinkedHashMap<>();

    public ExpectedFacts(String artifact) {
        this.artifact = artifact;
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("owned", owned.size());
        m.put("normalized", normalized.size());
        m.put("excluded", excluded.size());
        m.put("requests", requests.size());
        m.put("results", results.size());
        m.put("responses", responses.size());
        m.put("prompts", prompts.size());
        m.put("usage", usages.size());
        m.put("cost", costs.size());
        m.put("decisions", decisions.size());
        m.put("operations", operations.size());
        m.put("turn_completed", completed.size());
        m.put("turn_started", started.size());
        m.put("contexts", contexts.size());
        return m;
    }
}
