#!/usr/bin/env python3
"""Measure bytes: native sources, events.jsonl, normalized-only vs residual (whole-record and native-fragment data),
run.json, extension source, compiled extension closure (extension classes/objects plus the core objects they reach).
Usage: measure.py --pkg DIR --compiled COMPILED.json --extension-dir DIR
"""
import argparse, copy, json, os

RESIDUAL_KEYS = {"data", "raw_data", "raw_bytes_b64", "structured_result"}

def strip_residual(ev):
    def walk(v):
        if isinstance(v, dict): return {k: walk(x) for k, x in v.items() if k not in RESIDUAL_KEYS}
        if isinstance(v, list): return [walk(x) for x in v]
        return v
    return walk(ev)

def closure(c, ext):
    objs = c["objects"]; seen = set(); todo = [k for k in objs if k.startswith(ext + "/")]
    classes = {k: v for k, v in c["classes"].items() if k.startswith(ext + "/")}
    for cls in classes.values():
        for a in cls["attributes"].values():
            if a.get("object_type"): todo.append(a["object_type"])
    while todo:
        k = todo.pop()
        if k in seen: continue
        key = k if k in objs else (ext + "/" + k if (ext + "/" + k) in objs else k)
        if key not in objs: continue
        seen.add(key)
        for a in objs[key].get("attributes", {}).values():
            if a.get("object_type"): todo.append(a["object_type"])
    ext_objs = {k: objs[k] for k in seen if k.startswith(ext + "/")}; core_objs = {k: objs[k] for k in seen if not k.startswith(ext + "/")}
    return classes, ext_objs, core_objs

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--pkg", required=True); ap.add_argument("--compiled", required=True); ap.add_argument("--extension-dir", required=True)
    a = ap.parse_args()
    run = json.load(open(os.path.join(a.pkg, "run.json"))); ev_bytes = os.path.getsize(os.path.join(a.pkg, "events.jsonl"))
    events = [json.loads(l) for l in open(os.path.join(a.pkg, "events.jsonl"))]
    normalized = sum(len(json.dumps(strip_residual(e), ensure_ascii=False)) + 1 for e in events)
    env_only = sum(len(json.dumps({k: e[k] for k in ("class_uid","class_name","category_uid","category_name","activity_id","activity_name","type_uid","type_name","severity_id","severity","time","time_dt","metadata","cli_agent","journal","source_ref")}, ensure_ascii=False)) + 1 for e in events)
    c = json.load(open(a.compiled)); classes, ext_objs, core_objs = closure(c, "agentic_cli")
    ext_src = sum(f["bytes"] for f in run["extension"]["source_files"])
    out = {
        "native_sources": {s["artifact"]: {"bytes": s["bytes"], "records": s["records"]} for s in run["sources"]},
        "native_total_bytes": sum(s["bytes"] for s in run["sources"]),
        "events_jsonl_bytes": ev_bytes, "event_count": len(events),
        "normalized_only_bytes (residual data/raw/structured_result removed)": normalized,
        "envelope_only_bytes (OCSF envelope + metadata + cli_agent + journal + source_ref)": env_only,
        "residual_bytes (events - normalized)": ev_bytes - normalized,
        "run_json_bytes": os.path.getsize(os.path.join(a.pkg, "run.json")),
        "run_json_embedded_contracts_bytes": len(json.dumps({k: v["embedded"] for k, v in run["mapping_contracts"].items()})),
        "extension_source_bytes": ext_src,
        "compiled_schema_total_bytes": os.path.getsize(a.compiled),
        "compiled_extension_closure_bytes": {"extension_classes": len(json.dumps(classes)), "extension_objects": len(json.dumps(ext_objs)), "reached_core_objects": len(json.dumps(core_objs)), "reached_core_object_names": sorted(core_objs)},
        "ratios": {"events/native": round(ev_bytes / max(1, sum(s["bytes"] for s in run["sources"])), 2), "envelope/events": round(env_only / ev_bytes, 2)},
        "note": "The whole native record is retained once per line (is_whole_record), so residual bytes >= native bytes; normalized fields duplicate parts of it. Transcript admission is bounded; excluded families are counted in run.json family_inventory, not here."
    }
    json.dump(out, open(os.path.join(a.pkg, "measure.json"), "w"), indent=1)
    print(json.dumps({k: v for k, v in out.items() if k not in ("note", "compiled_extension_closure_bytes")}))
    print("closure:", {k: v for k, v in out["compiled_extension_closure_bytes"].items() if k != "reached_core_object_names"})

if __name__ == "__main__":
    main()
