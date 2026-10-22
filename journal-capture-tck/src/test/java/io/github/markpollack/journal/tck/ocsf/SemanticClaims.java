package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The semantic claims of the mapping catalog ({@code concepts[].claims[]}): the digest of a claim's normative content
 * and the Java checks of its links and approval. A claim states one reviewed meaning with a revision; its digest covers
 * the statement, applicability, semantics, destinations and the normative examples (their native input, expected
 * values and counterexamples), and nothing else, so adding supporting evidence or checks leaves an approval valid while
 * any change of meaning invalidates it. Approval is a record in {@code owner-decisions.json} naming the exact revision
 * and digest. These checks validate structure and linkage; they cannot establish that an interpretation is true.
 */
final class SemanticClaims {

    static final Path DIR = Path.of(SemanticClaims.class.getResource("/ocsf/mapping-catalog").getPath().replace("%20", " "));

    private SemanticClaims() {
    }

    /** One claim with the concept that carries it. */
    record Claim(String concept, Json json) {
        String id() {
            return SchemaCatalog.text(json, "id");
        }

        int revision() {
            return Event.integer(json.member("revision"));
        }

        String digest() {
            return SchemaCatalog.text(json, "digest");
        }

        String status() {
            return SchemaCatalog.text(json.member("approval"), "status");
        }
    }

    /** What the links of the claims resolve against: the compiled schema and this run's reports, contracts and registry. */
    record Context(SchemaCatalog schema, Set<String> reportedProperties, Set<String> compatibilityEntries, Set<String> contractUids) {
    }

    static List<Claim> claims(Json catalog) {
        List<Claim> out = new ArrayList<>();
        for (Json c : catalog.member("concepts").elements()) {
            for (Json k : c.member("claims") == null ? List.<Json>of() : c.member("claims").elements()) {
                out.add(new Claim(SchemaCatalog.text(c, "id"), k));
            }
        }
        return out;
    }

    static Map<String, Json> examples() {
        Map<String, Json> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(DIR.resolve("examples"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                Json ex = NeutralJson.readDocument(p);
                out.put(SchemaCatalog.text(ex, "id"), ex);
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static Json decisions() {
        return NeutralJson.readDocument(DIR.resolve("owner-decisions.json"));
    }

    static Json references() {
        return NeutralJson.readDocument(DIR.resolve("references.json"));
    }

    // ---- digest -----------------------------------------------------------------------------------------------------

    /** The normative content of a claim, the input of its digest (see the catalog's {@code semantic_claims.digest_boundary}). */
    static Json normative(Json claim, Map<String, Json> examples) {
        Map<String, Json> m = new LinkedHashMap<>();
        for (String k : List.of("statement", "applicability", "semantics", "destinations")) {
            if (claim.member(k) != null) {
                m.put(k, claim.member(k));
            }
        }
        List<Json> exs = new ArrayList<>();
        for (Json id : claim.member("normative_examples").elements()) {
            Json ex = examples.get(id.text());
            Map<String, Json> e = new LinkedHashMap<>();
            e.put("id", id);
            if (ex != null) {
                e.put("native", ex.member("native"));
                e.put("expected", ex.member("expected"));
                List<Json> counters = new ArrayList<>();
                for (Json c : ex.member("counterexamples") == null ? List.<Json>of() : ex.member("counterexamples").elements()) {
                    Map<String, Json> cm = new LinkedHashMap<>(((Json.JObject) c).members());
                    cm.remove("why");
                    counters.add(new Json.JObject(cm));
                }
                e.put("counterexamples", new Json.JArray(counters));
                if (ex.member("absent_events") != null) {
                    List<Json> absent = new ArrayList<>();
                    for (Json a : ex.member("absent_events").elements()) {
                        Map<String, Json> am = new LinkedHashMap<>(((Json.JObject) a).members());
                        am.remove("why");
                        absent.add(new Json.JObject(am));
                    }
                    e.put("absent_events", new Json.JArray(absent));
                }
            }
            exs.add(new Json.JObject(e));
        }
        m.put("examples", new Json.JArray(exs));
        return new Json.JObject(m);
    }

    static String digest(Json claim, Map<String, Json> examples) {
        try {
            byte[] bytes = canonical(normative(claim, examples)).getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical JSON: object keys sorted, no whitespace, numbers as spelled, only quote, backslash and control characters escaped. */
    static String canonical(Json v) {
        StringBuilder sb = new StringBuilder();
        canonical(v, sb);
        return sb.toString();
    }

    private static void canonical(Json v, StringBuilder sb) {
        if (v instanceof Json.JObject o) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Json> e : new TreeMap<>(o.members()).entrySet()) {
                sb.append(first ? "" : ",");
                quote(e.getKey(), sb);
                sb.append(':');
                canonical(e.getValue(), sb);
                first = false;
            }
            sb.append('}');
        }
        else if (v instanceof Json.JArray a) {
            sb.append('[');
            for (int i = 0; i < a.elements().size(); i++) {
                sb.append(i > 0 ? "," : "");
                canonical(a.elements().get(i), sb);
            }
            sb.append(']');
        }
        else if (v instanceof Json.JString s) {
            quote(s.value(), sb);
        }
        else if (v instanceof Json.JNumber n) {
            sb.append(n.text());
        }
        else if (v instanceof Json.JBool b) {
            sb.append(b.value());
        }
        else {
            sb.append("null");
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            }
            else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            }
            else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---- linkage and approval ---------------------------------------------------------------------------------------

    /** Every problem of the claims' digests, links and approvals; empty when the claims are consistent. */
    static List<String> problems(Json catalog, Map<String, Json> examples, Json decisions, Json references, Context ctx) {
        List<String> problems = new ArrayList<>();
        Set<String> refs = new TreeSet<>();
        for (Json r : references.member("references").elements()) {
            refs.add(SchemaCatalog.text(r, "id"));
        }
        Map<String, Json> decisionById = new TreeMap<>();
        for (Json d : decisions.member("decisions").elements()) {
            decisionById.put(SchemaCatalog.text(d, "id"), d);
        }
        Set<String> ids = new TreeSet<>();
        List<Claim> claims = claims(catalog);
        for (Claim c : claims) {
            if (!ids.add(c.id())) {
                problems.add(c.id() + ": duplicate claim id");
            }
        }
        for (Claim c : claims) {
            String at = c.id() + " r" + c.revision();
            String computed = digest(c.json(), examples);
            if (!computed.equals(c.digest())) {
                problems.add(at + ": digest " + c.digest() + " does not match its normative content " + computed + " (a changed meaning needs a new revision)");
            }
            problems.addAll(linkProblems(at, c.json(), examples, refs, ctx));
            problems.addAll(approvalProblems(at, c, decisionById, ids));
        }
        for (Map.Entry<String, Json> e : examples.entrySet()) {
            for (Json r : e.getValue().member("authored_from").elements()) {
                if (!refs.contains(r.text())) {
                    problems.add("example " + e.getKey() + ": authored_from " + r.text() + " is not a pinned reference");
                }
            }
        }
        return problems;
    }

    private static List<String> linkProblems(String at, Json claim, Map<String, Json> examples, Set<String> refs, Context ctx) {
        List<String> problems = new ArrayList<>();
        for (Json e : claim.member("normative_examples").elements()) {
            if (!examples.containsKey(e.text())) {
                problems.add(at + ": normative example " + e.text() + " does not exist");
            }
        }
        for (Json r : claim.member("evidence").elements()) {
            if (!refs.contains(r.text())) {
                problems.add(at + ": evidence " + r.text() + " is not a pinned reference");
            }
        }
        for (Json d : claim.member("destinations").elements()) {
            String why = FieldDispositionTest.resolveDestination(ctx.schema(), d.text());
            if (why != null) {
                problems.add(at + ": destination " + d.text() + " does not resolve: " + why);
            }
        }
        for (Json ch : claim.member("checks").elements()) {
            String why = checkProblem(ch.text(), ctx);
            if (why != null) {
                problems.add(at + ": check " + ch.text() + " " + why);
            }
        }
        Json app = claim.member("applicability");
        for (Json r : app.member("routes").elements()) {
            if (!ctx.compatibilityEntries().contains(r.text())) {
                problems.add(at + ": route " + r.text() + " is not a compatibility entry");
            }
        }
        for (Json k : app.member("contracts") == null ? List.<Json>of() : app.member("contracts").elements()) {
            if (!ctx.contractUids().contains(k.text())) {
                problems.add(at + ": contract " + k.text() + " is not a bundled mapping contract");
            }
        }
        return problems;
    }

    /** Why a check reference does not resolve, or {@code null}: an oracle property this run reported, or an existing JUnit test. */
    static String checkProblem(String check, Context ctx) {
        if (check.startsWith("oracle:")) {
            String[] pp = check.substring("oracle:".length()).split("/", 2);
            if (!CatalogCoverage.PROFILE_NAMES.containsKey(pp[0])) {
                return "names no oracle profile";
            }
            return ctx.reportedProperties().contains(pp[1]) ? null : "names a property the oracle did not report in this run";
        }
        if (check.startsWith("junit:")) {
            return FieldDispositionTest.testExists(check.substring("junit:".length())) ? null : "names no test in the test sources";
        }
        return "is neither oracle: nor junit:";
    }

    private static List<String> approvalProblems(String at, Claim c, Map<String, Json> decisions, Set<String> ids) {
        List<String> problems = new ArrayList<>();
        Json a = c.json().member("approval");
        String status = c.status();
        if ("superseded".equals(status) && !ids.contains(SchemaCatalog.text(a, "superseded_by"))) {
            problems.add(at + ": superseded by " + SchemaCatalog.text(a, "superseded_by") + ", which is not a claim");
        }
        Json previous = a.member("previous");
        if (previous != null) {
            // the anchor of a revision awaiting binding: a recorded decision that approved exactly the earlier revision and digest
            Json earlier = decisions.get(SchemaCatalog.text(previous, "decision"));
            boolean anchored = earlier != null && Event.integer(previous.member("revision")) != null && Event.integer(previous.member("revision")) < c.revision()
                    && earlier.member("approves").elements().stream().anyMatch(x -> c.id().equals(SchemaCatalog.text(x, "claim"))
                            && Objects.equals(Event.integer(x.member("revision")), Event.integer(previous.member("revision"))) && Objects.equals(SchemaCatalog.text(x, "digest"), SchemaCatalog.text(previous, "digest")));
            if (!anchored) {
                problems.add(at + ": previous approval r" + Event.integer(previous.member("revision")) + " " + SchemaCatalog.text(previous, "digest") + " is not one decision "
                        + SchemaCatalog.text(previous, "decision") + " recorded for an earlier revision");
            }
        }
        if (!"approved".equals(status)) {
            return problems;
        }
        Integer revision = Event.integer(a.member("revision"));
        String digest = SchemaCatalog.text(a, "digest");
        if (revision == null || revision != c.revision() || !c.digest().equals(digest)) {
            problems.add(at + ": stale approval refused: it names revision " + revision + " digest " + digest + "; the claim is revision " + c.revision() + " digest " + c.digest());
        }
        Json decision = decisions.get(SchemaCatalog.text(a, "decision"));
        if (decision == null) {
            problems.add(at + ": approval decision " + SchemaCatalog.text(a, "decision") + " is not a recorded Owner decision");
            return problems;
        }
        if (!SchemaCatalog.text(decision, "approver").equals(SchemaCatalog.text(a, "approver"))) {
            problems.add(at + ": approver " + SchemaCatalog.text(a, "approver") + " differs from the decision's " + SchemaCatalog.text(decision, "approver"));
        }
        boolean listed = false;
        for (Json x : decision.member("approves").elements()) {
            listed |= c.id().equals(SchemaCatalog.text(x, "claim")) && Integer.valueOf(c.revision()).equals(Event.integer(x.member("revision")))
                    && c.digest().equals(SchemaCatalog.text(x, "digest"));
        }
        if (!listed) {
            problems.add(at + ": decision " + SchemaCatalog.text(a, "decision") + " does not approve this revision and digest");
        }
        return problems;
    }

    /**
     * The support a claim may report from its approval and its executed checks: approved support needs both the
     * Owner's approval of this exact revision and every check passing; passing checks alone are a candidate.
     */
    static String support(Claim c, boolean checksPassed, boolean approvalValid) {
        boolean approved = "approved".equals(c.status()) && approvalValid;
        if (approved) {
            return checksPassed ? "approved, verified" : "approved, not verified";
        }
        String status = c.status() == null ? "no approval record" : c.status();
        return (checksPassed ? "candidate verified" : "candidate not verified") + " (" + status + "; not approved support)";
    }

    /** Concept id to its claims' approval summary, for the coverage table's separate semantic-approval column. */
    static Map<String, String> approvalByConcept(Json catalog) {
        Map<String, List<String>> by = new LinkedHashMap<>();
        for (Claim c : claims(catalog)) {
            by.computeIfAbsent(c.concept(), k -> new ArrayList<>()).add(c.status() + " " + c.id() + "@" + c.revision());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Json c : catalog.member("concepts").elements()) {
            List<String> l = by.get(SchemaCatalog.text(c, "id"));
            out.put(SchemaCatalog.text(c, "id"), l == null ? "no claim recorded (meaning not yet submitted for approval)" : String.join("; ", l));
        }
        return out;
    }
}
