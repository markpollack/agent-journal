package io.github.markpollack.journal.ocsf.writer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Shared, stateless mapping helpers: the OCSF envelope, source references, content blocks, usage components, time
 * and status captions. Everything a mapper writes passes through here so the conventions (captions, siblings,
 * required fields) are in one place.
 */
final class MapperSupport {

    static final Map<Integer, String> TOOL_KIND = Map.ofEntries(Map.entry(0, "Unknown"), Map.entry(1, "read"), Map.entry(2, "edit"),
            Map.entry(3, "delete"), Map.entry(4, "move"), Map.entry(5, "search"), Map.entry(6, "execute"), Map.entry(7, "think"),
            Map.entry(8, "fetch"), Map.entry(9, "switch_mode"), Map.entry(99, "other"));
    static final Map<Integer, String> BLOCK_KIND = Map.of(0, "Unknown", 1, "Text", 2, "Thought", 3, "Tool call", 4, "Tool result", 5, "Image", 99, "Other");
    static final Map<Integer, String> SCOPE = Map.of(0, "Unknown", 1, "Tool call", 2, "Model response", 3, "Prompt turn", 4, "Run", 5, "Session", 6, "Model", 7, "Child run");
    static final Map<Integer, String> USAGE_STATUS = Map.of(0, "Unknown", 1, "Provisional", 2, "Final");
    static final Map<Integer, String> OUTCOME = Map.of(0, "Unknown", 1, "Unique", 2, "Ambiguous", 3, "Unattributed");
    static final Map<Integer, String> METHOD = Map.of(0, "Unknown", 1, "Reported identifier", 2, "Derived by rule");
    static final Map<Integer, String> DECISION = Map.of(0, "Unknown", 1, "Allowed", 2, "Denied", 99, "Other");
    static final Map<Integer, String> STATUS = Map.of(0, "Unknown", 1, "Success", 2, "Failure", 99, "Other");
    static final Map<Integer, String> STATUS_BASIS = Map.of(0, "Unknown", 1, "Reported", 2, "Derived", 3, "Not derivable");
    static final Map<Integer, String> IDENTITY_BASIS = Map.of(0, "Unknown", 1, "Model-facing call identifier", 2, "Stream-local item identifier", 99, "Other");
    static final Map<Integer, String> MEASUREMENT_STATUS = Map.of(0, "Unknown", 1, "Measured", 2, "Possibly default");
    static final Map<Integer, String> REASONING_FORM = Map.of(0, "Unknown", 1, "Full reasoning", 2, "Summary", 3, "Redacted", 99, "Other");
    static final Map<Integer, String> TIME_BASIS = Map.of(1, "Source record", 2, "Earliest available fallback", 3, "Capture observation");
    static final Map<Integer, String> PROVIDER_BASIS = Map.of(0, "Unknown", 1, "Reported", 2, "Derived", 3, "Unavailable");

    private static final DateTimeFormatter RFC3339_MS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private MapperSupport() {
    }

    /** A status triple for the envelope. */
    record Status(int id, int basisId, String derivation, String label) {
        /** A status whose caption is the schema's; {@code label} is the source's own word for status 99 Other. */
        Status(int id, int basisId, String derivation) {
            this(id, basisId, derivation, null);
        }
    }

    /** A time decision for the envelope. */
    record Time(long ms, int basisId, String detail) {
    }

    static long rfc3339ToMs(String text) {
        return OffsetDateTime.parse(text).toInstant().toEpochMilli();
    }

    static String msToRfc3339(long ms) {
        return RFC3339_MS.format(Instant.ofEpochMilli(ms));
    }

    static String text(Jv v, String... path) {
        Jv x = v == null ? null : v.path(path);
        return x == null || x.isNull() ? null : x.text();
    }

    static boolean isIntegral(Jv v) {
        return v instanceof Jv.Num n && n.integral();
    }

    static Jv.Obj modelRef(String name, int providerBasis, String providerKind) {
        Jv.Obj m = new Jv.Obj().put("name", name);
        if (providerKind != null) {
            m.put("provider_kind", providerKind);
        }
        return m.put("provider_basis_id", providerBasis).put("provider_basis", PROVIDER_BASIS.get(providerBasis));
    }

    static Jv.Obj usage(int scope, int status, String basis, Jv sourceRef, Jv.Obj modelRef, Jv data) {
        Jv.Obj u = new Jv.Obj().put("scope_id", scope).put("scope", SCOPE.get(scope))
                .put("usage_status_id", status).put("usage_status", USAGE_STATUS.get(status)).put("basis", basis).put("source_ref", sourceRef);
        if (modelRef != null) {
            u.put("model_ref", modelRef);
        }
        return u.put("data", data == null ? Jv.Null.INSTANCE : data);
    }

    static Jv.Obj attribution(int outcome, int method, List<String> candidates, String basis, String scopeUid) {
        Jv.Obj a = new Jv.Obj().put("outcome_id", outcome).put("outcome", OUTCOME.get(outcome));
        Jv.Arr c = new Jv.Arr();
        for (String s : candidates) {
            c.add(new Jv.Str(s));
        }
        a.put("candidates", c).put("basis", basis);
        if (scopeUid != null) {
            a.put("scope_uid", scopeUid);
        }
        return a.put("method_id", method).put("method", METHOD.get(method));
    }

    static int decisionId(String reported) {
        if (reported == null) {
            return 99;
        }
        return switch (reported) {
            case "accept", "allow" -> 1;
            case "deny", "denied", "reject", "rejected" -> 2;
            default -> 99;
        };
    }

    /** Content blocks of a message: a bare string is one Text block; each array element keeps its native value in data. */
    static Jv.Arr blocks(MappingContracts contracts, String contract, Jv content) {
        Jv.Arr out = new Jv.Arr();
        if (content instanceof Jv.Str s) {
            out.add(new Jv.Obj().put("block_index", 0).put("block_type", "string").put("block_kind_id", 1).put("block_kind", "Text")
                    .put("basis", "message.content is a bare string").put("text", s.value()).put("data", s));
            return out;
        }
        int i = 0;
        for (Jv b : content == null ? List.<Jv>of() : content.elements()) {
            String type = text(b, "type");
            int k = contracts.blockKind(contract, type);
            Jv.Obj cb = new Jv.Obj().put("block_index", i++).put("block_type", type == null ? "(untyped)" : type)
                    .put("block_kind_id", k).put("block_kind", BLOCK_KIND.get(k)).put("basis", contracts.blockKindRule(contract));
            if (b.member("text") instanceof Jv.Str t) {
                cb.put("text", t.value());
            }
            if (b.member("thinking") instanceof Jv.Str t) {
                cb.put("text", t.value());
            }
            if ("tool_use".equals(type)) {
                cb.put("tool_call_uid", text(b, "id"));
            }
            if ("tool_result".equals(type)) {
                cb.put("tool_call_uid", text(b, "tool_use_id"));
            }
            out.add(cb.put("data", b));
        }
        return out;
    }

    /** Blocks from Claude's attachment {@code rendered} array ({@code [{content}]}): each element is one Text block. */
    static Jv.Arr renderedBlocks(Jv rendered) {
        Jv.Arr out = new Jv.Arr();
        int i = 0;
        for (Jv r : rendered == null ? List.<Jv>of() : rendered.elements()) {
            Jv.Obj cb = new Jv.Obj().put("block_index", i++).put("block_type", "rendered").put("block_kind_id", 1).put("block_kind", "Text")
                    .put("basis", "attachment.rendered[] element; content is the text rendered to the model");
            if (r.member("content") instanceof Jv.Str t) {
                cb.put("text", t.value());
            }
            out.add(cb.put("data", r));
        }
        return out;
    }

    // ---- shared by the Claude stream and transcript mappers (the same message shapes on two routes) -------------------

    /**
     * The constants of one Claude route: its mapping contract, the prefix of its rule ids ({@code claude.stream} or
     * {@code claude.transcript}), the bases it states for usage status and response activity, and the record member
     * that carries a tool's structured result ({@code tool_use_result} on the stream, {@code toolUseResult} in the transcript).
     */
    record ClaudeRoute(String contract, String rulePrefix, String usageBasis, String activityBasis, String structuredMember) {
    }

    /** One Claude record under a route: the mapping context, the record, its time, the reported time text, native uid and session. */
    record ClaudeMessage(SourceContext ctx, NativeRecord r, Time t, String ts, String uid, String sess) {
    }

    /** A Claude assistant message's usage as a response-scope observation of that message's own total, in canonical tranches (thinking is part of output). */
    static Jv.Obj claudeMessageUsage(ClaudeRoute route, ClaudeMessage c, Jv.Obj m, boolean terminal) {
        Jv.Obj usage = usage(2, terminal ? 2 : 1, route.usageBasis(), c.ctx().sourceRef(c.r(), c.uid(), c.ts(), false), modelRef(text(m, "model"), 3, null), m.member("usage"));
        UsageTranches.aggregation(usage, 1, "anthropic.usage.per_message.v1: message.usage is the usage of this one API message, the response's own total");
        UsageTranches.anthropic(usage, m.member("usage"));
        return usage;
    }

    /** A Claude assistant message as a model_response event (Partial until stop_reason is reported, then Completed), its usage observation attached. */
    static Jv.Obj claudeModelResponse(ClaudeRoute route, ClaudeMessage c, Jv.Obj m, Jv o) {
        boolean terminal = m.member("stop_reason") != null && !m.member("stop_reason").isNull();
        Jv.Obj usage = claudeMessageUsage(route, c, m, terminal);
        Jv.Obj ev = c.ctx().event("model_response", terminal ? 2 : 1, c.r(), c.t(), route.contract(), route.rulePrefix() + ".assistant->model_response", c.ts(), c.uid(), true, null);
        ev.put("session_uid", c.sess()).put("response_uid", text(m, "id")).put("model_ref", modelRef(text(m, "model"), 3, null))
                .put("content_blocks", blocks(c.ctx().contracts, route.contract(), m.member("content")))
                .put("usage_observations", new Jv.Arr(List.of(usage)))
                .put("basis", route.activityBasis());
        if (terminal) {
            ev.put("stop_reason", text(m, "stop_reason"));
        }
        ev.put("data", o);
        return ev;
    }

    /** A tool_use block of a Claude assistant message as a tool_call Requested event, with the exact arguments. */
    static Jv.Obj claudeToolRequest(ClaudeRoute route, ClaudeMessage c, String responseUid, Jv b) {
        int k = c.ctx().contracts.toolKind(route.contract(), text(b, "name"));
        Jv.Obj req = c.ctx().event("tool_call", 1, c.r(), c.t(), route.contract(), route.rulePrefix() + ".assistant.tool_use->tool_call.requested", c.ts(), c.uid(), false, null);
        req.put("session_uid", c.sess()).put("response_uid", responseUid).put("tool_call_uid", text(b, "id")).put("tool_name", text(b, "name"))
                .put("tool_kind_id", k).put("tool_kind", TOOL_KIND.get(k)).put("tool_kind_basis", c.ctx().contracts.toolKindRule(route.contract()))
                .put("arguments", b.member("input") == null ? Jv.Null.INSTANCE : b.member("input")).put("data", b);
        return req;
    }

    /**
     * A tool_result block of a Claude user record as a tool_call Result observed event: the result content whole, the
     * reported error bit as the status (not derivable when absent), and the record's structured result under the member
     * the route names.
     */
    static Jv.Obj claudeToolResult(ClaudeRoute route, ClaudeMessage c, boolean first, Jv o, Jv b) {
        String id = text(b, "tool_use_id");
        Jv.Obj res = new Jv.Obj().put("data", b.member("content") == null ? Jv.Null.INSTANCE : b.member("content"));
        Jv isError = b.member("is_error");
        Status status;
        if (isError instanceof Jv.Bool e) {
            res.put("error_reported", e);
            status = new Status(e.value() ? 2 : 1, 1, "reported: tool_result.is_error");
        }
        else {
            status = new Status(0, 3, "is_error not reported on this tool_result block");
        }
        if (o.member(route.structuredMember()) != null) {
            res.put("structured_result", o.member(route.structuredMember()));
        }
        Jv.Obj ev = c.ctx().event("tool_call", 2, c.r(), c.t(), route.contract(), route.rulePrefix() + ".user.tool_result->tool_call.result", c.ts(), c.uid(), first, status);
        ev.put("session_uid", c.sess()).put("tool_call_uid", id).put("tool_result", res);
        return ev;
    }
}
