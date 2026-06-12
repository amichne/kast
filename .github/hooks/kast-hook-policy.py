#!/usr/bin/env python3
from __future__ import annotations

import runpy
from pathlib import Path

policy = Path(__file__).resolve().parents[2] / "kast-copilot-plugin" / "hooks" / "kast-hook-policy.py"
runpy.run_path(str(policy), run_name="__main__")
