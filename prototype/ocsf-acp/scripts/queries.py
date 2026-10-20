#!/usr/bin/env python3
"""The five common queries, ONE definition applied to every package, compared with hand-written expected answers.

Q1 tools      : per tool call id -> requested name/kind/arguments, result content actually returned, status + basis, operations
Q2 content    : per response id -> ordered block types (per source occurrence) and whether exact native structure is retained
Q3 usage      : final reported usage observations with scope and source; 'unavailable' when none at a scope
Q4 permission : permission decisions with scope and attribution outcome/candidates
Q5 vendor     : can named vendor details be read from Level 1 without Level 0 (looked up by native record index)
Each answer value is either a fact or one of: "unavailable at source", "ambiguous", "unattributed", "unsupported".
Usage: queries.py --pkg DIR [--expected FILE] [--out FILE]
"""
import argparse, json, os, sys

def load(pkg):
    return [json.loads(l) for l in open(os.path.join(pkg, "events.jsonl"))]

def q1(events):
    calls = {}
    for e in events:
        if e["class_name"] != "Tool Call": continue
        if e["activity_id"] == 3:
            key = e.get("tool_call_uid") or f"(no owner) op:{e['tool_operation']['uid']}"
            c = calls.setdefault(key, {})
            c.setdefault("operations", []).append({"uid": e["tool_operation"]["uid"], "argv": e["tool_operation"].get("argv"), "exit_code": e["tool_operation"].get("exit_code"), "reported_state": e["tool_operation"].get("reported_state"),
                                                   "attribution": e["tool_operation"]["attribution"]["outcome"], "candidates": e["tool_operation"]["attribution"].get("candidates"), "source": f"{e['source_ref']['artifact']}:{e['source_ref']['record_index']}"})
            continue
        c = calls.setdefault(e.get("tool_call_uid"), {})
        if e["activity_id"] == 1:
            c.setdefault("requests", []).append({"name": e.get("tool_name"), "kind": e.get("tool_kind"), "arguments": e.get("arguments"), "source": f"{e['source_ref']['artifact']}:{e['source_ref']['record_index']}"})
        if e["activity_id"] == 2:
            r = e.get("tool_result")
            c.setdefault("results", []).append({"content": (r or {}).get("data") if r else "unavailable at source", "error_reported": (r or {}).get("error_reported", "unavailable at source") if r else "unavailable at source",
                                                 "status": e.get("status"), "status_basis": e.get("status_basis"), "reported_state": e.get("reported_state"), "source": f"{e['source_ref']['artifact']}:{e['source_ref']['record_index']}"})
    return calls

def q2(events):
    resp = {}
    for e in events:
        if e["class_name"] != "Model Response": continue
        r = resp.setdefault(e["response_uid"], {"occurrences": []})
        blocks = e.get("content_blocks")
        r["occurrences"].append({"source": f"{e['source_ref']['artifact']}:{e['source_ref']['record_index']}", "activity": e["activity_name"], "block_types": [b["block_type"] for b in blocks] if blocks else "unavailable at source",
                                 "exact_native_structure_retained": bool(e.get("data")) and e["source_ref"].get("is_whole_record", False), "stop_reason": e.get("stop_reason", "unavailable at source")})
    return resp

def q3(events):
    out = {"final": [], "non_final": []}
    for e in events:
        for u in e.get("usage_observations", []) or []:
            row = {"scope": u["scope"], "status": u["usage_status"], "owner": e.get("response_uid") or e.get("turn_uid") or e["class_name"], "input_tokens": u.get("input_tokens"), "output_tokens": u.get("output_tokens"),
                   "reasoning_tokens": u.get("reasoning_tokens", "not reported"), "source": f"{u['source_ref']['artifact']}:{u['source_ref']['record_index']}", "basis": u["basis"]}
            (out["final"] if u["usage_status_id"] == 2 else out["non_final"]).append(row)
    scopes = {"Model response", "Prompt turn", "Run"}
    out["final_scopes_present"] = sorted({r["scope"] for r in out["final"]})
    out["final_unavailable_scopes"] = sorted(scopes - set(out["final_scopes_present"]))
    costs = [e["cost_observation"] for e in events if e.get("cost_observation")]
    out["cost"] = [{"total": c["total_cost"], "currency": c["currency"], "scope": c["scope"], "basis": c.get("cost_basis"), "source": f"{c['source_ref']['artifact']}:{c['source_ref']['record_index']}"} for c in costs] or "unavailable at source"
    return out

def q4(events):
    rows = []
    for e in events:
        for pd in ([e["permission_decision"]] if e.get("permission_decision") else []) + (e.get("permission_decisions") or []):
            rows.append({"decision": pd.get("reported_decision", pd["decision"]), "normalized": pd["decision"], "scope": pd["scope"], "attribution": pd["attribution"]["outcome"], "candidates": pd["attribution"].get("candidates"), "decision_source": pd.get("decision_source"),
                         "owner_event": e["class_name"] + ("/" + e["tool_call_uid"] if e.get("tool_call_uid") else ""), "source": f"{(pd.get('source_ref') or e['source_ref'])['artifact']}:{(pd.get('source_ref') or e['source_ref'])['record_index']}"})
    return rows or "unavailable at source"

def q5(events, probes):
    """probes: list of {artifact, record_index, path (dot/[] path into the native record)} -> value from Level 1 or 'unsupported'."""
    idx = {}
    for e in events:
        sr = e["source_ref"]
        if sr.get("is_whole_record"): idx[(sr["artifact"], sr["record_index"])] = e
    out = []
    for p in probes:
        e = idx.get((p["artifact"], p["record_index"]))
        if not e: out.append({**p, "value": "unsupported: no whole-record event"}); continue
        v = e.get("data");
        if v is None: v = e.get("raw_data", e.get("raw_bytes_b64"))
        try:
            for part in p["path"].split(".") if p["path"] else []:
                if part.endswith("]"):
                    name, i = part[:-1].split("["); v = (v[name] if name else v)[int(i)]
                else: v = v[part]
            out.append({**p, "value": v, "from_event": e["type_name"]})
        except Exception as ex:
            out.append({**p, "value": f"unsupported: {ex}"})
    return out

def compare(actual, expected, path="", diffs=None):
    diffs = diffs if diffs is not None else []
    if isinstance(expected, dict):
        if not isinstance(actual, dict): diffs.append((path, "expected object", actual)); return diffs
        for k, v in expected.items(): compare(actual.get(k, "<missing>"), v, f"{path}.{k}", diffs)
    elif isinstance(expected, list):
        if not isinstance(actual, list) or len(actual) != len(expected): diffs.append((path, expected, actual)); return diffs
        for i, (a, b) in enumerate(zip(actual, expected)): compare(a, b, f"{path}[{i}]", diffs)
    else:
        if actual != expected: diffs.append((path, expected, actual))
    return diffs

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--pkg", required=True); ap.add_argument("--expected"); ap.add_argument("--out")
    a = ap.parse_args(); ev = load(a.pkg)
    exp = json.load(open(a.expected)) if a.expected else {}
    ans = {"Q1_tools": q1(ev), "Q2_content": q2(ev), "Q3_usage": q3(ev), "Q4_permission": q4(ev), "Q5_vendor_without_level0": q5(ev, exp.get("Q5_probes", []))}
    json.dump(ans, open(a.out or os.path.join(a.pkg, "queries.json"), "w"), indent=1, ensure_ascii=False)
    if a.expected:
        diffs = compare(ans, exp.get("expected", {}))
        print(json.dumps({"package": os.path.basename(a.pkg.rstrip('/')), "expected_cases": exp.get("case_count"), "differences": len(diffs)}))
        for d in diffs[:15]: print("  DIFF", d[0], "expected", json.dumps(d[1])[:120], "actual", json.dumps(d[2])[:160])
        sys.exit(1 if diffs else 0)

if __name__ == "__main__":
    main()
