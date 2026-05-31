#!/usr/bin/env python3
"""Record an action in the workflow history.
Usage: python3 record_action.py <project_root> <action> <detail_json> <outcome> [state_change]
History is bounded to 50 entries.
Output JSON: {"ok": true, "history_length": 12}
"""
import json, os, sys
from datetime import datetime, timezone
MAX_HISTORY = 50

def main():
    if len(sys.argv)<5:
        json.dump({"ok":False,"error":"Usage: record_action.py <project_root> <action> <detail_json> <outcome> [state_change]"}, sys.stdout); sys.exit(1)
    pr = os.path.abspath(sys.argv[1]); action=sys.argv[2]; rd=sys.argv[3]; outcome=sys.argv[4]
    sc = sys.argv[5] if len(sys.argv)>5 else None
    sf = os.path.join(pr, ".agent-workflow", "state.json")
    if not os.path.exists(sf):
        json.dump({"ok":False,"error":f"State file not found: {sf}"}, sys.stdout); sys.exit(1)
    try: detail = json.loads(rd)
    except json.JSONDecodeError as e:
        json.dump({"ok":False,"error":f"Invalid detail JSON: {e}"}, sys.stdout); sys.exit(1)
    with open(sf) as f: state = json.load(f)
    entry = {"timestamp":datetime.now(timezone.utc).isoformat(),"action":action,"detail":detail,"outcome":outcome}
    if sc: entry["state_change"] = sc
    h = state.get("history",[])
    h.append(entry)
    if len(h) > MAX_HISTORY: h = h[-MAX_HISTORY:]
    state["history"] = h
    state["updated_at"] = datetime.now(timezone.utc).isoformat()
    with open(sf,"w") as f: json.dump(state, f, indent=2)
    json.dump({"ok":True,"history_length":len(h)}, sys.stdout)

if __name__ == "__main__":
    main()
