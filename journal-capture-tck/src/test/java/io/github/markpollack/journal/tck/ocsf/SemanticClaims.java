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
    record Context(SchemaCatalog schema, Set<String> reportedProperties, Set<String> compatibilityEntries, Set<String> contractUids, Definitions definitions,
            Map<String, Json> contracts, Map<String, String> exampleFiles, String extension) {
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

    /** The digest of a claim's meaning under its declared boundary: 1 (meaning with version pins) or 2 (meaning apart from its binding). */
    static String digest(Json claim, Map<String, Json> examples) {
        return sha256(canonical(boundary(claim) == 2 ? meaning(claim, examples) : normative(claim, examples)));
    }

    static int boundary(Json claim) {
        Integer b = Event.integer(claim.member("digest_boundary"));
        return b == null ? 1 : b;
    }

    static String sha256(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- boundary 2: meaning and binding --------------------------------------------------------------------------

    /**
     * The meaning of a boundary-2 claim: statement, semantics, destinations and the normative examples (native input,
     * expected values, counterexamples, absent events), with this claim's own binding identifiers (its routes,
     * contract uids and extension version) replaced by role tokens wherever an example states one as a whole value.
     * Version pins are therefore outside the meaning: they belong to the binding.
     */
    static Json meaning(Json claim, Map<String, Json> examples) {
        Map<String, Json> m = new LinkedHashMap<>();
        for (String k : List.of("statement", "semantics", "destinations")) {
            if (claim.member(k) != null) {
                m.put(k, claim.member(k));
            }
        }
        Json normative = normative(claim, examples);
        m.put("examples", substitute(normative.member("examples"), bindingTokens(claim)));
        return new Json.JObject(m);
    }

    /** This claim's binding identifiers and the role token each becomes inside the meaning and the relevant content. */
    static Map<String, String> bindingTokens(Json claim) {
        Map<String, String> out = new LinkedHashMap<>();
        Json app = claim.member("applicability");
        for (Json r : app.member("routes").elements()) {
            out.put(r.text(), "<binding:route>");
        }
        for (Json k : app.member("contracts") == null ? List.<Json>of() : app.member("contracts").elements()) {
            out.put(k.text(), "<binding:contract>");
        }
        if (app.member("extension") != null) {
            out.put(app.member("extension").text(), "<binding:extension>");
        }
        return out;
    }

    /** A copy with every string value equal to a key of {@code tokens} replaced by its token (whole values only). */
    static Json substitute(Json v, Map<String, String> tokens) {
        if (v instanceof Json.JString s) {
            return tokens.containsKey(s.value()) ? new Json.JString(tokens.get(s.value())) : s;
        }
        if (v instanceof Json.JArray a) {
            List<Json> out = new ArrayList<>();
            a.elements().forEach(x -> out.add(substitute(x, tokens)));
            return new Json.JArray(out);
        }
        if (v instanceof Json.JObject o) {
            Map<String, Json> out = new LinkedHashMap<>();
            o.members().forEach((k, x) -> out.put(k, substitute(x, tokens)));
            return new Json.JObject(out);
        }
        return v;
    }

    /** The extension's source definitions: classes, objects and the attribute dictionary, by name. */
    record Definitions(Map<String, Json> classes, Map<String, Json> objects, Map<String, Json> dictionary) {
        static Definitions read(Path extension) {
            Map<String, Json> classes = new TreeMap<>();
            Map<String, Json> objects = new TreeMap<>();
            try (Stream<Path> s = Files.walk(extension.resolve("events"))) {
                for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                    Json d = NeutralJson.readDocument(p);
                    classes.put(SchemaCatalog.text(d, "name"), d);
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try (Stream<Path> s = Files.list(extension.resolve("objects"))) {
                for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                    Json d = NeutralJson.readDocument(p);
                    objects.put(SchemaCatalog.text(d, "name"), d);
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Map<String, Json> dictionary = new TreeMap<>(((Json.JObject) NeutralJson.readDocument(extension.resolve("dictionary.json")).member("attributes")).members());
            return new Definitions(classes, objects, dictionary);
        }

        Json container(String name) {
            return classes.containsKey(name) ? classes.get(name) : objects.get(name);
        }
    }

    /**
     * What a destination path depends on in the extension: for each segment, its declaration in the containing class
     * or object and its dictionary entry (type, description, enum captions); a path ending at an object-typed
     * attribute includes that object's declarations with their dictionary entries; a segment the extension does not
     * define is named as core (pinned by the OCSF core version of the binding). Versions and file names are excluded.
     */
    static Json destinationDefinition(String destination, Definitions defs, List<String> problems) {
        List<Json> steps = new ArrayList<>();
        String[] segs = destination.replace("[]", "").split("\\.");
        Json container = defs.container(segs[0]);
        if (container == null) {
            problems.add("destination " + destination + ": " + segs[0] + " is neither an extension class nor an object");
            return new Json.JArray(steps);
        }
        String containerName = segs[0];
        for (int i = 1; i < segs.length; i++) {
            String attr = segs[i];
            Json declared = container == null ? null : container.path("attributes", attr);
            Json dict = defs.dictionary().get(attr);
            Map<String, Json> step = new LinkedHashMap<>();
            step.put("container", new Json.JString(containerName));
            step.put("attribute", new Json.JString(attr));
            if (declared == null && dict == null) {
                step.put("core", new Json.JBool(true));
                steps.add(new Json.JObject(step));
                break;
            }
            step.put("declared", declared == null ? Json.JNull.INSTANCE : declared);
            step.put("dictionary", dict == null ? Json.JNull.INSTANCE : dict);
            steps.add(new Json.JObject(step));
            String type = dict == null ? null : SchemaCatalog.text(dict, "type");
            container = type == null ? null : defs.objects().get(type);
            containerName = type;
            if (i == segs.length - 1 && container != null) {
                // the path ends at an object: its declarations and their dictionary entries are part of what it means
                Map<String, Json> members = new TreeMap<>();
                for (Map.Entry<String, Json> a : ((Json.JObject) container.member("attributes")).members().entrySet()) {
                    Json d = defs.dictionary().get(a.getKey());
                    members.put(a.getKey(), new Json.JObject(Map.of("declared", a.getValue(), "dictionary", d == null ? Json.JNull.INSTANCE : d)));
                }
                steps.add(new Json.JObject(Map.of("object", new Json.JString(type), "attributes", new Json.JObject(members))));
            }
        }
        return new Json.JArray(steps);
    }

    /**
     * The relevant content a boundary-2 claim's binding protects: the extension definitions of its destinations and
     * the contract rules (or contract tables, {@code table:<member>}) it names in {@code binding.rules}, taken from
     * its applicable contracts, with binding identifiers replaced by role tokens.
     */
    static Json relevant(Json claim, Definitions defs, Map<String, Json> contracts, List<String> problems) {
        Map<String, Json> m = new LinkedHashMap<>();
        Map<String, Json> dests = new TreeMap<>();
        for (Json d : claim.member("destinations").elements()) {
            dests.put(d.text(), destinationDefinition(d.text(), defs, problems));
        }
        m.put("definitions", new Json.JObject(dests));
        Map<String, Json> rules = new TreeMap<>();
        Json app = claim.member("applicability");
        List<Json> applicable = new ArrayList<>();
        for (Json k : app.member("contracts") == null ? List.<Json>of() : app.member("contracts").elements()) {
            Json c = contracts.get(k.text());
            if (c != null) {
                applicable.add(c);
            }
        }
        Json named = claim.path("binding", "rules");
        for (Json r : named == null ? List.<Json>of() : named.elements()) {
            Json found = null;
            for (Json c : applicable) {
                if (r.text().startsWith("table:")) {
                    found = found != null ? found : c.member(r.text().substring("table:".length()));
                }
                else {
                    for (Json rule : c.member("rules").elements()) {
                        if (r.text().equals(SchemaCatalog.text(rule, "id"))) {
                            found = rule;
                        }
                    }
                }
            }
            if (found == null) {
                problems.add("binding rule " + r.text() + " is not in the claim's applicable contracts");
            }
            else {
                rules.put(r.text(), found);
            }
        }
        m.put("rules", new Json.JObject(rules));
        return substitute(new Json.JObject(m), bindingTokens(claim));
    }

    /** The binding digest: applicability (routes and pins), the relevant-content digest and the bytes of each normative example. */
    static String bindingDigest(Json claim, String relevantDigest, Map<String, String> exampleFiles) {
        Map<String, Json> m = new LinkedHashMap<>();
        m.put("applicability", claim.member("applicability"));
        m.put("relevant_digest", new Json.JString(relevantDigest));
        Map<String, Json> ex = new TreeMap<>();
        for (Json id : claim.member("normative_examples").elements()) {
            String sha = exampleFiles.get(id.text());
            ex.put(id.text(), sha == null ? Json.JNull.INSTANCE : new Json.JString(sha));
        }
        m.put("examples", new Json.JObject(ex));
        return sha256(canonical(new Json.JObject(m)));
    }

    /** Example id to the sha256 of its file's bytes. */
    static Map<String, String> exampleFiles() {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(DIR.resolve("examples"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                out.put(SchemaCatalog.text(NeutralJson.readDocument(p), "id"), NeutralJson.sha256(p));
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** How an approved boundary-2 claim's current binding relates to the binding its approval recorded. */
    enum BindingState {
        /** the approval recorded exactly this binding */
        EXACT,
        /** only pins or example bytes changed: the relevant content is unchanged and every route was approved */
        CARRIED,
        /** relevant content unchanged, but routes were added that the approval did not cover */
        ROUTES_PENDING,
        /** the definitions or rules the meaning depends on changed: the approval does not carry */
        CHANGED,
        /** no approval, or a boundary-1 claim */
        NONE
    }

    static BindingState bindingState(Json claim) {
        Json a = claim.member("approval");
        Json approved = a == null ? null : a.member("binding");
        if (boundary(claim) != 2 || !"approved".equals(SchemaCatalog.text(a, "status")) || approved == null) {
            return BindingState.NONE;
        }
        Json b = claim.member("binding");
        if (!Objects.equals(SchemaCatalog.text(approved, "relevant_digest"), SchemaCatalog.text(b, "relevant_digest"))) {
            return BindingState.CHANGED;
        }
        Set<String> routes = new TreeSet<>();
        claim.path("applicability", "routes").elements().forEach(r -> routes.add(r.text()));
        Set<String> approvedRoutes = new TreeSet<>();
        approved.member("routes").elements().forEach(r -> approvedRoutes.add(r.text()));
        if (!approvedRoutes.containsAll(routes)) {
            return BindingState.ROUTES_PENDING;
        }
        return Objects.equals(SchemaCatalog.text(approved, "digest"), SchemaCatalog.text(b, "digest")) ? BindingState.EXACT : BindingState.CARRIED;
    }

    /** The routes of a claim that its approval does not cover (empty unless {@link BindingState#ROUTES_PENDING}). */
    static Set<String> pendingRoutes(Json claim) {
        Set<String> out = new TreeSet<>();
        if (bindingState(claim) != BindingState.ROUTES_PENDING) {
            return out;
        }
        claim.path("applicability", "routes").elements().forEach(r -> out.add(r.text()));
        claim.path("approval", "binding").member("routes").elements().forEach(r -> out.remove(r.text()));
        return out;
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
            if (boundary(c.json()) == 2) {
                problems.addAll(bindingProblems(at, c.json(), ctx));
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

    /** A boundary-2 claim's stored relevant and binding digests must be what its current definitions, rules, pins and examples give. */
    private static List<String> bindingProblems(String at, Json claim, Context ctx) {
        List<String> problems = new ArrayList<>();
        Json b = claim.member("binding");
        if (b == null) {
            problems.add(at + ": a boundary-2 claim states its binding (rules, relevant_digest, digest)");
            return problems;
        }
        List<String> resolution = new ArrayList<>();
        String relevant = sha256(canonical(relevant(claim, ctx.definitions(), ctx.contracts(), resolution)));
        resolution.forEach(r -> problems.add(at + ": " + r));
        if (!relevant.equals(SchemaCatalog.text(b, "relevant_digest"))) {
            problems.add(at + ": binding relevant_digest " + SchemaCatalog.text(b, "relevant_digest") + " is not what its definitions and rules give " + relevant
                    + " (the content the meaning depends on changed: restate the binding; an approval does not carry over it)");
        }
        String binding = bindingDigest(claim, relevant, ctx.exampleFiles());
        String extension = SchemaCatalog.text(claim.member("applicability"), "extension");
        if (extension != null && !extension.equals(ctx.extension())) {
            problems.add(at + ": binding names " + extension + ", not the current " + ctx.extension());
        }
        if (!binding.equals(SchemaCatalog.text(b, "digest"))) {
            problems.add(at + ": binding digest " + SchemaCatalog.text(b, "digest") + " is not what its pins, relevant content and example files give " + binding);
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
        if (boundary(c.json()) == 2) {
            Json approvedBinding = a.member("binding");
            if (approvedBinding == null) {
                problems.add(at + ": a boundary-2 approval records the binding it approved (relevant_digest, routes, digest)");
            }
            else {
                boolean recorded = false;
                for (Json x : decision.member("approves").elements()) {
                    recorded |= c.id().equals(SchemaCatalog.text(x, "claim")) && x.member("binding") != null
                            && Objects.equals(SchemaCatalog.text(x.member("binding"), "relevant_digest"), SchemaCatalog.text(approvedBinding, "relevant_digest"))
                            && Objects.equals(x.member("binding").member("routes"), approvedBinding.member("routes"));
                }
                if (!recorded) {
                    problems.add(at + ": decision " + SchemaCatalog.text(a, "decision") + " does not record the approved binding (relevant content and routes)");
                }
                if (bindingState(c.json()) == BindingState.CHANGED) {
                    problems.add(at + ": approved binding refused: the definitions or rules this meaning depends on changed since the approval (relevant "
                            + SchemaCatalog.text(approvedBinding, "relevant_digest") + " approved, " + SchemaCatalog.text(c.json().member("binding"), "relevant_digest")
                            + " now); review the change before the approval can bind it");
                }
            }
        }
        return problems;
    }

    /**
     * The support a claim may report from its approval and its executed checks: approved support needs both the
     * Owner's approval of this exact revision and every check passing; passing checks alone are a candidate.
     */
    static String support(Claim c, boolean checksPassed, boolean approvalValid) {
        boolean approved = "approved".equals(c.status()) && approvalValid && bindingState(c.json()) != BindingState.CHANGED;
        if (approved) {
            String routes = pendingRoutes(c.json()).isEmpty() ? "" : "; routes " + pendingRoutes(c.json()) + " await binding review";
            String carried = bindingState(c.json()) == BindingState.CARRIED ? " (binding carried: pins changed, relevant content unchanged)" : "";
            return (checksPassed ? "approved, verified" : "approved, not verified (the binding does not conform)") + carried + routes;
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
