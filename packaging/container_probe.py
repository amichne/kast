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

from composed_host_probe import HostIdentity, live_host
from composition_platform import host_platform

type JsonValue = None | bool | int | float | str | list[JsonValue] | dict[str, JsonValue]


@dataclass(frozen=True)
class ContainerProbeReceipt:
    controlSha256: str
    runtimeSha256: str
    cases: tuple[str, ...]
    hosts: tuple[HostIdentity, HostIdentity]


def contains_name(value: JsonValue, name: str) -> bool:
    if isinstance(value, dict):
        return value.get("name") == name or any(
            contains_name(item, name) for item in value.values()
        )
    if isinstance(value, list):
        return any(contains_name(item, name) for item in value)
    return False


def compiler_workspace_lifetime(value: dict[str, JsonValue]) -> str:
    body = value.get("document")
    live = body.get("live") if isinstance(body, dict) else None
    lifetime = live.get("host") if isinstance(live, dict) else None
    if not isinstance(lifetime, str):
        raise RuntimeError("compiler result: live workspace lifetime required")
    return lifetime


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
    target = host_platform()
    runtime_key = record.get("runtime")
    if not isinstance(runtime_key, str) or not re.fullmatch(
        r"[0-9]+\.[0-9]+\.[0-9]+-" + re.escape(target.value), runtime_key
    ):
        raise RuntimeError("create: native runtime identity required")
    store = home / ".local/share/kast-workspaces"
    scope = store / "containers" / identity
    if record.get("gradleHome") != str(scope / "state/home/.gradle"):
        raise RuntimeError("create: Gradle state must be private by default")
    runtime_home = (
        store / "runtimes" / runtime_key / ("host" if target.is_linux else "host/Kast.app/Contents")
    )
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
        cold_host = live_host(
            scope, fixture, target, evidence, "cold-host", runtime_home=runtime_home
        )
        if compiler_workspace_lifetime(cold) != cold_host.workspaceLifetime:
            raise RuntimeError("cold-query: result is not bound to the observed private Host")
        _ = call("stop", "container", "stop", identity, "--json")
        stopped = call("sticky-stop", *query, expected_exit=1)
        if stopped.get("type") != "rejected" or stopped.get("failure") != "STOPPED":
            raise RuntimeError("sticky-stop: finite stopped failure required")
        _ = call("resume", "container", "resume", identity, "--json")
        resumed = call("resumed-query", *query)
        if resumed.get("type") != "complete" or not contains_name(resumed, "invokeCallback"):
            raise RuntimeError("resumed-query: independent compiler result missing")
        resumed_host = live_host(
            scope, fixture, target, evidence, "resumed-host", runtime_home=runtime_home
        )
        if (
            compiler_workspace_lifetime(resumed) != resumed_host.workspaceLifetime
            or cold_host.lifetime == resumed_host.lifetime
        ):
            raise RuntimeError("resumed-query: a fresh private Host must own the result")
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
    result = ContainerProbeReceipt(control_digest, digest, tuple(cases), (cold_host, resumed_host))
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
