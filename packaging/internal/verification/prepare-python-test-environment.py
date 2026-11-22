#!/usr/bin/env python3
"""Provision the build-owned Python environment for routine schema tests.

The locked uv project in config/python owns the dependencies. `uv sync --locked` installs exactly
its lockfile into build/python-tests/env; a changed pyproject without a refreshed lock rejects.
"""

from pathlib import Path
import os
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[3]
ENVIRONMENT = ROOT / "build/python-tests/env"
PROJECT = ROOT / "config/python"


def main() -> int:
    uv = shutil.which("uv")
    if uv is None:
        print(
            "prepare-python-test-environment: UV_UNAVAILABLE: install the uv version pinned in "
            "config/python/uv.toml, for example through `mise install`",
            file=sys.stderr,
        )
        return 2
    environment = {**os.environ, "UV_PROJECT_ENVIRONMENT": str(ENVIRONMENT)}
    command = [uv, "sync", "--locked", "--no-install-project", "--project", str(PROJECT)]
    return subprocess.run(command, env=environment, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
