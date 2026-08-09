#!/usr/bin/env python3
"""Focused contract test for public-invocation trace correlation."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
from enum import Enum
from pathlib import Path
from typing import Callable, NoReturn


REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
FIXTURE_ROOT = Path(__file__).with_name("fixtures") / "kast-trace-correlation"
TASK_ROOT = Path("/private/tmp/kast-574-v2.4b4fa4")
BASELINE_MANIFEST = FIXTURE_ROOT / "baseline-manifest.json"
DISABLED_MANIFEST = FIXTURE_ROOT / "disabled-manifest.json"
ENABLED_MANIFEST = FIXTURE_ROOT / "enabled-manifest.json"
BASELINE_TRACE = FIXTURE_ROOT / "baseline-trace.jsonl"
ENABLED_TRACE = FIXTURE_ROOT / "enabled-trace.jsonl"

CORRELATION_ATTRIBUTES = (
    "kast.invocation.id",
    "kast.invocation.parentId",
    "kast.request.id",
    "kast.trace.role",
)
TRACE_ROLES = frozenset({"CLI", "TRANSPORT", "BACKEND_OPERATION", "PHASE"})
SENSITIVE_ATTRIBUTE_FRAGMENTS = (
    "argument",
    "environment",
    "parameter",
    "query",
    "secret",
    "source.text",
    "sourcetext",
)
HASH_40 = re.compile(r"[0-9a-f]{40}")
HASH_64 = re.compile(r"[0-9a-f]{64}")
INVOCATION_ID = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
)
REQUEST_ID = re.compile(r"[A-Za-z0-9._:-]{1,128}")
TRACE_ID = re.compile(r"[0-9a-f]{32}")
SPAN_ID = re.compile(r"[0-9a-f]{16}")

PRODUCTION_CONTRACT = {
    REPOSITORY_ROOT / "cli-rs/src/execution/runtime.rs": (
        "mod trace_correlation",
    ),
    REPOSITORY_ROOT / "cli-rs/src/execution/runtime/wire/trace_correlation.rs": (
        "kastTrace",
        "kast.invocation.id",
        "kast.invocation.parentId",
        "kast.request.id",
    ),
    REPOSITORY_ROOT / "cli-rs/src/execution/runtime/wire/rpc.rs": (
        "trace_correlated_rpc_request",
    ),
    REPOSITORY_ROOT
    / "analysis-server/src/main/kotlin/io/github/amichne/kast/server/transport/RpcTraceCorrelation.kt": (
        "kastTrace",
        "kast.transport.rpc",
        "withRpcTraceCorrelation",
    ),
    REPOSITORY_ROOT
    / "analysis-server/src/main/kotlin/io/github/amichne/kast/server/transport/LocalRpcServer.kt": (
        "withRpcTraceCorrelation",
    ),
    REPOSITORY_ROOT
    / "indexer/src/main/kotlin/io/github/amichne/kast/idea/semantic/support/IdeaBackendTelemetry.kt": (
        "kast.invocation.id",
        "kast.invocation.parentId",
        "kast.phase.name",
        "kast.request.id",
    ),
}


class CorrelationFailureCode(Enum):
    CORRELATION_UNAVAILABLE = "CORRELATION_UNAVAILABLE"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    MULTIPLY_OWNED_SPAN = "MULTIPLY_OWNED_SPAN"
    ORPHAN_SPAN = "ORPHAN_SPAN"
    ROLE_CARDINALITY_INVALID = "ROLE_CARDINALITY_INVALID"
    SENSITIVE_ATTRIBUTE_RETAINED = "SENSITIVE_ATTRIBUTE_RETAINED"
    TRACE_JOIN_UNAVAILABLE = "TRACE_JOIN_UNAVAILABLE"
    UNATTRIBUTED_INTERVAL_EXCEEDED = "UNATTRIBUTED_INTERVAL_EXCEEDED"


class HarnessFailure(RuntimeError):
    pass


class CorrelationFailure(RuntimeError):
    def __init__(self, code: CorrelationFailureCode, message: str) -> None:
        super().__init__(message)
        self.code = code


def harness_failure(message: str) -> NoReturn:
    raise HarnessFailure(message)


def correlation_failure(code: CorrelationFailureCode, message: str) -> NoReturn:
    raise CorrelationFailure(code, message)


def canonical_json(payload: object) -> str:
    return json.dumps(payload, indent=2, sort_keys=True) + "\n"


def canonical_json_line(payload: object) -> str:
    return json.dumps(payload, separators=(",", ":"), sort_keys=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as error:
        harness_failure(f"artifact is unreadable at {path}: {error}")
    return digest.hexdigest()


def load_manifest(path: Path) -> dict[str, object]:
    try:
        raw = path.read_text(encoding="utf-8")
        payload = json.loads(raw)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        harness_failure(f"manifest is unreadable at {path}: {error}")
    if not isinstance(payload, dict):
        harness_failure(f"manifest root must be an object: {path}")
    if raw != canonical_json(payload):
        harness_failure(f"manifest is not canonical JSON: {path}")
    validate_manifest(payload, path)
    return payload


def load_trace(path: Path) -> list[dict[str, object]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as error:
        harness_failure(f"trace is unreadable at {path}: {error}")
    spans: list[dict[str, object]] = []
    for line_number, raw in enumerate(lines, start=1):
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as error:
            harness_failure(f"trace line {line_number} is invalid JSON: {error}")
        if not isinstance(payload, dict):
            harness_failure(f"trace line {line_number} must be an object")
        if raw != canonical_json_line(payload):
            harness_failure(f"trace line {line_number} is not canonical JSON")
        validate_span(payload, line_number)
        spans.append(payload)
    return spans


def require_object(payload: dict[str, object], key: str, owner: str) -> dict[str, object]:
    value = payload.get(key)
    if not isinstance(value, dict):
        harness_failure(f"{owner}.{key} must be an object")
    return value


def require_string(payload: dict[str, object], key: str, owner: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value:
        harness_failure(f"{owner}.{key} must be a non-empty string")
    return value


def require_integer(payload: dict[str, object], key: str, owner: str) -> int:
    value = payload.get(key)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        harness_failure(f"{owner}.{key} must be a non-negative integer")
    return value


def validate_manifest(manifest: dict[str, object], path: Path) -> None:
    required = {
        "basis",
        "correlation",
        "environment",
        "fixtureKind",
        "host",
        "installation",
        "mode",
        "observer",
        "runId",
        "schemaVersion",
        "source",
        "teardown",
        "timing",
        "trace",
        "type",
        "workload",
    }
    if set(manifest) != required:
        harness_failure(f"manifest has an incomplete or undeclared root binding: {path}")
    if manifest["schemaVersion"] != 1 or manifest["type"] != "KAST_TRACE_CORRELATION_RUN":
        harness_failure(f"manifest identity is unsupported: {path}")
    if manifest["fixtureKind"] != "DETERMINISTIC_CONTRACT_FIXTURE":
        harness_failure(f"manifest must identify deterministic fixture evidence: {path}")

    environment = require_object(manifest, "environment", str(path))
    for key in (
        "artifactRoot",
        "cacheRoot",
        "configRoot",
        "dataRoot",
        "gradleRoot",
        "homeRoot",
        "installationRoot",
        "javaHomeRoot",
        "runtimeRoot",
        "socketPath",
        "temporaryRoot",
    ):
        value = Path(require_string(environment, key, "environment"))
        if not value.is_absolute() or not value.is_relative_to(TASK_ROOT):
            harness_failure(f"environment.{key} escapes the task root: {value}")
    if not HASH_64.fullmatch(require_string(environment, "variableDigestSha256", "environment")):
        harness_failure("environment.variableDigestSha256 is not a SHA-256 identity")
    if any(key.lower() in {"variables", "environmentdump", "secrets"} for key in environment):
        harness_failure("environment retains ambient values instead of one digest")

    host = require_object(manifest, "host", str(path))
    installation = require_object(manifest, "installation", str(path))
    if host.get("identityAuthority") != "HOST_OBSERVATION":
        harness_failure("host identity must come from host observation")
    if installation.get("receiptPlatform") != "linux-x64":
        harness_failure("fixture must retain the observed linux-x64 receipt platform")
    if installation.get("binaryFormat") != "Mach-O 64-bit executable arm64":
        harness_failure("fixture must retain the observed Mach-O arm64 binary format")
    if installation.get("receiptPlatformSemantics") != "PACKAGING_METADATA_NOT_HOST_IDENTITY":
        harness_failure("receipt platform must not be treated as host identity")
    for key in ("backendArtifactSha256", "binarySha256", "releaseDigest"):
        if not HASH_64.fullmatch(require_string(installation, key, "installation")):
            harness_failure(f"installation.{key} is not a SHA-256 identity")

    source = require_object(manifest, "source", str(path))
    if not HASH_40.fullmatch(require_string(source, "commitSha", "source")):
        harness_failure("source.commitSha is not exact")
    if not HASH_40.fullmatch(require_string(source, "treeSha", "source")):
        harness_failure("source.treeSha is not exact")
    workload = require_object(manifest, "workload", str(path))
    if not HASH_64.fullmatch(require_string(workload, "inputSha256", "workload")):
        harness_failure("workload.inputSha256 is not exact")
    if any(key in workload for key in ("arguments", "parameters", "query", "sourceText")):
        harness_failure("workload retains raw request input")

    correlation = require_object(manifest, "correlation", str(path))
    if correlation.get("state") == "REQUIRED":
        if not INVOCATION_ID.fullmatch(require_string(correlation, "invocationId", "correlation")):
            harness_failure("correlation.invocationId is not a canonical version-4 UUID")
        if not HASH_64.fullmatch(
            require_string(correlation, "parentInvocationId", "correlation")
        ):
            harness_failure("correlation.parentInvocationId is not a hashed identity")
        if not REQUEST_ID.fullmatch(require_string(correlation, "requestId", "correlation")):
            harness_failure("correlation.requestId is not a bounded JSON-RPC identity")


def validate_span(span: dict[str, object], line_number: int) -> None:
    required = {
        "attributes",
        "durationNanos",
        "endEpochNanos",
        "events",
        "kind",
        "name",
        "spanId",
        "startEpochNanos",
        "status",
        "traceId",
    }
    allowed = required | {"parentSpanId"}
    if not required.issubset(span) or not set(span).issubset(allowed):
        harness_failure(f"trace line {line_number} has an incomplete or undeclared binding")
    if not TRACE_ID.fullmatch(require_string(span, "traceId", f"trace[{line_number}]")):
        harness_failure(f"trace line {line_number} has an invalid traceId")
    if not SPAN_ID.fullmatch(require_string(span, "spanId", f"trace[{line_number}]")):
        harness_failure(f"trace line {line_number} has an invalid spanId")
    parent = span.get("parentSpanId")
    if parent is not None and (not isinstance(parent, str) or not SPAN_ID.fullmatch(parent)):
        harness_failure(f"trace line {line_number} has an invalid parentSpanId")
    start = require_integer(span, "startEpochNanos", f"trace[{line_number}]")
    end = require_integer(span, "endEpochNanos", f"trace[{line_number}]")
    duration = require_integer(span, "durationNanos", f"trace[{line_number}]")
    if end < start or duration != end - start:
        harness_failure(f"trace line {line_number} has contradictory timing")
    attributes = span.get("attributes")
    if not isinstance(attributes, dict) or not all(
        isinstance(key, str) and isinstance(value, str)
        for key, value in attributes.items()
    ):
        harness_failure(f"trace line {line_number} attributes must be string bindings")
    events = span.get("events")
    if not isinstance(events, list):
        harness_failure(f"trace line {line_number} events must be an array")


def validate_trace_artifact(manifest: dict[str, object], path: Path, spans: list[dict[str, object]]) -> None:
    trace = require_object(manifest, "trace", "manifest")
    if trace.get("state") != "CAPTURED" or trace.get("type") != "RAW_TRACE_ARTIFACT":
        harness_failure(f"captured trace state is invalid: {path}")
    if trace.get("path") != path.name:
        harness_failure(f"trace path binding does not select {path.name}")
    if trace.get("sha256") != sha256(path):
        harness_failure(f"trace digest does not match raw bytes: {path}")
    if trace.get("recordCount") != len(spans):
        harness_failure(f"trace record count does not match raw lines: {path}")


def require_safe_attributes(attributes: dict[str, object]) -> None:
    for key in attributes:
        normalized = key.lower()
        if any(fragment in normalized for fragment in SENSITIVE_ATTRIBUTE_FRAGMENTS):
            correlation_failure(
                CorrelationFailureCode.SENSITIVE_ATTRIBUTE_RETAINED,
                f"trace attribute retains prohibited input: {key}",
            )


def reconcile(manifest: dict[str, object], spans: list[dict[str, object]]) -> int:
    correlation = require_object(manifest, "correlation", "manifest")
    if correlation.get("state") != "REQUIRED":
        correlation_failure(
            CorrelationFailureCode.CORRELATION_UNAVAILABLE,
            "trace does not expose invocation and parent identity",
        )

    span_ids = [require_string(span, "spanId", "trace span") for span in spans]
    if len(span_ids) != len(set(span_ids)):
        correlation_failure(
            CorrelationFailureCode.MULTIPLY_OWNED_SPAN,
            "one spanId has more than one ownership record",
        )
    by_id = dict(zip(span_ids, spans, strict=True))
    for span in spans:
        parent = span.get("parentSpanId")
        if parent is not None and parent not in by_id:
            correlation_failure(
                CorrelationFailureCode.ORPHAN_SPAN,
                f"span {span['spanId']} has no recorded parent {parent}",
            )

    expected_identity = {
        "kast.invocation.id": require_string(correlation, "invocationId", "correlation"),
        "kast.invocation.parentId": require_string(
            correlation, "parentInvocationId", "correlation"
        ),
        "kast.request.id": require_string(correlation, "requestId", "correlation"),
    }
    by_role: dict[str, list[dict[str, object]]] = {role: [] for role in TRACE_ROLES}
    for span in spans:
        attributes = require_object(span, "attributes", "trace span")
        require_safe_attributes(attributes)
        if any(attributes.get(key) != value for key, value in expected_identity.items()):
            correlation_failure(
                CorrelationFailureCode.IDENTITY_MISMATCH,
                f"span {span['spanId']} lost the invocation, parent, or request identity",
            )
        role = attributes.get("kast.trace.role")
        if role not in TRACE_ROLES:
            correlation_failure(
                CorrelationFailureCode.ROLE_CARDINALITY_INVALID,
                f"span {span['spanId']} has no closed trace role",
            )
        by_role[str(role)].append(span)

    if any(len(by_role[role]) != 1 for role in ("CLI", "TRANSPORT", "BACKEND_OPERATION")):
        correlation_failure(
            CorrelationFailureCode.ROLE_CARDINALITY_INVALID,
            "trace must have exactly one CLI, transport, and backend operation owner",
        )
    if not by_role["PHASE"]:
        correlation_failure(
            CorrelationFailureCode.ROLE_CARDINALITY_INVALID,
            "long backend operation has no phase spans",
        )

    cli = by_role["CLI"][0]
    transport = by_role["TRANSPORT"][0]
    operation = by_role["BACKEND_OPERATION"][0]
    if cli.get("parentSpanId") is not None:
        correlation_failure(CorrelationFailureCode.ORPHAN_SPAN, "CLI owner must be the trace root")
    if transport.get("parentSpanId") != cli["spanId"]:
        correlation_failure(CorrelationFailureCode.ORPHAN_SPAN, "transport is not owned by CLI")
    if operation.get("parentSpanId") != transport["spanId"]:
        correlation_failure(CorrelationFailureCode.ORPHAN_SPAN, "backend is not owned by transport")
    if operation.get("name") != correlation.get("operationSpanName"):
        correlation_failure(
            CorrelationFailureCode.IDENTITY_MISMATCH,
            "backend operation identity does not match the manifest",
        )
    if cli.get("durationNanos") != require_object(manifest, "timing", "manifest").get("durationNanos"):
        correlation_failure(
            CorrelationFailureCode.IDENTITY_MISMATCH,
            "CLI duration does not match the invocation record",
        )

    trace_ids = {span["traceId"] for span in spans}
    if len(trace_ids) != 1:
        correlation_failure(
            CorrelationFailureCode.IDENTITY_MISMATCH,
            "one invocation crosses more than one trace identity",
        )
    operation_start = int(operation["startEpochNanos"])
    operation_end = int(operation["endEpochNanos"])
    intervals: list[tuple[int, int]] = []
    phase_names: set[str] = set()
    for phase in by_role["PHASE"]:
        if phase.get("parentSpanId") != operation["spanId"]:
            correlation_failure(CorrelationFailureCode.ORPHAN_SPAN, "phase is not owned by backend")
        attributes = require_object(phase, "attributes", "phase")
        phase_names.add(require_string(attributes, "kast.phase.name", "phase.attributes"))
        start = int(phase["startEpochNanos"])
        end = int(phase["endEpochNanos"])
        if start < operation_start or end > operation_end:
            correlation_failure(CorrelationFailureCode.ORPHAN_SPAN, "phase escapes backend interval")
        intervals.append((start, end))
    if "operationBody" not in phase_names:
        correlation_failure(
            CorrelationFailureCode.ROLE_CARDINALITY_INVALID,
            "backend operation has no explicit operationBody phase",
        )

    attributed = 0
    cursor_start, cursor_end = sorted(intervals)[0]
    for start, end in sorted(intervals)[1:]:
        if start <= cursor_end:
            cursor_end = max(cursor_end, end)
        else:
            attributed += cursor_end - cursor_start
            cursor_start, cursor_end = start, end
    attributed += cursor_end - cursor_start
    unattributed = int(operation["durationNanos"]) - attributed
    maximum = require_integer(correlation, "maxUnattributedNanos", "correlation")
    if unattributed < 0 or unattributed > maximum:
        correlation_failure(
            CorrelationFailureCode.UNATTRIBUTED_INTERVAL_EXCEEDED,
            f"backend operation retains {unattributed} unattributed nanoseconds; maximum is {maximum}",
        )
    return unattributed


def validate_pair(disabled: dict[str, object], enabled: dict[str, object]) -> None:
    for key in ("host", "installation", "source", "workload"):
        if disabled[key] != enabled[key]:
            harness_failure(f"paired runs disagree on {key} identity")
    disabled_environment = dict(require_object(disabled, "environment", "disabled"))
    enabled_environment = dict(require_object(enabled, "environment", "enabled"))
    disabled_environment.pop("artifactRoot")
    enabled_environment.pop("artifactRoot")
    if disabled_environment != enabled_environment:
        harness_failure("paired runs disagree on controlled environment identity")
    disabled_observer = require_object(disabled, "observer", "disabled")
    enabled_observer = require_object(enabled, "observer", "enabled")
    if disabled_observer.get("pairId") != enabled_observer.get("pairId"):
        harness_failure("paired runs do not share a pair identity")
    if disabled_observer.get("peerRunId") != enabled.get("runId"):
        harness_failure("disabled run does not identify its enabled peer")
    if enabled_observer.get("peerRunId") != disabled.get("runId"):
        harness_failure("enabled run does not identify its disabled peer")
    disabled_duration = require_object(disabled, "timing", "disabled")["durationNanos"]
    enabled_duration = require_object(enabled, "timing", "enabled")["durationNanos"]
    overhead = int(enabled_duration) - int(disabled_duration)
    if enabled_observer.get("observerOverheadNanos") != overhead or overhead < 0:
        harness_failure("enabled run does not retain measured observer overhead")
    if require_object(disabled, "trace", "disabled").get("state") != "DISABLED":
        harness_failure("minimal pair member must retain disabled trace state")


def expect_correlation_failure(
    code: CorrelationFailureCode,
    operation: Callable[[], object],
) -> None:
    try:
        operation()
    except CorrelationFailure as failure:
        if failure.code != code:
            harness_failure(
                f"expected {code.value}, observed {failure.code.value}: {failure}"
            )
        return
    harness_failure(f"expected semantic rejection {code.value}")


def require_production_contract() -> None:
    missing: list[str] = []
    for path, tokens in PRODUCTION_CONTRACT.items():
        try:
            source = path.read_text(encoding="utf-8")
        except OSError:
            missing.append(f"{path.relative_to(REPOSITORY_ROOT)}:missing-owner")
            continue
        for token in tokens:
            if token not in source:
                missing.append(f"{path.relative_to(REPOSITORY_ROOT)}:{token}")
    if missing:
        correlation_failure(
            CorrelationFailureCode.TRACE_JOIN_UNAVAILABLE,
            "production trace cannot join the enabled CLI/transport/backend/phase fixture; "
            + ", ".join(missing),
        )


def main() -> int:
    baseline = load_manifest(BASELINE_MANIFEST)
    disabled = load_manifest(DISABLED_MANIFEST)
    enabled = load_manifest(ENABLED_MANIFEST)
    baseline_spans = load_trace(BASELINE_TRACE)
    enabled_spans = load_trace(ENABLED_TRACE)
    validate_trace_artifact(baseline, BASELINE_TRACE, baseline_spans)
    validate_trace_artifact(enabled, ENABLED_TRACE, enabled_spans)
    validate_pair(disabled, enabled)

    expect_correlation_failure(
        CorrelationFailureCode.CORRELATION_UNAVAILABLE,
        lambda: reconcile(baseline, baseline_spans),
    )
    unattributed = reconcile(enabled, enabled_spans)
    if unattributed != 20_000_000:
        harness_failure(f"enabled fixture has unexpected unattributed duration: {unattributed}")

    orphaned = copy.deepcopy(enabled_spans)
    orphaned[-1]["parentSpanId"] = "ffffffffffffffff"
    expect_correlation_failure(
        CorrelationFailureCode.ORPHAN_SPAN,
        lambda: reconcile(enabled, orphaned),
    )
    multiply_owned = copy.deepcopy(enabled_spans)
    duplicate = copy.deepcopy(multiply_owned[-1])
    duplicate["parentSpanId"] = "bbbbbbbbbbbbbbbb"
    multiply_owned.append(duplicate)
    expect_correlation_failure(
        CorrelationFailureCode.MULTIPLY_OWNED_SPAN,
        lambda: reconcile(enabled, multiply_owned),
    )
    sensitive = copy.deepcopy(enabled_spans)
    require_object(sensitive[-1], "attributes", "phase")["request.parameters"] = "forbidden"
    expect_correlation_failure(
        CorrelationFailureCode.SENSITIVE_ATTRIBUTE_RETAINED,
        lambda: reconcile(enabled, sensitive),
    )
    unbounded_manifest = copy.deepcopy(enabled)
    require_object(unbounded_manifest, "correlation", "manifest")["maxUnattributedNanos"] = 19_999_999
    expect_correlation_failure(
        CorrelationFailureCode.UNATTRIBUTED_INTERVAL_EXCEEDED,
        lambda: reconcile(unbounded_manifest, enabled_spans),
    )

    require_production_contract()
    print("trace correlation contract: ok")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CorrelationFailure as failure:
        print(f"error: {failure.code.value}: {failure}", file=sys.stderr)
        raise SystemExit(1)
    except HarnessFailure as failure:
        print(f"infrastructure: {failure}", file=sys.stderr)
        raise SystemExit(2)
