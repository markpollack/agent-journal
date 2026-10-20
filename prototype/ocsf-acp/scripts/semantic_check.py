#!/usr/bin/env python3
"""Independent semantic oracle for a prototype package (NON-PRODUCTION; Java remains the acceptance authority).

It never calls adapt.py or its contracts. It reads the native artifacts as BYTES, splits on LF, parses each line
itself (strict duplicate-key detection), and judges each property against those native records.

Properties (each reported with examined population and outcome VERIFIED / DEFECTIVE / UNSUPPORTED / NOT_APPLICABLE):
  P1 result-owner      : a Tool Call "Result observed" event's tool_call_uid is the tool_use_id/call_id present in ITS OWN native record
  P2 usage-status      : a usage observation marked Final cites a native record that reports a terminal marker
                         (Claude: non-null stop_reason, or the result line; Codex: last token_count / turn.completed / task_complete; Antigravity: result line);
                         Provisional cites a record with stop_reason null
  P3 cost-availability : a cost_observation exists only when its cited native record carries a cost field
  P4 decision-scope    : a permission decision attributed Unique cites a native record that names that tool call id;
                         a decision whose record names no tool call must be Unattributed/Ambiguous at a non-tool scope
  P5 operation-attrib  : for each operation, recount candidate wrapping calls from the native rollout with the declared join keys;
                         outcome and candidates must match the recount
  P6 whole-record      : for each native line exactly one event has is_whole_record true and its data/raw equals the native line
                         (structure/value equality for parsed lines; byte equality for opaque lines)
  P7 framing           : line endings and trailing newline recorded in source refs match the bytes
  P8 original-time     : metadata.original_time equals the native timestamp text when present and is absent otherwise
"""
import argparse, base64, json, os, sys, hashlib

def strict(text):
    def nd(pairs):
        d = {}
        for k, v in pairs:
            if k in d: raise ValueError("dup key " + k)
            d[k] = v
        return d
    v = json.loads(text, object_pairs_hook=nd)
    if not isinstance(v, dict): raise ValueError("not object")
    return v

def read_native(path):
    data = open(path, "rb").read(); recs = []; i = 0; n = 0
    while i < len(data):
        j = data.find(b"\n", i)
        seg = data[i:] if j < 0 else data[i:j]; ending = "none" if j < 0 else ("CRLF" if seg.endswith(b"\r") else "LF")
        if ending == "CRLF": seg = seg[:-1]
        n += 1; rec = {"index": n, "raw": seg, "ending": ending, "obj": None, "status": "parsed"}
        try:
            t = seg.decode("utf-8")
            try: rec["obj"] = strict(t) if seg else None
            except ValueError as e: rec["status"] = "malformed:" + str(e)
            if seg == b"": rec["status"] = "blank"
        except UnicodeDecodeError: rec["status"] = "non-utf8"
        recs.append(rec)
        if j < 0: break
        i = j + 1
    return data, recs

class Report:
    def __init__(self): self.rows = []
    def add(self, prop, scope, outcome, detail="", examined=1):
        self.rows.append({"property": prop, "scope": scope, "outcome": outcome, "examined": examined, "detail": detail})
    def summary(self):
        s = {}
        for r in self.rows: s.setdefault(r["property"], {}).setdefault(r["outcome"], 0); s[r["property"]][r["outcome"]] += 1
        return s

def ids_in(obj):
    """All strings in a native record that look like tool call ids (Claude toolu_, Codex call_) or step ids."""
    out = set()
    def walk(v, key=None):
        if isinstance(v, dict):
            for k, x in v.items(): walk(x, k)
        elif isinstance(v, list):
            for x in v: walk(x, key)
        elif isinstance(v, str) and (v.startswith("toolu_") or v.startswith("call_")): out.add(v)
    walk(obj); return out

def check(pkg, plans, rep):
    run = json.load(open(os.path.join(pkg, "run.json")))
    events = [json.loads(l) for l in open(os.path.join(pkg, "events.jsonl"))]
    natives = {}
    for s in run["sources"]:
        p = s["path_at_capture"]
        natives[s["artifact"]] = read_native(p)
    def native(ev, sr=None):
        sr = sr or ev["source_ref"]; data, recs = natives[sr["artifact"]]; return recs[sr["record_index"] - 1]
    gen_uid = run["run"]["session_uid"]

    # P1
    for ev in events:
        if ev["class_name"] == "Tool Call" and ev["activity_id"] == 2:
            rec = native(ev); uid = ev.get("tool_call_uid")
            if uid and uid.startswith(gen_uid + "#step"):
                idx = int(uid.rsplit("#step", 1)[1]); ok = rec["obj"].get("step_update", {}).get("step_index") == idx
                rep.add("P1 result-owner", f"{ev['source_ref']['artifact']}:{rec['index']} {uid}", "VERIFIED" if ok else "DEFECTIVE", "generated id matches step_index" if ok else "step_index mismatch")
            else:
                present = uid in ids_in(rec["obj"]) if rec["obj"] else False
                rep.add("P1 result-owner", f"{ev['source_ref']['artifact']}:{rec['index']} {uid}", "VERIFIED" if present else "DEFECTIVE", "" if present else f"native record does not contain {uid}; it contains {sorted(ids_in(rec['obj']))}")
    # P2
    for ev in events:
        for u in ev.get("usage_observations", []) or []:
            rec = native(ev, u["source_ref"]); o = rec["obj"] or {}; st = u["usage_status_id"]; art = u["source_ref"]["artifact"]
            terminal = None
            if "message" in o and isinstance(o["message"], dict) and "stop_reason" in o["message"]:
                terminal = o["message"]["stop_reason"] is not None
            elif o.get("type") == "result" or o.get("event") == "result" or o.get("type") == "turn.completed":
                terminal = True
            elif o.get("type") == "event_msg" and (o.get("payload") or {}).get("type") == "token_count":
                data, recs = natives[art]
                later = [r for r in recs[rec["index"]:] if r["obj"] and r["obj"].get("type") == "event_msg" and (r["obj"].get("payload") or {}).get("type") in ("token_count", "task_complete")]
                terminal = bool(later) and (later[0]["obj"]["payload"]["type"] == "task_complete")
            elif o.get("event") == "step_update":
                terminal = None  # vendor does not distinguish; only Unknown is acceptable
            if st == 2 and terminal is True: out, d = "VERIFIED", "final marker present in cited record"
            elif st == 1 and terminal is False: out, d = "VERIFIED", "cited record is non-terminal"
            elif st == 0 and terminal is None: out, d = "VERIFIED", "vendor reports no provisional/final distinction; Unknown is the honest status"
            else: out, d = "DEFECTIVE", f"status {st} but terminal evidence is {terminal}"
            rep.add("P2 usage-status", f"{art}:{rec['index']} scope {u['scope_id']}", out, d)
    # P3
    for ev in events:
        co = ev.get("cost_observation")
        if co:
            rec = native(ev, co["source_ref"]); o = rec["obj"] or {}
            has = any(k in o for k in ("total_cost_usd", "costUSD", "cost_usd", "taskCostUsd"))
            rep.add("P3 cost-availability", f"{co['source_ref']['artifact']}:{rec['index']}", "VERIFIED" if has else "DEFECTIVE", "" if has else "cited native record carries no cost field")
        if ev["class_name"] == "Prompt Turn" and ev["activity_id"] == 2 and not co:
            rec = native(ev); o = rec["obj"] or {}
            has = any(k in o for k in ("total_cost_usd", "costUSD", "cost_usd", "taskCostUsd"))
            rep.add("P3 cost-availability", f"{ev['source_ref']['artifact']}:{rec['index']}", "DEFECTIVE" if has else "VERIFIED", "cost reported but not observed" if has else "no cost at source; none claimed")
    # P4
    for ev in events:
        pds = ([ev["permission_decision"]] if ev.get("permission_decision") else []) + (ev.get("permission_decisions") or [])
        for pd in pds:
            rec = native(ev, pd.get("source_ref") or ev["source_ref"]); o = rec["obj"] or {}
            named = ids_in(o); att = pd["attribution"]["outcome_id"]; scope = pd["scope_id"]
            if att == 1:
                target = pd["attribution"]["candidates"][0] if pd["attribution"].get("candidates") else None
                ok = scope == 1 and target and (target in named or (target.startswith(gen_uid + "#step") and False))
                rep.add("P4 decision-scope", f"{rec['index']} {target}", "VERIFIED" if ok else "DEFECTIVE", "record names the tool call" if ok else f"Unique claimed but record names {sorted(named)} and scope is {scope}")
            else:
                ok = scope != 1 and not any(c in named for c in pd["attribution"].get("candidates", []))
                rep.add("P4 decision-scope", f"{rec['index']} outcome {att}", "VERIFIED" if ok else "DEFECTIVE", "no tool reference in record; kept at reported scope" if ok else "record names a tool call or scope is tool call")
    # P5
    for ev in events:
        op = ev.get("tool_operation")
        if not op: continue
        rec = native(ev); art = ev["source_ref"]["artifact"]; data, recs = natives[art]; o = rec["obj"] or {}
        if o.get("type") == "event_msg":
            turn = (o.get("payload") or {}).get("turn_id"); cmd = (o["payload"]["item"].get("command") or [""])[-1]
            cands = []
            for r in recs:
                ro = r["obj"]
                if ro and ro.get("type") == "response_item" and (ro.get("payload") or {}).get("type") == "custom_tool_call":
                    p = ro["payload"]
                    if (p.get("internal_chat_message_metadata_passthrough") or {}).get("turn_id") == turn and p.get("name") == "exec" and cmd in (p.get("input") or ""):
                        cands.append(p["call_id"])
            expect = 1 if len(cands) == 1 else (2 if len(cands) > 1 else 3)
            att = op["attribution"]; ok = att["outcome_id"] == expect and sorted(att.get("candidates", [])) == sorted(cands) and (ev.get("tool_call_uid") == (cands[0] if expect == 1 else None))
            rep.add("P5 operation-attrib", f"{art}:{rec['index']} {op['uid']}", "VERIFIED" if ok else "DEFECTIVE", f"recount: {len(cands)} candidate(s) {cands}; package says outcome {att['outcome_id']} candidates {att.get('candidates')} tool_call_uid {ev.get('tool_call_uid')}")
        else:
            ok = op["attribution"]["outcome_id"] == 3 and not ev.get("tool_call_uid")
            rep.add("P5 operation-attrib", f"{art}:{rec['index']} {op['uid']}", "VERIFIED" if ok else "DEFECTIVE", "stream carries no call id; must be Unattributed")
    # P6 + P7 + P8
    for s in run["sources"]:
        art = s["artifact"]; data, recs = natives[art]
        whole = {}
        for ev in events:
            sr = ev["source_ref"]
            if sr["artifact"] == art and sr.get("is_whole_record"): whole.setdefault(sr["record_index"], []).append(ev)
        # Declared bounded admission (an INPUT of the capture, not an adapter output): recompute which records are admitted.
        admit = s.get("admit_response_uid"); admitted = None
        if admit:
            admitted = set(); tool_ids = set(); prompt_seen = False
            for r in recs:
                o = r["obj"] or {}; m = o.get("message") if isinstance(o.get("message"), dict) else None
                if o.get("type") == "assistant" and m and m.get("id") == admit:
                    admitted.add(r["index"]); tool_ids |= {b.get("id") for b in m.get("content", []) if b.get("type") == "tool_use"}
                elif o.get("type") == "user" and m and isinstance(m.get("content"), str) and not prompt_seen:
                    admitted.add(r["index"]); prompt_seen = True
                elif o.get("type") == "user" and m and isinstance(m.get("content"), list) and any(b.get("tool_use_id") in tool_ids for b in m["content"]):
                    admitted.add(r["index"])
            inv = s.get("family_inventory") or {}
        for r in recs:
            evs = whole.get(r["index"], [])
            if admitted is not None and r["index"] not in admitted:
                fam = (r["obj"] or {}).get("type") if r["obj"] else "opaque"
                disp = (inv.get(fam) or {}).get("disposition")
                if disp and "excluded" in disp or disp == "partially admitted":
                    rep.add("P6 whole-record", f"{art}:{r['index']}", "NOT_APPLICABLE", f"excluded by declared admission (family {fam}: {disp}); not preserved, not missing", 0)
                else:
                    rep.add("P6 whole-record", f"{art}:{r['index']}", "DEFECTIVE", f"record of family {fam} has no declared disposition")
                continue
            if len(evs) != 1:
                rep.add("P6 whole-record", f"{art}:{r['index']}", "DEFECTIVE", f"{len(evs)} whole-record events (expected 1)"); continue
            ev = evs[0]
            if r["status"] == "parsed":
                ok = ev.get("data") == r["obj"]
                rep.add("P6 whole-record", f"{art}:{r['index']}", "VERIFIED" if ok else "DEFECTIVE", "structure/value equal" if ok else "data differs from native record")
            elif r["status"] == "non-utf8":
                ok = base64.b64decode(ev.get("raw_bytes_b64", "")) == r["raw"]
                rep.add("P6 whole-record", f"{art}:{r['index']}", "VERIFIED" if ok else "DEFECTIVE", "bytes equal (opaque)")
            else:
                ok = ev.get("raw_data", None) == r["raw"].decode("utf-8")
                rep.add("P6 whole-record", f"{art}:{r['index']}", "VERIFIED" if ok else "DEFECTIVE", f"text equal (opaque: {r['status']})")
            sr = ev["source_ref"]
            ok7 = sr.get("line_ending") == r["ending"] and sr.get("record_bytes") == len(r["raw"])
            rep.add("P7 framing", f"{art}:{r['index']}", "VERIFIED" if ok7 else "DEFECTIVE", f"ending {r['ending']} bytes {len(r['raw'])}")
            ts = (r["obj"] or {}).get("timestamp") if r["obj"] else None
            ot = ev.get("metadata", {}).get("original_time")
            ok8 = (ot == ts) if ts else (ot is None)
            rep.add("P8 original-time", f"{art}:{r['index']}", "VERIFIED" if ok8 else "DEFECTIVE", f"native {ts!r} event {ot!r}")
        ok = s.get("trailing_newline") == data.endswith(b"\n") and s.get("sha256") == hashlib.sha256(data).hexdigest()
        rep.add("P7 framing", f"{art}:trailing-newline+sha256", "VERIFIED" if ok else "DEFECTIVE")

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--pkg", required=True); ap.add_argument("--steward-plans", required=True); ap.add_argument("--out")
    a = ap.parse_args(); rep = Report(); check(a.pkg, a.steward_plans, rep)
    s = rep.summary(); out = {"package": a.pkg, "summary": s, "defects": [r for r in rep.rows if r["outcome"] == "DEFECTIVE"], "rows": rep.rows}
    json.dump(out, open(a.out or os.path.join(a.pkg, "semantic-check.json"), "w"), indent=1)
    print(json.dumps({"package": os.path.basename(a.pkg.rstrip('/')), "summary": s}))
    for d in out["defects"][:12]: print("  DEFECTIVE", d["property"], d["scope"], d["detail"][:160])
    sys.exit(1 if out["defects"] else 0)

if __name__ == "__main__":
    main()
