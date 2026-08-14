#!/usr/bin/env python3
"""Validate the IntelliJ substrate program's source and performance ledger."""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from pathlib import Path
from typing import NoReturn


SHA = re.compile(r"[0-9a-f]{40}")
REQUIRED_SOURCES = {
    "kastArchitectureBaseline",
    "jetbrainsIndexReference",
    "slopsentralProcessBaseline",
    "pr604Implementation",
    "pr604Merge",
}
REQUIRED_CORPORA = {
    "kast-program-baseline": ("PRIMARY", 4, 931),
    "konditional-medium": ("MEDIUM_MULTI_MODULE", 4, 178),
    "kast-enterprise-shaped": ("ENTERPRISE_SHAPED", 6, 601),
}
REQUIRED_OPERATIONS = {
    "file-discovery": "KIP-013",
    "symbol-discovery": "KIP-013",
    "exact-definition": "KIP-014",
    "references": "KIP-015",
    "workspace-admission": "KIP-020",
    "add-declaration-planning": "KIP-030",
}
REQUIRED_TICKET_BASELINES = {
    "KIP-013": ["file-discovery", "symbol-discovery"],
    "KIP-014": ["exact-definition"],
    "KIP-015": ["references"],
    "KIP-017": ["workspace-admission"],
    "KIP-018": ["file-discovery", "symbol-discovery", "exact-definition", "references"],
    "KIP-020": ["workspace-admission"],
    "KIP-022": ["workspace-admission"],
    "KIP-030": ["add-declaration-planning"],
    "KIP-031": ["add-declaration-planning"],
    "KIP-040": ["references"],
    "KIP-056": list(REQUIRED_OPERATIONS),
}
REQUIRED_ENVIRONMENT_INPUTS = {
    "sourceRevision",
    "runtimeBuild",
    "ideaBuild",
    "canonicalRoot",
    "corpusId",
    "machineId",
    "operationId",
    "normalizedRequest",
    "publishedGeneration",
    "state",
}
REQUIRED_STAGE_TIMINGS = {
    "admissionQueueNanos",
    "resourceQueueNanos",
    "resourceAdmissionNanos",
    "smartModeOrTransitionWaitNanos",
    "searchScopeCompilationNanos",
    "nativeQueryNanos",
    "semanticResolutionNanos",
    "persistenceOrPublicationNanos",
    "projectionSerializationNanos",
    "ipcNanos",
}
REQUIRED_WORK_COUNTERS = {
    "vfsRefreshCount",
    "vfsRefreshScope",
    "gradleImportCount",
    "graphBuildCount",
    "sqliteWriteCount",
    "readActionCount",
}
REQUIRED_RESULT_MEASUREMENTS = {
    "recordCount",
    "outputBytes",
    "pageCount",
    "exactness",
    "cancellation",
    "edtObservation",
    "cpuNanos",
    "heapBytes",
}
KIP018_INCREMENT_ID = "kip018-native-public-fast-read-fixture"


def reject(code: str, message: str) -> NoReturn:
    print(f"invalid: {code}: {message}", file=sys.stderr)
    raise SystemExit(1)


def require(condition: bool, code: str, message: str) -> None:
    if not condition:
        reject(code, message)


def object_value(container: object, key: str, code: str) -> dict[str, object]:
    require(isinstance(container, dict), code, "expected an object")
    value = container.get(key)
    require(isinstance(value, dict), code, f"missing object: {key}")
    return value


def exact_string(value: object, code: str, label: str) -> str:
    require(isinstance(value, str) and bool(value.strip()), code, f"missing {label}")
    return str(value)


def exact_sha(value: object, code: str, label: str) -> str:
    rendered = exact_string(value, code, label)
    require(SHA.fullmatch(rendered) is not None, code, f"invalid {label}")
    return rendered


def exact_string_set(value: object, expected: set[str], code: str, label: str) -> None:
    require(
        isinstance(value, list)
        and all(isinstance(item, str) for item in value)
        and set(value) == expected
        and len(value) == len(expected),
        code,
        f"{label} mismatch",
    )


def validate_sources(ledger: dict[str, object]) -> None:
    sources = object_value(ledger, "sources", "SOURCE")
    require(set(sources) == REQUIRED_SOURCES, "SOURCE", "source set is not exact")
    for source_id, raw in sources.items():
        require(isinstance(raw, dict), "SOURCE", f"{source_id} is not an object")
        exact_string(raw.get("repository"), "SOURCE", f"{source_id}.repository")
        exact_sha(raw.get("commitSha"), "SOURCE", f"{source_id}.commitSha")
        exact_string(raw.get("kind"), "SOURCE", f"{source_id}.kind")
        supports = raw.get("supports")
        limitations = raw.get("doesNotSupport")
        require(isinstance(supports, list) and bool(supports), "SOURCE", f"{source_id} supports nothing")
        require(isinstance(limitations, list) and bool(limitations), "SOURCE", f"{source_id} limitations missing")
    for source_id in ("kastArchitectureBaseline", "pr604Implementation", "pr604Merge"):
        exact_sha(sources[source_id].get("treeSha"), "SOURCE", f"{source_id}.treeSha")


def validate_idea(ledger: dict[str, object]) -> None:
    idea = object_value(ledger, "intellij", "IDE")
    supported = object_value(idea, "supportedRuntime", "IDE")
    observed = object_value(idea, "observedMcpHost", "IDE")
    require(supported.get("compileBuild") == "261.25134.95", "IDE", "compile build drift")
    require(supported.get("sinceBuild") == "261", "IDE", "since-build drift")
    require("untilBuild" in supported, "IDE", "until-build policy missing")
    require(observed.get("version") == "2026.2.1", "IDE", "observed host version drift")
    require(observed.get("build") == "IU-262.9437.185", "IDE", "observed host build drift")
    require(observed.get("serverName") == "idea", "IDE", "native MCP server identity missing")


def validate_environment(ledger: dict[str, object]) -> None:
    environment = object_value(ledger, "captureEnvironment", "ENVIRONMENT")
    machine = object_value(environment, "machine", "ENVIRONMENT")
    for field in ("machineId", "osVersion", "architecture", "hardwareModel", "cpuModel"):
        exact_string(machine.get(field), "ENVIRONMENT", f"machine.{field}")
    for field in ("logicalCpuCount", "memoryBytes"):
        require(isinstance(machine.get(field), int) and int(machine[field]) > 0, "ENVIRONMENT", f"machine.{field} missing")
    exact_string(environment.get("javaRuntime"), "ENVIRONMENT", "javaRuntime")
    runtime = object_value(environment, "kastRuntime", "ENVIRONMENT")
    require(runtime.get("state") == "DISABLED_UNTIL_USEFUL", "ENVIRONMENT", "Kast runtime policy drift")
    require(runtime.get("perRunIdentity") == "EXACT_DISTRIBUTION_SHA256", "ENVIRONMENT", "runtime identity policy missing")
    require(environment.get("canonicalRootPolicy") == "PER_RUN_REALPATH", "ENVIRONMENT", "canonical-root policy missing")


def validate_corpora(ledger: dict[str, object]) -> None:
    corpora = ledger.get("corpora")
    require(isinstance(corpora, list), "CORPUS", "corpora are not a list")
    by_id: dict[str, dict[str, object]] = {}
    for raw in corpora:
        require(isinstance(raw, dict), "CORPUS", "corpus is not an object")
        corpus_id = exact_string(raw.get("id"), "CORPUS", "corpus.id")
        require(corpus_id not in by_id, "CORPUS", f"duplicate corpus: {corpus_id}")
        by_id[corpus_id] = raw
    require(set(by_id) == set(REQUIRED_CORPORA), "CORPUS", "corpus set is not exact")
    for corpus_id, (shape, project_count, source_count) in REQUIRED_CORPORA.items():
        corpus = by_id[corpus_id]
        require(corpus.get("shape") == shape, "CORPUS", f"{corpus_id} shape mismatch")
        exact_string(corpus.get("repository"), "CORPUS", f"{corpus_id}.repository")
        exact_sha(corpus.get("commitSha"), "CORPUS", f"{corpus_id}.commitSha")
        exact_sha(corpus.get("treeSha"), "CORPUS", f"{corpus_id}.treeSha")
        require(corpus.get("gradleProjectCount") == project_count, "CORPUS", f"{corpus_id} project count mismatch")
        require(corpus.get("jvmSourceFileCount") == source_count, "CORPUS", f"{corpus_id} source count mismatch")
        require(corpus.get("captureRootPolicy") == "EXACT_CANONICAL_CHECKOUT", "CORPUS", f"{corpus_id} root policy missing")


def validate_measurements(ledger: dict[str, object]) -> None:
    contract = object_value(ledger, "measurementContract", "MEASUREMENTS")
    exact_string_set(contract.get("environmentInputs"), REQUIRED_ENVIRONMENT_INPUTS, "MEASUREMENTS", "environment inputs")
    exact_string_set(contract.get("stageTimings"), REQUIRED_STAGE_TIMINGS, "MEASUREMENTS", "stage timings")
    exact_string_set(contract.get("workCounters"), REQUIRED_WORK_COUNTERS, "MEASUREMENTS", "work counters")
    exact_string_set(contract.get("resultMeasurements"), REQUIRED_RESULT_MEASUREMENTS, "MEASUREMENTS", "result measurements")


def validate_operations(ledger: dict[str, object]) -> None:
    operations = object_value(ledger, "operationBaselines", "OPERATION")
    require(set(operations) == set(REQUIRED_OPERATIONS), "OPERATION", "operation set is not exact")
    for operation_id, owner in REQUIRED_OPERATIONS.items():
        operation = operations[operation_id]
        require(isinstance(operation, dict), "OPERATION", f"{operation_id} is not an object")
        require(operation.get("ownerTicket") == owner, "OPERATION", f"{operation_id} owner mismatch")
        require(operation.get("states") == ["COLD", "WARM"], "OPERATION", f"{operation_id} states mismatch")
        require(operation.get("corpusIds") == list(REQUIRED_CORPORA), "OPERATION", f"{operation_id} corpus matrix mismatch")
        fields = operation.get("normalizedRequestFields")
        require(isinstance(fields, list) and bool(fields) and len(fields) == len(set(fields)), "OPERATION", f"{operation_id} request identity missing")
    ticket_map = ledger.get("ticketPerformanceBaselines")
    require(ticket_map == REQUIRED_TICKET_BASELINES, "TICKET_BASELINE", "ticket-to-operation mapping mismatch")


def validate_history_and_claims(ledger: dict[str, object]) -> None:
    history = ledger.get("historicalEvidence")
    require(isinstance(history, list) and len(history) == 1, "HISTORICAL", "exactly one historical baseline is required")
    evidence = history[0]
    require(isinstance(evidence, dict), "HISTORICAL", "historical baseline is not an object")
    require(evidence.get("id") == "pr604-warm-symbol-discovery", "HISTORICAL", "historical identity drift")
    require(evidence.get("operationId") == "symbol-discovery", "HISTORICAL", "historical operation drift")
    require(evidence.get("sampleNanos") == [640_000_000, 730_000_000, 1_030_000_000], "HISTORICAL", "historical samples were rewritten")
    samples = evidence["sampleNanos"]
    require(evidence.get("medianNanos") == int(statistics.median(samples)), "HISTORICAL", "historical median mismatch")
    require(evidence.get("claimClass") == "HISTORICAL_CONTEXT_ONLY", "HISTORICAL", "historical claim class missing")
    require(evidence.get("comparisonEligible") is False, "HISTORICAL", "historical sample became comparable")
    require(evidence.get("universalTarget") is False, "HISTORICAL", "historical sample became a target")
    require(evidence.get("rawTraceRetained") is False, "HISTORICAL", "raw trace retention was invented")
    limitations = evidence.get("limitations")
    require(isinstance(limitations, list) and len(limitations) >= 3, "HISTORICAL", "historical limitations missing")
    require(ledger.get("referenceProjectLatencyClaims") == [], "REFERENCE_CLAIM", "unmeasured reference latency claim")
    comparison_claims = ledger.get("comparisonClaims")
    require(isinstance(comparison_claims, list), "COMPARISON", "comparison claims are not a list")
    require(not comparison_claims, "COMPARISON", "no comparable same-corpus runs are checked in")


def validate_performance_increments(ledger: dict[str, object]) -> None:
    increments = ledger.get("performanceIncrements")
    require(isinstance(increments, list) and len(increments) == 1, "PERFORMANCE_INCREMENT", "exactly one increment is required")
    increment = increments[0]
    require(isinstance(increment, dict), "PERFORMANCE_INCREMENT", "increment is not an object")
    require(increment.get("id") == KIP018_INCREMENT_ID, "PERFORMANCE_INCREMENT", "increment identity drift")
    require(increment.get("ownerTicket") == "KIP-018", "PERFORMANCE_INCREMENT", "owner mismatch")
    require(
        increment.get("proofClass") == "io.github.amichne.kast.idea.backend.contract.suite.NativePublicSymbolReadTest",
        "PERFORMANCE_INCREMENT",
        "proof class mismatch",
    )
    require(
        increment.get("operationSequence") == ["symbol-discovery", "exact-definition"],
        "PERFORMANCE_INCREMENT",
        "operation sequence mismatch",
    )
    require(increment.get("fixture") == "indexer-light-kotlin-fixture", "PERFORMANCE_INCREMENT", "fixture mismatch")
    require(increment.get("state") == "WARM", "PERFORMANCE_INCREMENT", "state mismatch")
    require(increment.get("runtimeBuild") == "261.25134.95", "PERFORMANCE_INCREMENT", "runtime build mismatch")
    require(
        isinstance(increment.get("publishedGeneration"), int) and int(increment["publishedGeneration"]) > 0,
        "PERFORMANCE_INCREMENT",
        "published generation missing",
    )
    require(increment.get("completeness") == "EXACT", "PERFORMANCE_INCREMENT", "completeness is not exact")
    require(increment.get("qualifications") == [], "PERFORMANCE_INCREMENT", "qualified result cannot prove the increment")

    observations = object_value(increment, "stageObservations", "PERFORMANCE_INCREMENT")
    require(set(observations) == REQUIRED_STAGE_TIMINGS, "PERFORMANCE_INCREMENT", "stage observation set mismatch")
    measured = {
        "admissionQueueNanos",
        "searchScopeCompilationNanos",
        "nativeQueryNanos",
        "semanticResolutionNanos",
        "projectionSerializationNanos",
    }
    not_applicable = {
        "resourceQueueNanos",
        "resourceAdmissionNanos",
        "smartModeOrTransitionWaitNanos",
        "persistenceOrPublicationNanos",
    }
    for stage in measured:
        observation = object_value(observations, stage, "PERFORMANCE_INCREMENT")
        require(observation.get("state") == "MEASURED", "PERFORMANCE_INCREMENT", f"{stage} is not measured")
        require(
            isinstance(observation.get("nanoseconds"), int) and int(observation["nanoseconds"]) >= 0,
            "PERFORMANCE_INCREMENT",
            f"{stage} duration missing",
        )
    for stage in not_applicable:
        require(
            observations.get(stage) == {"state": "NOT_APPLICABLE"},
            "PERFORMANCE_INCREMENT",
            f"{stage} applicability drift",
        )
    require(
        observations.get("ipcNanos") == {"state": "OUTSIDE_RESPONSE_BOUNDARY"},
        "PERFORMANCE_INCREMENT",
        "IPC boundary drift",
    )

    counters = object_value(increment, "workCounters", "PERFORMANCE_INCREMENT")
    require(set(counters) == REQUIRED_WORK_COUNTERS, "PERFORMANCE_INCREMENT", "work counter set mismatch")
    for counter in ("vfsRefreshCount", "gradleImportCount", "graphBuildCount", "sqliteWriteCount"):
        require(counters.get(counter) == 0, "PERFORMANCE_INCREMENT", f"forbidden work observed: {counter}")
    require(counters.get("vfsRefreshScope") == "NONE", "PERFORMANCE_INCREMENT", "refresh scope is not empty")
    require(counters.get("readActionCount") == 1, "PERFORMANCE_INCREMENT", "read action count mismatch")

    result = object_value(increment, "resultMeasurements", "PERFORMANCE_INCREMENT")
    require(set(result) == {"recordCount", "outputBytes", "exactness"}, "PERFORMANCE_INCREMENT", "result shape mismatch")
    require(result.get("recordCount") == 1, "PERFORMANCE_INCREMENT", "record count mismatch")
    require(isinstance(result.get("outputBytes"), int) and int(result["outputBytes"]) > 0, "PERFORMANCE_INCREMENT", "output bytes missing")
    require(result.get("exactness") == "EXACT", "PERFORMANCE_INCREMENT", "result exactness mismatch")
    require(increment.get("claimClass") == "BOUNDED_WORK_NON_REGRESSION", "PERFORMANCE_INCREMENT", "claim class mismatch")
    require(increment.get("comparisonEligible") is False, "PERFORMANCE_INCREMENT", "increment became comparison eligible")
    comparison = object_value(increment, "historicalComparison", "PERFORMANCE_INCREMENT")
    require(comparison.get("evidenceId") == "pr604-warm-symbol-discovery", "PERFORMANCE_INCREMENT", "historical evidence mismatch")
    require(comparison.get("outcome") == "INELIGIBLE", "PERFORMANCE_INCREMENT", "historical comparison became eligible")
    reasons = comparison.get("reasons")
    require(isinstance(reasons, list) and len(reasons) >= 4, "PERFORMANCE_INCREMENT", "ineligibility reasons missing")
    require(
        increment.get("proofCommand")
        == "./gradlew :indexer:test --tests '*NativePublicSymbolReadTest' --no-daemon --console=plain",
        "PERFORMANCE_INCREMENT",
        "proof command mismatch",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, required=True)
    args = parser.parse_args()
    try:
        ledger = json.loads(args.ledger.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        reject("READ", str(error))
    require(isinstance(ledger, dict), "SHAPE", "ledger is not an object")
    require(ledger.get("type") == "KAST_INTELLIJ_SUBSTRATE_LEDGER", "SHAPE", "wrong ledger type")
    require(ledger.get("schemaVersion") == 1, "SHAPE", "wrong schema version")
    validate_sources(ledger)
    validate_idea(ledger)
    validate_environment(ledger)
    validate_corpora(ledger)
    validate_measurements(ledger)
    validate_operations(ledger)
    validate_history_and_claims(ledger)
    validate_performance_increments(ledger)
    commands = ledger.get("proofCommands")
    require(isinstance(commands, list) and len(commands) == 2, "PROOF", "proof commands missing")
    require(all(isinstance(command, str) and command.startswith("python3 .agents/arch/") for command in commands), "PROOF", "proof command is not repository-relative")
    print("IntelliJ substrate source/performance ledger: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
