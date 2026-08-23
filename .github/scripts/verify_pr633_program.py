#!/usr/bin/env python3
"""Validate the installed PR 633 program and its exact delivery evidence."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


TASK_IDS = ["001", "002", "010", "020", "030", "040", "050", "060", "070"]
OPERATION_IDS = [
    "workspace.inspect",
    "topology.build",
    "symbol.discover",
    "symbol.resolve",
    "symbol.describe",
    "relation.read",
    "traversal.run",
    "diagnostic.check",
    "change.plan",
    "change.apply",
    "change.verify",
    "change.recover",
]


class VerificationFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationFailure(message)


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
    return {
        "program": base / "kast-pr633-program.json",
        "program_schema": base / "schemas" / "kast-pr633-program.schema.json",
        "evidence_schema": base / "schemas" / "pr633-gate-evidence.schema.json",
        "lifecycle_schema": base / "schemas" / "topology-installed-lifecycle.schema.json",
        "expected_registry": base / "operation-registry.expected.json",
        "path_policy": base / "policies" / "pr633-path-policy.json",
        "cleanup_policy": base / "policies" / "cleanup-path-policy.json",
    }


def verify_artifact(root: Path) -> dict[str, Any]:
    paths = repository_paths(root)
    missing = [str(path.relative_to(root)) for path in paths.values() if not path.is_file()]
    require(not missing, f"missing installed PR 633 artifacts: {missing}")
    program = load_json(paths["program"])
    schema = load_json(paths["program_schema"])
    require(isinstance(program, dict) and isinstance(schema, dict), "program and schema must be objects")
    validate_schema(program, schema, schema)
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
    expected = load_json(paths["expected_registry"])
    require(expected == {"schemaVersion": 1, "operationIds": OPERATION_IDS}, "registry authority differs")
    require(len(set(OPERATION_IDS)) == len(OPERATION_IDS), "registry authority contains duplicates")
    return {"status": "passed", "tasks": task_ids, "gates": gate_ids}


def registry_document(path: Path, nested: bool = False) -> dict[str, Any]:
    value = load_json(path)
    require(isinstance(value, dict), f"registry document is not an object: {path}")
    if nested:
        value = value.get("operationRegistry")
        require(isinstance(value, dict), f"schema has no operationRegistry: {path}")
    return value


def verify_registry(args: argparse.Namespace) -> dict[str, Any]:
    expected = registry_document(args.expected)
    generated = registry_document(args.generated)
    installed = registry_document(args.installed)
    schema = registry_document(args.schema, nested=True)
    require(expected == generated == installed == schema, "operation registry projections differ")
    require(expected.get("operationIds") == OPERATION_IDS, "operation registry order or IDs differ")
    require(args.generated.read_bytes() == args.installed.read_bytes(), "installed registry is not byte-exact")
    return {"status": "passed", "operationIds": OPERATION_IDS}


def verify_authorities(root: Path) -> dict[str, Any]:
    artifact = verify_artifact(root)
    forbidden = ["traversal-run-required", "TopologyGraph.kt"]
    required = ["TOPOLOGY_BUILD_REQUIRED", "REQUIRED_TRAVERSAL_INCOMPLETE"]
    authority_paths = [
        root / "docs/public/questions/safe-change.md",
        root / "docs/public/questions/code-connections.md",
        root / "docs/public/reference/cli.md",
    ]
    missing = [str(path.relative_to(root)) for path in authority_paths if not path.is_file()]
    require(not missing, f"missing public authorities: {missing}")
    joined = "\n".join(path.read_text(encoding="utf-8") for path in authority_paths)
    require(not any(term in joined for term in forbidden), "public authority retains obsolete topology text")
    require(all(term in joined for term in required), "public authority omits typed topology prerequisites")
    return {"status": "passed", "artifact": artifact["status"], "authorities": len(authority_paths)}


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
    registry = commands.add_parser("registry")
    for name in ("expected", "generated", "installed", "schema"):
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
