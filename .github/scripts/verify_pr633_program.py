#!/usr/bin/env python3
"""Validate the installed PR 633 program and its exact delivery evidence."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

from pr633.program_authority import (
    EXPECTED_CLEAN_SLATE_DEPENDENCIES,
    VerificationFailure,
    require,
    verify_ci_reuse,
    verify_gate_authority_tokens,
    verify_gate_check_bindings,
    verify_invariant_enforcers,
    verify_no_rust_paths,
    verify_no_rust_product,
    verify_repository_sources,
    verify_topology_api_zero_budget_policy,
)


TASK_IDS = "001 002 010 020 030 040 050 060 070".split()
FORBIDDEN_FOLLOW_ON_OPERATION_IDS = """topology.path topology.condensation topology.quotient
topology.cycles topology.scc topology.query""".split()
CLEAN_SLATE_SEMANTICS = {
    "relation.read": {"authority": "LIVE_K2", "maximumHops": 1, "topology": "NOT_USED"},
    "topology.build": {"authority": "PUBLISHED_WORKSPACE_K2", "effect": "PUBLISH_COMPLETE_SQLITE_SNAPSHOT"},
    "traversal.run": {"authority": "ELIGIBLE_SQLITE_SNAPSHOT", "bounded": True, "implicitBuild": False,
                      "missingOrStale": "TOPOLOGY_BUILD_REQUIRED", "staleSelector": "SELECTOR_STALE"},
    "change.plan": {"implicitBuild": False, "missingOrStaleTopology": "TOPOLOGY_BUILD_REQUIRED",
                    "incompleteRequiredTraversal": "REQUIRED_TRAVERSAL_INCOMPLETE"},
}


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationFailure(f"cannot read JSON {path}: {error}") from error


def resolve_reference(reference: str, root: dict[str, Any]) -> dict[str, Any]:
    require(reference.startswith("#/"), f"unsupported schema reference: {reference}")
    value: Any = root
    for part in reference[2:].split("/"):
        require(isinstance(value, dict) and part in value, f"missing schema reference: {reference}")
        value = value[part]
    require(isinstance(value, dict), f"schema reference is not an object: {reference}")
    return value


def validate_schema(value: Any, schema: dict[str, Any], root: dict[str, Any], path: str = "$") -> None:
    if "$ref" in schema:
        validate_schema(value, resolve_reference(schema["$ref"], root), root, path)
        return
    expected_type = schema.get("type")
    type_matches = {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
    }
    if expected_type:
        require(type_matches.get(expected_type, False), f"{path} is not {expected_type}")
    if "const" in schema:
        require(value == schema["const"], f"{path} differs from schema constant")
    if "enum" in schema:
        require(value in schema["enum"], f"{path} is outside the closed schema enum")
    if isinstance(value, int) and not isinstance(value, bool) and "minimum" in schema:
        require(value >= schema["minimum"], f"{path} is below the schema minimum")
    if isinstance(value, str):
        require(len(value) >= schema.get("minLength", 0), f"{path} is too short")
        if "pattern" in schema:
            require(re.search(schema["pattern"], value) is not None, f"{path} does not match schema")
    if isinstance(value, list):
        require(len(value) >= schema.get("minItems", 0), f"{path} has too few items")
        if "maxItems" in schema:
            require(len(value) <= schema["maxItems"], f"{path} has too many items")
        if schema.get("uniqueItems"):
            encoded = [json.dumps(item, sort_keys=True, separators=(",", ":")) for item in value]
            require(len(encoded) == len(set(encoded)), f"{path} has duplicate items")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(value):
                validate_schema(item, item_schema, root, f"{path}[{index}]")
    if isinstance(value, dict):
        properties = schema.get("properties", {})
        for name in schema.get("required", []):
            require(name in value, f"{path}.{name} is required")
        if schema.get("additionalProperties") is False:
            extra = sorted(set(value) - set(properties))
            require(not extra, f"{path} has unexpected fields: {extra}")
        for name, child in value.items():
            child_schema = properties.get(name)
            if child_schema is None and isinstance(schema.get("additionalProperties"), dict):
                child_schema = schema["additionalProperties"]
            if isinstance(child_schema, dict):
                validate_schema(child, child_schema, root, f"{path}.{name}")


def repository_paths(root: Path) -> dict[str, Path]:
    base = root / "gradle" / "pr633"
    schemas = base / "schemas"
    return {
        "program": base / "kast-pr633-program.json", "program_schema": schemas / "kast-pr633-program.schema.json",
        "evidence_schema": schemas / "pr633-gate-evidence.schema.json", "lifecycle_schema": schemas / "topology-installed-lifecycle.schema.json",
        "path_policy": base / "policies/pr633-path-policy.json",
        "cleanup_policy": base / "policies/cleanup-path-policy.json", "clean_slate_plan": root / "kast-clean-slate-plan.md",
        "clean_slate_graph": root / "kast-clean-slate-task-graph.json", "clean_slate_graph_schema": schemas / "kast-clean-slate-task-graph.schema.json",
        "ci_workflow": root / ".github/workflows/ci.yml",
    }


def program_operation_ids(program: dict[str, Any]) -> list[str]:
    operation_sets = program.get("operationSets")
    require(isinstance(operation_sets, dict), "program operationSets is not an object")
    operation_ids = operation_sets.get("pr633")
    require(isinstance(operation_ids, list) and operation_ids, "program pr633 operation set is not a non-empty array")
    require(all(isinstance(value, str) and value for value in operation_ids), "program pr633 operation ID is invalid")
    require(len(operation_ids) == len(set(operation_ids)), "program pr633 operation set contains duplicates")
    return operation_ids


def verify_artifact(root: Path) -> dict[str, Any]:
    paths = repository_paths(root)
    missing = [str(path.relative_to(root)) for path in paths.values() if not path.is_file()]
    require(not missing, f"missing installed PR 633 artifacts: {missing}")
    program = load_json(paths["program"])
    schema = load_json(paths["program_schema"])
    require(isinstance(program, dict) and isinstance(schema, dict), "program and schema must be objects")
    validate_schema(program, schema, schema)
    operation_ids = program_operation_ids(program)
    verify_topology_operation_ids(operation_ids)
    verify_repository_sources(program, root)
    verify_invariant_enforcers(program, root)
    verify_gate_check_bindings(program, root)
    verify_ci_reuse(paths["ci_workflow"].read_text(encoding="utf-8"))
    observed = program["observedState"]
    require([fact["gateId"] for fact in observed["deliveryFacts"]] == ["GATE-001", "GATE-002", "GATE-070"], "delivery fact gates differ")
    require(observed["terminalDisposition"] == program["status"], "observed terminal disposition differs")
    require(re.search(r"\b[0-9a-f]{40}\b", json.dumps(program)) is None, "program embeds a self-stale Git SHA")
    expected_tasks = [f"KTP633-{value}" for value in TASK_IDS]
    expected_gates = [f"GATE-{value}" for value in TASK_IDS]
    task_ids = [task.get("id") for task in program["tasks"]]
    gate_ids = [gate.get("id") for gate in program["gates"]]
    require(task_ids == expected_tasks, f"task execution chain differs: {task_ids}")
    require(gate_ids == expected_gates, f"gate execution chain differs: {gate_ids}")
    require(program["status"] == "merge-ready-unmerged", "program status must stop before merge")
    for index, gate in enumerate(program["gates"]):
        expected_dependencies = [] if index == 0 else [expected_gates[index - 1]]
        require(gate["dependsOn"] == expected_dependencies, f"{gate['id']} has wrong dependency chain")
        require(gate["taskId"] == expected_tasks[index], f"{gate['id']} has wrong task binding")
    final_gate = program["finalCleanGate"]
    require(final_gate["aggregateGradleTask"] == "pr633MergeCandidateAcceptance", "wrong aggregate")
    require(final_gate["commands"] == ["packaging/pr633-final-gate.sh"], "final gate bypasses wrapper")
    return {"status": "passed", "tasks": task_ids, "gates": gate_ids, "operationIds": operation_ids}


def registry_document(path: Path, nested: bool = False) -> dict[str, Any]:
    value = load_json(path)
    require(isinstance(value, dict), f"registry document is not an object: {path}")
    if nested:
        value = value.get("operationRegistry")
        require(isinstance(value, dict), f"schema has no operationRegistry: {path}")
    return value


def verify_registry(args: argparse.Namespace) -> dict[str, Any]:
    program = load_json(args.program)
    require(isinstance(program, dict), f"program document is not an object: {args.program}")
    operation_ids = program_operation_ids(program)
    expected = {"schemaVersion": 1, "operationIds": operation_ids}
    generated = registry_document(args.generated)
    installed = registry_document(args.installed)
    schema = registry_document(args.schema, nested=True)
    require(expected == generated == installed == schema, "operation registry projections differ")
    require(args.generated.read_bytes() == args.installed.read_bytes(), "installed registry is not byte-exact")
    return {"status": "passed", "operationIds": operation_ids}


def marked_plan_operations(plan: str) -> list[str]:
    match = re.search(
        r"<!-- canonical-operations:start -->\s*```text\s*(.*?)\s*```\s*"
        r"<!-- canonical-operations:end -->",
        plan,
        re.DOTALL,
    )
    require(match is not None, "clean-slate plan has no canonical operation block")
    return [line.strip() for line in match.group(1).splitlines() if line.strip()]


def verify_clean_slate_graph(graph: dict[str, Any], operation_ids: list[str]) -> None:
    require(graph.get("operationIds") == operation_ids, "clean-slate graph operation set differs")
    require(graph.get("semantics") == CLEAN_SLATE_SEMANTICS, "clean-slate semantics differ")
    tasks = graph.get("tasks")
    require(isinstance(tasks, list), "clean-slate tasks are not an array")
    task_ids = [task.get("id") for task in tasks]
    expected_ids = {f"KCS-{index:03d}" for index in range(1, 24)}
    require(len(task_ids) == len(set(task_ids)), "clean-slate graph has duplicate task IDs")
    require(set(task_ids) == expected_ids, f"clean-slate task set differs: {task_ids}")
    task_by_id = {task["id"]: task for task in tasks}
    remaining: dict[str, set[str]] = {}
    for task_id, task in task_by_id.items():
        dependencies = task.get("dependsOn")
        require(isinstance(dependencies, list), f"{task_id} dependencies are not an array")
        require(len(dependencies) == len(set(dependencies)), f"{task_id} repeats a dependency")
        require(set(dependencies) <= expected_ids, f"{task_id} has an unknown dependency")
        require(
            all(task_by_id[dependency]["wave"] <= task["wave"] for dependency in dependencies),
            f"{task_id} depends on a later wave",
        )
        remaining[task_id] = set(dependencies)
    for task_id, expected in EXPECTED_CLEAN_SLATE_DEPENDENCIES.items():
        require(remaining[task_id] == expected, f"{task_id} dependency set differs")
    while remaining:
        ready = {task_id for task_id, dependencies in remaining.items() if not dependencies}
        require(bool(ready), "clean-slate task graph contains a dependency cycle")
        remaining = {
            task_id: dependencies - ready
            for task_id, dependencies in remaining.items()
            if task_id not in ready
        }
    lifecycle = task_by_id["KCS-021"]["scope"] + task_by_id["KCS-021"]["green"]["expected"]
    require(
        all(term in lifecycle.lower() for term in ["publish", "reuse", "restart", "stale", "rebuild"]),
        "installed clean-slate acceptance omits the durable topology lifecycle",
    )


def verify_authorities(root: Path) -> dict[str, Any]:
    artifact = verify_artifact(root)
    operation_ids = artifact["operationIds"]
    paths = repository_paths(root)
    forbidden = ["traversal-run-required", "TopologyGraph.kt"]
    required = ["TOPOLOGY_BUILD_REQUIRED", "REQUIRED_TRAVERSAL_INCOMPLETE"]
    authority_paths = [
        root / "docs/public/questions/safe-change.md",
        root / "docs/public/questions/code-connections.md",
        root / "docs/public/reference/cli.md",
        paths["clean_slate_plan"],
        paths["clean_slate_graph"],
    ]
    missing = [str(path.relative_to(root)) for path in authority_paths if not path.is_file()]
    require(not missing, f"missing public authorities: {missing}")
    joined = "\n".join(path.read_text(encoding="utf-8") for path in authority_paths)
    require(not any(term in joined for term in forbidden), "public authority retains obsolete topology text")
    require(all(term in joined for term in required), "public authority omits typed topology prerequisites")
    require("eleven" not in joined.lower(), "authority still describes an eleven-operation product")

    plan = paths["clean_slate_plan"].read_text(encoding="utf-8")
    graph_text = paths["clean_slate_graph"].read_text(encoding="utf-8")
    graph = load_json(paths["clean_slate_graph"])
    graph_schema = load_json(paths["clean_slate_graph_schema"])
    require(isinstance(graph, dict), "clean-slate task graph is not an object")
    require(isinstance(graph_schema, dict), "clean-slate task graph schema is not an object")
    validate_schema(graph, graph_schema, graph_schema)
    require(marked_plan_operations(plan) == operation_ids, "clean-slate plan operation set differs")
    require(
        all(operation_id not in plan for operation_id in FORBIDDEN_FOLLOW_ON_OPERATION_IDS),
        "clean-slate plan exposes a follow-on topology operation",
    )
    require(re.search(r"\b[0-9a-f]{40}\b", plan + graph_text) is None, "clean-slate authority embeds a Git SHA")
    verify_clean_slate_graph(graph, operation_ids)
    safe_change = (root / "docs/public/questions/safe-change.md").read_text(encoding="utf-8")
    connections = (root / "docs/public/questions/code-connections.md").read_text(encoding="utf-8")
    cli = (root / "docs/public/reference/cli.md").read_text(encoding="utf-8")
    require(safe_change.index("kast topology build") < safe_change.index("kast change plan"), "safe-change order differs")
    require(all(term in connections for term in ["one-hop K2", "eligible SQLite snapshot"]), "connection authorities differ")
    cli_operations = re.findall(r"^\| `([a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+)` \|", cli, re.MULTILINE)
    require(cli_operations == operation_ids, f"generated CLI operation set differs: {cli_operations}")
    return {"status": "passed", "artifact": artifact["status"], "authorities": len(authority_paths)}


def verify_operation_names(root: Path) -> dict[str, Any]:
    paths = repository_paths(root)
    program = load_json(paths["program"])
    require(isinstance(program, dict), "program document is not an object")
    operation_ids = program_operation_ids(program)

    canonical_path = root / "protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt"
    canonical = canonical_path.read_text(encoding="utf-8")
    canonical_ids = re.findall(r'canonicalOperationId\("([^"]+)"\)', canonical)
    require(canonical_ids == operation_ids, f"canonical operation enum differs: {canonical_ids}")
    verify_topology_operation_ids(canonical_ids)

    production_roots = [
        root / "protocol/contract/src/main",
        root / "protocol/registry/src/main",
        root / "protocol/wire/src/main",
        root / "cli/src/main",
        root / "runtime/server/src/main",
        root / "runtime/composition/src/main",
    ]
    violations: list[str] = []
    for production_root in production_roots:
        for path in sorted(production_root.rglob("*")):
            if not path.is_file() or path.suffix not in {".kt", ".kts"}:
                continue
            text = path.read_text(encoding="utf-8")
            for operation_id in FORBIDDEN_FOLLOW_ON_OPERATION_IDS:
                if operation_id in text:
                    violations.append(f"{path.relative_to(root)}: {operation_id}")
    require(not violations, f"follow-on topology operation entered production: {violations}")
    return {"status": "passed", "operationIds": canonical_ids, "forbidden": FORBIDDEN_FOLLOW_ON_OPERATION_IDS}


def verify_topology_operation_ids(operation_ids: list[str]) -> None:
    topology_ids = [operation_id for operation_id in operation_ids if operation_id.startswith("topology.")]
    require(topology_ids == ["topology.build"], f"topology operation set differs: {topology_ids}")


def verify_lifecycle(report: Path, schema_path: Path) -> dict[str, Any]:
    value = load_json(report)
    schema = load_json(schema_path)
    require(isinstance(value, dict), "lifecycle report is not an object")
    require(isinstance(schema, dict), "lifecycle schema is not an object")
    validate_schema(value, schema, schema)
    return {"status": "passed", "kind": value.get("kind"), "report": str(report)}


def verify_ci_artifact(report: Path, expected_head: str) -> dict[str, Any]:
    require(re.fullmatch(r"[0-9a-f]{40}", expected_head) is not None, "expected head is not a Git SHA")
    value = load_json(report)
    require(isinstance(value, dict), "gate report is not an object")
    require(value.get("gateId") == "GATE-060", "CI artifact is not GATE-060")
    require(value.get("headSha") == expected_head, "CI artifact belongs to another head")
    require(value.get("status") == "passed", "CI artifact did not pass")
    require(value.get("dependencyEvidence"), "CI artifact has no dependency evidence")
    return {"status": "passed", "headSha": expected_head, "gateId": "GATE-060"}


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    artifact = commands.add_parser("artifact")
    artifact.add_argument("--root", type=Path, required=True)
    authorities = commands.add_parser("authorities")
    authorities.add_argument("--root", type=Path, required=True)
    operation_names = commands.add_parser("operation-names")
    operation_names.add_argument("--root", type=Path, required=True)
    no_rust = commands.add_parser("no-rust-product")
    no_rust.add_argument("--root", type=Path, required=True)
    registry = commands.add_parser("registry")
    for name in ("program", "generated", "installed", "schema"):
        registry.add_argument(f"--{name}", type=Path, required=True)
    ci = commands.add_parser("ci-artifact")
    ci.add_argument("--report", type=Path, required=True)
    ci.add_argument("--expected-head", required=True)
    lifecycle = commands.add_parser("lifecycle")
    lifecycle.add_argument("--report", type=Path, required=True)
    lifecycle.add_argument("--schema", type=Path, required=True)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "artifact":
            result = verify_artifact(args.root.resolve())
        elif args.command == "authorities":
            result = verify_authorities(args.root.resolve())
        elif args.command == "operation-names":
            result = verify_operation_names(args.root.resolve())
        elif args.command == "no-rust-product":
            result = verify_no_rust_product(args.root.resolve())
        elif args.command == "registry":
            result = verify_registry(args)
        elif args.command == "lifecycle":
            result = verify_lifecycle(args.report, args.schema)
        else:
            result = verify_ci_artifact(args.report, args.expected_head)
        print(json.dumps(result, sort_keys=True))
        return 0
    except VerificationFailure as error:
        print(f"PR 633 verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
