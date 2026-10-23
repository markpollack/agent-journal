package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RULE-38, RULE-39, RULE-41 / UC5-AC17 to UC5-AC21: the semantic claims of the mapping catalog, their reviewed
 * examples, pinned references and the Owner's decisions. Shape is checked by JSON Schema (draft 2020-12); Java checks
 * the digest of each claim's normative content, every link (examples, references, destinations in the compiled schema,
 * oracle properties this run reported, JUnit tests in the sources, routes, contracts) and every approval (the exact
 * revision and digest, a recorded decision that lists them). Each corruption control changes one thing on a copy and
 * expects the failure for the intended reason, beside the committed data that must validate. A valid claim is
 * consistent; whether its interpretation is right is the Project Owner's decision, made from its examples.
 */
class SemanticClaimTest {

    static Json catalog;
    static Map<String, Json> examples;
    static Json decisions;
    static Json references;
    static SemanticClaims.Context ctx;
    /** The committed data with one claim approved by a test-local decision: the approval mechanics are controlled apart from which claims are approved today. */
    static Json approvedCatalog;
    static Json approvedDecisions;

    @BeforeAll
    static void load(@TempDir Path work) throws IOException {
        catalog = MappingCatalogTest.catalogDocument();
        examples = SemanticClaims.examples();
        decisions = SemanticClaims.decisions();
        references = SemanticClaims.references();
        Set<String> reported = new TreeSet<>();
        MappingCatalogTest.reportedChecks(work).forEach(c -> reported.add(c.property()));
        ctx = context(FieldDispositionTest.schema(work), reported, MappingCatalogTest.contractsByUid(), SemanticClaims.Definitions.read(FoundationComparisonTest.EXTENSION),
                "agentic_cli " + SchemaCatalog.text(NeutralJson.readDocument(FoundationComparisonTest.EXTENSION.resolve("extension.json")), "version"));
        Json k = claim(APPROVED);
        Map<String, Json> control = new LinkedHashMap<>();
        control.put("id", new Json.JString(CONTROL_DECISION));
        control.put("date", new Json.JString("2026-10-22"));
        control.put("approver", new Json.JString("Project Owner (markpollack)"));
        control.put("summary", new Json.JString("TEST CONTROL: approves one claim so the approval checks can be exercised"));
        control.put("record", new Json.JString("none: a test-local decision, never committed"));
        control.put("approves", new Json.JArray(List.of(new Json.JObject(Map.of("claim", new Json.JString(APPROVED), "revision", k.member("revision"), "digest", k.member("digest"),
                "binding", approvedBinding(k))))));
        List<Json> ds = new ArrayList<>(decisions.member("decisions").elements());
        ds.add(new Json.JObject(control));
        approvedDecisions = with(decisions, "decisions", new Json.JArray(ds));
        approvedCatalog = claimChanged(catalog, APPROVED, c -> with(c, "approval", new Json.JObject(Map.of("status", new Json.JString("approved"), "revision", c.member("revision"),
                "digest", c.member("digest"), "approver", new Json.JString("Project Owner (markpollack)"), "decision", new Json.JString(CONTROL_DECISION),
                "binding", approvedBinding(c)))));
    }

    static SemanticClaims.Context context(SchemaCatalog schema, Set<String> reported, Map<String, Json> contracts, SemanticClaims.Definitions defs, String extension) {
        return new SemanticClaims.Context(schema, reported, MappingCatalogTest.compatibilityEntries(), contracts.keySet(), defs, contracts, SemanticClaims.exampleFiles(), extension);
    }

    /** The binding an approval of {@code claim} records: its current relevant digest, routes and binding digest. */
    static Json approvedBinding(Json claim) {
        return new Json.JObject(Map.of("relevant_digest", claim.path("binding", "relevant_digest"), "routes", claim.path("applicability", "routes"), "digest",
                claim.path("binding", "digest")));
    }

    static List<String> problems(Json cat, Map<String, Json> exs, Json dec, SemanticClaims.Context c) {
        return SemanticClaims.problems(cat, exs, dec, references, c);
    }

    /** The claim with its binding restated as an author would after a change: the relevant and binding digests recomputed under {@code c}. */
    static Json restated(Json claim, SemanticClaims.Context c) {
        List<String> ignored = new ArrayList<>();
        String relevant = SemanticClaims.sha256(SemanticClaims.canonical(SemanticClaims.relevant(claim, c.definitions(), c.contracts(), ignored)));
        Json binding = with(with(claim.member("binding"), "relevant_digest", new Json.JString(relevant)), "digest",
                new Json.JString(SemanticClaims.bindingDigest(claim, relevant, c.exampleFiles())));
        return with(claim, "binding", binding);
    }

    static final String CONTROL_DECISION = "OWNER-2026-10-22-TEST-CONTROL";

    static List<String> problems(Json cat, Map<String, Json> exs, Json dec) {
        return problems(cat, exs, dec, ctx);
    }

    // ---- copies with one change -------------------------------------------------------------------------------------

    static Json with(Json obj, String key, Json value) {
        Map<String, Json> m = new LinkedHashMap<>(((Json.JObject) obj).members());
        if (value == null) {
            m.remove(key);
        }
        else {
            m.put(key, value);
        }
        return new Json.JObject(m);
    }

    /** The catalog with one claim replaced by {@code f(claim)}. */
    static Json claimChanged(Json cat, String id, UnaryOperator<Json> f) {
        List<Json> concepts = new ArrayList<>();
        for (Json c : cat.member("concepts").elements()) {
            if (c.member("claims") == null) {
                concepts.add(c);
                continue;
            }
            List<Json> ks = new ArrayList<>();
            for (Json k : c.member("claims").elements()) {
                ks.add(id.equals(SchemaCatalog.text(k, "id")) ? f.apply(k) : k);
            }
            concepts.add(with(c, "claims", new Json.JArray(ks)));
        }
        return with(cat, "concepts", new Json.JArray(concepts));
    }

    static Json claim(String id) {
        return claimIn(catalog, id);
    }

    static Json claimIn(Json cat, String id) {
        for (SemanticClaims.Claim c : SemanticClaims.claims(cat)) {
            if (id.equals(c.id())) {
                return c.json();
            }
        }
        throw new AssertionError("no claim " + id);
    }

    static final String APPROVED = "claude.stream.child-scope-prompt";
    /** The current revision of {@link #APPROVED} in the committed catalog. */
    static final int REV = 3;
    static final String PROPOSED = "codex.exec-stream.mcp-tool-call";

    // ---- the committed data -----------------------------------------------------------------------------------------

    @Test
    void theCommittedClaimsExamplesReferencesAndDecisionsAreConsistent() throws IOException {
        assertThat(SemanticClaims.claims(catalog)).isNotEmpty();
        assertThat(problems(catalog, examples, decisions)).isEmpty();
        JsonSchema claimSchema = CoverageDocumentSchemaTest.schema("semantic-claim.schema.json");
        JsonNode cat = CoverageDocumentSchemaTest.read(MappingCatalogTest.PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/mapping-catalog/mapping-catalog.json"));
        for (JsonNode c : cat.path("concepts")) {
            for (JsonNode k : c.path("claims")) {
                assertThat(claimSchema.validate(k)).as(k.path("id").asText()).isEmpty();
            }
        }
        JsonSchema exampleSchema = CoverageDocumentSchemaTest.schema("semantic-example.schema.json");
        try (var s = Files.list(SemanticClaims.DIR.resolve("examples"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).toList()) {
                assertThat(exampleSchema.validate(CoverageDocumentSchemaTest.read(p))).as(p.getFileName().toString()).isEmpty();
            }
        }
        assertThat(CoverageDocumentSchemaTest.schema("owner-decisions.schema.json").validate(CoverageDocumentSchemaTest.read(SemanticClaims.DIR.resolve("owner-decisions.json")))).isEmpty();
        assertThat(CoverageDocumentSchemaTest.schema("coverage-references.schema.json").validate(CoverageDocumentSchemaTest.read(SemanticClaims.DIR.resolve("references.json")))).isEmpty();
        write();
    }

    // ---- RULE-41 / UC5-AC17, UC5-AC18: the digest boundary --------------------------------------------------------------

    @Test
    void aChangedMeaningUnderAnOldApprovalIsRefused() {
        // the statement changes without a new revision: the stored digest no longer matches the meaning
        Json altered = claimChanged(approvedCatalog, APPROVED, k -> with(k, "statement", new Json.JString(SchemaCatalog.text(k, "statement") + " A main-thread prompt is inferred from the first child prompt.")));
        assertThat(problems(altered, examples, approvedDecisions)).anyMatch(p -> p.startsWith(APPROVED + " r" + REV + ": digest") && p.contains("needs a new revision"));
        // properly revised and re-digested, but still carrying the old approval: refused as stale
        Json revised = claimChanged(altered, APPROVED, k -> with(with(k, "revision", new Json.JNumber(Integer.toString(REV + 1))), "digest", new Json.JString(SemanticClaims.digest(k, examples))));
        assertThat(problems(revised, examples, approvedDecisions)).anyMatch(p -> p.startsWith(APPROVED + " r" + (REV + 1) + ": stale approval refused"))
                .noneMatch(p -> p.contains(": digest "));
        // and a changed normative example (an expected value) changes the meaning too
        Map<String, Json> exs = new TreeMap<>(examples);
        Json ex = exs.get("claude-child-scope-prompt-live");
        Json first = ex.member("expected").elements().get(0);
        exs.put("claude-child-scope-prompt-live", with(ex, "expected", new Json.JArray(List.of(with(first, "values", with(first.member("values"), "/session_uid", new Json.JString("another-session")))))));
        assertThat(problems(approvedCatalog, exs, approvedDecisions)).anyMatch(p -> p.startsWith(APPROVED + " r" + REV + ": digest"));
    }

    @Test
    void addedEvidenceAndChecksLeaveTheDigestAndTheApprovalValid() {
        Json k = claim(APPROVED);
        Json more = claimChanged(approvedCatalog, APPROVED, c -> with(with(c, "evidence", new Json.JArray(List.of(c.member("evidence").elements().get(0), c.member("evidence").elements().get(1),
                new Json.JString("ocsf-1.9.0-unmapped")))), "checks", new Json.JArray(List.of(c.member("checks").elements().get(0), new Json.JString("junit:MappingCatalogTest#everyCatalogReferenceResolvesInJava")))));
        assertThat(SemanticClaims.digest(claim(APPROVED), examples)).isEqualTo(SchemaCatalog.text(k, "digest"));
        assertThat(problems(more, examples, approvedDecisions)).isEmpty();
    }

    // ---- RULE-39 / UC5-AC19: links -----------------------------------------------------------------------------------

    @Test
    void aRemovedOrAlteredLinkIsExposed() {
        String id = PROPOSED;
        Json noExample = claimChanged(catalog, id, k -> with(k, "normative_examples", new Json.JArray(List.of(new Json.JString("no-such-example")))));
        assertThat(problems(noExample, examples, decisions)).anyMatch(p -> p.contains("normative example no-such-example does not exist"));
        Json noPin = claimChanged(catalog, id, k -> with(k, "evidence", new Json.JArray(List.of(new Json.JString("codex-exec-0.160.1-unpinned")))));
        assertThat(problems(noPin, examples, decisions)).anyMatch(p -> p.contains("evidence codex-exec-0.160.1-unpinned is not a pinned reference"));
        Json foreign = claimChanged(catalog, id, k -> with(k, "destinations", new Json.JArray(List.of(new Json.JString("tool_call.model_call_uid")))));
        assertThat(problems(foreign, examples, decisions)).anyMatch(p -> p.contains("destination tool_call.model_call_uid does not resolve"));
        Json noCheck = claimChanged(catalog, id, k -> with(k, "checks", new Json.JArray(List.of(new Json.JString("junit:SemanticExampleTest#aTestThatNeverExisted"), new Json.JString("oracle:CodexStreamProfile/request.model_call_uid")))));
        assertThat(problems(noCheck, examples, decisions)).anyMatch(p -> p.contains("names no test in the test sources")).anyMatch(p -> p.contains("did not report in this run"));
        Json noRoute = claimChanged(catalog, id, k -> with(k, "applicability", with(k.member("applicability"), "routes", new Json.JArray(List.of(new Json.JString("codex-cli/exec-json-stream/0.161.0"))))));
        assertThat(problems(noRoute, examples, decisions)).anyMatch(p -> p.contains("route codex-cli/exec-json-stream/0.161.0 is not a compatibility entry"));
        // an example's native input altered: the pinned recording no longer matches, so the meaning's digest changes
        Map<String, Json> exs = new TreeMap<>(examples);
        Json ex = exs.get("codex-mcp-tool-call-live");
        exs.put("codex-mcp-tool-call-live", with(ex, "native", with(ex.member("native"), "sha256", new Json.JString("0".repeat(64)))));
        assertThat(problems(catalog, exs, decisions)).anyMatch(p -> p.startsWith(id + " r3: digest"));
    }

    // ---- RULE-38 / UC5-AC21: approval is a recorded decision, never a test result -------------------------------------

    @Test
    void anApprovalNeedsARecordedDecisionThatListsTheExactRevisionAndDigest() {
        Json unrecorded = claimChanged(catalog, PROPOSED, k -> with(k, "approval", new Json.JObject(Map.of("status", new Json.JString("approved"), "revision", new Json.JNumber("1"),
                "digest", k.member("digest"), "approver", new Json.JString("Project Owner (markpollack)"), "decision", new Json.JString("OWNER-2026-10-22-NOT-RECORDED")))));
        assertThat(problems(unrecorded, examples, decisions)).anyMatch(p -> p.contains("OWNER-2026-10-22-NOT-RECORDED is not a recorded Owner decision"));
        // a real decision that approves a different claim does not approve this one
        Json borrowed = claimChanged(approvedCatalog, PROPOSED, k -> with(k, "approval", with(with(claimIn(approvedCatalog, APPROVED).member("approval"), "digest", k.member("digest")), "revision", k.member("revision"))));
        assertThat(problems(borrowed, examples, approvedDecisions)).anyMatch(p -> p.contains("does not approve this revision and digest"));
        // a decision whose listed digest differs (it approved another wording)
        List<Json> ds = new ArrayList<>();
        for (Json d : approvedDecisions.member("decisions").elements()) {
            Json x = d.member("approves").elements().get(0);
            ds.add(with(d, "approves", new Json.JArray(List.of(with(x, "digest", new Json.JString("sha256:" + "1".repeat(64)))))));
        }
        assertThat(problems(approvedCatalog, examples, with(approvedDecisions, "decisions", new Json.JArray(ds)))).anyMatch(p -> p.startsWith(APPROVED + " r" + REV + ": decision") && p.contains("does not approve"));
    }

    @Test
    void aDelegatedDecisionStatesItsDelegationAndClaimsNoDirectOwnerReviewOfEachDigest() {
        // the delegated SEM-1 pass is recorded under the reviewer's identity and the Owner's delegation, exactly as decided; its
        // binding says the digests were not each reviewed by the Owner, and its record is a public-safe pointer, not private prose
        Json delegated = decisions.member("decisions").elements().stream().filter(d -> "OWNER-2026-10-22-SEM1-DELEGATED-ACCEPT".equals(SchemaCatalog.text(d, "id"))).findFirst().orElseThrow();
        assertThat(SchemaCatalog.text(delegated, "approver")).isEqualTo("Managing semantic reviewer, explicitly delegated by Project Owner (markpollack)");
        assertThat(SchemaCatalog.text(delegated, "binding")).contains("not direct human review of each digest");
        assertThat(SchemaCatalog.text(delegated, "record")).startsWith("private steward decision record:").doesNotContain("/").doesNotContain(".md");
        for (SemanticClaims.Claim c : SemanticClaims.claims(catalog)) {
            if ("OWNER-2026-10-22-SEM1-DELEGATED-ACCEPT".equals(SchemaCatalog.text(c.json().member("approval"), "decision"))) {
                assertThat(SchemaCatalog.text(c.json().member("approval"), "approver")).as(c.id()).isEqualTo(SchemaCatalog.text(delegated, "approver"));
            }
        }
    }

    /**
     * A revision awaiting binding names the earlier approved revision it changes and the delta, as evidence for the
     * reviewer; the anchor must be a recorded decision of exactly that earlier revision and digest, and it approves
     * nothing: the claim stays proposed.
     */
    @Test
    void aPreviousApprovalAnchorMustBeTheRecordedDecisionOfAnEarlierRevisionAndApprovesNothing() {
        List<SemanticClaims.Claim> anchored = SemanticClaims.claims(catalog).stream().filter(c -> c.json().path("approval", "previous") != null).toList();
        assertThat(anchored).extracting(SemanticClaims.Claim::id).contains(APPROVED, "codex.exec-stream.thread-usage", "codex.exec-stream.web-search-duplicate-id");
        assertThat(anchored).allMatch(c -> "proposed".equals(c.status()));
        assertThat(SemanticClaims.support(anchored.get(0), true, true)).startsWith("candidate verified");
        Json wrongDigest = claimChanged(catalog, APPROVED, k -> with(k, "approval", with(k.member("approval"), "previous",
                with(k.path("approval", "previous"), "digest", new Json.JString("sha256:" + "2".repeat(64))))));
        assertThat(problems(wrongDigest, examples, decisions)).anyMatch(p -> p.startsWith(APPROVED + " r" + REV + ": previous approval r1"));
        Json notEarlier = claimChanged(catalog, APPROVED, k -> with(k, "approval", with(k.member("approval"), "previous",
                with(k.path("approval", "previous"), "revision", new Json.JNumber(Integer.toString(REV))))));
        assertThat(problems(notEarlier, examples, decisions)).anyMatch(p -> p.contains("previous approval r" + REV));
    }

    @Test
    void aProposedClaimWithPassingChecksIsACandidateNotApprovedSupport() {
        SemanticClaims.Claim proposed = SemanticClaims.claims(catalog).stream().filter(c -> PROPOSED.equals(c.id())).findFirst().orElseThrow();
        SemanticClaims.Claim approved = SemanticClaims.claims(approvedCatalog).stream().filter(c -> APPROVED.equals(c.id())).findFirst().orElseThrow();
        assertThat(SemanticClaims.support(proposed, true, true)).startsWith("candidate verified").contains("not approved support");
        assertThat(SemanticClaims.support(approved, true, true)).isEqualTo("approved, verified");
        assertThat(SemanticClaims.support(approved, false, true)).startsWith("approved, not verified");
        assertThat(SemanticClaims.support(approved, true, false)).startsWith("candidate verified");
        // a reviewed date on the catalog and a passing build are not approvals: no claim is approved without its approval record
        assertThat(SemanticClaims.claims(catalog)).filteredOn(c -> "approved".equals(c.status())).allMatch(c -> c.json().path("approval", "decision") != null);
    }

    // ---- the generated table ----------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static void write() throws IOException {
        Path out = MappingCatalogTest.outputDirectory();
        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder md = new StringBuilder("# Semantic claims (from the mapping catalog)\n\nGenerated by `SemanticClaimTest`. Each claim is one reviewed meaning: its revision, the digest of its normative content and its approval. ")
                .append("`proposed` meanings have candidate code and tests and await the Project Owner's approval of the exact revision and digest; `approved` names the recorded decision. ")
                .append("Behaviour verification is in CLI-COVERAGE.md and the requirements accounting; live exercise in LIVE-SCENARIOS.md. Do not edit; regenerate.\n\n")
                .append("Digest boundary 2: the digest is the meaning only; the binding (routes, contract and extension pins, the relevant definitions and rules, the example files) has its own digests, so a pin-only change carries an approval and a changed definition or rule refuses it.\n\n")
                .append("| Claim | Concept | Rev | Approval | Meaning digest | Relevant / binding digest | Binding rules | Routes | Examples |\n|---|---|---|---|---|---|---|---|---|\n");
        for (SemanticClaims.Claim c : SemanticClaims.claims(catalog)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", c.id());
            r.put("concept", c.concept());
            r.put("revision", c.revision());
            r.put("digest", c.digest());
            r.put("approval", NeutralJson.write(c.json().member("approval")));
            r.put("statement", SchemaCatalog.text(c.json(), "statement"));
            r.put("routes", c.json().path("applicability", "routes").elements().stream().map(Json::text).toList());
            r.put("destinations", c.json().member("destinations").elements().stream().map(Json::text).toList());
            r.put("normative_examples", c.json().member("normative_examples").elements().stream().map(Json::text).toList());
            r.put("evidence", c.json().member("evidence").elements().stream().map(Json::text).toList());
            r.put("checks", c.json().member("checks").elements().stream().map(Json::text).toList());
            r.put("digest_boundary", SemanticClaims.boundary(c.json()));
            if (c.json().member("binding") != null) {
                r.put("binding", NeutralJson.write(c.json().member("binding")));
            }
            rows.add(r);
            String decision = SchemaCatalog.text(c.json().member("approval"), "decision");
            md.append("| `").append(c.id()).append("` | ").append(c.concept()).append(" | ").append(c.revision()).append(" | ").append(c.status())
                    .append(decision == null ? "" : " (" + decision + ")").append(" | `").append(c.digest(), 0, 19).append("…` | ");
            Json b = c.json().member("binding");
            md.append(b == null ? "boundary 1" : "`" + SchemaCatalog.text(b, "relevant_digest").substring(0, 19) + "…` / `" + SchemaCatalog.text(b, "digest").substring(0, 19) + "…`")
                    .append(" | ").append(b == null ? "" : String.join(", ", b.member("rules").elements().stream().map(Json::text).toList())).append(" | ")
                    .append(String.join(", ", (List<String>) r.get("routes"))).append(" | ").append(String.join(", ", (List<String>) r.get("normative_examples"))).append(" |\n");
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("kind", "agent-journal/semantic-claims");
        doc.put("derived_by", "SemanticClaimTest (Java; digests computed in this run, links and approvals checked)");
        doc.put("catalog_version", SchemaCatalog.text(catalog, "version"));
        doc.put("claims", rows);
        Files.writeString(out.resolve("semantic-claims.json"), MappingCatalogTest.PRETTY.writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
        Files.writeString(out.resolve("SEMANTIC-CLAIMS.md"), md.toString(), StandardCharsets.UTF_8);
    }

    /** Prints each claim's computed digest (-Dclaims.print=true), for authoring a new revision; never part of acceptance. */
    @Test
    void printComputedDigests() {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getProperty("claims.print")), "no -Dclaims.print=true");
        for (SemanticClaims.Claim c : SemanticClaims.claims(catalog)) {
            System.out.println("DIGEST " + c.id() + " " + SemanticClaims.digest(c.json(), examples));
            if (SemanticClaims.boundary(c.json()) == 2) {
                Json r = restated(c.json(), ctx);
                System.out.println("BINDING " + c.id() + " " + SchemaCatalog.text(r.member("binding"), "relevant_digest") + " " + SchemaCatalog.text(r.member("binding"), "digest"));
            }
        }
    }

    // ---- meaning apart from its binding (boundary 2) ----------------------------------------------------------------

    /** The approved claim under a context whose contracts and extension version are renamed, content unchanged: a pin-only bump. */
    static SemanticClaims.Context bumped() {
        Map<String, Json> contracts = new LinkedHashMap<>(ctx.contracts());
        String uid = claim(APPROVED).path("applicability", "contracts").elements().get(0).text();
        contracts.put(uid.replaceAll("/[0-9.]+-prototype$", "/9.9.9-prototype"), with(contracts.get(uid), "contract_uid", new Json.JString(uid.replaceAll("/[0-9.]+-prototype$", "/9.9.9-prototype"))));
        return new SemanticClaims.Context(ctx.schema(), ctx.reportedProperties(), ctx.compatibilityEntries(), contracts.keySet(), ctx.definitions(), contracts, ctx.exampleFiles(),
                "agentic_cli 9.9.9-prototype");
    }

    static Json repinned(Json cat, SemanticClaims.Context c) {
        return claimChanged(cat, APPROVED, k -> {
            String uid = k.path("applicability", "contracts").elements().get(0).text();
            Json app = with(with(k.member("applicability"), "contracts", new Json.JArray(List.of(new Json.JString(uid.replaceAll("/[0-9.]+-prototype$", "/9.9.9-prototype"))))),
                    "extension", new Json.JString("agentic_cli 9.9.9-prototype"));
            return restated(with(k, "applicability", app), c);
        });
    }

    @Test
    void anUnrelatedVersionBumpCarriesAnApprovedMeaning() {
        SemanticClaims.Context c = bumped();
        Json cat = repinned(approvedCatalog, c);
        Json k = claimIn(cat, APPROVED);
        // the meaning digest is unchanged by new pins; the binding digest changes, its relevant content does not
        assertThat(SemanticClaims.digest(k, examples)).isEqualTo(SchemaCatalog.text(claim(APPROVED), "digest"));
        assertThat(SchemaCatalog.text(k.member("binding"), "digest")).isNotEqualTo(SchemaCatalog.text(claim(APPROVED).member("binding"), "digest"));
        // (the other claims still name the old pins under the bumped context; only the repinned claim is judged here)
        assertThat(problems(cat, examples, approvedDecisions, c)).noneMatch(p -> p.startsWith(APPROVED + " "));
        assertThat(SemanticClaims.bindingState(k)).isEqualTo(SemanticClaims.BindingState.CARRIED);
        SemanticClaims.Claim carried = SemanticClaims.claims(cat).stream().filter(x -> APPROVED.equals(x.id())).findFirst().orElseThrow();
        assertThat(SemanticClaims.support(carried, true, true)).startsWith("approved, verified").contains("binding carried");
        // the same bump without restating the binding is caught: stale pins are not silently accepted
        Json unstated = claimChanged(approvedCatalog, APPROVED, x -> with(x, "applicability", with(x.member("applicability"), "extension", new Json.JString("agentic_cli 9.9.9-prototype"))));
        assertThat(problems(unstated, examples, approvedDecisions, c)).anyMatch(p -> p.contains("binding digest"));
    }

    @Test
    void aChangedDefinitionOrRuleRefusesTheApprovedBinding() {
        // the dictionary definition of a destination attribute changes (its meaning text): relevant content differs
        Map<String, Json> dictionary = new TreeMap<>(ctx.definitions().dictionary());
        String attr = "turn_uid";
        dictionary.put(attr, with(dictionary.get(attr), "description", new Json.JString("A turn id Journal assigns when the source reports none.")));
        SemanticClaims.Definitions changed = new SemanticClaims.Definitions(ctx.definitions().classes(), ctx.definitions().objects(), dictionary);
        SemanticClaims.Context c = new SemanticClaims.Context(ctx.schema(), ctx.reportedProperties(), ctx.compatibilityEntries(), ctx.contractUids(), changed, ctx.contracts(),
                ctx.exampleFiles(), ctx.extension());
        assertThat(problems(approvedCatalog, examples, approvedDecisions, c)).anyMatch(p -> p.startsWith(APPROVED + " r" + REV + ": binding relevant_digest"));
        Json restatedCat = claimChanged(approvedCatalog, APPROVED, k -> restated(k, c));
        assertThat(problems(restatedCat, examples, approvedDecisions, c)).anyMatch(p -> p.startsWith(APPROVED + " r" + REV + ": approved binding refused"));
        // a changed conversion in a named contract rule is refused the same way
        Map<String, Json> contracts = new LinkedHashMap<>(ctx.contracts());
        String uid = claim(APPROVED).path("applicability", "contracts").elements().get(0).text();
        String rule = claim(APPROVED).path("binding", "rules").elements().get(0).text();
        List<Json> rules = new ArrayList<>();
        for (Json r : contracts.get(uid).member("rules").elements()) {
            rules.add(rule.equals(SchemaCatalog.text(r, "id")) ? with(r, "owner", new Json.JString("the main thread's turn, inferred from the first child prompt")) : r);
        }
        contracts.put(uid, with(contracts.get(uid), "rules", new Json.JArray(rules)));
        SemanticClaims.Context ruleChanged = new SemanticClaims.Context(ctx.schema(), ctx.reportedProperties(), ctx.compatibilityEntries(), ctx.contractUids(), ctx.definitions(),
                contracts, ctx.exampleFiles(), ctx.extension());
        Json restatedRule = claimChanged(approvedCatalog, APPROVED, k -> restated(k, ruleChanged));
        assertThat(problems(restatedRule, examples, approvedDecisions, ruleChanged)).anyMatch(p -> p.startsWith(APPROVED + " r" + REV + ": approved binding refused"));
        // a changed default in the meaning itself (semantics) is a changed meaning: the digest no longer matches
        Json absence = claimChanged(approvedCatalog, APPROVED, k -> with(k, "semantics", with(k.member("semantics"), "absence_default", new Json.JString("the main thread's prompt defaults to the first child prompt"))));
        assertThat(problems(absence, examples, approvedDecisions)).anyMatch(p -> p.startsWith(APPROVED + " r" + REV + ": digest"));
    }

    @Test
    void anExpandedRouteAwaitsBindingReviewWhileTheApprovedRouteStaysApproved() {
        String added = "claude-code/stream-json/2.1.303-sdk";
        Json cat = claimChanged(approvedCatalog, APPROVED, k -> {
            List<Json> routes = new ArrayList<>(k.path("applicability", "routes").elements());
            routes.add(new Json.JString(added));
            return restated(with(k, "applicability", with(k.member("applicability"), "routes", new Json.JArray(routes))), ctx);
        });
        Json k = claimIn(cat, APPROVED);
        assertThat(SemanticClaims.digest(k, examples)).isEqualTo(SchemaCatalog.text(claim(APPROVED), "digest"));
        assertThat(problems(cat, examples, approvedDecisions)).isEmpty();
        assertThat(SemanticClaims.bindingState(k)).isEqualTo(SemanticClaims.BindingState.ROUTES_PENDING);
        assertThat(SemanticClaims.pendingRoutes(k)).containsExactly(added);
        SemanticClaims.Claim c = SemanticClaims.claims(cat).stream().filter(x -> APPROVED.equals(x.id())).findFirst().orElseThrow();
        assertThat(SemanticClaims.support(c, true, true)).contains("await binding review").contains(added);
    }

    @Test
    void anUnchangedMeaningWithAFailedImplementationIsApprovedButNotVerified() {
        SemanticClaims.Claim approved = SemanticClaims.claims(approvedCatalog).stream().filter(c -> APPROVED.equals(c.id())).findFirst().orElseThrow();
        assertThat(problems(approvedCatalog, examples, approvedDecisions)).isEmpty();
        assertThat(SemanticClaims.support(approved, false, true)).startsWith("approved, not verified").contains("does not conform");
    }
}
