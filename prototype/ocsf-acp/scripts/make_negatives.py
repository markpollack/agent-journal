#!/usr/bin/env python3
"""Build LABELLED SYNTHETIC fixture variants and package mutations for the negative tests.

Synthetic fixtures (derived from real captures; never live evidence):
  S1 claude-stream-opaque   : pair A stream + a duplicate-key line, a non-UTF-8 line, a blank line, one CRLF line
  S2 codex-two-exec         : pair B rollout + a second custom_tool_call with identical input in the same turn
  S3 agy-two-run_command    : pair A stream + a second run_command step (index 7) before the result line
Package mutations (applied to generated events.jsonl of the REAL packages):
  invalid shape  -> the toolkit must reject:  M1 time as string, M2 unknown top-level attribute, M3 missing required source_ref,
                                               M4 type_uid off by one, M5 tool_kind_id 42 (undefined enum value)
  wrong but schema-valid -> toolkit passes, semantic oracle must catch:
     W1 swap tool_call_uid between the two Claude Read results (identical content)
     W2 mark the stream's provisional response usage Final
     W3 add a cost_observation to the Codex turn (source reports none)
     W4 attach Antigravity's run-level denial to step 6 as a Unique tool-call decision
     W5 attribute an ambiguous Codex operation (S2) to the first call as Unique
Usage: make_negatives.py --steward-plans DIR --out DIR
"""
import argparse, json, os, copy

def w(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data)

def synth_fixtures(plans, out):
    # S1
    src = os.path.join(plans, "evidence/2026-10-19-competitor-refresh/journal-side/claude-2.1.303-pairs/A-multitool/stream.jsonl")
    lines = open(src, "rb").read().split(b"\n")
    assert lines[-1] == b""; body = lines[:-1]
    dup = b'{"type":"system","subtype":"synthetic_duplicate_key","session_id":"synthetic","subtype":"second_value"}'
    bad = b'{"type":"system","subtype":"synthetic_non_utf8","note":"\xff\xfe not utf-8"}'
    out_lines = body[:2] + [dup, bad, b""] + body[2:]
    out_lines[6] = out_lines[6] + b"\r"   # CRLF on the first assistant line (original line 4, now index 6)
    w(os.path.join(out, "S1-claude-stream-opaque/stream.jsonl"), b"\n".join(out_lines) + b"\n")
    # S2
    src = os.path.join(plans, "evidence/2026-10-19-stage-13/codex-pairs/B-multitool-failure/rollout-1.jsonl")
    lines = open(src, "rb").read().split(b"\n"); body = lines[:-1]
    call = json.loads(body[12]); assert call["payload"]["type"] == "custom_tool_call"
    c2 = copy.deepcopy(call); c2["payload"]["call_id"] = "call_SYNTHETIC_second_exec_same_commands"; c2["payload"]["id"] = "ctc_SYNTHETIC"; c2["ordinal"] = 12.5
    out_lines = body[:13] + [json.dumps(c2, separators=(",", ":")).encode()] + body[13:]
    w(os.path.join(out, "S2-codex-two-exec/rollout-1.jsonl"), b"\n".join(out_lines) + b"\n")
    # S3
    src = os.path.join(plans, "evidence/2026-10-19-stage-13/antigravity-pairs/A-multitool/stream.jsonl")
    lines = open(src, "rb").read().split(b"\n"); body = lines[:-1]
    act = json.loads(body[9]); done = json.loads(body[10]); assert act["step_update"]["tool_name"] == "run_command"
    a2 = copy.deepcopy(act); d2 = copy.deepcopy(done)
    for x in (a2, d2):
        x["step_update"]["step_index"] = 7; x["step_update"]["tool_info"]["parameters"]["CommandLine"] = "wc -l src/c.txt"
    out_lines = body[:11] + [json.dumps(a2, separators=(",", ":")).encode(), json.dumps(d2, separators=(",", ":")).encode()] + body[11:]
    w(os.path.join(out, "S3-agy-two-run_command/stream.jsonl"), b"\n".join(out_lines) + b"\n")
    print("synthetic fixtures written (LABELLED SYNTHETIC)")

def load_events(pkg):
    return [json.loads(l) for l in open(os.path.join(pkg, "events.jsonl"))]

def write_mutation(pkg_root, name, events, note):
    d = os.path.join(pkg_root, name); os.makedirs(os.path.join(d, "events"), exist_ok=True)
    with open(os.path.join(d, "events.jsonl"), "w") as f:
        for ev in events: f.write(json.dumps(ev, ensure_ascii=False) + "\n")
    for ev in events:
        json.dump(ev, open(os.path.join(d, "events", f"{ev['journal']['sequence']:04d}.json"), "w"), ensure_ascii=False)
    json.dump({"mutation": name, "note": note, "synthetic": True}, open(os.path.join(d, "MUTATION.json"), "w"), indent=1)

def find(events, pred):
    r = [e for e in events if pred(e)]
    assert r, "mutation target not found"
    return r

def mutations(pkgs, out):
    cl = load_events(pkgs["claude"]); cx = load_events(pkgs["codex"]); ag = load_events(pkgs["agy"])
    # invalid shape (toolkit must reject)
    e = copy.deepcopy(cl); t = find(e, lambda x: x["class_name"] == "Tool Call")[0]; t["time"] = "2026-10-19T22:37:48.339Z"
    write_mutation(out, "M1-time-as-string", e, "time must be timestamp_t (epoch ms)")
    e = copy.deepcopy(cl); e[0]["synthetic_unknown_attribute"] = 1
    write_mutation(out, "M2-unknown-attribute", e, "undeclared top-level attribute")
    e = copy.deepcopy(cl); t = find(e, lambda x: x["class_name"] == "Tool Call")[0]; del t["source_ref"]
    write_mutation(out, "M3-missing-source_ref", e, "source_ref is required on every extension class")
    e = copy.deepcopy(cl); e[0]["type_uid"] = e[0]["type_uid"] + 1
    write_mutation(out, "M4-type_uid-arithmetic", e, "type_uid must equal class_uid*100+activity_id")
    e = copy.deepcopy(cl); t = find(e, lambda x: x["class_name"] == "Tool Call" and x["activity_id"] == 1)[0]; t["tool_kind_id"] = 42; t["tool_kind"] = "forty-two"
    write_mutation(out, "M5-undefined-enum", e, "tool_kind_id 42 is not an enum value")
    # wrong but schema-valid (semantic oracle must catch)
    e = copy.deepcopy(cl)
    r = [x for x in e if x["class_name"] == "Tool Call" and x["activity_id"] == 2 and x["source_ref"]["artifact"] == "stream.jsonl"]
    a = [x for x in r if x["tool_call_uid"] == "toolu_01AL5H2zJ9TcWgZ3oxXHAEcE"][0]; b = [x for x in r if x["tool_call_uid"] == "toolu_01GbaDHPwMzG7cdNHUJGHx3d"][0]
    assert a["tool_result"]["data"] == b["tool_result"]["data"], "the two Read results are expected to be identical text"
    a["tool_call_uid"], b["tool_call_uid"] = b["tool_call_uid"], a["tool_call_uid"]
    write_mutation(out, "W1-swap-identical-results", e, "the two Read results have identical content; owners swapped")
    e = copy.deepcopy(cl)
    m = [x for x in e if x["class_name"] == "Model Response" and x["source_ref"]["artifact"] == "stream.jsonl" and x["response_uid"] == "msg_011CgCPypwJSoRRgCLrta6Ft"][0]
    m["usage_observations"][0]["usage_status_id"] = 2; m["usage_observations"][0]["usage_status"] = "Final"
    write_mutation(out, "W2-provisional-as-final", e, "stream usage (output_tokens 6) marked Final")
    e = copy.deepcopy(cx)
    t = [x for x in e if x["class_name"] == "Prompt Turn" and x["activity_id"] == 2 and x["source_ref"]["artifact"] == "rollout-1.jsonl"][0]
    t["cost_observation"] = {"total_cost": 0.0123, "currency": "USD", "scope_id": 4, "scope": "Run", "source_ref": t["source_ref"] | {"is_whole_record": False}, "data": {"synthetic": 0.0123}}
    write_mutation(out, "W3-false-cost-availability", e, "cost added where the rollout reports none")
    e = copy.deepcopy(ag)
    t = [x for x in e if x["class_name"] == "Tool Call" and x["activity_id"] == 2 and x["tool_name"] == "run_command"][0]
    pt = [x for x in e if x["class_name"] == "Prompt Turn" and x["activity_id"] == 2][0]
    pd = copy.deepcopy(pt["permission_decisions"][0]); pd["scope_id"] = 1; pd["scope"] = "Tool call"; pd["attribution"] = {"outcome_id": 1, "outcome": "Unique", "candidates": [t["tool_call_uid"]], "basis": "SYNTHETIC: claimed unique"}
    t["permission_decision"] = pd
    write_mutation(out, "W4-denial-attached-to-tool", e, "run-level denial claimed as a unique tool-call decision")
    if "codex_s2" in pkgs:
        e = load_events(pkgs["codex_s2"])
        ops = [x for x in e if x["class_name"] == "Tool Call" and x["activity_id"] == 3 and x["source_ref"]["artifact"] == "rollout-1.jsonl"]
        assert all(o["tool_operation"]["attribution"]["outcome_id"] == 2 for o in ops), "S2 operations should be Ambiguous"
        for o in ops:
            o["tool_operation"]["attribution"] = {"outcome_id": 1, "outcome": "Unique", "candidates": [o["tool_operation"]["attribution"]["candidates"][0]], "basis": "SYNTHETIC: order-based guess"}
            o["tool_call_uid"] = o["tool_operation"]["attribution"]["candidates"][0]
        write_mutation(out, "W5-ambiguous-attributed-by-order", e, "ambiguous operations attributed to the first call by order")
    print("mutations written")

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--steward-plans", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--pkg-claude"); ap.add_argument("--pkg-codex"); ap.add_argument("--pkg-agy"); ap.add_argument("--pkg-codex-s2")
    a = ap.parse_args()
    synth_fixtures(a.steward_plans, os.path.join(a.out, "fixtures"))
    if a.pkg_claude:
        pk = {"claude": a.pkg_claude, "codex": a.pkg_codex, "agy": a.pkg_agy}
        if a.pkg_codex_s2: pk["codex_s2"] = a.pkg_codex_s2
        mutations(pk, os.path.join(a.out, "mutations"))

if __name__ == "__main__":
    main()
