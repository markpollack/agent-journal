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
    static final Map<Integer, String> STATUS = Map.of(0, "Unknown", 1, "Success", 2, "Failure");
    static final Map<Integer, String> STATUS_BASIS = Map.of(0, "Unknown", 1, "Reported", 2, "Derived", 3, "Not derivable");
    static final Map<Integer, String> TIME_BASIS = Map.of(1, "Source record", 2, "Earliest available fallback", 3, "Capture observation");
    static final Map<Integer, String> PROVIDER_BASIS = Map.of(0, "Unknown", 1, "Reported", 2, "Derived", 3, "Unavailable");

    private static final DateTimeFormatter RFC3339_MS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private MapperSupport() {
    }

    /** A status triple for the envelope. */
    record Status(int id, int basisId, String derivation) {
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

    /** Copies integral native usage components into normalized names; absent or non-integral stay absent. */
    static void components(Jv.Obj into, Jv usage, String... pairs) {
        if (usage == null) {
            return;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Jv v = usage.member(pairs[i]);
            if (isIntegral(v)) {
                into.put(pairs[i + 1], v);
            }
        }
    }

    static Jv.Obj usage(int scope, int status, String basis, Jv sourceRef, Jv.Obj modelRef, Jv data) {
        Jv.Obj u = new Jv.Obj().put("scope_id", scope).put("scope", SCOPE.get(scope))
                .put("usage_status_id", status).put("usage_status", USAGE_STATUS.get(status)).put("basis", basis).put("source_ref", sourceRef);
        if (modelRef != null) {
            u.put("model_ref", modelRef);
        }
        return u.put("data", data == null ? Jv.NULL : data);
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
            case "deny", "denied" -> 2;
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
}
