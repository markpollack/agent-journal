package io.github.markpollack.journal.ocsf.writer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Child-run usage that Claude Code reports in the parent's records (contract {@code claude-code-2.1.303-stream}
 * 0.5.0-prototype): the Agent tool's result ({@code tool_use_result.usage} of a record whose one tool_result block
 * answers an Agent request) and the {@code system/task_progress} and {@code system/task_notification} records
 * ({@code usage.total_tokens} of the task their {@code tool_use_id} spawned). Each becomes a session_context Accounting
 * reported event in the record's own core, whose {@code tool_call_uid} names the spawning call and whose one usage
 * observation is at scope 7 Child run. No aggregation is stated (the recordings do not establish either figure as the
 * child run's total) and nothing is added to a parent or run total. The only state is the set of Agent requests seen.
 */
final class ClaudeChildUsage {

    static final String AGENT_TOOL = "Agent";
    static final String AGENT_RULE = "claude.stream.user.tool_result.agent_usage->session_context.accounting";
    static final String TASK_RULE = "claude.stream.system.task_usage->session_context.accounting";
    static final String AGENT_BASIS = "claude.stream.agent_result_usage.v1: the Agent tool's result reports an Anthropic usage object for the child run its call spawned, "
            + "once, at the call's end (Final); it is not established as the child run's total (in the 2.1.304 recording its input tranches equal the child's last response), "
            + "so no aggregation is stated and it is never summed with the parent's, the run's or the child's other observations";
    static final String TASK_BASIS = "claude.stream.task_usage.v1: task_progress reports the spawned task's token figure so far (Provisional), task_notification the figure "
            + "at the task's end (Final), for the child run of the call tool_use_id names; the recordings do not establish it as a sum of the child's calls, so no aggregation "
            + "is stated, and progress and terminal figures are never summed with each other or with any parent total";

    /** Tool-use ids of Agent requests seen so far in this artifact. */
    private final Set<String> agentCalls = new HashSet<>();

    /** Notes an Agent request ({@code tool_use} block). */
    void request(Jv block) {
        if (AGENT_TOOL.equals(text(block, "name")) && text(block, "id") != null) {
            agentCalls.add(text(block, "id"));
        }
    }

    /**
     * The child-run accounting of an Agent result, or {@code null}: the record must carry exactly one tool_result block
     * (with several, the result's owner block is not established) answering an Agent request, and tool_use_result must
     * report a usage object. Its data is tool_use_result, the native member the observation comes from.
     */
    Jv.Obj agentResult(MapperSupport.ClaudeMessage msg, Jv record, List<String> resultIds) {
        Jv result = record.member(ClaudeStreamMapper.ROUTE.structuredMember());
        if (resultIds.size() != 1 || !agentCalls.contains(resultIds.get(0)) || result == null || !(result.member("usage") instanceof Jv.Obj usage)) {
            return null;
        }
        Jv.Obj observation = MapperSupport.usage(7, 2, AGENT_BASIS, msg.ctx().sourceRef(msg.r(), msg.uid(), msg.ts(), false), null, usage);
        UsageTranches.anthropic(observation, usage);
        Jv.Obj ev = msg.ctx().event("session_context", 3, msg.r(), msg.t(), ClaudeStreamMapper.CONTRACT, AGENT_RULE, msg.ts(), msg.uid(), false, null);
        return ev.put("session_uid", msg.sess()).put("tool_call_uid", resultIds.get(0)).put("context_kind", "user/tool_result")
                .put("usage_observations", new Jv.Arr(List.of(observation))).put("basis", AGENT_BASIS).put("data", result);
    }

    /**
     * The child-run accounting of a task_progress or task_notification record that reports a usage object, or
     * {@code null}. The observation keeps the usage object as data; only total_tokens is a canonical component.
     */
    static Jv.Obj task(SourceContext ctx, NativeRecord r, String family, Jv record) {
        if (!(record.member("usage") instanceof Jv.Obj usage)) {
            return null;
        }
        String ts = text(record, "timestamp");
        String uid = text(record, "uuid");
        MapperSupport.Time t = ctx.timeFor(r, ts);
        int status = "system/task_notification".equals(family) ? 2 : 1;
        Jv.Obj observation = MapperSupport.usage(7, status, TASK_BASIS, ctx.sourceRef(r, uid, ts, false), null, usage);
        UsageTranches.reportedTotal(observation, usage);
        Jv.Obj ev = ctx.event("session_context", 3, r, t, ClaudeStreamMapper.CONTRACT, TASK_RULE, ts, uid, true, null);
        return ev.put("session_uid", text(record, "session_id")).put("tool_call_uid", text(record, "tool_use_id")).put("context_kind", family)
                .put("usage_observations", new Jv.Arr(List.of(observation))).put("basis", TASK_BASIS).put("data", record);
    }
}
