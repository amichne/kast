#!/usr/bin/env python3
"""Update a specific section of the workflow state.
Usage: python3 update_state.py <project_root> <dotpath> <json_value>
When value is a dict and target is also a dict, fields are merged (not replaced).
Output JSON: {"ok": true, "path": "...", "old_value": ..., "new_value": ...}
"""
import json, os, sys
from datetime import datetime, timezone

def main():
    if len(sys.argv)<4:
        json.dump({"ok":False,"error":"Usage: update_state.py <project_root> <dotpath> <json_value>"}, sys.stdout); sys.exit(1)
    pr = os.path.abspath(sys.argv[1]); dp = sys.argv[2]; rv = sys.argv[3]
    sf = os.path.join(pr, ".agent-workflow", "state.json")
    if not os.path.exists(sf):
        json.dump({"ok":False,"error":f"State file not found: {sf}"}, sys.stdout); sys.exit(1)
    try: nv = json.loads(rv)
    except json.JSONDecodeError as e:
        json.dump({"ok":False,"error":f"Invalid JSON value: {e}"}, sys.stdout); sys.exit(1)
    with open(sf) as f: state = json.load(f)
    parts = dp.split("."); cur = state
    for p in parts[:-1]:
        if isinstance(cur,dict) and p in cur: cur = cur[p]
        else: json.dump({"ok":False,"error":f"Path not found: {dp}"}, sys.stdout); sys.exit(1)
    lk = parts[-1]
    if not isinstance(cur,dict):
        json.dump({"ok":False,"error":f"Cannot set key on non-dict at: {dp}"}, sys.stdout); sys.exit(1)
    ov = cur.get(lk)
    if isinstance(nv,dict) and isinstance(cur.get(lk),dict): cur[lk].update(nv)
    else: cur[lk] = nv
    state["updated_at"] = datetime.now(timezone.utc).isoformat()
    with open(sf,"w") as f: json.dump(state, f, indent=2)
    json.dump({"ok":True,"path":dp,"old_value":ov,"new_value":nv}, sys.stdout)

if __name__ == "__main__":
    main()
