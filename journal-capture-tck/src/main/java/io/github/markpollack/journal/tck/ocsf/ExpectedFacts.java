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
     * The first observed stage of a tool activity: the model's request (activity 1) or a started invocation (activity 4).
     *
     * @param name the tool name the source reports, or {@code null} when it reports none (a family is not a name)
     * @param namespace the namespace the source reports beside the tool name (an MCP server), or {@code null}
     * @param identityBasisId the kind of identifier {@code id} is (2: stream-local item id), or {@code null} when the profile states none
     * @param activityId 1 when the source reports the model's request, 4 when it reports an invocation as started
     * @param family the source's own family of the activity (a Codex item type), or {@code null} when it reports none
     * @param kindId the kind the profile itself establishes from the pinned interface, or {@code null} to read it from the package's embedded name table
     */
    public record Request(int recordIndex, String id, String name, Json arguments, String responseUid, String reportedState, String namespace, Integer identityBasisId,
            int activityId, String family, Integer kindId) {
        public Request(int recordIndex, String id, String name, Json arguments, String responseUid, String reportedState) {
            this(recordIndex, id, name, arguments, responseUid, reportedState, null, null, 1, null, null);
        }
    }

    /** {@code isError} is the native boolean when reported, else {@code null}; {@code content} is {@code null} when the source reports no result content. */
    public record Result(int recordIndex, String id, Json content, Json isError, Json structured, Decision decision, String reportedState, Integer identityBasisId, String family) {
        public Result(int recordIndex, String id, Json content, Json isError, Json structured, Decision decision, String reportedState) {
            this(recordIndex, id, content, isError, structured, decision, reportedState, null, null);
        }
    }

    /**
     * {@code blocks} are the native block values in order, or {@code null} when the source carries no content;
     * {@code reasoningForm} is the reasoning_form_id every block must carry, or {@code null} when the profile states none.
     * When {@code itemIdentityBasisId} is set, {@code id} is the source's own content-item id ({@code item_uid} of that
     * basis), the model response is not identified (no {@code response_uid}) and the activity is 3 Item completed, unless
     * the profile states another: {@code statedActivity} 1 Partial for a non-final view of such an item (an Antigravity
     * agent-response step still ACTIVE, with one text fragment). {@code textUpdate} is the text_update_id every block must
     * carry (1 Increment for an Antigravity text fragment), or {@code null} when the profile states none, and then no block
     * may carry one.
     */
    public record Response(int recordIndex, String id, List<Json> blocks, String stopReason, boolean terminal, String model, Integer reasoningForm, Integer itemIdentityBasisId,
            Integer statedActivity, Integer textUpdate) {
        public Response(int recordIndex, String id, List<Json> blocks, String stopReason, boolean terminal, String model) {
            this(recordIndex, id, blocks, stopReason, terminal, model, null, null, null, null);
        }

        public Response(int recordIndex, String id, List<Json> blocks, String stopReason, boolean terminal, String model, Integer reasoningForm, Integer itemIdentityBasisId) {
            this(recordIndex, id, blocks, stopReason, terminal, model, reasoningForm, itemIdentityBasisId, null, null);
        }

        /** The activity the event must carry. */
        public int activityId() {
            return statedActivity != null ? statedActivity : itemIdentityBasisId != null ? 3 : terminal ? 2 : 1;
        }
    }

    public record Prompt(int recordIndex, String turnUid, List<String> texts) {
    }

    /** One source-reported part of the cache writes: the tier's native name, its exact count and the pointer of its member in the usage object. */
    public record Tier(String tier, Json count, String pointer) {
    }

    /**
     * The canonical tranches of one native usage object as the profile reads them from the pinned definitions: each
     * component's exact value (absent when not established), how each named component is established (1 reported,
     * 2 derived, 3 unavailable) with the native pointers it is read from, the cache-write tiers, and whether reasoning is
     * part of output ({@code null} when not established).
     *
     * @param statuses for each component, what the source establishes about its quantity (1 Measured, 2 Possibly default,
     *        3 Conditional, 4 Lower bound), only where a declared rule establishes it; absent otherwise
     * @param conditionals for an unavailable component with status 3, the declared formula's result, which is never the
     *        canonical value
     */
    public record Tranches(Map<String, Json> values, Map<String, Integer> bases, Map<String, List<String>> pointers, List<Tier> tiers, Integer reasoningRelationId,
            Map<String, Integer> statuses, Map<String, Json> conditionals) {
    }

    /**
     * @param eventRecordIndex the record whose event carries the observation
     * @param recordIndex the record the observation cites
     * @param components normalized component name to exact native number
     * @param aggregationId 1 scope total, 2 cumulative snapshot, or {@code null} when the profile establishes none
     * @param tranches the component bases, tiers and reasoning relation, or {@code null} when the profile states none
     * @param scopeName at scope 99 Other, the source-defined unit the scope caption must name (for example Conversation step)
     */
    public record Usage(int eventRecordIndex, int recordIndex, int scopeId, int statusId, Map<String, Json> components, String model, String provider, Integer measurementStatusId,
            Integer aggregationId, Tranches tranches, String scopeName) {
        /** A usage whose record reports no provider (the provider must then be absent on the observation, never inferred). */
        public Usage(int eventRecordIndex, int recordIndex, int scopeId, int statusId, Map<String, Json> components, String model) {
            this(eventRecordIndex, recordIndex, scopeId, statusId, components, model, null, null, null, null, null);
        }

        public Usage(int eventRecordIndex, int recordIndex, int scopeId, int statusId, Map<String, Json> components, String model, String provider) {
            this(eventRecordIndex, recordIndex, scopeId, statusId, components, model, provider, null, null, null, null);
        }

        /** A usage stated in canonical tranches. */
        public Usage(int eventRecordIndex, int recordIndex, int scopeId, int statusId, Tranches tranches, String model, String provider, Integer measurementStatusId, Integer aggregationId) {
            this(eventRecordIndex, recordIndex, scopeId, statusId, tranches.values(), model, provider, measurementStatusId, aggregationId, tranches, null);
        }

        /** A usage in canonical tranches at scope 99 Other, whose caption names the source-defined unit. */
        public Usage(int eventRecordIndex, int recordIndex, String scopeName, int statusId, Tranches tranches, Integer aggregationId) {
            this(eventRecordIndex, recordIndex, 99, statusId, tranches.values(), null, null, null, aggregationId, tranches, scopeName);
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

    /**
     * @param texts the turn's response text blocks the record reports, in order (an Antigravity result's response), or
     *        {@code null} when it reports none: the event must then carry no content blocks
     */
    public record TurnCompleted(int recordIndex, int statusId, String stopReason, List<String> texts) {
        public TurnCompleted(int recordIndex, int statusId, String stopReason) {
            this(recordIndex, statusId, stopReason, null);
        }
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

    /**
     * These facts without those of the given records: records that arrived under a non-normalizing compatibility
     * selection are retained whole and never normalized, so no fact of theirs is owed or credited, and their events
     * carry the fallback time. Ownership and admission are unchanged.
     */
    public ExpectedFacts without(Set<Integer> records) {
        ExpectedFacts f = new ExpectedFacts(artifact);
        requests.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.requests::add);
        results.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.results::add);
        responses.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.responses::add);
        prompts.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.prompts::add);
        usages.stream().filter(x -> !records.contains(x.eventRecordIndex())).forEach(f.usages::add);
        costs.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.costs::add);
        decisions.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.decisions::add);
        operations.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.operations::add);
        completed.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.completed::add);
        started.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.started::add);
        contexts.stream().filter(x -> !records.contains(x.recordIndex())).forEach(f.contexts::add);
        f.owned.addAll(owned);
        normalized.stream().filter(i -> !records.contains(i)).forEach(f.normalized::add);
        f.excluded.putAll(excluded);
        sessionOf.forEach((i, v) -> {
            if (!records.contains(i)) {
                f.sessionOf.put(i, v);
            }
        });
        nativeUid.forEach((i, v) -> {
            if (!records.contains(i)) {
                f.nativeUid.put(i, v);
            }
        });
        nativeTime.forEach((i, v) -> {
            if (!records.contains(i)) {
                f.nativeTime.put(i, v);
            }
        });
        return f;
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
