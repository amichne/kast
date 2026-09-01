#!/usr/bin/env python3
"""Fail-closed enterprise acceptance for the staged Kast product."""

from __future__ import annotations

import argparse
from collections.abc import Collection
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from typing import Any


def fail(message: str) -> None:
    raise SystemExit(f"enterprise-acceptance: {message}")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--product-root", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--thresholds", type=Path, required=True)
    parser.add_argument("--runtime-archive", type=Path, required=True)
    return parser.parse_args()


def positive_integer(document: dict[str, Any], name: str, minimum: int = 1) -> int:
    value = document.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        fail(f"{name} must be an integer greater than or equal to {minimum}")
    return value


def declaration_candidates(document: dict[str, Any]) -> list[str]:
    items = document.get("items")
    if not isinstance(items, list):
        fail(f"symbol discovery omitted structured items: {document}")
    candidates: list[str] = []
    for item in items:
        if not isinstance(item, dict):
            fail(f"symbol discovery returned a non-object item: {document}")
        if item.get("type") != "declaration":
            continue
        candidate = item.get("candidateSelector")
        if not isinstance(candidate, str):
            fail(f"declaration item omitted its candidate selector: {item}")
        candidates.append(candidate)
    return candidates


class Acceptance:
    def __init__(
        self,
        executable: Path,
        workspace: Path,
        runtime: Path,
        runtime_archive: Path,
        bounds: dict[str, Any],
    ):
        self.executable = executable
        self.workspace = workspace
        self.runtime = runtime
        self.runtime_archive = runtime_archive
        self.maximum_output_bytes = positive_integer(bounds, "maximumOutputBytes")
        self.maximum_operation_seconds = positive_integer(bounds, "maximumOperationSeconds")
        self.maximum_startup_seconds = positive_integer(bounds, "maximumStartupSeconds")
        self.maximum_reconciliation_seconds = positive_integer(
            bounds, "maximumReconciliationSeconds"
        )
        self.started_at = time.monotonic()

    def command(
        self,
        *argv: str,
        allowed_codes: Collection[int] = (0,),
        timeout: int | None = None,
    ) -> dict[str, Any]:
        started_at = time.monotonic()
        result = subprocess.run(
            [str(self.executable), *argv],
            cwd=self.workspace,
            env={
                **os.environ,
                "KAST_RUNTIME_DIRECTORY": str(self.runtime / "endpoints"),
                "KAST_RUNTIME_STORE": str(self.runtime / "store"),
                "KAST_RUNTIME_ARCHIVE": str(self.runtime_archive),
                "KAST_CACHE_ROOT": str(self.runtime / "intellij-caches"),
            },
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout or self.maximum_operation_seconds,
            check=False,
        )
        elapsed = time.monotonic() - started_at
        if result.returncode not in allowed_codes:
            fail(
                f"{' '.join(argv)} exited {result.returncode} after {elapsed:.3f}s: "
                f"{result.stderr.strip()}"
            )
        output_size = len(result.stdout.encode("utf-8"))
        if output_size > self.maximum_output_bytes:
            fail(
                f"{' '.join(argv)} emitted {output_size} bytes; "
                f"bound is {self.maximum_output_bytes}"
            )
        try:
            document = json.loads(result.stdout)
        except json.JSONDecodeError as error:
            fail(f"{' '.join(argv)} emitted invalid JSON: {error}")
        if not isinstance(document, dict):
            fail(f"{' '.join(argv)} emitted a non-object JSON response")
        document["_acceptanceExitCode"] = result.returncode
        return document

    def prove_installed_surface(self, bounds: dict[str, Any]) -> None:
        inspected = self.command("workspace", "inspect", timeout=self.maximum_startup_seconds)
        if inspected.get("status") not in {"complete", "qualified"}:
            fail(f"enterprise workspace did not become readable: {inspected}")

        discovery_limit = positive_integer(bounds, "symbolDiscoveryLimit")
        discovery_arguments = (
            "symbol",
            "discover",
            "--query",
            "EnterpriseNode",
            "--limit",
            str(discovery_limit),
        )
        first_discovery = self.command(*discovery_arguments)
        second_discovery = self.command(*discovery_arguments)
        if first_discovery != second_discovery:
            fail("bounded symbol discovery is not deterministic")
        candidates = declaration_candidates(first_discovery)
        if first_discovery.get("status") not in {"complete", "qualified"}:
            fail(f"scale fixture did not produce bounded discovery: {first_discovery}")
        minimum_modules = positive_integer(bounds, "minimumFixtureModules", 2)
        if len(candidates) < minimum_modules or len(candidates) > discovery_limit:
            fail(
                f"discovery returned {len(candidates)} candidates; expected at least "
                f"{minimum_modules} and at most {discovery_limit}"
            )

        initial_overloads = self.resolve_symbols(
            "enterpriseRouteOverload", discovery_limit
        )
        initial_route_overloads = {
            selector: symbol
            for selector, symbol in initial_overloads.items()
            if symbol.get("name") == "enterpriseRouteOverload"
        }
        overload_locations = {
            (symbol.get("file"), json.dumps(symbol.get("range"), sort_keys=True))
            for symbol in initial_route_overloads.values()
        }
        if len(initial_route_overloads) != 2 or len(overload_locations) != 2:
            fail(f"exact overload identity collapsed: {initial_route_overloads}")

        initial_roots = self.resolve_symbols("enterpriseRootOperation", discovery_limit)
        initial_exact_roots = [
            selector
            for selector, symbol in initial_roots.items()
            if symbol.get("name") == "enterpriseRootOperation"
        ]
        if len(initial_exact_roots) != 1:
            fail(f"expected one exact enterprise traversal root: {initial_roots}")

        initial_routers = self.resolve_symbols("EnterpriseRouter", discovery_limit)
        initial_exact_routers = [
            selector
            for selector, symbol in initial_routers.items()
            if symbol.get("kind") == "classlike"
            and symbol.get("qualifiedIdentity")
            == "enterprise.alpha.one.EnterpriseRouter"
        ]
        if len(initial_exact_routers) != 1:
            fail(f"expected one exact enterprise mutation target: {initial_routers}")

        topology = self.command(
            "topology", "build", timeout=self.maximum_startup_seconds
        )
        if (
            topology.get("operation") != "topology.build"
            or topology.get("status") != "complete"
            or topology.get("snapshotStatus") != "published"
            or not isinstance(topology.get("digest"), str)
        ):
            fail(f"installed K2 topology build did not publish: {topology}")
        self.prove_topology_snapshot_restart(topology)

        overloads = self.resolve_symbols("enterpriseRouteOverload", discovery_limit)
        roots = self.resolve_symbols("enterpriseRootOperation", discovery_limit)
        routers = self.resolve_symbols("EnterpriseRouter", discovery_limit)
        self.prove_restart_semantic_equivalence(
            "enterpriseRouteOverload", initial_overloads, overloads
        )
        self.prove_restart_semantic_equivalence(
            "enterpriseRootOperation", initial_roots, roots
        )
        self.prove_restart_semantic_equivalence(
            "EnterpriseRouter", initial_routers, routers
        )
        route_overloads = {
            selector: symbol
            for selector, symbol in overloads.items()
            if symbol.get("name") == "enterpriseRouteOverload"
        }
        exact_roots = [
            selector
            for selector, symbol in roots.items()
            if symbol.get("name") == "enterpriseRootOperation"
        ]
        exact_routers = [
            selector
            for selector, symbol in routers.items()
            if symbol.get("kind") == "classlike"
            and symbol.get("qualifiedIdentity")
            == "enterprise.alpha.one.EnterpriseRouter"
        ]
        if len(route_overloads) != 2 or len(exact_roots) != 1 or len(exact_routers) != 1:
            fail("restart changed exact enterprise selector cardinality")
        root_selector = exact_roots[0]

        relation_limit = positive_integer(bounds, "relationResultLimit")
        relation = self.command(
            "relation",
            "read",
            "--selector",
            root_selector,
            "--relation",
            "callees",
            "--limit",
            str(relation_limit),
        )
        self.prove_bounded_result(
            relation, "relation.read", "relations", relation_limit
        )

        traversal_limit = positive_integer(bounds, "traversalResultLimit")
        traversal_depth = positive_integer(bounds, "traversalMaximumDepth")
        traversal = self.command(
            "traversal",
            "run",
            "--selector",
            root_selector,
            "--relation",
            "callees",
            "--maximum-depth",
            str(traversal_depth),
            "--maximum-results",
            str(traversal_limit),
        )
        self.prove_normalized_traversal_graph(
            traversal,
            traversal_limit,
            traversal_depth,
        )

        self.prove_generation_transition(exact_routers[0], next(iter(route_overloads)))

    def prove_topology_snapshot_restart(self, published: dict[str, Any]) -> None:
        stopped = self.command("stop")
        if (
            stopped.get("command") != "stop"
            or stopped.get("status") != "complete"
            or stopped.get("runtime") != "stopped"
        ):
            fail(f"public runtime stop did not complete: {stopped}")
        reused = self.command(
            "topology", "build", timeout=self.maximum_startup_seconds
        )
        if (
            reused.get("operation") != "topology.build"
            or reused.get("status") != "complete"
            or reused.get("snapshotStatus") != "reused"
            or not isinstance(reused.get("digest"), str)
            or not isinstance(reused.get("generation"), int)
        ):
            fail(
                "restarted runtime did not rebind the exact published SQLite "
                f"topology facts: {reused}"
            )
        if (
            reused.get("generation") == published.get("generation")
            and reused.get("digest") != published.get("digest")
        ):
            fail(f"same-generation topology digest changed across restart: {reused}")

    @staticmethod
    def prove_restart_semantic_equivalence(
        query: str,
        before: dict[str, dict[str, Any]],
        after: dict[str, dict[str, Any]],
    ) -> None:
        def normalized(symbols: dict[str, dict[str, Any]]) -> list[str]:
            return sorted(
                json.dumps(
                    {key: value for key, value in symbol.items() if key != "selector"},
                    sort_keys=True,
                    separators=(",", ":"),
                )
                for symbol in symbols.values()
            )

        if normalized(before) != normalized(after):
            fail(f"semantic evidence changed across restart for {query}")

    def prove_generation_transition(self, target: str, stale_selector: str) -> None:
        plan = self.command(
            "change",
            "plan",
            "--intent",
            "add-declaration",
            "--target",
            target,
            "--declaration",
            "fun enterpriseMutationMarker(): Int = 1",
        )
        plan_identity = plan.get("planIdentity")
        if plan.get("status") != "complete" or not isinstance(plan_identity, str):
            fail(f"enterprise mutation plan was not complete: {plan}")
        applied = self.command("change", "apply", "--plan", plan_identity)
        application_identity = applied.get("applicationIdentity")
        if applied.get("status") != "complete" or not isinstance(application_identity, str):
            fail(f"enterprise mutation did not reach AppliedUnverified: {applied}")
        verified = self.command(
            "change", "verify", "--application", application_identity,
            timeout=self.maximum_reconciliation_seconds,
        )
        if verified.get("status") != "complete" or not verified.get("receiptIdentity"):
            fail(f"enterprise mutation did not publish a verified generation: {verified}")
        stale = self.command(
            "symbol",
            "describe",
            "--selector",
            stale_selector,
        )
        if stale.get("status") != "rejected" or stale.get("reason") != "selector-stale":
            fail(f"prior-generation selector was not rejected: {stale}")

    def prove_workspace_write_scope(self) -> None:
        tracked = git(self.workspace, "diff", "--name-only").splitlines()
        untracked = git(
            self.workspace, "ls-files", "--others", "--exclude-standard"
        ).splitlines()
        expected = [
            "domains/alpha/one/src/main/kotlin/enterprise/alpha/one/Enterprise.kt"
        ]
        gradle_bootstrap_files = {
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
        }
        unexpected_untracked = [
            path
            for path in untracked
            if not path.startswith(".gradle/") and path not in gradle_bootstrap_files
        ]
        if tracked != expected or unexpected_untracked:
            fail(
                "sidecar wrote outside the explicit mutation target: "
                f"tracked={tracked}, unexpectedUntracked={unexpected_untracked}"
            )

    def resolve_symbols(self, query: str, limit: int) -> dict[str, dict[str, Any]]:
        discovery = self.command(
            "symbol", "discover", "--query", query, "--limit", str(limit)
        )
        candidates = declaration_candidates(discovery)
        if not candidates:
            fail(f"symbol discovery found no candidates for {query}: {discovery}")
        resolved: dict[str, dict[str, Any]] = {}
        for candidate in candidates:
            resolution = self.command(
                "symbol", "resolve", "--candidate", candidate
            )
            if resolution.get("status") != "complete":
                continue
            selector = resolution.get("exactSelector")
            if not isinstance(selector, str):
                fail(f"symbol.resolve omitted exact identity: {resolution}")
            description = self.command("symbol", "describe", "--selector", selector)
            symbol = description.get("symbol")
            if not isinstance(symbol, dict):
                fail(f"symbol.describe omitted structured symbol evidence: {description}")
            if symbol.get("selector") != selector:
                fail(f"symbol.describe returned mismatched exact identity: {description}")
            resolved[selector] = symbol
        return resolved

    @staticmethod
    def prove_bounded_result(
        document: dict[str, Any], operation: str, field: str, limit: int
    ) -> None:
        values = document.get(field)
        if document.get("operation") != operation or not isinstance(values, list) or not values:
            fail(f"{operation} returned no bounded semantic evidence: {document}")
        if len(values) > limit:
            fail(f"{operation} returned {len(values)} results for limit {limit}")

    def prove_normalized_traversal_graph(
        self,
        document: dict[str, Any],
        result_limit: int,
        maximum_depth: int,
    ) -> None:
        if document.get("operation") != "traversal.run" or document.get("status") not in {
            "complete",
            "qualified",
        }:
            fail(f"traversal.run returned no semantic evidence: {document}")
        if "records" in document:
            fail(f"traversal.run retained the denormalized records projection: {document}")

        graph = document.get("graph")
        if not isinstance(graph, dict):
            fail(f"traversal.run omitted its normalized graph: {document}")
        snapshot = graph.get("snapshot")
        nodes = graph.get("nodes")
        edges = graph.get("edges")
        proofs = graph.get("proofs")
        if not isinstance(snapshot, dict):
            fail(f"traversal.run omitted its snapshot identity: {graph}")
        if snapshot.get("canonicalRoot") != str(self.workspace.resolve()):
            fail(f"traversal.run returned a foreign snapshot root: {snapshot}")
        generation = snapshot.get("generation")
        if not isinstance(generation, int) or isinstance(generation, bool) or generation < 1:
            fail(f"traversal.run returned an invalid snapshot generation: {snapshot}")
        if not isinstance(nodes, list) or not nodes:
            fail(f"traversal.run returned no normalized nodes: {graph}")
        if not isinstance(edges, list) or not edges:
            fail(f"traversal.run returned no normalized edges: {graph}")
        if len(edges) > result_limit:
            fail(
                f"traversal.run returned {len(edges)} edges for limit {result_limit}"
            )
        if not isinstance(proofs, list) or not proofs:
            fail(f"traversal.run returned no normalized proofs: {graph}")

        node_ids: set[int] = set()
        node_proof_ids: set[int] = set()
        selectors: set[str] = set()
        for node in nodes:
            if not isinstance(node, dict):
                fail(f"traversal.run returned a non-object node: {node}")
            node_id = node.get("id")
            proof_id = node.get("proof")
            selector = node.get("selector")
            if not isinstance(node_id, int) or isinstance(node_id, bool):
                fail(f"traversal.run returned an invalid node identity: {node}")
            if not isinstance(proof_id, int) or isinstance(proof_id, bool):
                fail(f"traversal.run returned an invalid node proof reference: {node}")
            if not isinstance(selector, str) or not selector:
                fail(f"traversal.run returned a node without an exact selector: {node}")
            if node_id in node_ids or selector in selectors:
                fail(f"traversal.run returned a duplicate normalized node: {node}")
            node_ids.add(node_id)
            node_proof_ids.add(proof_id)
            selectors.add(selector)
        if node_ids != set(range(len(nodes))):
            fail(f"traversal.run node identities are not dense: {sorted(node_ids)}")

        proof_ids: set[int] = set()
        proof_identities: set[str] = set()
        for proof in proofs:
            if not isinstance(proof, dict):
                fail(f"traversal.run returned a non-object proof: {proof}")
            proof_id = proof.get("id")
            identity = proof.get("identity")
            if not isinstance(proof_id, int) or isinstance(proof_id, bool):
                fail(f"traversal.run returned an invalid proof identity: {proof}")
            if not isinstance(identity, str) or not identity:
                fail(f"traversal.run returned an empty compiler proof: {proof}")
            if proof_id in proof_ids or identity in proof_identities:
                fail(f"traversal.run returned a duplicate normalized proof: {proof}")
            proof_ids.add(proof_id)
            proof_identities.add(identity)
        if proof_ids != set(range(len(proofs))):
            fail(f"traversal.run proof identities are not dense: {sorted(proof_ids)}")
        if node_proof_ids != proof_ids:
            fail(
                "traversal.run node proof references do not match the proof table: "
                f"nodes={sorted(node_proof_ids)}, proofs={sorted(proof_ids)}"
            )

        referenced_node_ids: set[int] = set()
        for edge in edges:
            if not isinstance(edge, dict):
                fail(f"traversal.run returned a non-object edge: {edge}")
            source = edge.get("source")
            target = edge.get("target")
            depth = edge.get("depth")
            if source not in node_ids or target not in node_ids:
                fail(f"traversal.run returned a dangling edge: {edge}")
            if (
                not isinstance(depth, int)
                or isinstance(depth, bool)
                or depth < 1
                or depth > maximum_depth
            ):
                fail(f"traversal.run returned an out-of-bounds edge depth: {edge}")
            referenced_node_ids.update((source, target))
        if referenced_node_ids != node_ids:
            fail(
                "traversal.run returned unreferenced normalized nodes: "
                f"nodes={sorted(node_ids)}, referenced={sorted(referenced_node_ids)}"
            )


def git(workspace: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=workspace,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        fail(f"git {' '.join(arguments)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def prepare_workspace_fixture(workspace: Path) -> None:
    git(workspace, "init", "--quiet")
    git(workspace, "config", "user.name", "Kast Acceptance")
    git(workspace, "config", "user.email", "acceptance@kast.invalid")
    git(workspace, "add", ".")
    git(workspace, "commit", "--quiet", "-m", "baseline")


def stop_indexer(acceptance: Acceptance) -> None:
    stopped = acceptance.command("stop")
    if (
        stopped.get("command") != "stop"
        or stopped.get("status") != "complete"
        or stopped.get("runtime") != "stopped"
    ):
        fail(f"acceptance cleanup did not retire the public runtime: {stopped}")


def main() -> None:
    args = arguments()
    executable = args.product_root / "bin" / "kast"
    if not executable.is_file():
        fail("staged kast executable is missing")
    if not args.thresholds.is_file():
        fail("performance and output bounds are unproven")
    bounds = json.loads(args.thresholds.read_text(encoding="utf-8"))
    if not isinstance(bounds, dict):
        fail("benchmark thresholds must be a JSON object")
    minimum_modules = positive_integer(bounds, "minimumFixtureModules", 2)
    module_builds = sorted(
        build for build in args.fixture.glob("**/build.gradle.kts")
        if build.parent != args.fixture
    )
    if len(module_builds) < minimum_modules:
        fail(
            f"multi-module scale is unproven: expected {minimum_modules} module builds, "
            f"found {len(module_builds)}"
        )

    maximum_acceptance_seconds = positive_integer(bounds, "maximumAcceptanceSeconds")
    started_at = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="kast-enterprise-") as workspace_text:
        with tempfile.TemporaryDirectory(prefix="kr.", dir="/tmp") as runtime_text:
            workspace = Path(workspace_text)
            runtime = Path(runtime_text)
            shutil.copytree(
                args.fixture,
                workspace,
                dirs_exist_ok=True,
                ignore=shutil.ignore_patterns(".gradle", ".idea", "build"),
            )
            prepare_workspace_fixture(workspace)
            acceptance = Acceptance(
                executable,
                workspace,
                runtime,
                args.runtime_archive,
                bounds,
            )
            try:
                acceptance.prove_installed_surface(bounds)
            finally:
                stop_indexer(acceptance)
            acceptance.prove_workspace_write_scope()
    elapsed = time.monotonic() - started_at
    if elapsed > maximum_acceptance_seconds:
        fail(
            f"acceptance took {elapsed:.3f}s; bound is {maximum_acceptance_seconds}s"
        )


if __name__ == "__main__":
    main()
