#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12"
# dependencies = []
# [tool.uv]
# no-build = true
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Run macOS reset checks with bounded configuration and retained stall evidence."""

from pathlib import Path

import gradle_graph

ROOT = Path(__file__).resolve().parents[3]
COMMAND = (
    "./gradlew",
    "--no-parallel",
    "--max-workers=2",
    "-Dorg.gradle.jvmargs=-Xmx4g",
    ":distribution:cli:test",
    "--tests",
    "*NativeResetListenerTest",
    "--tests",
    "*NativeResetPeerAcceptanceTest",
    "--tests",
    "*ResetLaunchObservationTest",
    "--console=plain",
    "--info",
    # Bind diagnostic attachment to a new daemon owned by this invocation.
    "--no-daemon",
)


if __name__ == "__main__":
    result = gradle_graph.run(COMMAND, ROOT, ROOT / "build/reports/native/reset-checks")
    raise SystemExit(result.exitCode)
