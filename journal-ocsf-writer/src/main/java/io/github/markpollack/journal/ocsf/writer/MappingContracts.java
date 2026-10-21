package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The mapping contract documents the writer embeds by value in {@code run.json}: one per (CLI, source, version),
 * bundled as resources under {@code ocsf/mappings/}. A mapper names the rule id it executes; executing a rule the
 * contract does not declare is a programming error, and executed-rule counts are reported per contract so an
 * unexercised rule is visible.
 */
final class MappingContracts {

    private final Map<String, Jv> contracts = new LinkedHashMap<>();
    private final Map<String, String> fileSha = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> executed = new LinkedHashMap<>();

    /** The contract document named {@code name} (resource {@code ocsf/mappings/<name>.json}). */
    Jv contract(String name) {
        return contracts.computeIfAbsent(name, n -> {
            String resource = "/ocsf/mappings/" + n + ".json";
            try (InputStream in = MappingContracts.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("missing mapping contract " + resource);
                }
                byte[] bytes = in.readAllBytes();
                fileSha.put(n, SchemaPin.sha256(bytes));
                return Jv.parse(new String(bytes, StandardCharsets.UTF_8));
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    String uid(String name) {
        return contract(name).member("contract_uid").text();
    }

    /** Validates that the rule is declared and counts its execution. */
    String rule(String name, String ruleId) {
        Jv c = contract(name);
        boolean declared = false;
        for (Jv r : c.member("rules").elements()) {
            if (ruleId.equals(r.member("id").text())) {
                declared = true;
            }
        }
        if (!declared) {
            throw new IllegalStateException("rule " + ruleId + " is not declared in contract " + name);
        }
        executed.computeIfAbsent(uid(name), k -> new TreeMap<>()).merge(ruleId, 1, Integer::sum);
        return ruleId;
    }

    /** {@code [kind, rule]} for a tool name from the contract's name table. */
    int toolKind(String name, String toolName) {
        Jv t = contract(name).member("tool_kind_table");
        Jv v = toolName == null ? null : t.path("map", toolName);
        if (v == null) {
            v = t.member("default");
        }
        return (int) ((Jv.Num) v).value().longValueExact();
    }

    String toolKindRule(String name) {
        return contract(name).path("tool_kind_table", "rule").text();
    }

    int blockKind(String name, String blockType) {
        Jv t = contract(name).member("block_kind_table");
        Jv v = blockType == null ? null : t.path("map", blockType);
        if (v == null) {
            v = t.member("default");
        }
        return (int) ((Jv.Num) v).value().longValueExact();
    }

    String blockKindRule(String name) {
        return contract(name).path("block_kind_table", "rule").text();
    }

    /**
     * The {@code shared.contracts} section of the ancillary manifest: each loaded contract by uid with its hashes and
     * executed-rule counts (the mapping audit). The contract document itself is declared in {@code events.jsonl}.
     */
    Jv manifestSection() {
        Jv.Obj out = new Jv.Obj();
        for (Map.Entry<String, Jv> c : contracts.entrySet()) {
            String uid = c.getValue().member("contract_uid").text();
            Jv.Obj entry = new Jv.Obj();
            entry.put("sha256", SchemaPin.sha256(Jv.write(c.getValue()).getBytes(StandardCharsets.UTF_8)));
            entry.put("source_file_sha256", fileSha.get(c.getKey()));
            Jv.Obj rules = new Jv.Obj();
            for (Map.Entry<String, Integer> r : executed.getOrDefault(uid, Map.of()).entrySet()) {
                rules.put(r.getKey(), r.getValue());
            }
            entry.put("rules_executed", rules);
            entry.put("declared_in", "events.jsonl contract:" + uid);
            out.put(uid, entry);
        }
        return out;
    }

    /** The {@code contract} declaration payload for a loaded contract uid, or {@code null} when none is loaded under it. */
    Jv.Obj declaration(String contractUid) {
        for (Map.Entry<String, Jv> c : contracts.entrySet()) {
            if (contractUid.equals(c.getValue().member("contract_uid").text())) {
                return new Jv.Obj().put("contract_uid", contractUid)
                        .put("sha256", SchemaPin.sha256(Jv.write(c.getValue()).getBytes(StandardCharsets.UTF_8)))
                        .put("source_file_sha256", fileSha.get(c.getKey()))
                        .put("embedded", c.getValue())
                        .put("note", "a JSON mapping description (rules, tables, applicability) identified by contract_uid; not a JSON Schema; the mapper it names is provenance, not something a reader executes");
            }
        }
        return null;
    }
}
