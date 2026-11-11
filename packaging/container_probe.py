#!/usr/bin/env python3
"""Qualify native workspace containers in an explicitly supplied disposable installation."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

type JsonValue = None | bool | int | float | str | list[JsonValue] | dict[str, JsonValue]


@dataclass(frozen=True)
class ContainerProbeReceipt:
    controlSha256: str
    runtimeSha256: str
    cases: tuple[str, ...]


def contains_name(value: JsonValue, name: str) -> bool:
    if isinstance(value, dict):
        return value.get("name") == name or any(
            contains_name(item, name) for item in value.values()
        )
    if isinstance(value, list):
        return any(contains_name(item, name) for item in value)
    return False


def probe(
    root: Path, fixture: Path, archive: Path, digest: str, evidence: Path
) -> ContainerProbeReceipt:
    home = root / "state/home"
    command = home / ".local/bin/kast"
    if not command.is_file() or not fixture.is_dir() or evidence.exists():
        raise ValueError("disposable installation, fixture, and fresh evidence required")
    evidence.mkdir(parents=True)
    environment = {"HOME": str(home), "PATH": os.defpath}
    cases: list[str] = []

    def call(label: str, *arguments: str, expected_exit: int = 0) -> dict[str, JsonValue]:
        result = subprocess.run(
            [str(command), *arguments],
            cwd=fixture,
            env=environment,
            capture_output=True,
            timeout=1080,
            check=False,
        )
        _ = (evidence / f"{label}.stdout").write_bytes(result.stdout)
        _ = (evidence / f"{label}.stderr").write_bytes(result.stderr)
        _ = (evidence / f"{label}.exit").write_text(str(result.returncode) + "\n")
        if result.returncode != expected_exit:
            raise RuntimeError(
                f"{label}: exit {result.returncode}, expected {expected_exit}; see {evidence}"
            )
        value: JsonValue = json.loads(result.stdout)
        if not isinstance(value, dict):
            raise RuntimeError(f"{label}: document required")
        cases.append(label)
        return value

    _ = call(
        "import",
        "container",
        "import-runtime",
        "--archive",
        str(archive),
        "--sha256",
        digest,
        "--json",
    )
    created = call(
        "create",
        "container",
        "create",
        "--name",
        "native-container-proof",
        "--root",
        str(fixture),
        "--json",
    )
    record = created.get("container")
    if created.get("type") != "created" or not isinstance(record, dict):
        raise RuntimeError("create: typed creation record required")
    identity = record.get("id")
    if not isinstance(identity, str) or not re.fullmatch("[a-f0-9]{32}", identity):
        raise RuntimeError("create: exact container id required")
    query = (
        "query",
        "--name",
        "invokeCallback",
        "--kind",
        "function",
        "--in",
        "invocation",
        "--fields",
        "name",
        "--json",
    )
    try:
        cold = call("cold-query", *query)
        if cold.get("type") != "complete" or not contains_name(cold, "invokeCallback"):
            raise RuntimeError("cold-query: independent compiler result missing")
        _ = call("stop", "container", "stop", identity, "--json")
        stopped = call("sticky-stop", *query, expected_exit=1)
        if stopped.get("type") != "rejected" or stopped.get("failure") != "STOPPED":
            raise RuntimeError("sticky-stop: finite stopped failure required")
        _ = call("resume", "container", "resume", identity, "--json")
        resumed = call("resumed-query", *query)
        if resumed.get("type") != "complete" or not contains_name(resumed, "invokeCallback"):
            raise RuntimeError("resumed-query: independent compiler result missing")
    finally:
        _ = call("cleanup-stop", "container", "stop", identity, "--json")
    _ = call("remove", "container", "rm", identity, "--json")
    missing = call("removed-query", *query, expected_exit=1)
    if missing.get("type") != "rejected" or missing.get("failure") != "NOT_FOUND":
        raise RuntimeError(
            "removed-query: container must not be recreated or routed to desktop IDEA"
        )
    if not (fixture / "settings.gradle.kts").is_file():
        raise RuntimeError("remove: workspace was not preserved")
    with command.open("rb") as content:
        control_digest = hashlib.file_digest(content, "sha256").hexdigest()
    result = ContainerProbeReceipt(control_digest, digest, tuple(cases))
    _ = (evidence / "receipt.json").write_text(json.dumps(asdict(result), indent=2) + "\n")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "fixture", "archive", "evidence"):
        _ = parser.add_argument("--" + name, type=Path, required=True)
    _ = parser.add_argument("--sha256", required=True)
    args = parser.parse_args()
    result = probe(args.root, args.fixture, args.archive, args.sha256, args.evidence)
    print(json.dumps(asdict(result)))


if __name__ == "__main__":
    main()
