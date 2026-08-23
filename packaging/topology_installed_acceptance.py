#!/usr/bin/env python3
"""Run and verify the installed durable-topology lifecycle for PR #633."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

from topology_installed_support import AcceptanceFailure
from topology_installed_support import InstalledKast
from topology_installed_support import discover_selector
from topology_installed_support import expect_rejection
from topology_installed_support import expect_status
from topology_installed_support import invocation_projection
from topology_installed_support import load_registry
from topology_installed_support import normalize_traversal
from topology_installed_support import operation_ids
from topology_installed_support import require
from topology_installed_support import require_build_identity
from topology_installed_support import sha256_text
from topology_installed_support import topology_build
from topology_installed_support import traversal_callers
from topology_installed_support import verify_document
from topology_installed_support import write_json


def change_plan(kast: InstalledKast, selector: str, declaration: str) -> dict[str, Any]:
    return kast.semantic(
        "change.plan",
        "change",
        "plan",
        "--intent",
        "add-declaration",
        "--target",
        selector,
        "--declaration",
        declaration,
    )


def require_topology_prerequisite(
    kast: InstalledKast,
    class_selector: str,
    greeting_selector: str,
    declaration: str,
) -> dict[str, str]:
    traversal = traversal_callers(kast, greeting_selector)
    expect_rejection(traversal, "topology-build-required")
    require("reached" not in traversal, "topology prerequisite returned traversal payload")
    planning = change_plan(kast, class_selector, declaration)
    expect_rejection(planning, "topology-build-required")
    require("planIdentity" not in planning, "topology prerequisite returned plan payload")
    return {
        "traversal": str(traversal["reason"]),
        "changePlan": str(planning["reason"]),
    }


def run(args: argparse.Namespace) -> dict[str, Any]:
    kast = InstalledKast(Path(args.kast), Path(args.workspace))
    registry = load_registry(Path(args.registry))
    source_path = kast.workspace / "src/main/kotlin/example/Greeter.kt"
    require(source_path.is_file(), f"fixture source is missing: {source_path}")
    source_before = source_path.read_text(encoding="utf-8")
    require("fun firstCaller(): String = greeting()" in source_before, "fixture has no firstCaller")
    require("fun secondCaller(): String = greeting()" not in source_before, "fixture has secondCaller")

    schema = kast.schema()
    require(schema.get("operationRegistry") == registry, "installed schema registry differs")
    inspected = kast.semantic("workspace.inspect", "workspace", "inspect")
    require(inspected.get("status") in {"complete", "qualified"}, str(inspected))

    class_selector_g0 = discover_selector(kast, "Greeter", "Greeter")
    greeting_selector_g0 = discover_selector(kast, "greeting", "greeting")
    described = kast.semantic(
        "symbol.describe",
        "symbol",
        "describe",
        "--selector",
        greeting_selector_g0,
    )
    expect_status(described, "complete")
    before_first_build = require_topology_prerequisite(
        kast,
        class_selector_g0,
        greeting_selector_g0,
        "fun neverAppliedBeforeBuild(): String = greeting()",
    )

    generation_g0, digest_d0 = require_build_identity(topology_build(kast), "published")
    reused_generation, reused_digest = require_build_identity(topology_build(kast), "reused")
    require(reused_generation == generation_g0, "reused build changed generation")
    require(reused_digest == digest_d0, "reused build changed digest")

    traversal_before = traversal_callers(kast, greeting_selector_g0)
    normalized_before = normalize_traversal(traversal_before)
    require("firstCaller" in normalized_before, "baseline traversal omits firstCaller")
    require("secondCaller" not in normalized_before, "baseline traversal contains future caller")

    kast.invoke("stop", json_output=False)
    kast.invoke("start", json_output=False)
    normalized_after_restart = normalize_traversal(traversal_callers(kast, greeting_selector_g0))
    require(normalized_after_restart == normalized_before, "restart changed traversal result")
    class_selector_for_change = discover_selector(kast, "Greeter", "Greeter")

    plan = change_plan(
        kast,
        class_selector_for_change,
        "fun secondCaller(): String = greeting()",
    )
    expect_status(plan, "complete")
    plan_identity = plan.get("planIdentity")
    require(isinstance(plan_identity, str) and plan_identity, f"plan identity missing: {plan}")
    applied = kast.semantic("change.apply", "change", "apply", "--plan", plan_identity)
    expect_status(applied, "complete")
    application_identity = applied.get("applicationIdentity")
    require(isinstance(application_identity, str) and application_identity, "application missing")
    verified = kast.semantic(
        "change.verify",
        "change",
        "verify",
        "--application",
        application_identity,
    )
    expect_status(verified, "complete")
    require(verified.get("receiptIdentity"), f"receipt identity missing: {verified}")
    source_after = source_path.read_text(encoding="utf-8")
    require("fun secondCaller(): String = greeting()" in source_after, "mutation missing")

    old_selector_result = traversal_callers(kast, greeting_selector_g0)
    expect_rejection(old_selector_result, "selector-stale")
    class_selector_g1 = discover_selector(kast, "Greeter", "Greeter")
    greeting_selector_g1 = discover_selector(kast, "greeting", "greeting")
    require(greeting_selector_g1 != greeting_selector_g0, "fresh selector did not advance")
    after_mutation = require_topology_prerequisite(
        kast,
        class_selector_g1,
        greeting_selector_g1,
        "fun neverAppliedAfterMutation(): String = greeting()",
    )

    generation_g1, digest_d1 = require_build_identity(topology_build(kast), "published")
    require(generation_g1 != generation_g0, "rebuild did not advance generation")
    require(digest_d1 != digest_d0, "rebuild did not change digest")
    normalized_after_rebuild = normalize_traversal(
        traversal_callers(kast, greeting_selector_g1),
    )
    require("firstCaller" in normalized_after_rebuild, "rebuilt traversal omits firstCaller")
    require("secondCaller" in normalized_after_rebuild, "rebuilt traversal omits secondCaller")

    report = {
        "schemaVersion": 1,
        "kind": "kast-pr633-installed-topology-lifecycle",
        "operationIds": operation_ids(registry),
        "operationRegistrySha256": sha256_text(
            json.dumps(registry, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        ),
        "workspace": str(kast.workspace),
        "source": {
            "relativePath": "src/main/kotlin/example/Greeter.kt",
            "beforeSha256": sha256_text(source_before),
            "afterSha256": sha256_text(source_after),
        },
        "prerequisites": {
            "beforeFirstBuild": before_first_build,
            "afterMutation": after_mutation,
        },
        "selectors": {
            "classG0Sha256": sha256_text(class_selector_g0),
            "classMutationSha256": sha256_text(class_selector_for_change),
            "classG1Sha256": sha256_text(class_selector_g1),
            "greetingG0Sha256": sha256_text(greeting_selector_g0),
            "greetingG1Sha256": sha256_text(greeting_selector_g1),
            "oldSelectorRejection": old_selector_result["reason"],
        },
        "firstBuild": {
            "status": "published",
            "generation": generation_g0,
            "digest": "sha256:" + digest_d0,
        },
        "secondBuild": {
            "status": "reused",
            "generation": reused_generation,
            "digest": "sha256:" + reused_digest,
        },
        "restart": {
            "traversalBeforeSha256": sha256_text(normalized_before),
            "traversalAfterSha256": sha256_text(normalized_after_restart),
            "semanticResultEqual": normalized_before == normalized_after_restart,
            "topologyBuildInvokedBetweenStopAndTraversal": False,
        },
        "mutation": {
            "planIdentitySha256": sha256_text(plan_identity),
            "applicationIdentitySha256": sha256_text(application_identity),
            "receiptIdentitySha256": sha256_text(str(verified["receiptIdentity"])),
            "sourceChanged": source_before != source_after,
        },
        "rebuild": {
            "status": "published",
            "generation": generation_g1,
            "digest": "sha256:" + digest_d1,
            "generationChanged": generation_g1 != generation_g0,
            "digestChanged": digest_d1 != digest_d0,
        },
        "semanticResults": {
            "beforeRestartSha256": sha256_text(normalized_before),
            "afterRestartSha256": sha256_text(normalized_after_restart),
            "afterRebuildSha256": sha256_text(normalized_after_rebuild),
            "beforeRestartNames": sorted(
                value.get("name")
                for value in json.loads(normalized_before)
                if isinstance(value.get("name"), str)
            ),
            "afterRebuildNames": sorted(
                value.get("name")
                for value in json.loads(normalized_after_rebuild)
                if isinstance(value.get("name"), str)
            ),
        },
        "invocations": [invocation_projection(value) for value in kast.invocations],
    }
    verify_document(report, registry)
    return report


def verify(args: argparse.Namespace) -> dict[str, Any]:
    path = Path(args.report)
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise AcceptanceFailure(f"report is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise AcceptanceFailure(f"report is invalid JSON: {path}: {error}") from error
    require(isinstance(report, dict), "report root must be an object")
    verify_document(report, load_registry(Path(args.registry)))
    return {
        "schemaVersion": 1,
        "kind": "kast-pr633-installed-topology-lifecycle-verification",
        "report": str(path),
        "reportSha256": "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest(),
        "status": "passed",
    }


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    run_parser = commands.add_parser("run")
    run_parser.add_argument("--kast", required=True)
    run_parser.add_argument("--workspace", required=True)
    run_parser.add_argument("--registry", required=True)
    run_parser.add_argument("--report", required=True)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--report", required=True)
    verify_parser.add_argument("--registry", required=True)
    verify_parser.add_argument("--output")
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "run":
            write_json(run(args), args.report)
        elif args.command == "verify":
            write_json(verify(args), args.output)
        else:
            raise AssertionError(args.command)
    except AcceptanceFailure as error:
        print(f"installed topology acceptance failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
