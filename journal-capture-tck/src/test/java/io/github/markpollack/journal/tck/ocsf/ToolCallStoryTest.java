package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_CODEX_MCP;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.eventLines;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.isEvent;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.natives;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.recordIndex;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architecture guide's tool-call story, generated from committed evidence and this build's checks: one Codex CLI MCP
 * call ({@code echo_record}, the reviewed example {@code codex-mcp-tool-call-live}) from the sanitized live recording through
 * the reviewed meaning and its contract rule to the saved events, the independent oracle's verdict, and what happens to each
 * reviewed counterexample. Three sources stay apart: the reviewed example states the expectation, the committed package is the
 * writer's output for this input ({@code SemanticExampleTest} replays the same input through the current writer and compares
 * the same pointers in every build), and the oracle judges the package against the native bytes without the writer.
 * <p>Each counterexample is applied to a copy of the saved package with the same corruption helpers the oracle's mutation
 * tests use, and the oracle is run again; every check that leaves VERIFIED is reported with its detail. The page
 * {@code docs/architecture/generated/tool-call-story.md} must equal what this build yields; {@code -Darchitecture.regenerate=true}
 * (or {@code scripts/architecture-docs.sh}) rewrites it.
 */
class ToolCallStoryTest {

    static final String EXAMPLE = "codex-mcp-tool-call-live";
    static final String OWNER = "main/stream.jsonl";
    static final Path OUT = Path.of("..", "docs", "architecture", "generated", "tool-call-story.md").toAbsolutePath().normalize();
    static final String ROOT = "journal-capture-tck/src/test/resources/ocsf/";

    @Test
    void theCommittedStoryIsWhatTheEvidenceAndThisBuildsChecksYield(@TempDir Path work) throws IOException {
        Json example = resource("mapping-catalog/examples/" + EXAMPLE + ".json");
        Json catalog = resource("mapping-catalog/mapping-catalog.json");
        Json references = resource("mapping-catalog/references.json");
        Path fixture = copy(LIVE_CODEX_MCP, work.resolve("unchanged"));
        Json provenance = NeutralJson.readDocument(fixture.resolve("provenance.json"));
        Path stream = natives(fixture).resolve("stream.jsonl");
        List<String> nativeLines = Files.readAllLines(stream, StandardCharsets.UTF_8);
        List<Json> events = eventLines(pkg(fixture).resolve("events.jsonl"));
        OcsfPackage pkg = OcsfFixtures.open(fixture);
        OracleReport baseline = verify(fixture);

        Json concept = null;
        List<Json> claims = new ArrayList<>();
        for (Json c : catalog.member("concepts").elements()) {
            if (c.member("id").text().equals(example.member("concept").text())) {
                concept = c;
            }
            for (Json claim : c.member("claims") == null ? List.<Json>of() : c.member("claims").elements()) {
                if (texts(claim.member("normative_examples")).contains(EXAMPLE)) {
                    claims.add(claim);
                }
            }
        }
        assertThat(concept).as("the example's concept is in the catalog").isNotNull();
        assertThat(claims).as("claims citing the example as normative").isNotEmpty();
        Set<String> properties = new LinkedHashSet<>();
        Set<String> evidence = new LinkedHashSet<>(texts(example.member("authored_from")));
        for (Json claim : claims) {
            for (String check : texts(claim.member("checks"))) {
                if (check.startsWith("oracle:")) {
                    properties.add(check.substring(check.indexOf('/') + 1));
                }
            }
            evidence.addAll(texts(claim.member("evidence")));
        }

        StringBuilder md = new StringBuilder();
        md.append("<!-- GENERATED by ToolCallStoryTest from committed evidence and this build's oracle runs; do not edit. Regenerate with scripts/architecture-docs.sh. -->\n\n");
        md.append("# Tool-call story: one Codex MCP call from native stream to checked event\n\n");
        md.append("**Status:** executed example. The input is a sanitized recording of a live run, replayed; nothing here is a new live run. ")
                .append("Expected values come from the reviewed example, saved values from the committed package the writer produced for this input, ")
                .append("and verdicts from the independent oracle run in this build. The claims below are **")
                .append(String.join(", ", claims.stream().map(c -> c.path("approval", "status").text()).distinct().toList()))
                .append("**: their meaning is reviewed evidence awaiting approval, not an approved definition. See the [architecture guide](../README.md) for how the pieces fit.\n\n");

        // 1. input
        md.append("## 1. The native input\n\n");
        md.append("| Item | Value |\n|---|---|\n");
        row(md, "Fixture", "`" + ROOT + "fixtures/" + LIVE_CODEX_MCP + "/native/stream.jsonl`");
        row(md, "Evidence class", provenance.member("origin").text() + "; provenance in `" + ROOT + "fixtures/" + LIVE_CODEX_MCP + "/provenance.json`");
        row(md, "CLI and route", provenance.member("cli").text() + " " + provenance.member("cli_version").text() + ", `exec --json` on standard output (" + provenance.member("mode_and_route").text() + ")");
        row(md, "Scenario", provenance.member("scenario").text());
        row(md, "Native sha256", "`" + NeutralJson.sha256(stream) + "` (" + nativeLines.size() + " records); the example pins `" + example.path("native", "sha256").text() + "`");
        md.append("\nThe whole stream, one record per line (record *n* is line *n*). This story follows `item_1` (records 4 and 5) and the failed `item_2` (record 7).\n\n```json\n");
        for (String line : nativeLines) {
            md.append(line).append('\n');
        }
        md.append("```\n\n");

        // 2. foundations
        md.append("## 2. Where the common definition comes from\n\n");
        md.append("Concept `").append(concept.member("id").text()).append("`: *").append(concept.member("question").text()).append("* ")
                .append("Each foundation below was inspected for how it names a tool request; the decision says how Journal's definition relates to it ")
                .append("(`").append(ROOT).append("mapping-catalog/mapping-catalog.json`).\n\n");
        md.append("| Foundation | Its representation | Decision | Difference Journal keeps |\n|---|---|---|---|\n");
        for (Json f : concept.member("foundations").elements()) {
            row(md, "`" + f.member("foundation").text() + "`", "`" + f.member("selector").text() + "`", f.member("decision").text(), f.member("differences").text());
        }
        md.append("\nConcept decision: **").append(concept.path("decision", "value").text()).append("**. ").append(concept.path("decision", "reason").text()).append("\n\n");

        // 3. CLI source evidence
        md.append("## 3. What the CLI's own source establishes\n\n");
        md.append("The pinned Codex source the example and claims were authored from (`").append(ROOT).append("mapping-catalog/references.json`).\n\n");
        md.append("| Reference | Repository and revision | File | Symbols | Note |\n|---|---|---|---|---|\n");
        for (Json r : references.member("references").elements()) {
            if (evidence.contains(r.member("id").text())) {
                row(md, "`" + r.member("id").text() + "`", r.member("source").text() + " `" + r.member("revision").text().substring(0, 12) + "`", "`" + r.member("path").text() + "`",
                        r.member("symbol").text(), r.member("note") == null ? "" : r.member("note").text());
            }
        }
        md.append('\n');

        // 4. claims, selection and contract rules
        md.append("## 4. The reviewed meaning and the rule that executes it\n\n");
        Json selection = pkg.declarations.compatibility("stream.jsonl").get(0);
        md.append("The package declares its compatibility selection before any record is interpreted: entry `").append(selection.member("entry_id").text())
                .append("`, status `").append(selection.member("status").text()).append("`. Claims that cite this example as normative:\n\n");
        md.append("| Claim | Revision | Approval | Statement |\n|---|---|---|---|\n");
        for (Json claim : claims) {
            row(md, "`" + claim.member("id").text() + "`", "r" + claim.member("revision").scalarText(), claim.path("approval", "status").text(), claim.member("statement").text());
        }
        md.append("\nThe mapping contract the package carries (declared in `events.jsonl` itself) names the rule each saved event executed:\n\n");
        md.append("| Rule | Native selector | Saved as | Contract text |\n|---|---|---|---|\n");
        Set<String> rules = new LinkedHashSet<>();
        for (Json e : events) {
            if (isEvent(e, "tool_call", 4) || isEvent(e, "tool_call", 2)) {
                rules.add(e.path("journal", "rule_uid").text());
            }
        }
        String contractUid = events.stream().filter(e -> isEvent(e, "tool_call", 4)).findFirst().orElseThrow().path("journal", "contract_uid").text();
        Json declared = pkg.declarations.contracts().get(contractUid);
        Json contract = declared == null ? null : declared.member("embedded");
        assertThat(contract).as("the package embeds contract " + contractUid).isNotNull();
        for (Json rule : contract.member("rules").elements()) {
            if (rules.contains(rule.member("id").text())) {
                String text = rule.member("request") != null ? rule.member("request").text() : rule.member("result").text();
                row(md, "`" + rule.member("id").text() + "`", "`" + NeutralJson.write(rule.member("selector")) + "`", rule.member("activity").text() + "; " + rule.member("owner").text(), text);
            }
        }
        md.append('\n');

        // 5. saved events against the reviewed example
        md.append("## 5. The saved events, against the reviewed example\n\n");
        md.append("Each row compares the value the reviewed example states with the value saved in the committed package (`").append(ROOT).append("fixtures/")
                .append(LIVE_CODEX_MCP).append("/package/events.jsonl`) at the same JSON Pointer. Provenance and retention rows are read from the saved event.\n\n");
        List<String> mismatches = new ArrayList<>();
        for (Json exp : example.member("expected").elements()) {
            int record = integer(exp.member("record"));
            int activity = integer(exp.member("activity_id"));
            Json event = find(events, exp.member("class").text(), activity, record);
            assertThat(event).as("a saved event for record " + record + " activity " + activity).isNotNull();
            md.append("### Record ").append(record).append(" → `").append(event.member("type_name").text()).append("`\n\n");
            md.append("| Field (pointer) | Reviewed example | Saved | |\n|---|---|---|---|\n");
            for (Map.Entry<String, Json> v : ((Json.JObject) exp.member("values")).members().entrySet()) {
                Json actual = at(event, v.getKey());
                boolean same = actual != null && Json.sameValue(v.getValue(), actual);
                if (!same) {
                    mismatches.add(record + " " + v.getKey());
                }
                row(md, "`" + v.getKey() + "`", code(v.getValue()), actual == null ? "*absent*" : code(actual), same ? "same" : "**DIFFERS**");
            }
            for (String absent : exp.member("absent") == null ? List.<String>of() : texts(exp.member("absent"))) {
                Json actual = at(event, absent);
                if (actual != null) {
                    mismatches.add(record + " " + absent + " present");
                }
                row(md, "`" + absent + "`", "*absent*", actual == null ? "*absent*" : code(actual), actual == null ? "same" : "**PRESENT**");
            }
            row(md, "`/journal/rule_uid`", "", code(event.path("journal", "rule_uid")), "provenance");
            row(md, "`/source_ref/record_index`", "", code(event.path("source_ref", "record_index")), "provenance");
            row(md, "`/journal/time_basis`", "", code(event.path("journal", "time_basis")), "provenance");
            boolean whole = Json.sameValue(NeutralJson.parse(nativeLines.get(record - 1)), event.member("data"));
            row(md, "`/data`", "", whole ? "the whole native record " + record : code(event.member("data")), whole ? "retained" : "**NOT THE RECORD**");
            md.append('\n');
        }
        for (Json absent : example.member("absent_events") == null ? List.<Json>of() : example.member("absent_events").elements()) {
            int record = integer(absent.member("record"));
            Json event = find(events, absent.member("class").text(), integer(absent.member("activity_id")), record);
            md.append("No `").append(absent.member("class").text()).append("` event with activity ").append(absent.member("activity_id").scalarText())
                    .append(" exists for record ").append(record).append(event == null ? " (as expected): " : " **(one was saved)**: ").append(absent.member("why").text()).append(".\n\n");
            if (event != null) {
                mismatches.add(record + " absent event present");
            }
        }
        assertThat(mismatches).as("the committed package states what the reviewed example expects").isEmpty();

        // 6. oracle on the unchanged package
        md.append("## 6. The independent oracle on the unchanged package\n\n");
        md.append("`OcsfOracle` reads the native stream as bytes, lets `CodexStreamProfile` state what each record reports, and compares the saved package with it. ")
                .append("The properties the claims name, on `").append(OWNER).append("`:\n\n");
        md.append("| Property | Outcome | Detail |\n|---|---|---|\n");
        for (String property : properties) {
            Check c = baseline.check(property, OWNER);
            row(md, "`" + property + "`", c == null ? "*not reported*" : c.outcome().name(), c == null ? "" : c.detail());
        }
        md.append("\nWhole report: ").append(baseline.defects().size()).append(" defects among ").append(baseline.checks().size()).append(" checks.\n\n");

        // 7. counterexamples
        md.append("## 7. Misleading interpretations and what catches them\n\n");
        md.append("Each reviewed counterexample is applied to a copy of the saved package, and the oracle is run again. The rows list every check that is no longer VERIFIED.\n\n");
        int caught = 0;
        for (Json counter : example.member("counterexamples").elements()) {
            String id = counter.member("id").text();
            int record = integer(counter.member("record"));
            int activity = integer(counter.member("activity_id"));
            String cls = counter.member("class").text();
            Json.JObject values = (Json.JObject) counter.member("values");
            Path corrupted = copy(LIVE_CODEX_MCP, work.resolve(id));
            boolean[] added = {false};
            editEvents(pkg(corrupted), list -> {
                Json target = find(list, cls, activity, record);
                if (target == null) {
                    Json sibling = list.stream().filter(e -> isEvent(e, cls, 4) && recordIndex(e) == record).findFirst().orElseThrow();
                    target = OcsfFixtures.copy(sibling);
                    int classUid = Integer.parseInt(target.member("class_uid").scalarText());
                    obj(target).members().put("activity_id", new Json.JNumber(Integer.toString(activity)));
                    obj(target).members().put("type_uid", new Json.JNumber(Long.toString(classUid * 100L + activity)));
                    list.add(list.indexOf(sibling), target);
                    added[0] = true;
                }
                for (Map.Entry<String, Json> v : values.members().entrySet()) {
                    put(target, v.getKey(), v.getValue());
                }
            });
            OracleReport report = verify(corrupted);
            List<Check> changed = new ArrayList<>();
            for (Check c : report.checks()) {
                Check before = baseline.check(c.property(), c.owner());
                if (c.outcome() != Outcome.VERIFIED && (before == null || before.outcome() != c.outcome() || !before.detail().equals(c.detail()))) {
                    changed.add(c);
                }
            }
            if (!changed.isEmpty()) {
                caught++;
            }
            md.append("### `").append(id).append("`\n\n").append(counter.member("why").text()).append("\n\n");
            md.append("Edit: ").append(added[0] ? "a `" + cls + "` event with activity " + activity + " added for record " + record + ", carrying " : "on the saved `" + cls + "` event for record " + record + " (activity " + activity + "), set ")
                    .append(String.join(", ", values.members().entrySet().stream().map(v -> "`" + v.getKey() + "` = " + code(v.getValue())).toList())).append(".\n\n");
            if (added[0]) {
                md.append("The added event is a copy of the record's existing `").append(cls).append("` event, so the checks on event order and whole-record retention also report the copy; ")
                        .append("the request population check reports the invented request itself.\n\n");
            }
            if (changed.isEmpty()) {
                md.append("**Not detected by the oracle.** Only the reviewed example rejects it.\n\n");
            }
            else {
                md.append("| Check | Outcome | Detail |\n|---|---|---|\n");
                for (Check c : changed) {
                    row(md, "`" + c.property() + "` on `" + c.owner() + "`", c.outcome().name(), c.detail());
                }
                md.append('\n');
            }
        }
        md.append("Of ").append(example.member("counterexamples").elements().size()).append(" reviewed counterexamples, the oracle reports ").append(caught)
                .append(" as a changed check. The oracle's own mutation tests (for example `IdentityClassificationControlTest`) assert several of these outcomes in every build.\n\n");
        md.append("## Regenerate\n\n`scripts/architecture-docs.sh` regenerates this page with the component view; ")
                .append("`./mvnw test -pl journal-capture-tck -Dtest=ToolCallStoryTest -Darchitecture.regenerate=true` regenerates this page alone. ")
                .append("The build fails while the committed page differs from what the evidence and the current oracle yield.\n");

        String page = md.toString();
        if (Boolean.getBoolean("architecture.regenerate")) {
            Files.createDirectories(OUT.getParent());
            Files.writeString(OUT, page, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(OUT) ? Files.readString(OUT, StandardCharsets.UTF_8) : "")
                .as("docs/architecture/generated/tool-call-story.md is stale; regenerate with scripts/architecture-docs.sh and review the diff").isEqualTo(page);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    static Json resource(String path) {
        try {
            return NeutralJson.readDocument(Path.of(ToolCallStoryTest.class.getResource("/ocsf/" + path).toURI()));
        }
        catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static Json find(List<Json> events, String cls, int activity, int record) {
        for (Json e : events) {
            if (isEvent(e, cls, activity) && recordIndex(e) == record) {
                return e;
            }
        }
        return null;
    }

    static Json at(Json root, String pointer) {
        Json cur = root;
        for (String token : pointer.substring(1).split("/", -1)) {
            if (cur instanceof Json.JObject o) {
                cur = o.get(token);
            }
            else if (cur instanceof Json.JArray a && token.chars().allMatch(Character::isDigit) && Integer.parseInt(token) < a.elements().size()) {
                cur = a.elements().get(Integer.parseInt(token));
            }
            else {
                return null;
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    static void put(Json root, String pointer, Json value) {
        int slash = pointer.lastIndexOf('/');
        Json parent = slash == 0 ? root : at(root, pointer.substring(0, slash));
        obj(parent).members().put(pointer.substring(slash + 1), value);
    }

    static List<String> texts(Json array) {
        return array == null ? List.of() : array.elements().stream().map(Json::text).toList();
    }

    static int integer(Json v) {
        return Integer.parseInt(v.scalarText());
    }

    static String code(Json v) {
        return "`" + NeutralJson.write(v) + "`";
    }

    static void row(StringBuilder md, String... cells) {
        md.append("| ").append(String.join(" | ", java.util.Arrays.stream(cells).map(c -> c.replace("\n", " ").replace("|", "\\|")).toList())).append(" |\n");
    }
}
