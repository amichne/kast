#!/usr/bin/env python3
"""Validate or execute an externally supplied bounded Pi plan; never imports credentials."""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--plan", required=True, type=Path)
parser.add_argument("--execute", action="store_true")
args = parser.parse_args()
plan = json.loads(args.plan.read_text())
repository = Path(__file__).resolve().parents[2]
workspace = Path(plan["workspaceRoot"]).resolve()
output = Path(plan["outputRoot"]).resolve()
if workspace == repository or repository in workspace.parents:
    raise SystemExit("Use an independent fixture/source-only workspace")
if output == workspace or workspace in output.parents:
    raise SystemExit("Keep evaluation output outside participant context")
for name in ("authPath", "modelsStorePath"):
    credential = Path(plan[name]).resolve()
    if credential == workspace or workspace in credential.parents:
        raise SystemExit("Keep authentication and model storage outside participant context")
if args.execute:
    if platform.system() == "Linux":
        raise SystemExit("NATIVE_RUNTIME_UNAVAILABLE: current Kast installer/selected-IDE admission requires macOS; Linux execution is unqualified")
    if not Path(plan["authPath"]).is_file():
        raise SystemExit("APPROVED_AUTHENTICATION_UNAVAILABLE: supply the supported cloud credential path; never copy Mac OAuth")
    # The worker performs exact native tools and provider admission. This wrapper
    # never turns a configuration check or status observation into readiness.
subprocess.run(["node", str(repository / "experiments/host-observation/run_pi_evaluation.mjs"),
                "--execute" if args.execute else "--plan", str(args.plan.resolve())],
               env={**os.environ, "PI_EVAL_PLAN": str(args.plan.resolve())}, check=True)
