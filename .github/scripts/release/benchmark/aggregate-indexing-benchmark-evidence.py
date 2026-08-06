#!/usr/bin/env python3
"""Validate and aggregate comparative real-repository benchmark evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import NoReturn


SCHEMA_VERSION = 1
COLD_INDEX_LIMIT_MILLIS = 2_700_000
PHASE_REGRESSION_PERCENT = 15
PHASE_REGRESSION_MILLIS = 60_000
DISK_REGRESSION_PERCENT = 15
DISK_REGRESSION_BYTES = 268_435_456
REQUIRED_PHASES = [
    "setup",
    "configure",
    "runtimeAdmission",
    "workspaceIndex",
    "coldIndex",
    "graphRefresh",
    "graphSummary",
    "semanticIdentity",
]
COMPARABLE_PHASES = [
    "setup",
    "configure",
    "coldIndex",
    "graphRefresh",
    "graphSummary",
    "semanticIdentity",
]
CORRECTNESS_INTEGER_FIELDS = {
    "sourceIndexGeneration",
    "workspaceExactTotalCount",
    "refreshSymbolCount",
    "graphNodeCount",
    "graphEdgeOccurrenceCount",
}
CORRECTNESS_NUMERIC_FIELDS = CORRECTNESS_INTEGER_FIELDS | {"graphWeightedEdgeCount"}
CORRECTNESS_HASH_FIELDS = {
    "workspaceFileIdentitySha256",
    "graphNodeIdentitySha256",
    "graphEdgeIdentitySha256",
}
CORRECTNESS_IDENTITY_FIELDS = CORRECTNESS_HASH_FIELDS | {
    "semanticIdentityAlgorithm",
    "workspaceFileIdentities",
    "refreshedPaths",
    "removedPaths",
}
STORAGE_FIELDS = {
    "initialDatabaseBytes", "finalDatabaseBytes", "peakDatabaseBytes", "databaseGrowthBytes",
    "initialWalBytes", "finalWalBytes", "peakWalBytes", "walGrowthBytes",
    "initialKastHomeBytes", "finalKastHomeBytes", "peakKastHomeBytes",
    "initialKastCacheBytes", "finalKastCacheBytes", "peakKastCacheBytes",
    "initialGradleCacheBytes", "finalGradleCacheBytes", "peakGradleCacheBytes",
    "initialUserHomeBytes", "finalUserHomeBytes", "peakUserHomeBytes",
    "initialWorkspaceBytes", "finalWorkspaceBytes", "peakWorkspaceBytes",
    "initialOwnedBytes", "finalOwnedBytes", "peakOwnedBytes", "ownedGrowthBytes",
}
RESOURCE_FIELDS = {
    "peakRssBytes", "peakVirtualBytes", "peakCpuPercent", "peakProcessCount",
}
RETRY_FIELDS = {
    "workspaceIndexPoll", "semanticNotReadyPoll", "graphGenerationConflict",
    "runtimeStatusFailures", "runtimeTransitionCount", "runtimeTransitions",
}
SAMPLE_STORAGE_FIELDS = {
    "databaseBytes", "walBytes", "kastHomeBytes", "kastCacheBytes",
    "gradleCacheBytes", "userHomeBytes", "workspaceBytes", "ownedBytes",
}
SAMPLE_RESOURCE_FIELDS = {
    "rssBytes", "virtualBytes", "cpuPercent", "processCount", "processIds",
}
STORAGE_SAMPLE_SUMMARIES = {
    "databaseBytes": (
        "initialDatabaseBytes", "finalDatabaseBytes", "peakDatabaseBytes",
        "databaseGrowthBytes",
    ),
    "walBytes": (
        "initialWalBytes", "finalWalBytes", "peakWalBytes", "walGrowthBytes",
    ),
    "kastHomeBytes": (
        "initialKastHomeBytes", "finalKastHomeBytes", "peakKastHomeBytes", None,
    ),
    "kastCacheBytes": (
        "initialKastCacheBytes", "finalKastCacheBytes", "peakKastCacheBytes", None,
    ),
    "gradleCacheBytes": (
        "initialGradleCacheBytes", "finalGradleCacheBytes", "peakGradleCacheBytes", None,
    ),
    "userHomeBytes": (
        "initialUserHomeBytes", "finalUserHomeBytes", "peakUserHomeBytes", None,
    ),
    "workspaceBytes": (
        "initialWorkspaceBytes", "finalWorkspaceBytes", "peakWorkspaceBytes", None,
    ),
    "ownedBytes": (
        "initialOwnedBytes", "finalOwnedBytes", "peakOwnedBytes", "ownedGrowthBytes",
    ),
}
RESOURCE_SAMPLE_SUMMARIES = {
    "rssBytes": "peakRssBytes",
    "virtualBytes": "peakVirtualBytes",
    "cpuPercent": "peakCpuPercent",
    "processCount": "peakProcessCount",
}
RETRY_TIME_FIELDS = {
    "retryAt", "retryAtEpochMillis", "retryTime", "retryTimeEpochMillis",
    "nextRetryAt", "nextRetryAtEpochMillis",
}
RETRY_DETAIL_FIELDS = RETRY_TIME_FIELDS | {
    "attempt", "retryAttempt", "phase", "lastError",
}
POLICY = {
    "phaseRegressionPercent": PHASE_REGRESSION_PERCENT,
    "phaseRegressionMillis": PHASE_REGRESSION_MILLIS,
    "diskRegressionPercent": DISK_REGRESSION_PERCENT,
    "diskRegressionBytes": DISK_REGRESSION_BYTES,
    "coldIndexLimitMillis": COLD_INDEX_LIMIT_MILLIS,
}
HEX_64 = re.compile(r"[0-9a-f]{64}")
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
SHA = re.compile(r"[0-9a-f]{40}")


def fail(message: str) -> NoReturn:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load_object(path: Path) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"unreadable JSON at {path}: {error}")
    require(isinstance(payload, dict), f"JSON root must be an object at {path}")
    return payload


def nonnegative_integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def nonnegative_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0


def normalized_version(value: object) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    return value[1:] if value.startswith("v") else value


def canonical_relative_path(value: object) -> bool:
    if not isinstance(value, str) or not value or "\\" in value:
        return False
    pure = PurePosixPath(value)
    return (
        not pure.is_absolute()
        and pure.as_posix() == value
        and all(part not in {"", ".", ".."} for part in pure.parts)
    )


def canonical_path_array(value: object) -> bool:
    return (
        isinstance(value, list)
        and all(canonical_relative_path(item) for item in value)
        and value == sorted(value)
        and len(value) == len(set(value))
    )


def runtime_observation(payload: object) -> tuple[set[str], set[str]]:
    states: set[str] = set()
    versions: set[str] = set()
    if not isinstance(payload, dict):
        return states, versions
    result = payload.get("result", payload)
    if not isinstance(result, dict):
        return states, versions
    candidates: list[object] = []
    runtime = result.get("runtime")
    if isinstance(runtime, dict):
        candidates.extend([runtime, runtime.get("status"), runtime.get("runtimeStatus")])
    selected = result.get("selected")
    if isinstance(selected, dict):
        candidates.extend([
            selected.get("runtimeStatus"),
            selected.get("descriptor"),
            selected.get("capabilities"),
        ])
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        state = candidate.get("state")
        version = candidate.get("backendVersion")
        if isinstance(state, str):
            states.add(state)
        if isinstance(version, str):
            versions.add(version)
    return states, versions


def validate_process_array(value: object, prefix: str) -> None:
    require(isinstance(value, list), f"{prefix} must be an array")
    identities: set[tuple[int, str]] = set()
    for index, process in enumerate(value):
        require(isinstance(process, dict), f"{prefix}[{index}] must be an object")
        pid = process.get("pid")
        start_identity = process.get("startIdentity")
        require(isinstance(pid, int) and not isinstance(pid, bool) and pid > 0,
                f"{prefix}[{index}].pid is invalid")
        require(isinstance(start_identity, str) and bool(start_identity),
                f"{prefix}[{index}].startIdentity is invalid")
        identity = (pid, start_identity)
        require(identity not in identities, f"{prefix} contains duplicate identities")
        identities.add(identity)


def validate_closure(
    value: object,
    prefix: str,
    *,
    must_be_proven: bool,
) -> dict[str, object]:
    require(isinstance(value, dict), f"{prefix} must be an object")
    required = value.get("required")
    proven = value.get("proven")
    require(isinstance(required, bool), f"{prefix}.required must be boolean")
    require(proven is None or isinstance(proven, bool), f"{prefix}.proven is invalid")
    require(isinstance(value.get("pidfdsRetained"), bool),
            f"{prefix}.pidfdsRetained must be boolean")
    validate_process_array(value.get("capturedProcesses"), f"{prefix}.capturedProcesses")
    validate_process_array(value.get("remainingProcesses"), f"{prefix}.remainingProcesses")
    require(nonnegative_integer(value.get("recapturePasses")),
            f"{prefix}.recapturePasses is invalid")
    require(nonnegative_integer(value.get("stableConfirmationPasses")),
            f"{prefix}.stableConfirmationPasses is invalid")
    if required:
        require(isinstance(proven, bool), f"{prefix}.proven must be boolean")
    else:
        require(proven is None, f"{prefix} claimed an unnecessary proof")
        require(value.get("pidfdsRetained") is False,
                f"{prefix} retained pidfds without requiring closure")
        require(value["capturedProcesses"] == [] and value["remainingProcesses"] == [],
                f"{prefix} captured processes without requiring closure")
        require(value["recapturePasses"] == 0 and value["stableConfirmationPasses"] == 0,
                f"{prefix} ran closure passes without requiring closure")
    if must_be_proven:
        require(required is True and proven is True, f"{prefix} is not proven")
        require(value.get("pidfdsRetained") is True, f"{prefix} did not retain pidfds")
        require(value["remainingProcesses"] == [], f"{prefix} retained live processes")
        require(value["stableConfirmationPasses"] >= 2,
                f"{prefix} lacks stable confirmation")
        require("enumerationError" not in value, f"{prefix} contains an enumeration error")
    return value


def derived_storage(samples: list[dict[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for sample_field, summary_fields in STORAGE_SAMPLE_SUMMARIES.items():
        values = [sample["storage"][sample_field] for sample in samples]
        initial_field, final_field, peak_field, growth_field = summary_fields
        peak = max(values)
        result[initial_field] = values[0]
        result[final_field] = values[-1]
        result[peak_field] = peak
        if growth_field is not None:
            result[growth_field] = max(peak - values[0], 0)
    return result


def derived_resources(samples: list[dict[str, object]]) -> dict[str, object]:
    return {
        summary_field: max(sample["resources"][sample_field] for sample in samples)
        for sample_field, summary_field in RESOURCE_SAMPLE_SUMMARIES.items()
    }


def normalized_retry(value: dict[str, object]) -> dict[str, object]:
    return {
        key: value[key]
        for key in sorted(RETRY_DETAIL_FIELDS)
        if key in value
    }


def retry_values(value: object):
    if isinstance(value, dict):
        retry = value.get("retry")
        if isinstance(retry, dict):
            yield normalized_retry(retry) or retry
        if RETRY_TIME_FIELDS & value.keys() and (
            "lastError" in value
            or "attempt" in value
            or "retryAttempt" in value
        ):
            yield normalized_retry(value)
        for nested in value.values():
            yield from retry_values(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from retry_values(nested)


def derived_retry_transitions(samples: list[dict[str, object]]) -> list[dict[str, object]]:
    seen: set[str] = set()
    transitions: list[dict[str, object]] = []
    for sample in samples:
        for retry in retry_values(sample.get("runtimeStatus")):
            identity = json.dumps(retry, sort_keys=True, separators=(",", ":"))
            if identity in seen:
                continue
            seen.add(identity)
            transitions.append({
                "observedAtEpochMillis": sample.get("observedAtEpochMillis"),
                "retry": retry,
            })
    return transitions


def derived_retries(
    samples: list[dict[str, object]],
    commands: list[dict[str, object]],
) -> dict[str, object]:
    transitions = derived_retry_transitions(samples)

    def successful(operation: str) -> int:
        return sum(
            event.get("type") == "KAST_BENCHMARK_SUPERVISED_COMMAND"
            and event.get("operation") == operation
            and event.get("outcome") == "SUCCEEDED"
            for event in commands
        )

    return {
        "workspaceIndexPoll": sum(
            event.get("type") == "KAST_BENCHMARK_SUPERVISED_COMMAND"
            and event.get("operation") == "workspace-state-classification"
            and event.get("outcome") == "FAILED"
            for event in commands
        ),
        "semanticNotReadyPoll": successful("semantic-readiness-classification"),
        "graphGenerationConflict": successful("graph-conflict-classification"),
        "runtimeStatusFailures": sum(sample["statusExitCode"] != 0 for sample in samples),
        "runtimeTransitionCount": len(transitions),
        "runtimeTransitions": transitions,
    }


def validate_run(run: object, role: str, tag: str, digest: str, repository_name: str) -> dict[str, object]:
    prefix = f"{repository_name}.{role}"
    require(isinstance(run, dict), f"{prefix} run must be an object")
    require(run.get("schemaVersion") == SCHEMA_VERSION, f"{prefix} schemaVersion must equal 1")
    require(run.get("role") == role, f"{prefix} role mismatch")

    bundle = run.get("bundle")
    require(isinstance(bundle, dict), f"{prefix}.bundle must be an object")
    expected_name = f"kast-linux-x64-{tag}.tar.gz"
    require(bundle.get("fileName") == expected_name, f"{prefix} bundle file mismatch")
    require(bundle.get("sha256") == digest and HEX_64.fullmatch(digest) is not None,
            f"{prefix} bundle digest mismatch")
    require(bundle.get("expectedVersion") == tag, f"{prefix} expected bundle version mismatch")
    require(normalized_version(bundle.get("version")) == normalized_version(tag),
            f"{prefix} installed bundle version mismatch")
    runtime_versions = bundle.get("runtimeBackendVersions")
    require(isinstance(runtime_versions, list) and bool(runtime_versions),
            f"{prefix} runtime backend versions are missing")
    require(all(normalized_version(value) == normalized_version(tag) for value in runtime_versions),
            f"{prefix} runtime backend version mismatch")

    require(run.get("correctness") is True, f"{prefix} correctness is not proven")
    correctness = run.get("correctnessEvidence")
    require(isinstance(correctness, dict), f"{prefix}.correctnessEvidence must be an object")
    required_correctness = CORRECTNESS_NUMERIC_FIELDS | CORRECTNESS_IDENTITY_FIELDS
    require(required_correctness <= correctness.keys(), f"{prefix} correctness evidence is incomplete")
    for field in CORRECTNESS_INTEGER_FIELDS:
        require(nonnegative_integer(correctness.get(field)), f"{prefix}.{field} must be a non-negative integer")
    require(nonnegative_number(correctness.get("graphWeightedEdgeCount")),
            f"{prefix}.graphWeightedEdgeCount must be non-negative")
    require(correctness.get("semanticIdentityAlgorithm") == "sha256-canonical-json-v2",
            f"{prefix} semantic identity algorithm is unsupported")
    for field in CORRECTNESS_HASH_FIELDS:
        value = correctness.get(field)
        require(isinstance(value, str) and HEX_64.fullmatch(value) is not None,
                f"{prefix}.{field} must be a lowercase SHA-256")
    for field in ("workspaceFileIdentities", "refreshedPaths", "removedPaths"):
        require(canonical_path_array(correctness.get(field)), f"{prefix}.{field} is not canonical")
    identities = correctness["workspaceFileIdentities"]
    require(len(identities) == correctness["workspaceExactTotalCount"],
            f"{prefix} workspace identity count mismatch")
    expected_workspace_hash = hashlib.sha256(json.dumps(
        identities,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")).hexdigest()
    require(correctness["workspaceFileIdentitySha256"] == expected_workspace_hash,
            f"{prefix} workspace identity hash mismatch")

    workspace = run.get("workspace")
    require(isinstance(workspace, dict), f"{prefix}.workspace must be an object")
    relative_root = workspace.get("repositoryRelativeRoot")
    require(relative_root == "." or canonical_relative_path(relative_root),
            f"{prefix} repository-relative workspace root is invalid")
    require(canonical_relative_path(workspace.get("graphFile")), f"{prefix} graph file is invalid")
    require(correctness["refreshedPaths"] == [workspace["graphFile"]],
            f"{prefix} refreshed path does not equal its graph probe")

    diagnostic = run.get("diagnostic")
    require(isinstance(diagnostic, dict), f"{prefix}.diagnostic must be an object")
    require(diagnostic.get("outcome") == "SUCCEEDED", f"{prefix} diagnostic outcome is not successful")
    require(diagnostic.get("roleExitCode") == 0, f"{prefix} role exit code is not zero")
    commands = diagnostic.get("supervisedCommands")
    require(isinstance(commands, list) and bool(commands), f"{prefix} supervised command evidence is missing")
    teardown_indices: list[int] = []
    worktree_indices: list[int] = []
    finalization_indices: list[int] = []
    for index, event in enumerate(commands):
        require(isinstance(event, dict), f"{prefix} supervisedCommands[{index}] must be an object")
        require(event.get("schemaVersion") == 1, f"{prefix} supervisedCommands[{index}] schema mismatch")
        require(event.get("type") in {
            "KAST_BENCHMARK_SUPERVISED_COMMAND", "KAST_BENCHMARK_OWNED_PROCESSES"
        }, f"{prefix} supervisedCommands[{index}] type mismatch")
        require(isinstance(event.get("operation"), str) and bool(event.get("operation")),
                f"{prefix} supervisedCommands[{index}] operation is missing")
        require(event.get("outcome") != "SUPERVISION_FAILED",
                f"{prefix} contains a supervision failure")
        for field in (
            "startedAtEpochMillis", "finishedAtEpochMillis",
            "startedAtMonotonicMillis", "finishedAtMonotonicMillis",
            "durationMillis", "deadlineMonotonicMillis",
        ):
            require(nonnegative_integer(event.get(field)),
                    f"{prefix} supervisedCommands[{index}].{field} is invalid")
        termination = event.get("termination")
        require(
            isinstance(termination, dict)
            and isinstance(termination.get("termSent"), bool)
            and isinstance(termination.get("killSent"), bool),
            f"{prefix} supervisedCommands[{index}] termination is invalid",
        )
        started_epoch = event["startedAtEpochMillis"]
        finished_epoch = event["finishedAtEpochMillis"]
        started_monotonic = event["startedAtMonotonicMillis"]
        finished_monotonic = event["finishedAtMonotonicMillis"]
        require(finished_epoch >= started_epoch,
                f"{prefix} supervised command epoch time reversed")
        require(finished_monotonic >= started_monotonic,
                f"{prefix} supervised command monotonic time reversed")
        require(finished_monotonic - started_monotonic == event["durationMillis"],
                f"{prefix} supervised command duration mismatch")
        if event["type"] == "KAST_BENCHMARK_SUPERVISED_COMMAND":
            require(event.get("outcome") in {
                "SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERVISION_FAILED",
            }, f"{prefix} supervisedCommands[{index}] outcome is invalid")
            require(nonnegative_integer(event.get("exitCode")),
                    f"{prefix} supervisedCommands[{index}] exit code is invalid")
            outcome = event["outcome"]
            expected_exit_code = {
                "SUCCEEDED": 0,
                "TIMED_OUT": 124,
                "SUPERVISION_FAILED": 125,
            }.get(outcome)
            if expected_exit_code is not None:
                require(event["exitCode"] == expected_exit_code,
                        f"{prefix} supervised command outcome and exit code disagree")
            if outcome == "FAILED":
                require(event["exitCode"] != 0,
                        f"{prefix} failed command exit code is invalid")
            if outcome != "SUPERVISION_FAILED":
                require(finished_monotonic <= event["deadlineMonotonicMillis"],
                        f"{prefix} successful command finished after its deadline")
            closure = validate_closure(
                event.get("processGroupClosure"),
                f"{prefix} supervisedCommands[{index}].processGroupClosure",
                must_be_proven=outcome in {"FAILED", "TIMED_OUT"},
            )
            if outcome == "SUCCEEDED":
                require(closure["required"] is False,
                        f"{prefix} successful command unexpectedly required closure")
            if event["operation"] == "worktree-cleanup":
                require(outcome == "SUCCEEDED" and event["exitCode"] == 0,
                        f"{prefix} worktree cleanup was not successful")
                worktree_indices.append(index)
            if event["operation"] == "finalization":
                require(outcome == "SUCCEEDED" and event["exitCode"] == 0,
                        f"{prefix} finalization was not successful")
                finalization_indices.append(index)
        else:
            require(event.get("outcome") in {
                "SUCCEEDED", "TIMED_OUT", "SUPERVISION_FAILED", "TEARDOWN_FAILED",
            }, f"{prefix} supervisedCommands[{index}] outcome is invalid")
            processes = event.get("processes")
            require(isinstance(processes, list),
                    f"{prefix} supervisedCommands[{index}] processes are invalid")
            if event["outcome"] == "SUCCEEDED":
                require(finished_monotonic <= event["deadlineMonotonicMillis"],
                        f"{prefix} successful ownership operation finished after its deadline")
            if event["operation"] == "teardown-signaling":
                require(event["outcome"] == "SUCCEEDED" and processes == [],
                        f"{prefix} teardown signaling was not successful")
                ownership_closure = event.get("ownershipClosure")
                require(
                    isinstance(ownership_closure, dict)
                    and ownership_closure.get("required") is True
                    and ownership_closure.get("proven") is True,
                    f"{prefix} teardown closure is not proven",
                )
                closure = validate_closure(
                    ownership_closure,
                    f"{prefix} supervisedCommands[{index}].ownershipClosure",
                    must_be_proven=True,
                )
                require(closure["proven"] is True,
                        f"{prefix} teardown closure is not proven")
                teardown_indices.append(index)

    require(
        len(teardown_indices) == len(worktree_indices) == len(finalization_indices) == 1,
        f"{prefix} derived isolation evidence is incomplete",
    )
    require(teardown_indices[0] < worktree_indices[0] < finalization_indices[0],
            f"{prefix} derived isolation evidence is out of order")

    isolation = run.get("isolation")
    require(isinstance(isolation, dict), f"{prefix}.isolation must be an object")
    require(isolation.get("processTeardownProven") is True, f"{prefix} process teardown is unproven")
    require(isolation.get("worktreeRemoved") is True, f"{prefix} worktree removal is unproven")

    phases = run.get("phases")
    require(isinstance(phases, list), f"{prefix}.phases must be an array")
    require([phase.get("name") for phase in phases if isinstance(phase, dict)] == REQUIRED_PHASES,
            f"{prefix} phase sequence mismatch")
    for phase in phases:
        require(isinstance(phase, dict), f"{prefix} phase must be an object")
        for field in (
            "startedAtEpochMillis", "finishedAtEpochMillis",
            "startedAtMonotonicMillis", "finishedAtMonotonicMillis", "durationMillis",
        ):
            require(nonnegative_integer(phase.get(field)), f"{prefix}.{phase.get('name')}.{field} is invalid")
        require(phase["finishedAtEpochMillis"] >= phase["startedAtEpochMillis"],
                f"{prefix}.{phase['name']} epoch time reversed")
        require(
            phase["finishedAtMonotonicMillis"] - phase["startedAtMonotonicMillis"]
            == phase["durationMillis"],
            f"{prefix}.{phase['name']} duration mismatch",
        )

    storage = run.get("storage")
    require(isinstance(storage, dict) and STORAGE_FIELDS <= storage.keys(),
            f"{prefix}.storage is incomplete")
    for field in STORAGE_FIELDS:
        require(nonnegative_integer(storage.get(field)), f"{prefix}.storage.{field} is invalid")
    resources = run.get("resources")
    require(isinstance(resources, dict) and RESOURCE_FIELDS <= resources.keys(),
            f"{prefix}.resources is incomplete")
    for field in RESOURCE_FIELDS:
        require(nonnegative_number(resources.get(field)), f"{prefix}.resources.{field} is invalid")
    retries = run.get("retries")
    require(isinstance(retries, dict) and RETRY_FIELDS <= retries.keys(),
            f"{prefix}.retries is incomplete")
    for field in RETRY_FIELDS - {"runtimeTransitions"}:
        require(nonnegative_integer(retries.get(field)), f"{prefix}.retries.{field} is invalid")
    transitions = retries.get("runtimeTransitions")
    require(isinstance(transitions, list), f"{prefix} runtime transitions must be an array")
    require(retries["runtimeTransitionCount"] == len(transitions),
            f"{prefix} runtime transition count mismatch")

    samples = run.get("progressSamples")
    require(isinstance(samples, list) and bool(samples), f"{prefix} progress samples are missing")
    observed_states: set[str] = set()
    observed_versions: set[str] = set()
    previous_time: int | None = None
    for index, sample in enumerate(samples):
        require(isinstance(sample, dict), f"{prefix}.progressSamples[{index}] must be an object")
        observed_at = sample.get("observedAtEpochMillis")
        require(nonnegative_integer(observed_at), f"{prefix}.progressSamples[{index}] time is invalid")
        if previous_time is not None:
            require(observed_at >= previous_time, f"{prefix} progress sample time reversed")
        previous_time = observed_at
        require(isinstance(sample.get("phase"), str) and bool(sample.get("phase")),
                f"{prefix}.progressSamples[{index}] phase is invalid")
        require(nonnegative_integer(sample.get("statusExitCode")),
                f"{prefix}.progressSamples[{index}] status exit is invalid")
        require(isinstance(sample.get("storageFresh"), bool),
                f"{prefix}.progressSamples[{index}] storage freshness is invalid")
        storage_observed_at = sample.get("storageObservedAtEpochMillis")
        require(
            nonnegative_integer(storage_observed_at) and storage_observed_at <= observed_at,
            f"{prefix}.progressSamples[{index}] storage observation time is invalid",
        )
        sample_storage = sample.get("storage")
        sample_resources = sample.get("resources")
        require(isinstance(sample_storage, dict) and SAMPLE_STORAGE_FIELDS <= sample_storage.keys(),
                f"{prefix}.progressSamples[{index}] storage is invalid")
        for field in SAMPLE_STORAGE_FIELDS:
            require(nonnegative_integer(sample_storage.get(field)),
                    f"{prefix}.progressSamples[{index}].storage.{field} is invalid")
        require(isinstance(sample_resources, dict) and SAMPLE_RESOURCE_FIELDS <= sample_resources.keys(),
                f"{prefix}.progressSamples[{index}] resources are invalid")
        for field in ("rssBytes", "virtualBytes", "processCount"):
            require(nonnegative_integer(sample_resources.get(field)),
                    f"{prefix}.progressSamples[{index}].resources.{field} is invalid")
        require(nonnegative_number(sample_resources.get("cpuPercent")),
                f"{prefix}.progressSamples[{index}].resources.cpuPercent is invalid")
        process_ids = sample_resources.get("processIds")
        require(
            isinstance(process_ids, list)
            and all(isinstance(pid, int) and not isinstance(pid, bool) and pid > 0 for pid in process_ids)
            and len(process_ids) == len(set(process_ids)),
            f"{prefix}.progressSamples[{index}].resources.processIds is invalid",
        )
        require(sample_resources["processCount"] == len(process_ids),
                f"{prefix}.resources does not match raw benchmark evidence")
        states, versions = runtime_observation(sample.get("runtimeStatus"))
        observed_states.update(states)
        observed_versions.update(versions)
    require(storage == derived_storage(samples),
            f"{prefix}.storage does not match raw benchmark evidence")
    require(resources == derived_resources(samples),
            f"{prefix}.resources does not match raw benchmark evidence")
    require(retries == derived_retries(samples, commands),
            f"{prefix}.retries does not match raw benchmark evidence")
    require(bool(observed_states & {"STARTING", "INDEXING", "READY"}),
            f"{prefix} has no admitted runtime progress evidence")
    require(sorted(set(runtime_versions)) == sorted(observed_versions),
            f"{prefix} recorded runtime versions do not match progress evidence")
    return run


def expected_comparison(stable: dict[str, object], candidate: dict[str, object]) -> dict[str, object]:
    failures: list[dict[str, object]] = []
    correctness_regressions: list[dict[str, object]] = []
    identity_comparisons: list[dict[str, object]] = []
    if stable["workspace"] != candidate["workspace"]:
        failures.append({"code": "CORRECTNESS_CONTEXT_MISMATCH"})
    stable_correctness = stable["correctnessEvidence"]
    candidate_correctness = candidate["correctnessEvidence"]
    for field in sorted(CORRECTNESS_NUMERIC_FIELDS):
        baseline = stable_correctness[field]
        observed = candidate_correctness[field]
        result = {
            "field": field,
            "stableValue": baseline,
            "candidateValue": observed,
            "failed": observed != baseline,
        }
        correctness_regressions.append(result)
        if result["failed"]:
            failures.append({"code": "CORRECTNESS_REGRESSION", **result})
    for field in sorted(CORRECTNESS_IDENTITY_FIELDS):
        baseline = stable_correctness[field]
        observed = candidate_correctness[field]
        result = {
            "field": field,
            "stableValue": baseline,
            "candidateValue": observed,
            "failed": observed != baseline,
        }
        identity_comparisons.append(result)
        if result["failed"]:
            failures.append({"code": "CORRECTNESS_IDENTITY_MISMATCH", **result})

    stable_phases = {phase["name"]: phase["durationMillis"] for phase in stable["phases"]}
    candidate_phases = {phase["name"]: phase["durationMillis"] for phase in candidate["phases"]}
    phase_regressions = []
    for name in COMPARABLE_PHASES:
        baseline = stable_phases[name]
        observed = candidate_phases[name]
        delta = observed - baseline
        failed = baseline == 0 and observed > PHASE_REGRESSION_MILLIS
        failed = failed or (
            baseline > 0
            and observed * 100 > baseline * (100 + PHASE_REGRESSION_PERCENT)
            and delta > PHASE_REGRESSION_MILLIS
        )
        result = {
            "phase": name,
            "stableMillis": baseline,
            "candidateMillis": observed,
            "deltaMillis": delta,
            "deltaPercent": None if baseline == 0 else delta * 100.0 / baseline,
            "failed": failed,
        }
        phase_regressions.append(result)
        if failed:
            failures.append({"code": "PHASE_REGRESSION", **result})
    cold_index = candidate_phases["coldIndex"]
    if cold_index > COLD_INDEX_LIMIT_MILLIS:
        failures.append({
            "code": "COLD_INDEX_LIMIT",
            "candidateMillis": cold_index,
            "limitMillis": COLD_INDEX_LIMIT_MILLIS,
        })
    stable_disk = stable["storage"]["peakOwnedBytes"]
    candidate_disk = candidate["storage"]["peakOwnedBytes"]
    disk_delta = candidate_disk - stable_disk
    disk_failed = stable_disk == 0 and candidate_disk > DISK_REGRESSION_BYTES
    disk_failed = disk_failed or (
        stable_disk > 0
        and candidate_disk * 100 > stable_disk * (100 + DISK_REGRESSION_PERCENT)
        and disk_delta > DISK_REGRESSION_BYTES
    )
    disk_regression = {
        "stableBytes": stable_disk,
        "candidateBytes": candidate_disk,
        "deltaBytes": disk_delta,
        "deltaPercent": None if stable_disk == 0 else disk_delta * 100.0 / stable_disk,
        "failed": disk_failed,
    }
    if disk_failed:
        failures.append({"code": "DISK_REGRESSION", **disk_regression})
    return {
        "schemaVersion": SCHEMA_VERSION,
        "passed": not failures,
        "policy": POLICY,
        "correctnessRegressions": correctness_regressions,
        "correctnessIdentityComparisons": identity_comparisons,
        "phaseRegressions": phase_regressions,
        "comparedPhases": COMPARABLE_PHASES,
        "diskRegression": disk_regression,
        "coldIndexLimitMillis": COLD_INDEX_LIMIT_MILLIS,
        "failures": failures,
    }


@dataclass(frozen=True)
class RepositorySpec:
    name: str
    url: str
    revision: str
    graph_file: str

    @classmethod
    def parse(cls, raw: str) -> "RepositorySpec":
        parts = raw.split("|", 3)
        require(len(parts) == 4, "repository spec must be name|url|revision|graphFile")
        name, url, revision, graph_file = parts
        require(re.fullmatch(r"[a-z0-9-]+", name) is not None, "repository spec name is invalid")
        require(re.fullmatch(r"https://github\.com/[^/]+/[^/]+\.git", url) is not None,
                f"repository URL is invalid for {name}")
        require(SHA.fullmatch(revision) is not None, f"repository revision is invalid for {name}")
        require(canonical_relative_path(graph_file), f"repository graph file is invalid for {name}")
        return cls(name, url, revision, graph_file)

    def payload(self) -> dict[str, str]:
        return {
            "name": self.name,
            "url": self.url,
            "revision": self.revision,
            "graphFile": self.graph_file,
        }


def validate_repository(
    payload: dict[str, object],
    spec: RepositorySpec,
    stable_tag: str,
    stable_digest: str,
    candidate_tag: str,
    candidate_digest: str,
) -> None:
    require(payload.get("schemaVersion") == SCHEMA_VERSION,
            f"{spec.name} repository evidence schemaVersion must equal 1")
    require(payload.get("repository") == spec.payload(), f"{spec.name} repository context mismatch")
    runs = payload.get("runs")
    require(isinstance(runs, list) and len(runs) == 2, f"{spec.name} must contain exactly two runs")
    require([run.get("role") for run in runs if isinstance(run, dict)] == ["stable", "candidate"],
            f"{spec.name} run order and roles must be stable then candidate")
    stable = validate_run(runs[0], "stable", stable_tag, stable_digest, spec.name)
    candidate = validate_run(runs[1], "candidate", candidate_tag, candidate_digest, spec.name)
    comparison = payload.get("comparison")
    require(isinstance(comparison, dict), f"{spec.name}.comparison must be an object")
    expected = expected_comparison(stable, candidate)
    require(comparison == expected, f"{spec.name} comparison does not match strict nested evidence")
    require(expected["passed"] is True, f"{spec.name} comparison did not pass")
    require(payload.get("passed") is True, f"{spec.name} repository evidence did not pass")


def valid_tag_and_digest(tag: str, digest: str, label: str) -> None:
    require(TAG.fullmatch(tag) is not None, f"{label} tag is invalid")
    require(HEX_64.fullmatch(digest) is not None, f"{label} digest is invalid")


def validate_repository_command(args: argparse.Namespace) -> int:
    spec = RepositorySpec.parse(args.repository_spec)
    valid_tag_and_digest(args.stable_tag, args.stable_digest, "stable")
    valid_tag_and_digest(args.candidate_tag, args.candidate_digest, "candidate")
    validate_repository(
        load_object(Path(args.evidence)),
        spec,
        args.stable_tag,
        args.stable_digest,
        args.candidate_tag,
        args.candidate_digest,
    )
    return 0


def aggregate_command(args: argparse.Namespace) -> int:
    require(SHA.fullmatch(args.release_sha) is not None, "release git SHA is invalid")
    valid_tag_and_digest(args.stable_tag, args.stable_digest, "stable")
    valid_tag_and_digest(args.release_tag, args.candidate_digest, "candidate")
    specs = [RepositorySpec.parse(raw) for raw in args.repository_spec]
    specs_by_name = {spec.name: spec for spec in specs}
    require(len(specs_by_name) == len(specs) and bool(specs), "repository specs must be unique and non-empty")
    evidence_by_name: dict[str, dict[str, object]] = {}
    for path in sorted(Path(args.evidence_root).rglob("*.json")):
        payload = load_object(path)
        repository = payload.get("repository")
        name = repository.get("name") if isinstance(repository, dict) else None
        require(name in specs_by_name, f"unexpected benchmark repository in {path}: {name}")
        require(name not in evidence_by_name, f"duplicate benchmark evidence for {name}")
        spec = specs_by_name[name]
        validate_repository(
            payload,
            spec,
            args.stable_tag,
            args.stable_digest,
            args.release_tag,
            args.candidate_digest,
        )
        evidence_by_name[name] = payload
    missing = sorted(set(specs_by_name) - set(evidence_by_name))
    require(not missing, f"benchmark evidence is missing repositories: {missing}")
    aggregate = {
        "schemaVersion": SCHEMA_VERSION,
        "release": {"tag": args.release_tag, "gitSha": args.release_sha},
        "stable": {"tag": args.stable_tag, "sha256": args.stable_digest},
        "candidate": {
            "tag": args.release_tag,
            "gitSha": args.release_sha,
            "sha256": args.candidate_digest,
        },
        "repositories": [evidence_by_name[name] for name in sorted(evidence_by_name)],
        "passed": True,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(aggregate, indent=2) + "\n", encoding="utf-8")
    return 0


def add_bundle_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--stable-tag", required=True)
    parser.add_argument("--stable-digest", required=True)
    parser.add_argument("--candidate-digest", required=True)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate-repository")
    validate.add_argument("--evidence", required=True)
    validate.add_argument("--repository-spec", required=True)
    validate.add_argument("--candidate-tag", required=True)
    add_bundle_arguments(validate)
    validate.set_defaults(handler=validate_repository_command)
    aggregate = commands.add_parser("aggregate-release")
    aggregate.add_argument("--evidence-root", required=True)
    aggregate.add_argument("--output", required=True)
    aggregate.add_argument("--release-tag", required=True)
    aggregate.add_argument("--release-sha", required=True)
    aggregate.add_argument("--repository-spec", action="append", required=True)
    add_bundle_arguments(aggregate)
    aggregate.set_defaults(handler=aggregate_command)
    return root


def main() -> int:
    args = parser().parse_args()
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
