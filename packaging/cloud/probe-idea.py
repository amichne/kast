#!/usr/bin/env python3
"""Bound actual vendor IDE startup without accepting first-run choices or asserting readiness."""
import argparse
import json
import os
from pathlib import Path
import resource
import shutil
import signal
import subprocess
import time

from bootstrap import verify_idea

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--idea-home", required=True, type=Path)
parser.add_argument("--root", required=True, type=Path, help="New owned probe directory outside the repository")
parser.add_argument("--mode", required=True, choices=("headless", "virtual-display"))
parser.add_argument("--seconds", type=int, default=45)
args = parser.parse_args()
root = args.root.resolve()
repositories = [parent for parent in Path(__file__).resolve().parents if (parent / ".git").exists()]
if root.exists() or any(root.is_relative_to(repository) for repository in repositories) or not 5 <= args.seconds <= 300:
    raise SystemExit("Use a new probe directory outside the repository and a 5..300 second bound")
home = args.idea_home.resolve(strict=True)
pins = json.loads(Path(__file__).with_name("pins.json").read_text())
verify_idea(home, pins["artifacts"]["idea"]["build"])
product = json.loads((home / "product-info.json").read_text())
launches = [item for item in product["launch"] if item["os"] == "Linux" and item["arch"] in ("amd64", "x86_64")]
if len(launches) != 1:
    raise SystemExit("One exact Linux x86_64 vendor launcher required")
launch = launches[0]
executable = (home / launch["launcherPath"]).resolve(strict=True)
options = (home / launch["vmOptionsFilePath"]).resolve(strict=True)
if not executable.is_relative_to(home) or not options.is_relative_to(home):
    raise SystemExit("Vendor launcher paths must stay inside the selected home")
if args.mode == "virtual-display" and shutil.which("xvfb-run") is None:
    raise SystemExit("Virtual display mode requires Xvfb and Xauth; use the prerequisite image")
root.mkdir(parents=True, mode=0o700)
for name in ("home", "config", "system", "plugins", "logs", "runtime"):
    (root / name).mkdir(mode=0o700)
vm = [line for line in options.read_text().splitlines() if not line.startswith(("-Xmx", "-Xms"))]
vm += ["-Xmx768m", f"-Djava.awt.headless={'true' if args.mode == 'headless' else 'false'}", f"-Duser.home={root}/home"]
vm += [f"-Didea.{name}.path={root}/{name}" for name in ("config", "system", "plugins")]
vm += [f"-Didea.log.path={root}/logs"]
(root / "probe.vmoptions").write_text("\n".join(vm) + "\n")
env = {**os.environ, "HOME": str(root / "home"), "XDG_RUNTIME_DIR": str(root / "runtime"),
       "XDG_CONFIG_HOME": str(root / "config"), "XDG_DATA_HOME": str(root / "home/.local/share"),
       "IDEA_VM_OPTIONS": str(root / "probe.vmoptions")}
env.pop("DISPLAY", None)
command = [str(executable), "nosplash", "dontReopenProjects"]
if args.mode == "virtual-display":
    command = ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24 -nolisten tcp", *command]
started = time.monotonic()
before = resource.getrusage(resource.RUSAGE_CHILDREN)
def group_exists(identifier):
    try:
        os.killpg(identifier, 0)
        return True
    except ProcessLookupError:
        return False


with (root / "launcher.log").open("w") as log:
    child = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        child.wait(timeout=args.seconds)
        outcome = "EXITED"
    except subprocess.TimeoutExpired:
        outcome = "BOUNDED_TIMEOUT"
    finally:
        # Retire only this probe's process group, including its virtual display.
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        grace = time.monotonic() + 5
        while group_exists(child.pid) and time.monotonic() < grace:
            child.poll()
            time.sleep(0.05)
        forced = group_exists(child.pid)
        if forced:
            os.killpg(child.pid, signal.SIGKILL)
        child.wait(timeout=5)
        teardown = "SIGKILL_REQUESTED" if forced else "GROUP_ABSENT"
after = resource.getrusage(resource.RUSAGE_CHILDREN)
receipt = {"type": "IDE_STARTUP_PROBE", "mode": args.mode, "ideBuild": product["buildNumber"],
    "outcome": outcome, "exit": child.returncode, "wallSeconds": round(time.monotonic() - started, 2),
    "teardown": teardown, "groupAbsentAfterWait": not group_exists(child.pid),
    "childUserCpuSeconds": after.ru_utime - before.ru_utime,
    "childSystemCpuSeconds": after.ru_stime - before.ru_stime, "peakChildRssKiB": after.ru_maxrss,
    "costScope": "Waited launcher process only; virtual-display wrapper excludes aggregate IDE/Xvfb descendant CPU and RAM",
    "pluginsInstalled": False, "importReadiness": "NOT_OBSERVED", "semanticReadiness": "NOT_OBSERVED",
    "firstRunChoicesAccepted": False, "logs": str(root / "logs")}
(root / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps(receipt))
if not receipt["groupAbsentAfterWait"]:
    raise SystemExit("Owned process group absence is unproven; inspect teardown and retire the disposable container")
