#!/usr/bin/env python3
"""Provision the build-owned Python environment for routine schema tests."""

from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
ENVIRONMENT = ROOT / "build/python-tests/env"
REQUIREMENTS = ROOT / "experiments/host-observation/requirements-test.txt"


def main() -> None:
    subprocess.run([sys.executable, "-m", "venv", str(ENVIRONMENT)], check=True)
    subprocess.run(
        [str(ENVIRONMENT / "bin/python3"), "-m", "pip", "install", "--disable-pip-version-check", "-r", str(REQUIREMENTS)],
        check=True,
    )


if __name__ == "__main__":
    main()
