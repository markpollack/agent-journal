#!/usr/bin/env python3
"""Prototype reader pin check (D1): a reader built against compiled schema X opens a package pinned to assignment Y.

The reader resolves class NAMES through the package's run.json pin (extension name, uid, version, class table, compiled
schema hash) and compares them with its own compiled schema. Any mismatch is a refusal with a named reason: numeric ids
are never remapped. Exit 0 = accepted, 2 = refused.
Usage: reader_check.py --pkg DIR --compiled COMPILED.json
"""
import argparse, hashlib, json, os, sys

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--pkg", required=True); ap.add_argument("--compiled", required=True)
    a = ap.parse_args()
    run = json.load(open(os.path.join(a.pkg, "run.json"))); pin = run["extension"]
    cb = open(a.compiled, "rb").read(); c = json.loads(cb); reasons = []
    ext = c["extensions"].get(pin["name"])
    if not ext: reasons.append(f"reader schema has no extension named {pin['name']}")
    else:
        if ext["uid"] != pin["uid"]: reasons.append(f"extension uid mismatch: package pinned {pin['uid']}, reader schema has {ext['uid']}")
        if ext["version"] != pin["version"]: reasons.append(f"extension version mismatch: package {pin['version']}, reader {ext['version']}")
    mine = {cls["name"]: cls["uid"] for k, cls in c["classes"].items() if k.startswith(pin["name"] + "/")}
    for name, p in pin["classes"].items():
        if name not in mine: reasons.append(f"class {name} unknown to reader")
        elif mine[name] != p["class_uid"]: reasons.append(f"class {name}: package class_uid {p['class_uid']} != reader {mine[name]}")
    if hashlib.sha256(cb).hexdigest() != run["ocsf"]["compiled_schema_sha256"]:
        reasons.append("compiled schema hash differs from the package pin (refuse unless the class table and uid match exactly; here they " + ("do)" if not reasons else "do not)"))
    hard = [r for r in reasons if not r.startswith("compiled schema hash")]
    events = [json.loads(l) for l in open(os.path.join(a.pkg, "events.jsonl"))]
    by_uid = {p["class_uid"]: n for n, p in pin["classes"].items()}
    unknown = [e["class_uid"] for e in events if e["class_uid"] not in by_uid]
    if unknown: hard.append(f"{len(unknown)} events carry class_uid values outside the pinned table: {sorted(set(unknown))}")
    if hard:
        print("REFUSED:", "; ".join(hard)); sys.exit(2)
    dispatched = {}
    for e in events: dispatched[by_uid[e["class_uid"]]] = dispatched.get(by_uid[e["class_uid"]], 0) + 1
    print("ACCEPTED: dispatched by symbolic class name via pin:", dispatched, ("(hash note: " + reasons[0] + ")") if reasons else "")

if __name__ == "__main__":
    main()
