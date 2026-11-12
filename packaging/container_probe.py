#!/usr/bin/env python3
"""Qualify private workspace generations through the test-native component harness."""

from __future__ import annotations

import hashlib
import json
import platform
from dataclasses import asdict, dataclass
from pathlib import Path

from component_probe import ComponentHarness
from composed_host_probe import HostIdentity, live_host
from composition_platform import UnsupportedHost, host_platform

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
    harness: ComponentHarness, fixture: Path, control: Path, evidence: Path
) -> ContainerProbeReceipt:
    if not control.is_file() or not fixture.is_dir() or evidence.exists():
        raise ValueError("exact Control, disposable fixture, and fresh evidence required")
    evidence.mkdir(parents=True)
    cases: list[str] = []

    def call(label: str, *arguments: str) -> dict[str, JsonValue]:
        result = harness.run(label, fixture, arguments, evidence)
        if result.returncode != 0:
            raise RuntimeError(f"{label}: exit {result.returncode}; see {evidence}")
        value: JsonValue = json.loads(result.stdout)
        if not isinstance(value, dict):
            raise RuntimeError(f"{label}: document required")
        cases.append(label)
        return value

    def stop(label: str) -> None:
        result = harness.run(label, fixture, ("stop",), evidence)
        if result.returncode != 0:
            raise RuntimeError(f"{label}: settled retirement was not established; see {evidence}")
        cases.append(label)

    target = host_platform(platform.system(), platform.machine())
    if isinstance(target, UnsupportedHost):
        raise RuntimeError("native qualification requires a supported OS and CPU")
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
        paths = harness.inspect(fixture, evidence, "inspect")
        generation: JsonValue = json.loads(
            (paths.workspaceDirectory / "generation.json").read_text()
        )
        if not isinstance(generation, dict) or generation.get("archiveSha256") != harness.digest:
            raise RuntimeError("generation must preserve the admitted runtime archive digest")
        cold_host = live_host(
            paths.workspaceState.parent,
            fixture,
            target,
            evidence,
            "cold-host",
            runtime_home=paths.runtimeHome,
        )
        if compiler_workspace_lifetime(cold) != cold_host.workspaceLifetime:
            raise RuntimeError("cold-query: result is not bound to the observed private Host")
        stop("stop")
        resumed = call("query-after-stop", *query)
        if resumed.get("type") != "complete" or not contains_name(resumed, "invokeCallback"):
            raise RuntimeError("query-after-stop: independent compiler result missing")
        resumed_host = live_host(
            paths.workspaceState.parent,
            fixture,
            target,
            evidence,
            "resumed-host",
            runtime_home=paths.runtimeHome,
        )
        if (
            compiler_workspace_lifetime(resumed) != resumed_host.workspaceLifetime
            or cold_host.lifetime == resumed_host.lifetime
        ):
            raise RuntimeError("query-after-stop: a fresh private Host must own the result")
    finally:
        stop("cleanup-stop")
    if not (fixture / "settings.gradle.kts").is_file():
        raise RuntimeError("stop must preserve the workspace")
    with control.open("rb") as content:
        control_digest = hashlib.file_digest(content, "sha256").hexdigest()
    result = ContainerProbeReceipt(
        control_digest, harness.digest, tuple(cases), (cold_host, resumed_host)
    )
    _ = (evidence / "receipt.json").write_text(json.dumps(asdict(result), indent=2) + "\n")
    return result
