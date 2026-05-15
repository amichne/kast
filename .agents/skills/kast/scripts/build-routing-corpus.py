#!/usr/bin/env python3
"""Entrypoint for the canonical Kast routing corpus builder."""

from __future__ import annotations

from pathlib import Path
import runpy


def main() -> None:
    maintenance_script = (
        Path(__file__).resolve().parents[1]
        / "fixtures"
        / "maintenance"
        / "scripts"
        / "build-routing-corpus.py"
    )
    runpy.run_path(str(maintenance_script), run_name="__main__")


if __name__ == "__main__":
    main()
