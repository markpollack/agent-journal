#!/usr/bin/env python3
"""NON-PRODUCTION prototype adapter: native fixture -> agentic_cli OCSF package.

Executes the mapping contracts in ../mappings by rule id. Numeric class/category/type ids are
never hand-written: they are resolved from the compiled schema passed on the command line and
pinned into run.json. Every native line is retained whole exactly once (source_ref.is_whole_record).

Usage: adapt.py --spec SPEC.json --compiled COMPILED.json --extension-dir EXT --out DIR
"""
import argparse, base64, hashlib, json, os, sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
MAPPINGS = os.path.join(HERE, "..", "mappings")
EXT_NAME = "agentic_cli"
OCSF_VERSION = "1.9.0"
PRODUCER = {"name": "agent-journal", "version": "prototype/acp-ocsf-worked-example", "vendor_name": "Agent Journal"}

# ----------------------------------------------------------------------------------------- utilities

def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()

def rfc3339_to_ms(text):
    t = text.replace("Z", "+00:00")
    dt = datetime.fromisoformat(t)
    return int(dt.timestamp() * 1000)

def ms_to_rfc3339(ms):
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{ms % 1000:03d}Z"

def strict_loads(text):
    """One JSON object per line; duplicate keys are rejected (they cannot enter an object model safely)."""
    def no_dupes(pairs):
        d = {}
        for k, v in pairs:
            if k in d:
                raise ValueError(f"duplicate key {k!r}")
            d[k] = v
        return d
    v = json.loads(text, object_pairs_hook=no_dupes)
    if not isinstance(v, dict):
        raise ValueError("not a JSON object")
    return v

class Line:
    def __init__(self, index, raw, ending):
        self.index = index            # 1-based occurrence
        self.raw = raw                # bytes without terminator
        self.ending = ending          # "LF" | "CRLF" | "none"
        self.text = None
        self.obj = None
        self.parse_status = 0
        self.reason = None
        if raw == b"":
            self.parse_status = 4; self.reason = "blank line"; self.text = ""
            return
        try:
            self.text = raw.decode("utf-8")
        except UnicodeDecodeError as e:
            self.parse_status = 3; self.reason = f"not UTF-8: {e.reason} at byte {e.start}"
            return
        try:
            self.obj = strict_loads(self.text)
            self.parse_status = 1
        except ValueError as e:
            self.parse_status = 2; self.reason = f"not one JSON object: {e}"

def read_lines(path):
    data = open(path, "rb").read()
    lines, i, idx = [], 0, 0
    while i < len(data):
        j = data.find(b"\n", i)
        if j < 0:
            lines.append(Line(idx + 1, data[i:], "none")); break
        seg = data[i:j]
        if seg.endswith(b"\r"):
            lines.append(Line(idx + 1, seg[:-1], "CRLF"))
        else:
            lines.append(Line(idx + 1, seg, "LF"))
        idx += 1; i = j + 1
    return data, lines

# ----------------------------------------------------------------------------------------- schema pin

class Pin:
    def __init__(self, compiled_path, extension_dir):
        self.compiled_bytes = open(compiled_path, "rb").read()
        c = json.loads(self.compiled_bytes)
        ext = c["extensions"][EXT_NAME]
        self.ext_uid, self.ext_version = ext["uid"], ext["version"]
        self.classes = {}
        for key, cls in c["classes"].items():
            if key.startswith(EXT_NAME + "/"):
                acts = {k: v["caption"] for k, v in cls["attributes"]["activity_id"]["enum"].items()}
                self.classes[cls["name"]] = {"class_uid": cls["uid"], "category_uid": cls["category_uid"],
                                             "category_name": cls.get("category_name"), "caption": cls["caption"], "activities": acts}
        self.compiled_sha256 = sha256_bytes(self.compiled_bytes)
        self.ocsf_version = c["version"]
        # extension source hash: every file under the extension dir, sorted
        h = hashlib.sha256(); self.source_files = []
        for root, _, files in sorted(os.walk(extension_dir)):
            for f in sorted(files):
                p = os.path.join(root, f); b = open(p, "rb").read()
                rel = os.path.relpath(p, extension_dir); h.update(rel.encode()); h.update(b)
                self.source_files.append({"path": rel, "bytes": len(b), "sha256": sha256_bytes(b)})
        self.extension_source_sha256 = h.hexdigest()

# ----------------------------------------------------------------------------------------- adapter

class Adapter:
    def __init__(self, spec, pin, out_dir):
        self.spec, self.pin, self.out = spec, pin, out_dir
        self.events = []
        self.seq = 0
        self.capture_time = datetime.now(timezone.utc)
        self.capture_ms = int(self.capture_time.timestamp() * 1000)
        self.capture_text = self.capture_time.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
        self.capture_start_ms = rfc3339_to_ms(spec["capture_start"])
        self.contracts = {}
        self.executed_rules = {}
        self.inventory = []
        cli = spec["cli"]
        self.product = {"name": cli["name"], "version": cli["version"], "vendor_name": cli["vendor_name"]}
        self.cli_agent = {"name": cli["name"], "version": cli["version"], "type_id": cli["type_id"], "type": cli["type"],
                          "instance_uid": spec["session_uid"], "product": self.product}

    # -- contract access
    def contract(self, name):
        if name not in self.contracts:
            self.contracts[name] = json.load(open(os.path.join(MAPPINGS, name + ".json")))
        return self.contracts[name]

    def rule(self, contract, rule_id):
        c = self.contract(contract)
        if not any(r["id"] == rule_id for r in c["rules"]):
            raise SystemExit(f"rule {rule_id} is not declared in contract {contract}")
        self.executed_rules.setdefault(c["contract_uid"], {}).setdefault(rule_id, 0)
        self.executed_rules[c["contract_uid"]][rule_id] += 1
        return rule_id

    def kind(self, contract, tool_name):
        t = self.contract(contract)["tool_kind_table"]
        return t["map"].get(tool_name, t["default"]), t["rule"]

    def block_kind(self, contract, block_type):
        t = self.contract(contract)["block_kind_table"]
        return t["map"].get(block_type, t["default"]), t["rule"]

    # -- envelope
    def source_ref(self, art, line, native_uid=None, native_time=None, whole=True):
        r = {"source_kind_id": art["kind"], "source_kind": {1: "Live output", 2: "Saved session file"}[art["kind"]],
             "artifact": art["artifact"], "artifact_sha256": art["sha256"], "record_index": line.index,
             "record_bytes": len(line.raw), "line_ending": line.ending, "is_whole_record": whole}
        if native_uid: r["native_uid"] = native_uid
        if native_time: r["native_time"] = native_time
        return r

    def emit(self, cls, activity, art, line, time_ms, time_basis, basis_detail, contract, rule_id, *, original_time=None,
             native_uid=None, whole=True, status=None, extra=None):
        p = self.pin.classes[cls]
        self.seq += 1
        caps = {1: "Source record", 2: "Earliest available fallback", 3: "Capture observation"}
        ev = {
            "class_uid": p["class_uid"], "class_name": p["caption"], "category_uid": p["category_uid"], "category_name": p["category_name"],
            "activity_id": activity, "activity_name": p["activities"][str(activity)],
            "type_uid": p["class_uid"] * 100 + activity, "type_name": f"{p['caption']}: {p['activities'][str(activity)]}",
            "severity_id": 0, "severity": "Unknown",
            "time": time_ms, "time_dt": ms_to_rfc3339(time_ms),
            "metadata": {"version": OCSF_VERSION, "product": self.product,
                          "extensions": [{"name": EXT_NAME, "uid": str(self.pin.ext_uid), "version": self.pin.ext_version}],
                          "profiles": ["datetime"]},
            "cli_agent": self.cli_agent,
            "journal": {"run_uid": self.spec["run_uid"], "invocation_index": self.spec["invocation_index"], "sequence": self.seq,
                         "producer": PRODUCER, "contract_uid": self.contract(contract)["contract_uid"],
                         "contract_sha256": self.contract_sha(contract), "capture_time": self.capture_ms,
                         "capture_time_text": self.capture_text, "time_basis_id": time_basis, "time_basis": caps[time_basis],
                         "basis": basis_detail},
            "source_ref": self.source_ref(art, line, native_uid, original_time, whole),
        }
        if original_time is not None:
            ev["metadata"]["original_time"] = original_time
        if native_uid:
            ev["metadata"]["original_event_uid"] = native_uid
        if status is not None:
            sid, sbasis, deriv = status
            ev["status_id"] = sid; ev["status"] = {0: "Unknown", 1: "Success", 2: "Failure"}[sid]
            ev["status_basis_id"] = sbasis; ev["status_basis"] = {0: "Unknown", 1: "Reported", 2: "Derived", 3: "Not derivable"}[sbasis]
            if deriv: ev["derivation"] = deriv
        if extra: ev.update(extra)
        ev.setdefault("unmapped", {})["rule_id"] = rule_id
        self.events.append(ev)
        return ev

    def contract_sha(self, contract):
        return sha256_bytes(open(os.path.join(MAPPINGS, contract + ".json"), "rb").read())

    def time_for(self, art, line, state, reported_text):
        """D2 prototype: source time when reported; else latest preceding reported time; else capture start."""
        if reported_text:
            ms = rfc3339_to_ms(reported_text); state["last"] = (ms, line.index)
            return ms, 1, f"record reports timestamp {reported_text}"
        if state.get("last"):
            ms, idx = state["last"]
            return ms, 2, f"no timestamp on record {line.index}; latest preceding reported time is record {idx} of {art['artifact']}"
        return self.capture_start_ms, 2, f"no timestamp on record {line.index} and none precedes; capture start {self.spec['capture_start']}"

    def opaque(self, art, line, contract, state):
        ms, tb, bd = self.time_for(art, line, state, None)
        extra = {"parse_status_id": line.parse_status, "parse_status": {2: "Malformed JSON", 3: "Undecodable bytes", 4: "Blank"}[line.parse_status],
                 "basis": line.reason, "record_type": "opaque"}
        if line.parse_status == 3:
            extra["raw_bytes_b64"] = base64.b64encode(line.raw).decode()
        else:
            extra["raw_data"] = line.text
        self.emit("source_record", 2, art, line, ms, tb, bd, contract, self.rule(contract, "common.opaque->source_record"), extra=extra)

    def fallback(self, art, line, contract, rule_id, record_type, state, reported_text=None, native_uid=None, session=None):
        ms, tb, bd = self.time_for(art, line, state, reported_text)
        extra = {"parse_status_id": 1, "parse_status": "Parsed", "record_type": record_type, "basis": "no normalization rule for this record family; retained whole", "data": line.obj}
        if session: extra["session_uid"] = session
        self.emit("source_record", 1, art, line, ms, tb, bd, contract, self.rule(contract, rule_id), original_time=reported_text, native_uid=native_uid, extra=extra)

    def blocks(self, contract, content):
        out = []
        if isinstance(content, str):
            return [{"block_index": 0, "block_type": "string", "block_kind_id": 1, "block_kind": "Text", "basis": "message.content is a bare string", "text": content, "data": content}]
        for i, b in enumerate(content):
            k, rule = self.block_kind(contract, b.get("type"))
            cb = {"block_index": i, "block_type": b.get("type"), "block_kind_id": k, "block_kind": {1: "Text", 2: "Thought", 3: "Tool call", 4: "Tool result", 5: "Image", 99: "Other"}[k], "basis": rule, "data": b}
            if isinstance(b.get("text"), str): cb["text"] = b["text"]
            if isinstance(b.get("thinking"), str): cb["text"] = b["thinking"]
            if b.get("type") == "tool_use": cb["tool_call_uid"] = b.get("id")
            if b.get("type") == "tool_result": cb["tool_call_uid"] = b.get("tool_use_id")
            out.append(cb)
        return out

    # ------------------------------------------------------------------ Claude Code stream (live output)
    def claude_stream(self, art, lines):
        C = "claude-code-2.1.303-stream"; st = {}
        for line in lines:
            if line.parse_status != 1:
                self.opaque(art, line, C, st); continue
            o = line.obj; t = o.get("type"); ts = o.get("timestamp"); uid = o.get("uuid"); sess = o.get("session_id")
            if t == "assistant":
                m = o["message"]; ms, tb, bd = self.time_for(art, line, st, ts)
                act = 1 if m.get("stop_reason") is None else 2
                usage = {"scope_id": 2, "scope": "Model response", "usage_status_id": 1 if act == 1 else 2, "usage_status": "Provisional" if act == 1 else "Final",
                         "basis": "claude.stream.usage_status.v1: stop_reason null -> provisional", "source_ref": self.source_ref(art, line, uid, ts, False),
                         "model_ref": {"name": m["model"], "provider_basis_id": 3, "provider_basis": "Unavailable"}, "data": m["usage"]}
                for k_src, k_dst in (("input_tokens", "input_tokens"), ("output_tokens", "output_tokens"), ("cache_read_input_tokens", "cache_read_tokens"), ("cache_creation_input_tokens", "cache_write_tokens")):
                    if isinstance(m["usage"].get(k_src), int): usage[k_dst] = m["usage"][k_src]
                extra = {"session_uid": sess, "response_uid": m["id"], "model_ref": {"name": m["model"], "provider_basis_id": 3, "provider_basis": "Unavailable"},
                         "content_blocks": self.blocks(C, m["content"]), "usage_observations": [usage],
                         "basis": "claude.stream.activity.v1: stop_reason null -> Partial", "data": o}
                if m.get("stop_reason") is not None: extra["stop_reason"] = m["stop_reason"]
                self.emit("model_response", act, art, line, ms, tb, bd, C, self.rule(C, "claude.stream.assistant->model_response"), original_time=ts, native_uid=uid, extra=extra)
                for b in m["content"]:
                    if b.get("type") == "tool_use":
                        k, krule = self.kind(C, b["name"])
                        self.emit("tool_call", 1, art, line, ms, tb, bd, C, self.rule(C, "claude.stream.assistant.tool_use->tool_call.requested"), original_time=ts, native_uid=uid, whole=False,
                                  extra={"session_uid": sess, "response_uid": m["id"], "tool_call_uid": b["id"], "tool_name": b["name"], "tool_kind_id": k, "tool_kind": self.kind_caption(k), "tool_kind_basis": krule, "arguments": b.get("input"), "data": b})
            elif t == "user":
                m = o["message"]; ms, tb, bd = self.time_for(art, line, st, ts); first = True
                content = m.get("content")
                if isinstance(content, list) and any(b.get("type") == "tool_result" for b in content):
                    for b in content:
                        if b.get("type") != "tool_result": continue
                        res = {"data": b.get("content")}
                        if "is_error" in b: res["error_reported"] = b["is_error"]
                        if "tool_use_result" in o: res["structured_result"] = o["tool_use_result"]
                        status = (0, 3, "is_error not reported on this tool_result block") if "is_error" not in b else ((2 if b["is_error"] else 1), 1, "reported: tool_result.is_error")
                        extra = {"session_uid": sess, "tool_call_uid": b["tool_use_id"], "tool_result": res, "data": o if first else b}
                        for meta in o.get("tool_result_meta", []) or []:
                            if meta.get("id") == b["tool_use_id"] and meta.get("permission_decision"):
                                pd = meta["permission_decision"]
                                extra["permission_decision"] = {"decision_id": {"accept": 1, "allow": 1, "deny": 2}.get(pd.get("decision"), 99), "decision": {"accept": "Allowed", "allow": "Allowed", "deny": "Denied"}.get(pd.get("decision"), "Other"), "reported_decision": pd.get("decision"),
                                                                "decision_source": pd.get("source"), "reason_type": pd.get("reason_type"), "scope_id": 1, "scope": "Tool call",
                                                                "attribution": {"outcome_id": 1, "outcome": "Unique", "candidates": [b["tool_use_id"]], "basis": "tool_result_meta[].id == tool_result.tool_use_id"},
                                                                "source_ref": self.source_ref(art, line, uid, ts, False), "data": meta}
                        self.emit("tool_call", 2, art, line, ms, tb, bd, C, self.rule(C, "claude.stream.user.tool_result->tool_call.result"), original_time=ts, native_uid=uid, whole=first, status=status, extra=extra)
                        first = False
                else:
                    self.fallback(art, line, C, "claude.stream.fallback->source_record", "user (no tool_result)", st, ts, uid, sess)
            elif t == "result":
                ms, tb, bd = self.time_for(art, line, st, ts)
                obs = []
                u = o.get("usage") or {}
                ob = {"scope_id": 4, "scope": "Run", "usage_status_id": 2, "usage_status": "Final", "basis": "claude.stream.result_usage.v1: the result line reports run totals once", "source_ref": self.source_ref(art, line, uid, ts, False), "data": u}
                for k_src, k_dst in (("input_tokens", "input_tokens"), ("output_tokens", "output_tokens"), ("cache_read_input_tokens", "cache_read_tokens"), ("cache_creation_input_tokens", "cache_write_tokens")):
                    if isinstance(u.get(k_src), int): ob[k_dst] = u[k_src]
                if isinstance((u.get("output_tokens_details") or {}).get("thinking_tokens"), int): ob["reasoning_tokens"] = u["output_tokens_details"]["thinking_tokens"]
                obs.append(ob)
                basis_cost = None
                for mname, mu in (o.get("modelUsage") or {}).items():
                    mo = {"scope_id": 6, "scope": "Model", "usage_status_id": 2, "usage_status": "Final", "basis": "claude.stream.result_usage.v1: modelUsage per model on the result line", "source_ref": self.source_ref(art, line, uid, ts, False),
                          "model_ref": {"name": mname, "provider_basis_id": 1, "provider_basis": "Reported"}, "data": mu}
                    if mu.get("provider"): mo["model_ref"]["provider_kind"] = mu["provider"]
                    for k_src, k_dst in (("inputTokens", "input_tokens"), ("outputTokens", "output_tokens"), ("cacheReadInputTokens", "cache_read_tokens"), ("cacheCreationInputTokens", "cache_write_tokens"), ("thinkingTokens", "reasoning_tokens")):
                        if isinstance(mu.get(k_src), int): mo[k_dst] = mu[k_src]
                    obs.append(mo); basis_cost = mu.get("costBasis") if len(o.get("modelUsage")) == 1 else None
                status = ((1 if (o.get("subtype") == "success" and o.get("is_error") is False) else 2), 1, "reported: result.subtype and result.is_error")
                extra = {"session_uid": sess, "stop_reason": o.get("stop_reason"), "usage_observations": obs, "data": o}
                if isinstance(o.get("total_cost_usd"), (int, float)):
                    co = {"total_cost": o["total_cost_usd"], "currency": "USD", "scope_id": 4, "scope": "Run", "source_ref": self.source_ref(art, line, uid, ts, False), "data": {"total_cost_usd": o["total_cost_usd"]}}
                    if basis_cost: co["cost_basis"] = basis_cost
                    extra["cost_observation"] = co
                pds = []
                for d in o.get("permission_denials") or []:
                    pds.append({"decision_id": 2, "decision": "Denied", "reported_decision": "denied", "scope_id": 4, "scope": "Run", "attribution": {"outcome_id": 3, "outcome": "Unattributed", "basis": "permission_denials entry; link to a tool call only if it names one"}, "source_ref": self.source_ref(art, line, uid, ts, False), "data": d})
                if pds: extra["permission_decisions"] = pds
                self.emit("prompt_turn", 2, art, line, ms, tb, bd, C, self.rule(C, "claude.stream.result->prompt_turn.completed"), original_time=ts, native_uid=uid, status=status, extra=extra)
            else:
                self.fallback(art, line, C, "claude.stream.fallback->source_record", f"{t}/{o.get('subtype')}" if o.get("subtype") else str(t), st, ts, uid, sess)

    # ------------------------------------------------------------------ Claude Code transcript (saved session file, bounded)
    def claude_transcript(self, art, lines):
        C = "claude-code-2.1.303-transcript"; st = {}
        admit = art["admit_response_uid"]; admitted_tools = set(); prompt_done = False
        fam = {}
        for line in lines:
            if line.parse_status != 1:
                self.opaque(art, line, C, st); continue
            o = line.obj; t = o.get("type"); ts = o.get("timestamp"); uid = o.get("uuid"); sess = o.get("sessionId")
            fam.setdefault(t, {"records": 0, "bytes": 0, "admitted": 0}); fam[t]["records"] += 1; fam[t]["bytes"] += len(line.raw)
            m = o.get("message") if isinstance(o.get("message"), dict) else None
            if t == "assistant" and m and m.get("id") == admit:
                fam[t]["admitted"] += 1
                ms, tb, bd = self.time_for(art, line, st, ts)
                final = m.get("stop_reason") is not None
                usage = {"scope_id": 2, "scope": "Model response", "usage_status_id": 2 if final else 1, "usage_status": "Final" if final else "Provisional",
                         "basis": "claude.transcript.usage_status.v1: stop_reason non-null -> final", "source_ref": self.source_ref(art, line, uid, ts, False),
                         "model_ref": {"name": m["model"], "provider_basis_id": 3, "provider_basis": "Unavailable"}, "data": m["usage"]}
                for k_src, k_dst in (("input_tokens", "input_tokens"), ("output_tokens", "output_tokens"), ("cache_read_input_tokens", "cache_read_tokens"), ("cache_creation_input_tokens", "cache_write_tokens")):
                    if isinstance(m["usage"].get(k_src), int): usage[k_dst] = m["usage"][k_src]
                if isinstance((m["usage"].get("output_tokens_details") or {}).get("thinking_tokens"), int): usage["reasoning_tokens"] = m["usage"]["output_tokens_details"]["thinking_tokens"]
                extra = {"session_uid": sess, "response_uid": m["id"], "model_ref": {"name": m["model"], "provider_basis_id": 3, "provider_basis": "Unavailable"}, "content_blocks": self.blocks(C, m["content"]),
                         "usage_observations": [usage], "basis": "claude.transcript.activity.v1: stop_reason non-null -> Completed", "data": o}
                if final: extra["stop_reason"] = m["stop_reason"]
                self.emit("model_response", 2 if final else 1, art, line, ms, tb, bd, C, self.rule(C, "claude.transcript.assistant->model_response"), original_time=ts, native_uid=uid, extra=extra)
                for b in m["content"]:
                    if b.get("type") == "tool_use":
                        admitted_tools.add(b["id"]); k, krule = self.kind(C, b["name"])
                        self.emit("tool_call", 1, art, line, ms, tb, bd, C, self.rule(C, "claude.transcript.assistant.tool_use->tool_call.requested"), original_time=ts, native_uid=uid, whole=False,
                                  extra={"session_uid": sess, "response_uid": m["id"], "tool_call_uid": b["id"], "tool_name": b["name"], "tool_kind_id": k, "tool_kind": self.kind_caption(k), "tool_kind_basis": krule, "arguments": b.get("input"), "data": b})
            elif t == "user" and m and isinstance(m.get("content"), str) and not prompt_done:
                fam[t]["admitted"] += 1; prompt_done = True
                ms, tb, bd = self.time_for(art, line, st, ts)
                self.emit("prompt_turn", 1, art, line, ms, tb, bd, C, self.rule(C, "claude.transcript.user.prompt->prompt_turn.submitted"), original_time=ts, native_uid=uid,
                          extra={"session_uid": sess, "turn_uid": o.get("promptId"), "content_blocks": self.blocks(C, m["content"]), "data": o})
            elif t == "user" and m and isinstance(m.get("content"), list) and any(b.get("type") == "tool_result" and b.get("tool_use_id") in admitted_tools for b in m["content"]):
                fam[t]["admitted"] += 1
                ms, tb, bd = self.time_for(art, line, st, ts); first = True
                for b in m["content"]:
                    if b.get("type") != "tool_result": continue
                    res = {"data": b.get("content")}
                    if "is_error" in b: res["error_reported"] = b["is_error"]
                    if "toolUseResult" in o: res["structured_result"] = o["toolUseResult"]
                    status = (0, 3, "is_error not reported on this tool_result block") if "is_error" not in b else ((2 if b["is_error"] else 1), 1, "reported: tool_result.is_error")
                    extra = {"session_uid": sess, "tool_call_uid": b["tool_use_id"], "tool_result": res, "data": o if first else b}
                    pd = o.get("permissionDecision")
                    if pd and len([x for x in m["content"] if x.get("type") == "tool_result"]) == 1:
                        extra["permission_decision"] = {"decision_id": {"accept": 1, "allow": 1, "deny": 2}.get(pd.get("decision"), 99), "decision": {"accept": "Allowed", "allow": "Allowed", "deny": "Denied"}.get(pd.get("decision"), "Other"), "reported_decision": pd.get("decision"), "decision_source": pd.get("source"), "reason_type": pd.get("reasonType"),
                                                        "scope_id": 1, "scope": "Tool call", "attribution": {"outcome_id": 1, "outcome": "Unique", "candidates": [b["tool_use_id"]], "basis": "record-level permissionDecision on a record with exactly one tool_result block"},
                                                        "source_ref": self.source_ref(art, line, uid, ts, False), "data": pd}
                    self.emit("tool_call", 2, art, line, ms, tb, bd, C, self.rule(C, "claude.transcript.user.tool_result->tool_call.result"), original_time=ts, native_uid=uid, whole=first, status=status, extra=extra)
                    first = False
            else:
                pass  # excluded with reason; inventoried below
        art["family_inventory"] = {k: dict(v, disposition=("admitted" if v["admitted"] == v["records"] else ("partially admitted" if v["admitted"] else "excluded: not admitted in prototype"))) for k, v in sorted(fam.items())}

    # ------------------------------------------------------------------ Codex rollout (saved session file)
    def codex_rollout(self, art, lines):
        C = "codex-cli-0.160.1-rollout"; st = {}
        calls = []       # custom_tool_call records seen: (turn_id, call_id, name, input)
        token_counts = []  # (line, obj) in current turn
        current_turn = None; session = self.spec["session_uid"]
        for line in lines:
            if line.parse_status != 1:
                self.opaque(art, line, C, st); continue
            o = line.obj; t = o.get("type"); ts = o.get("timestamp"); p = o.get("payload") or {}; pt = p.get("type")
            ms, tb, bd = self.time_for(art, line, st, ts)
            if t == "turn_context":
                current_turn = p.get("turn_id")
                self.fallback(art, line, C, "codex.rollout.fallback->source_record", "turn_context", st, ts, session=session); continue
            if t == "response_item" and pt == "message":
                blocks = self.blocks(C, p.get("content") or [])
                if p.get("role") == "user":
                    self.emit("prompt_turn", 1, art, line, ms, tb, bd, C, self.rule(C, "codex.rollout.user_message->prompt_turn.submitted"), original_time=ts, extra={"session_uid": session, "turn_uid": current_turn, "content_blocks": blocks, "data": o})
                elif p.get("role") == "assistant":
                    self.emit("model_response", 2, art, line, ms, tb, bd, C, self.rule(C, "codex.rollout.assistant_message->model_response"), original_time=ts, native_uid=p.get("id"),
                              extra={"session_uid": session, "turn_uid": current_turn, "response_uid": p.get("id"), "content_blocks": blocks, "reported_state": p.get("status"), "basis": "codex.rollout.activity.v1: a response_item message is written once after the response", "data": o})
                else:
                    self.fallback(art, line, C, "codex.rollout.fallback->source_record", f"response_item/message/{p.get('role')}", st, ts, session=session)
                continue
            if t == "response_item" and pt == "custom_tool_call":
                turn = (p.get("internal_chat_message_metadata_passthrough") or {}).get("turn_id")
                calls.append({"turn": turn, "call_id": p["call_id"], "name": p.get("name"), "input": p.get("input"), "line": line.index})
                k, krule = self.kind(C, p.get("name"))
                self.emit("tool_call", 1, art, line, ms, tb, bd, C, self.rule(C, "codex.rollout.custom_tool_call->tool_call.requested"), original_time=ts, native_uid=p.get("id"),
                          extra={"session_uid": session, "turn_uid": turn, "tool_call_uid": p["call_id"], "tool_name": p.get("name"), "tool_kind_id": k, "tool_kind": self.kind_caption(k), "tool_kind_basis": krule, "arguments": p.get("input"), "reported_state": p.get("status"), "data": o})
                continue
            if t == "response_item" and pt == "custom_tool_call_output":
                turn = (p.get("internal_chat_message_metadata_passthrough") or {}).get("turn_id")
                self.emit("tool_call", 2, art, line, ms, tb, bd, C, self.rule(C, "codex.rollout.custom_tool_call_output->tool_call.result"), original_time=ts, native_uid=p.get("id"),
                          status=(0, 3, "custom_tool_call_output reports no call-level status; per-command outcomes are on operations"),
                          extra={"session_uid": session, "turn_uid": turn, "tool_call_uid": p["call_id"], "tool_result": {"data": p.get("output")}, "data": o})
                continue
            if t == "event_msg" and pt == "item_completed":
                it = p.get("item") or {}; itype = it.get("type"); turn = p.get("turn_id")
                if itype == "UserMessage":
                    self.emit("prompt_turn", 1, art, line, ms, tb, bd, C, self.rule(C, "codex.rollout.item_completed.UserMessage->prompt_turn.submitted"), original_time=ts, native_uid=it.get("id"),
                              extra={"session_uid": session, "turn_uid": turn, "content_blocks": self.blocks(C, it.get("content")), "data": o}); continue
                if itype == "AgentMessage":
                    self.emit("model_response", 2, art, line, ms, tb, bd, C, self.rule(C, "codex.rollout.item_completed.AgentMessage->model_response"), original_time=ts, native_uid=it.get("id"),
                              extra={"session_uid": session, "turn_uid": turn, "response_uid": it.get("id"), "content_blocks": self.blocks(C, it.get("content")), "basis": "item_completed is terminal for the item", "data": o}); continue
                if itype == "CommandExecution":
                    cmd_text = (it.get("command") or [""])[-1]
                    cands = [c for c in calls if c["turn"] == turn and c["name"] == "exec" and isinstance(c["input"], str) and cmd_text in c["input"]]
                    outcome = 1 if len(cands) == 1 else (2 if len(cands) > 1 else 3)
                    attribution = {"outcome_id": outcome, "outcome": {1: "Unique", 2: "Ambiguous", 3: "Unattributed"}[outcome], "candidates": [c["call_id"] for c in cands], "scope_uid": turn,
                                   "basis": "codex.operation_attribution.v1: same turn_id, custom_tool_call.name == exec, command text occurs in call input"}
                    op = {"uid": it.get("id"), "argv": it.get("command"), "reported_state": it.get("status"), "attribution": attribution, "data": it}
                    for k in ("exit_code",):
                        if isinstance(it.get(k), int): op[k] = it[k]
                    for k in ("stdout", "stderr"):
                        if isinstance(it.get(k), str): op[k] = it[k]
                    status = ({"completed": 1, "failed": 2}.get(it.get("status"), 0), 1, "reported: item.status")
                    extra = {"session_uid": session, "turn_uid": turn, "tool_operation": op, "data": o}
                    if outcome == 1: extra["tool_call_uid"] = cands[0]["call_id"]
                    self.emit("tool_call", 3, art, line, ms, tb, bd, C, self.rule(C, "codex.rollout.CommandExecution->tool_call.operation"), original_time=ts, native_uid=it.get("id"), status=status, extra=extra); continue
            if t == "event_msg" and pt == "token_count":
                token_counts.append((line, o))
                self.fallback(art, line, C, "codex.rollout.fallback->source_record", "event_msg/token_count", st, ts, session=session); continue
            if t == "event_msg" and pt == "task_complete":
                obs = []
                for i, (tl, to) in enumerate(token_counts):
                    info = (to.get("payload") or {}).get("info") or {}; tu = info.get("total_token_usage") or {}
                    last = i == len(token_counts) - 1
                    ob = {"scope_id": 3, "scope": "Prompt turn", "usage_status_id": 2 if last else 1, "usage_status": "Final" if last else "Provisional",
                          "basis": "codex.rollout.token_count.v1: " + ("last cumulative token_count before task_complete" if last else "superseded by a later token_count in the same turn"),
                          "source_ref": self.source_ref(art, tl, None, to.get("timestamp"), False), "data": info}
                    for k_src, k_dst in (("input_tokens", "input_tokens"), ("output_tokens", "output_tokens"), ("cached_input_tokens", "cache_read_tokens"), ("cache_write_input_tokens", "cache_write_tokens"), ("reasoning_output_tokens", "reasoning_tokens"), ("total_tokens", "reported_total_tokens")):
                        if isinstance(tu.get(k_src), int): ob[k_dst] = tu[k_src]
                    obs.append(ob)
                self.emit("prompt_turn", 2, art, line, ms, tb, bd, C, self.rule(C, "codex.rollout.task_complete->prompt_turn.completed"), original_time=ts, status=(0, 3, "task_complete carries no status or error field"),
                          extra={"session_uid": session, "turn_uid": p.get("turn_id"), "usage_observations": obs, "data": o})
                token_counts = []; continue
            self.fallback(art, line, C, "codex.rollout.fallback->source_record", f"{t}/{pt}" if pt else str(t), st, ts, session=session)

    # ------------------------------------------------------------------ Codex exec stream (live output)
    def codex_stream(self, art, lines):
        C = "codex-cli-0.160.1-exec-stream"; st = {}; session = self.spec["session_uid"]
        for line in lines:
            if line.parse_status != 1:
                self.opaque(art, line, C, st); continue
            o = line.obj; t = o.get("type"); it = o.get("item") or {}
            ms, tb, bd = self.time_for(art, line, st, None)
            if t == "item.completed" and it.get("type") == "agent_message":
                self.emit("model_response", 2, art, line, ms, tb, bd, C, self.rule(C, "codex.stream.agent_message->model_response"), native_uid=it.get("id"),
                          extra={"session_uid": session, "response_uid": it["id"], "content_blocks": [{"block_index": 0, "block_type": "agent_message.text", "block_kind_id": 1, "block_kind": "Text", "basis": "item.text is a string", "text": it.get("text"), "data": it.get("text")}], "basis": "item.completed is terminal; response_uid is the stream-scoped item id", "data": o}); continue
            if t in ("item.started", "item.completed") and it.get("type") == "command_execution":
                op = {"uid": it.get("id"), "reported_state": it.get("status"), "attribution": {"outcome_id": 3, "outcome": "Unattributed", "candidates": [], "basis": "codex.stream_operation_attribution.v1: the exec stream reports no call id; item ids do not occur in the rollout (R13-08)"}, "data": it}
                if isinstance(it.get("exit_code"), int): op["exit_code"] = it["exit_code"]
                if isinstance(it.get("aggregated_output"), str): op["stdout"] = it["aggregated_output"]
                status = ({"completed": 1, "failed": 2}.get(it.get("status"), 0), 1, "reported: item.status")
                self.emit("tool_call", 3, art, line, ms, tb, bd, C, self.rule(C, "codex.stream.command_execution->tool_call.operation"), native_uid=it.get("id"), status=status, extra={"session_uid": session, "tool_operation": op, "data": o}); continue
            if t == "turn.completed":
                u = o.get("usage") or {}
                ob = {"scope_id": 3, "scope": "Prompt turn", "usage_status_id": 2, "usage_status": "Final", "basis": "codex.stream.turn_usage.v1: turn.completed reports the turn's usage once", "source_ref": self.source_ref(art, line, None, None, False), "data": u}
                for k_src, k_dst in (("input_tokens", "input_tokens"), ("output_tokens", "output_tokens"), ("cached_input_tokens", "cache_read_tokens"), ("cache_write_input_tokens", "cache_write_tokens"), ("reasoning_output_tokens", "reasoning_tokens")):
                    if isinstance(u.get(k_src), int): ob[k_dst] = u[k_src]
                self.emit("prompt_turn", 2, art, line, ms, tb, bd, C, self.rule(C, "codex.stream.turn_completed->prompt_turn.completed"), status=(0, 3, "turn.completed carries no status"), extra={"session_uid": session, "usage_observations": [ob], "data": o}); continue
            self.fallback(art, line, C, "codex.stream.fallback->source_record", str(t), st, session=session)

    # ------------------------------------------------------------------ Antigravity stream (live output)
    def agy_stream(self, art, lines):
        C = "antigravity-1.4.2-stream"; st = {}; tool_steps = []
        for line in lines:
            if line.parse_status != 1:
                self.opaque(art, line, C, st); continue
            o = line.obj; ev = o.get("event"); ms, tb, bd = self.time_for(art, line, st, None)
            if ev == "step_update":
                s = o["step_update"]; conv = s.get("conversation_id"); stype = s.get("step_type"); idx = s.get("step_index"); gen = f"{conv}#step{idx}"
                if stype == "user_input":
                    self.emit("prompt_turn", 1, art, line, ms, tb, bd, C, self.rule(C, "agy.stream.user_input->prompt_turn.submitted"), extra={"session_uid": conv, "data": o}); continue
                if stype == "agent_response":
                    u = s.get("usage") or {}
                    ob = {"scope_id": 2, "scope": "Model response", "usage_status_id": 0, "usage_status": "Unknown", "basis": "agy.stream.usage_status.v1: reported once with state DONE; provisional/final not distinguished by the vendor", "source_ref": self.source_ref(art, line, None, None, False), "data": u}
                    for k_src, k_dst in (("input_tokens", "input_tokens"), ("output_tokens", "output_tokens"), ("cache_read_tokens", "cache_read_tokens"), ("thinking_tokens", "reasoning_tokens"), ("total_tokens", "reported_total_tokens")):
                        if isinstance(u.get(k_src), int): ob[k_dst] = u[k_src]
                    self.emit("model_response", 2 if s.get("state") == "DONE" else 1, art, line, ms, tb, bd, C, self.rule(C, "agy.stream.agent_response->model_response"),
                              extra={"session_uid": conv, "response_uid": gen, "reported_state": s.get("state"), "usage_observations": [ob], "basis": "agy.stream activity: state DONE -> Completed; response_uid generated by antigravity.step_identity.v1 (source reports no response id); content unavailable at source", "data": o}); continue
                if stype == "tool":
                    name = s.get("tool_name"); k, krule = self.kind(C, name); ti = s.get("tool_info") or {}
                    if s.get("state") == "ACTIVE":
                        tool_steps.append({"uid": gen, "name": name})
                        self.emit("tool_call", 1, art, line, ms, tb, bd, C, self.rule(C, "agy.stream.tool.ACTIVE->tool_call.requested"),
                                  extra={"session_uid": conv, "tool_call_uid": gen, "tool_name": name, "tool_kind_id": k, "tool_kind": self.kind_caption(k), "tool_kind_basis": krule, "arguments": ti.get("parameters"), "reported_state": "ACTIVE", "data": o}); continue
                    if s.get("state") == "DONE":
                        if not any(t["uid"] == gen for t in tool_steps): tool_steps.append({"uid": gen, "name": name})
                        extra = {"session_uid": conv, "tool_call_uid": gen, "tool_name": name, "tool_kind_id": k, "tool_kind": self.kind_caption(k), "tool_kind_basis": krule, "arguments": ti.get("parameters"), "reported_state": "DONE", "data": o}
                        if "output" in ti: extra["tool_result"] = {"data": ti["output"], "reported_state": "DONE"}
                        self.emit("tool_call", 2, art, line, ms, tb, bd, C, self.rule(C, "agy.stream.tool.DONE->tool_call.result"), status=(0, 3, "DONE is a lifecycle state; no outcome, output or error reported" if "output" not in ti else "DONE with output; the stream reports no success/error flag"), extra=extra); continue
                self.fallback(art, line, C, "agy.stream.fallback->source_record", f"step_update/{stype}/{s.get('state')}", st, session=conv); continue
            if ev == "result":
                r = o["result"]; conv = r.get("conversation_id"); u = r.get("usage") or {}
                ob = {"scope_id": 4, "scope": "Run", "usage_status_id": 2, "usage_status": "Final", "basis": "agy.stream.result_usage.v1: the result line reports run usage once", "source_ref": self.source_ref(art, line, None, None, False), "data": u}
                for k_src, k_dst in (("input_tokens", "input_tokens"), ("output_tokens", "output_tokens"), ("cache_read_tokens", "cache_read_tokens"), ("thinking_tokens", "reasoning_tokens"), ("total_tokens", "reported_total_tokens")):
                    if isinstance(u.get(k_src), int): ob[k_dst] = u[k_src]
                pds = []
                for d in r.get("denied_actions") or []:
                    target = {"command": "run_command"}.get(d.get("action"))
                    cands = [t["uid"] for t in tool_steps if t["name"] == target]
                    pds.append({"decision_id": 2, "decision": "Denied", "reported_decision": "denied (derived label: denied_actions entry)", "decision_source": "cli", "scope_id": 4, "scope": "Run",
                                "attribution": {"outcome_id": 3, "outcome": "Unattributed", "candidates": cands, "scope_uid": conv, "basis": "agy.denial_candidates.v1: denied_actions carries no step reference; candidates are tool steps whose tool_name maps from action"},
                                "source_ref": self.source_ref(art, line, None, None, False), "data": d})
                extra = {"session_uid": conv, "usage_observations": [ob], "data": o}
                if pds: extra["permission_decisions"] = pds
                self.emit("prompt_turn", 2, art, line, ms, tb, bd, C, self.rule(C, "agy.stream.result->prompt_turn.completed"), status=((1 if r.get("status") == "SUCCESS" else 2), 1, "reported: result.status (run lifecycle status, not per-tool outcome)"), extra=extra); continue
            self.fallback(art, line, C, "agy.stream.fallback->source_record", str(ev), st)

    def opaque_text(self, art, lines):
        C = art["contract"]; st = {}
        for line in lines:
            if line.parse_status == 1:
                # a plain-text artifact that happens to parse as JSON is still retained under the opaque rule? No: parsed is parsed.
                self.fallback(art, line, C, art["fallback_rule"], "text", st)
            else:
                self.opaque(art, line, C, st)

    @staticmethod
    def kind_caption(k):
        return {0: "Unknown", 1: "read", 2: "edit", 3: "delete", 4: "move", 5: "search", 6: "execute", 7: "think", 8: "fetch", 9: "switch_mode", 99: "other"}[k]

    # ------------------------------------------------------------------ run
    def run(self):
        for art in self.spec["sources"]:
            data, lines = read_lines(art["path"])
            art["sha256"] = sha256_bytes(data); art["bytes"] = len(data); art["records"] = len(lines)
            art["opaque_records"] = sum(1 for l in lines if l.parse_status != 1)
            art["trailing_newline"] = data.endswith(b"\n")
            getattr(self, art["profile"])(art, lines)
        os.makedirs(self.out, exist_ok=True)
        with open(os.path.join(self.out, "events.jsonl"), "w") as f:
            for ev in self.events: f.write(json.dumps(ev, ensure_ascii=False) + "\n")
        evdir = os.path.join(self.out, "events"); os.makedirs(evdir, exist_ok=True)
        for ev in self.events:
            json.dump(ev, open(os.path.join(evdir, f"{ev['journal']['sequence']:04d}.json"), "w"), ensure_ascii=False)
        run = {
            "package": {"kind": "agent-journal/ocsf-prototype-package", "version": "0.0.0-prototype", "non_production": True,
                         "note": "Representative non-production package. events.jsonl holds ONLY conforming OCSF events; this file is the manifest."},
            "synthetic": self.spec.get("synthetic"),
            "run": {"run_uid": self.spec["run_uid"], "invocation_index": self.spec["invocation_index"], "session_uid": self.spec["session_uid"],
                    "capture_start": self.spec["capture_start"], "transformation_time": self.capture_text, "cli": self.spec["cli"], "launch_settings": self.spec.get("launch_settings"),
                    "children": []},
            "ocsf": {"version": self.pin.ocsf_version, "core_commit": self.spec["pins"]["ocsf_schema_commit"], "compiler_commit": self.spec["pins"]["compiler_commit"], "toolkit": self.spec["pins"]["toolkit"],
                      "compiled_schema_sha256": self.pin.compiled_sha256, "compiled_schema_location": "content-addressed, NOT embedded (D6 open: measured in measure.py)"},
            "extension": {"name": EXT_NAME, "uid": self.pin.ext_uid, "uid_status": "PROVISIONAL, UNREGISTERED prototype assignment", "version": self.pin.ext_version,
                           "source_sha256": self.pin.extension_source_sha256, "source_files": self.pin.source_files,
                           "classes": {n: {"class_uid": c["class_uid"], "category_uid": c["category_uid"]} for n, c in self.pin.classes.items()},
                           "reader_rule": "resolve class names through this pin; refuse a package whose pinned uid/class table differs from the reader's compiled schema"},
            "time_policy": {"time": "source-reported time when the record has one (time_basis 1); else the latest preceding reported time in the same artifact, else capture start (time_basis 2); metadata.original_time holds the source text verbatim; ordering is journal.sequence, never time", "severity_id": "always 0 Unknown: Journal convention, not a vendor assertion"},
            "mapping_contracts": {uid: {"sha256": self.contract_sha(name), "rules_executed": self.executed_rules.get(uid, {}), "embedded": self.contracts[name]} for name, c in self.contracts.items() for uid in [c["contract_uid"]]},
            "sources": [{k: v for k, v in art.items() if k != "path"} | {"path_at_capture": art["path"]} for art in self.spec["sources"]],
            "events": {"count": len(self.events), "by_class": self.count_by("class_name"), "by_type": self.count_by("type_name")},
            "excluded": self.spec.get("excluded", []),
        }
        json.dump(run, open(os.path.join(self.out, "run.json"), "w"), indent=1, ensure_ascii=False)
        print(f"wrote {len(self.events)} events to {self.out}")

    def count_by(self, key):
        c = {}
        for ev in self.events: c[ev[key]] = c.get(ev[key], 0) + 1
        return dict(sorted(c.items()))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", required=True); ap.add_argument("--compiled", required=True); ap.add_argument("--extension-dir", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--steward-plans", default=None, help="prefix for relative source paths in the spec")
    a = ap.parse_args()
    spec = json.load(open(a.spec))
    for s in spec["sources"]:
        if a.steward_plans and not os.path.isabs(s["path"]): s["path"] = os.path.join(a.steward_plans, s["path"])
    Adapter(spec, Pin(a.compiled, a.extension_dir), a.out).run()

if __name__ == "__main__":
    main()
