#!/usr/bin/env python3
"""Fail-closed enterprise acceptance for the staged Kast product."""

from __future__ import annotations

import argparse
from collections.abc import Collection, Mapping
from dataclasses import dataclass
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import stat
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
    parser.add_argument("--retain-failed-host", action="store_true")
    return parser.parse_args()


def diagnostic_summary(stage: str, document: dict[str, Any]) -> dict[str, Any]:
    """Retain compiler status/count/codes without messages, locations, or source values."""
    facts = document.get("diagnostics", [])
    if not isinstance(facts, list):
        fail("diagnostic summary has no bounded diagnostic list")
    errors = [fact for fact in facts if isinstance(fact, dict) and fact.get("severity") == "error"]
    # Wire enum values are lowercase; tolerate the generated uppercase projection explicitly.
    errors += [fact for fact in facts if isinstance(fact, dict) and fact.get("severity") == "ERROR"]
    codes = sorted({fact.get("code", "") for fact in errors
                    if isinstance(fact.get("code"), str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,95}", fact["code"])})[:16]
    summary = {"stage": stage, "status": document.get("status"), "errorCount": len(errors), "factoryCodes": codes}
    print("enterprise-diagnostics: " + json.dumps(summary, sort_keys=True), flush=True)
    return summary


def compiler_log_evidence(cache_root: Path) -> list[dict[str, Any]]:
    """Project only the bounded diagnostic event schema from owned IntelliJ logs."""
    evidence: list[dict[str, Any]] = []
    logs = sorted(cache_root.glob("*/log/idea.log"))
    if len(logs) > 16:
        fail("diagnostic compiler log cohort exceeds its bound")
    for log in logs:
        with log.open("rb") as stream:
            stream.seek(max(0, log.stat().st_size - 262144))
            lines = stream.read(262144).decode(errors="replace").splitlines()
        for line in lines:
            marker = "Kast diagnostic compilation: "
            if marker not in line:
                continue
            try:
                event = json.loads(line.split(marker, 1)[1])
            except json.JSONDecodeError:
                fail("diagnostic compiler event is malformed")
            if not isinstance(event, dict):
                fail("diagnostic compiler event is not an object")
            status = event.get("status")
            if event.get("stage") != "exact-scope" or status not in {"complete", "qualified", "rejected", "cancelled"}:
                fail("diagnostic compiler event has an unsupported terminal state")
            record: dict[str, Any] = {"stage": "exact-scope", "status": status}
            if status in {"complete", "qualified"}:
                errors = event.get("errors")
                if not isinstance(errors, dict):
                    fail("diagnostic compiler event omitted error evidence")
                for key in ("count", "withheldFactCount"):
                    value = errors.get(key)
                    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                        fail("diagnostic compiler event has an invalid count")
                codes = errors.get("factoryCodes")
                if not isinstance(codes, list) or len(codes) > 16 or any(
                    not isinstance(code, str) or re.fullmatch(r"[A-Z][A-Z0-9_]{0,95}", code) is None for code in codes
                ):
                    fail("diagnostic compiler event has invalid factory codes")
                record.update(errorCount=errors["count"], factoryCodes=codes, withheldFactCount=errors["withheldFactCount"])
            evidence.append(record)
    return evidence[-16:]


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


AMBIENT_ENVIRONMENT_ALLOWLIST = (
    "CODEX_EXECUTABLE",
    "DEVELOPER_DIR",
    "JAVA_HOME",
    "KAST_ACCEPTANCE_IDEA_HOME",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "PATH",
    "SDKROOT",
)
LAUNCHCTL_SERVICE_NOT_FOUND = 113


def broker_service_label(codex_home: Path) -> str:
    suffix = hashlib.sha256(str(codex_home).encode()).hexdigest()[:32]
    return f"io.github.amichne.kast.broker.{suffix}"


def launchctl_service(label: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["/bin/launchctl", "list", label],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


@dataclass(frozen=True)
class IsolatedAcceptanceHost:
    root: Path
    home: Path
    codex_home: Path
    runtime: Path
    archive: Path
    app_server_control: Path
    temporary: Path
    workspace: Path

    @classmethod
    def create(
        cls,
        root_candidate: Path,
        runtime_archive_candidate: Path,
    ) -> IsolatedAcceptanceHost:
        try:
            root = root_candidate.resolve(strict=True)
            source_archive = runtime_archive_candidate.resolve(strict=True)
        except OSError as error:
            fail(f"isolated host authority is unavailable: {error}")
        if not root.is_dir() or not source_archive.is_file():
            fail("isolated host root and runtime archive must have canonical file types")

        def directory(name: str) -> Path:
            candidate = root / name
            candidate.mkdir(mode=0o700)
            resolved = candidate.resolve(strict=True)
            if resolved.parent != root or not resolved.is_dir():
                fail(f"isolated host directory escaped its root: {name}")
            return resolved

        home = directory("home")
        codex_home = directory("codex-home")
        runtime = directory("runtime")
        archive_directory = directory("archive")
        app_server_control = codex_home / "app-server-control"
        app_server_control.mkdir(mode=0o700)
        app_server_control = app_server_control.resolve(strict=True)
        temporary = directory("tmp")
        workspace = directory("workspace")
        archive = archive_directory / source_archive.name
        shutil.copy2(source_archive, archive)
        archive = archive.resolve(strict=True)
        return cls(
            root,
            home,
            codex_home,
            runtime,
            archive,
            app_server_control,
            temporary,
            workspace,
        )

    @property
    def service_label(self) -> str:
        return broker_service_label(self.codex_home)

    @property
    def readiness_file(self) -> Path:
        return self.codex_home / "broker/service-readiness.json"

    @property
    def broker_socket(self) -> Path:
        return self.app_server_control / "app-server-control.sock"

    def child_environment(
        self,
        ambient: Mapping[str, str] = os.environ,
    ) -> dict[str, str]:
        environment = {
            name: ambient[name]
            for name in AMBIENT_ENVIRONMENT_ALLOWLIST
            if name in ambient
        }
        environment.update(
            {
                "HOME": str(self.home),
                "CODEX_HOME": str(self.codex_home),
                "JAVA_OPTS": f"-Duser.home={self.home}",
                "TMPDIR": str(self.temporary),
                "KAST_RUNTIME_DIRECTORY": str(self.runtime / "endpoints"),
                "KAST_RUNTIME_STORE": str(self.runtime / "store"),
                "KAST_RUNTIME_ARCHIVE": str(self.archive),
                "KAST_CACHE_ROOT": str(self.runtime / "intellij-caches"),
            }
        )
        return environment

    def retire_broker(self, timeout_seconds: int) -> None:
        observed = launchctl_service(self.service_label)
        if observed.returncode == 0:
            retired = subprocess.run(
                ["/bin/launchctl", "remove", self.service_label],
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            if retired.returncode != 0:
                fail(
                    "isolated broker retirement failed: "
                    + retired.stderr.decode(errors="replace").strip()
                )
        elif observed.returncode != LAUNCHCTL_SERVICE_NOT_FOUND:
            fail(
                "isolated broker observation failed: "
                + observed.stderr.decode(errors="replace").strip()
            )

        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            current = launchctl_service(self.service_label)
            if current.returncode == LAUNCHCTL_SERVICE_NOT_FOUND:
                break
            if current.returncode != 0:
                fail(
                    "isolated broker retirement observation failed: "
                    + current.stderr.decode(errors="replace").strip()
                )
            time.sleep(0.05)
        else:
            fail("isolated broker service remained registered after retirement")

        self._retire_artifact(self.readiness_file, stat.S_ISREG, "readiness file")
        self._retire_artifact(self.broker_socket, stat.S_ISSOCK, "socket")
        for artifact in (self.readiness_file, self.broker_socket):
            if os.path.lexists(artifact):
                fail(f"isolated broker artifact remained after cleanup: {artifact}")

    def _retire_artifact(self, path: Path, admitted_type, description: str) -> None:
        try:
            attributes = path.lstat()
        except FileNotFoundError:
            return
        except OSError as error:
            fail(f"isolated broker {description} could not be observed: {error}")
        if not admitted_type(attributes.st_mode):
            fail(f"isolated broker {description} has an unowned file type: {path}")
        try:
            path.unlink()
        except OSError as error:
            fail(f"isolated broker {description} could not be retired: {error}")

    def assert_confined(self) -> None:
        for path in self.root.rglob("*"):
            try:
                attributes = path.lstat()
            except OSError as error:
                fail(f"isolated host path could not be observed: {error}")
            if stat.S_ISLNK(attributes.st_mode):
                fail(f"acceptance created a symbolic link: {path}")
            try:
                path.resolve(strict=True).relative_to(self.root)
            except (OSError, ValueError) as error:
                fail(f"acceptance-created path escaped the isolated host: {path}: {error}")


@dataclass(frozen=True)
class AmbientBrokerSnapshot:
    codex_home: Path
    service_label: str
    service_observation: tuple[int, bytes, bytes]
    file_observation: tuple[tuple[str, str, int, str], ...]

    @classmethod
    def capture(cls, codex_home: Path | None = None) -> AmbientBrokerSnapshot:
        authority = codex_home or ambient_codex_home()
        label = broker_service_label(authority)
        service = launchctl_service(label)
        if service.returncode not in (0, LAUNCHCTL_SERVICE_NOT_FOUND):
            fail(
                "ambient broker observation failed: "
                + service.stderr.decode(errors="replace").strip()
            )
        return cls(
            authority,
            label,
            (service.returncode, service.stdout, service.stderr),
            snapshot_paths(authority, ("broker", "app-server-control")),
        )

    def assert_unchanged(self) -> None:
        current = AmbientBrokerSnapshot.capture(self.codex_home)
        if current != self:
            fail("ambient broker files or launchd service changed during acceptance")


def ambient_codex_home() -> Path:
    raw = os.environ.get("CODEX_HOME")
    if raw is None:
        try:
            return Path.home().resolve(strict=True) / ".codex"
        except OSError as error:
            fail(f"ambient HOME is not canonical: {error}")
    candidate = Path(raw)
    if not candidate.is_absolute() or candidate != Path(os.path.normpath(raw)):
        fail("ambient CODEX_HOME must be absolute and normalized")
    return candidate


def snapshot_paths(
    root: Path,
    relative_roots: tuple[str, ...],
) -> tuple[tuple[str, str, int, str], ...]:
    observations: list[tuple[str, str, int, str]] = []

    def observe(path: Path, relative: Path) -> None:
        try:
            attributes = path.lstat()
        except FileNotFoundError:
            observations.append((relative.as_posix(), "absent", 0, ""))
            return
        except OSError as error:
            fail(f"ambient broker path could not be observed: {path}: {error}")
        mode = stat.S_IMODE(attributes.st_mode)
        if stat.S_ISDIR(attributes.st_mode):
            observations.append((relative.as_posix(), "directory", mode, ""))
            try:
                children = sorted(path.iterdir(), key=lambda child: child.name)
            except OSError as error:
                fail(f"ambient broker directory could not be listed: {path}: {error}")
            for child in children:
                observe(child, relative / child.name)
        elif stat.S_ISREG(attributes.st_mode):
            try:
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
            except OSError as error:
                fail(f"ambient broker file could not be read: {path}: {error}")
            observations.append((relative.as_posix(), "regular", mode, digest))
        elif stat.S_ISLNK(attributes.st_mode):
            try:
                target = os.readlink(path)
            except OSError as error:
                fail(f"ambient broker link could not be read: {path}: {error}")
            observations.append((relative.as_posix(), "symlink", mode, target))
        elif stat.S_ISSOCK(attributes.st_mode):
            observations.append((relative.as_posix(), "socket", mode, ""))
        else:
            observations.append((relative.as_posix(), "other", mode, ""))

    for relative_root in relative_roots:
        observe(root / relative_root, Path(relative_root))
    return tuple(observations)


class Acceptance:
    def __init__(
        self,
        executable: Path,
        host: IsolatedAcceptanceHost,
        bounds: dict[str, Any],
    ):
        self.executable = executable
        self.workspace = host.workspace
        self.environment = host.child_environment()
        try:
            self.idea_home = Path(
                self.environment["KAST_ACCEPTANCE_IDEA_HOME"]
            ).resolve(strict=True)
        except (KeyError, OSError) as error:
            fail(f"acceptance IDEA home is unavailable: {error}")
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
            env=self.environment,
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
        started = self.command(
            "start",
            "--idea-home",
            str(self.idea_home),
            timeout=self.maximum_startup_seconds,
        )
        if (
            started.get("command") != "start"
            or started.get("status") != "complete"
            or started.get("runtime") != "running"
        ):
            fail(f"enterprise workspace did not become ready: {started}")
        status = self.command("status")
        if (
            status.get("command") != "status"
            or status.get("status") != "complete"
            or status.get("runtime") != "running"
        ):
            fail(f"passive lifecycle status did not observe the runtime: {status}")
        synchronized = self.command(
            "index", "sync", timeout=self.maximum_reconciliation_seconds
        )
        if (
            synchronized.get("operation") != "index.sync"
            or synchronized.get("status") != "complete"
        ):
            fail(f"explicit index synchronization did not complete: {synchronized}")

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

        source = self.command("source", "read", "--anchor", root_selector)
        if (
            source.get("operation") != "source.read"
            or source.get("status") not in {"complete", "qualified"}
            or not isinstance(source.get("snapshot"), dict)
        ):
            fail(f"source.read returned no bounded source evidence: {source}")

        diagnostics = self.command(
            "diagnostic",
            "check",
            "--scope",
            "domains/alpha/one/src/main/kotlin/enterprise/alpha/one/Enterprise.kt",
            "--limit",
            str(positive_integer(bounds, "relationResultLimit")),
        )
        if (
            diagnostics.get("operation") != "diagnostic.check"
            or diagnostics.get("status") not in {"complete", "qualified"}
        ):
            fail(f"diagnostic.check returned no scoped evidence: {diagnostics}")

        baseline = diagnostic_summary("baseline", diagnostics)
        print("enterprise-compiler-evidence: " + json.dumps(
            compiler_log_evidence(Path(self.environment["KAST_CACHE_ROOT"])), sort_keys=True,
        ), flush=True)
        if baseline["status"] != "complete" or baseline["errorCount"] != 0:
            fail("enterprise mutation requires complete error-free baseline diagnostics")

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
        restarted = self.command(
            "start",
            "--idea-home",
            str(self.idea_home),
            timeout=self.maximum_startup_seconds,
        )
        if (
            restarted.get("command") != "start"
            or restarted.get("status") != "complete"
            or restarted.get("runtime") != "running"
        ):
            fail(f"public runtime restart did not complete: {restarted}")
        reused = self.command(
            "topology", "build", timeout=self.maximum_startup_seconds
        )
        if (
            reused.get("operation") != "topology.build"
            or reused.get("status") != "complete"
            or reused.get("snapshotStatus") not in {"published", "reused"}
            or not isinstance(reused.get("digest"), str)
            or not isinstance(reused.get("generation"), int)
        ):
            fail(
                "restarted runtime did not publish or rebind the exact SQLite "
                f"topology facts: {reused}"
            )
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
        recovery_plan = self.command(
            "change",
            "plan",
            "--intent",
            "add-declaration",
            "--target",
            target,
            "--declaration",
            "fun enterpriseRecoveryProbe(): Int = 0",
        )
        recovery_plan_identity = recovery_plan.get("planIdentity")
        if recovery_plan.get("status") != "complete" or not isinstance(
            recovery_plan_identity, str
        ):
            fail(f"enterprise recovery plan was not complete: {recovery_plan}")
        recovered = self.command(
            "change", "recover", "--plan", recovery_plan_identity
        )
        if (
            recovered.get("operation") != "change.recover"
            or recovered.get("status") != "complete"
            or not isinstance(recovered.get("state"), str)
        ):
            fail(f"enterprise recovery did not restore known state: {recovered}")

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
        applied = self.command(
            "change", "apply", "--plan", plan_identity,
            timeout=self.maximum_reconciliation_seconds,
        )
        after_diagnostics = self.command(
            "diagnostic", "check", "--scope",
            "domains/alpha/one/src/main/kotlin/enterprise/alpha/one/Enterprise.kt", "--limit", "1000",
        )
        diagnostic_summary("after-mutation", after_diagnostics)
        compiler_evidence = compiler_log_evidence(Path(self.environment["KAST_CACHE_ROOT"]))
        if not compiler_evidence:
            fail("installed diagnostic compiler omitted durable terminal evidence")
        print("enterprise-compiler-evidence: " + json.dumps(compiler_evidence, sort_keys=True), flush=True)
        if applied.get("status") != "complete" or not applied.get("receiptIdentity"):
            fail(f"enterprise mutation did not return a verified receipt: {applied}")
        stale = self.command(
            "symbol",
            "inspect",
            "--selector",
            stale_selector,
        )
        if (
            stale.get("status") != "rejected"
            or stale.get("reason") != "exact-selector-stale"
        ):
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
            inspection = self.command(
                "symbol", "inspect", "--candidate", candidate
            )
            if inspection.get("status") != "complete":
                continue
            symbol = inspection.get("symbol")
            if not isinstance(symbol, dict):
                fail(f"symbol.inspect omitted structured symbol evidence: {inspection}")
            selector = symbol.get("selector")
            if not isinstance(selector, str):
                fail(f"symbol.inspect omitted exact identity: {inspection}")
            if symbol.get("selector") != selector:
                fail(f"symbol.inspect returned mismatched exact identity: {inspection}")
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
    source_root = Path(__file__).resolve().parents[1]
    for relative in (
        "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
    ):
        authority = source_root / relative
        target = workspace / relative
        if target.exists():
            if target.read_bytes() != authority.read_bytes():
                fail(f"fixture wrapper differs from pinned authority: {relative}")
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(authority, target)
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


def run_acceptance_scenario(
    acceptance: Acceptance,
    host: IsolatedAcceptanceHost,
    bounds: dict[str, Any],
    ambient: AmbientBrokerSnapshot,
    cleanup_timeout_seconds: int,
) -> None:
    try:
        acceptance.prove_installed_surface(bounds)
        acceptance.prove_workspace_write_scope()
    finally:
        try:
            stop_indexer(acceptance)
        finally:
            try:
                host.retire_broker(cleanup_timeout_seconds)
            finally:
                try:
                    host.assert_confined()
                finally:
                    ambient.assert_unchanged()


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
    ambient = AmbientBrokerSnapshot.capture()
    host_text = tempfile.mkdtemp(prefix="ka.", dir="/tmp")
    succeeded = False
    try:
        host = IsolatedAcceptanceHost.create(Path(host_text), args.runtime_archive)
        shutil.copytree(
            args.fixture,
            host.workspace,
            dirs_exist_ok=True,
            ignore=shutil.ignore_patterns(".gradle", ".idea", "build"),
        )
        prepare_workspace_fixture(host.workspace)
        acceptance = Acceptance(executable, host, bounds)
        run_acceptance_scenario(
            acceptance,
            host,
            bounds,
            ambient,
            maximum_acceptance_seconds,
        )
        succeeded = True
    finally:
        if succeeded or not args.retain_failed_host:
            shutil.rmtree(host_text)
        else:
            print("enterprise-acceptance: retained retired failed host at " + host_text, flush=True)
    elapsed = time.monotonic() - started_at
    if elapsed > maximum_acceptance_seconds:
        fail(
            f"acceptance took {elapsed:.3f}s; bound is {maximum_acceptance_seconds}s"
        )


if __name__ == "__main__":
    main()
