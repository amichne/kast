#!/usr/bin/env python3
"""Explicitly load/unload the compiled hosted plugin in one existing IDEA process."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("load", "unload"))
    parser.add_argument("--idea-contents", type=Path, required=True)
    parser.add_argument("--plugin-path", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    launcher = args.idea_contents / "MacOS/idea"
    rows = subprocess.check_output(["ps", "-ww", "-axo", "pid=,command="], text=True).splitlines()
    pids = [int(row.split(maxsplit=1)[0]) for row in rows if len(row.split(maxsplit=1)) == 2 and row.split(maxsplit=1)[1] == str(launcher)]
    if len(pids) != 1:
        raise SystemExit("EXACT_RUNNING_HOST_UNAVAILABLE")
    evidence = Path(tempfile.mkdtemp(prefix="kast-ide-management-"))
    print(f"Evidence: {evidence}", flush=True)
    target = evidence / "input.json"
    target.write_text(json.dumps(dict(action=args.action, root=str(args.root.resolve(strict=True)),
                                    pluginPath=str(args.plugin_path.resolve(strict=True)), hostPid=pids[0])))
    template = (Path(__file__).parent / "hosted-endpoint-manager.kts.template").read_text()
    script = evidence / "manage.kts"
    script.write_text(template.replace("@INPUT_BASE64@", base64.b64encode(str(target).encode()).decode()))
    subprocess.run([str(launcher), "ideScript", str(script)], check=True, timeout=45, stdout=subprocess.DEVNULL)
    deadline = time.monotonic() + 45
    while not (evidence / "management.json").exists():
        if time.monotonic() >= deadline:
            raise SystemExit("MANAGEMENT_UNCONFIRMED")
        time.sleep(0.1)
    answer = json.loads((evidence / "management.json").read_text())
    print(json.dumps(answer))
    return 0 if answer.get("outcome") == "COMPLETED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
