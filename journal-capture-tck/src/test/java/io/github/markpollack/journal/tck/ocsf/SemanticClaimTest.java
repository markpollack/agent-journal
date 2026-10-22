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

    @BeforeAll
    static void load(@TempDir Path work) throws IOException {
        catalog = MappingCatalogTest.catalogDocument();
        examples = SemanticClaims.examples();
        decisions = SemanticClaims.decisions();
        references = SemanticClaims.references();
        Set<String> reported = new TreeSet<>();
        MappingCatalogTest.reportedChecks(work).forEach(c -> reported.add(c.property()));
        ctx = new SemanticClaims.Context(FieldDispositionTest.schema(work), reported, MappingCatalogTest.compatibilityEntries(), MappingCatalogTest.contractsByUid().keySet());
    }

    static List<String> problems(Json cat, Map<String, Json> exs, Json dec) {
        return SemanticClaims.problems(cat, exs, dec, references, ctx);
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
        for (SemanticClaims.Claim c : SemanticClaims.claims(catalog)) {
            if (id.equals(c.id())) {
                return c.json();
            }
        }
        throw new AssertionError("no claim " + id);
    }

    static final String APPROVED = "claude.stream.child-scope-prompt";
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
        Json altered = claimChanged(catalog, APPROVED, k -> with(k, "statement", new Json.JString(SchemaCatalog.text(k, "statement") + " A main-thread prompt is inferred from the first child prompt.")));
        assertThat(problems(altered, examples, decisions)).anyMatch(p -> p.startsWith(APPROVED + " r1: digest") && p.contains("needs a new revision"));
        // properly revised and re-digested, but still carrying the old approval: refused as stale
        Json revised = claimChanged(altered, APPROVED, k -> with(with(k, "revision", new Json.JNumber("2")), "digest", new Json.JString(SemanticClaims.digest(k, examples))));
        assertThat(problems(revised, examples, decisions)).anyMatch(p -> p.startsWith(APPROVED + " r2: stale approval refused"))
                .noneMatch(p -> p.contains(": digest "));
        // and a changed normative example (an expected value) changes the meaning too
        Map<String, Json> exs = new TreeMap<>(examples);
        Json ex = exs.get("claude-child-scope-prompt-live");
        Json first = ex.member("expected").elements().get(0);
        exs.put("claude-child-scope-prompt-live", with(ex, "expected", new Json.JArray(List.of(with(first, "values", with(first.member("values"), "/session_uid", new Json.JString("another-session")))))));
        assertThat(problems(catalog, exs, decisions)).anyMatch(p -> p.startsWith(APPROVED + " r1: digest"));
    }

    @Test
    void addedEvidenceAndChecksLeaveTheDigestAndTheApprovalValid() {
        Json k = claim(APPROVED);
        Json more = claimChanged(catalog, APPROVED, c -> with(with(c, "evidence", new Json.JArray(List.of(c.member("evidence").elements().get(0), c.member("evidence").elements().get(1),
                new Json.JString("ocsf-1.9.0-unmapped")))), "checks", new Json.JArray(List.of(c.member("checks").elements().get(0), new Json.JString("junit:MappingCatalogTest#everyCatalogReferenceResolvesInJava")))));
        assertThat(SemanticClaims.digest(claim(APPROVED), examples)).isEqualTo(SchemaCatalog.text(k, "digest"));
        assertThat(problems(more, examples, decisions)).isEmpty();
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
        assertThat(problems(catalog, exs, decisions)).anyMatch(p -> p.startsWith(id + " r1: digest"));
    }

    // ---- RULE-38 / UC5-AC21: approval is a recorded decision, never a test result -------------------------------------

    @Test
    void anApprovalNeedsARecordedDecisionThatListsTheExactRevisionAndDigest() {
        Json unrecorded = claimChanged(catalog, PROPOSED, k -> with(k, "approval", new Json.JObject(Map.of("status", new Json.JString("approved"), "revision", new Json.JNumber("1"),
                "digest", k.member("digest"), "approver", new Json.JString("Project Owner (markpollack)"), "decision", new Json.JString("OWNER-2026-10-22-NOT-RECORDED")))));
        assertThat(problems(unrecorded, examples, decisions)).anyMatch(p -> p.contains("OWNER-2026-10-22-NOT-RECORDED is not a recorded Owner decision"));
        // a real decision that approves a different claim does not approve this one
        Json borrowed = claimChanged(catalog, PROPOSED, k -> with(k, "approval", with(claim(APPROVED).member("approval"), "digest", k.member("digest"))));
        assertThat(problems(borrowed, examples, decisions)).anyMatch(p -> p.contains("does not approve this revision and digest"));
        // a decision whose listed digest differs (it approved another wording)
        List<Json> ds = new ArrayList<>();
        for (Json d : decisions.member("decisions").elements()) {
            Json x = d.member("approves").elements().get(0);
            ds.add(with(d, "approves", new Json.JArray(List.of(with(x, "digest", new Json.JString("sha256:" + "1".repeat(64)))))));
        }
        assertThat(problems(catalog, examples, with(decisions, "decisions", new Json.JArray(ds)))).anyMatch(p -> p.startsWith(APPROVED + " r1: decision") && p.contains("does not approve"));
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

    @Test
    void aProposedClaimWithPassingChecksIsACandidateNotApprovedSupport() {
        SemanticClaims.Claim proposed = SemanticClaims.claims(catalog).stream().filter(c -> PROPOSED.equals(c.id())).findFirst().orElseThrow();
        SemanticClaims.Claim approved = SemanticClaims.claims(catalog).stream().filter(c -> APPROVED.equals(c.id())).findFirst().orElseThrow();
        assertThat(SemanticClaims.support(proposed, true, true)).startsWith("candidate verified").contains("not approved support");
        assertThat(SemanticClaims.support(approved, true, true)).isEqualTo("approved, verified");
        assertThat(SemanticClaims.support(approved, false, true)).isEqualTo("approved, not verified");
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
                .append("| Claim | Concept | Rev | Approval | Digest | Routes | Examples |\n|---|---|---|---|---|---|---|\n");
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
            rows.add(r);
            String decision = SchemaCatalog.text(c.json().member("approval"), "decision");
            md.append("| `").append(c.id()).append("` | ").append(c.concept()).append(" | ").append(c.revision()).append(" | ").append(c.status())
                    .append(decision == null ? "" : " (" + decision + ")").append(" | `").append(c.digest(), 0, 19).append("…` | ")
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
        }
    }
}
