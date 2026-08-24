"""Support types and finite checks for the installed topology lifecycle."""

from __future__ import annotations

import hashlib
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


class AcceptanceFailure(RuntimeError):
    """Finite installed-acceptance failure projected at the process boundary."""


@dataclass(frozen=True)
class Invocation:
    argv: tuple[str, ...]
    document: dict[str, Any] | None
    stdout: str
    stderr: str
    returncode: int


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sha256_text(value: str) -> str:
    return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


def load_registry(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise AcceptanceFailure(f"operation registry is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise AcceptanceFailure(f"operation registry is invalid JSON: {path}: {error}") from error
    require(isinstance(document, dict), "operation registry root must be an object")
    require(document.get("schemaVersion") == 1, "operation registry schemaVersion must be 1")
    operation_ids(document)
    return document


def operation_ids(document: dict[str, Any]) -> list[str]:
    values = document.get("operationIds")
    require(
        isinstance(values, list) and all(isinstance(value, str) and value for value in values),
        "operationIds must be a nonempty string list",
    )
    result = list(values)
    require(len(result) == len(set(result)), f"operation IDs are not unique: {result}")
    return result


class InstalledKast:
    def __init__(self, executable: Path, workspace: Path) -> None:
        self.executable = executable.resolve()
        self.workspace = workspace.resolve()
        require(self.executable.is_file(), f"installed kast is missing: {self.executable}")
        require(self.workspace.is_dir(), f"workspace is missing: {self.workspace}")
        self.invocations: list[Invocation] = []

    def invoke(
        self,
        *args: str,
        json_output: bool = True,
        accepted_returncodes: Iterable[int] = (0,),
    ) -> Invocation:
        argv = (str(self.executable), *args)
        completed = subprocess.run(
            argv,
            cwd=self.workspace,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if completed.returncode not in set(accepted_returncodes):
            raise AcceptanceFailure(
                f"command failed ({completed.returncode}): {' '.join(argv)}\n"
                f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
            )
        document: dict[str, Any] | None = None
        if json_output:
            try:
                parsed = json.loads(completed.stdout)
            except json.JSONDecodeError as error:
                raise AcceptanceFailure(
                    f"command did not emit one JSON document: {' '.join(argv)}: {error}\n"
                    f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
                ) from error
            require(isinstance(parsed, dict), f"command JSON is not an object: {' '.join(argv)}")
            document = parsed
        invocation = Invocation(
            argv=argv,
            document=document,
            stdout=completed.stdout,
            stderr=completed.stderr,
            returncode=completed.returncode,
        )
        self.invocations.append(invocation)
        return invocation

    def schema(self) -> dict[str, Any]:
        invocation = self.invoke("--schema")
        assert invocation.document is not None
        return invocation.document

    def semantic(self, operation: str, *args: str) -> dict[str, Any]:
        invocation = self.invoke(*args)
        assert invocation.document is not None
        document = invocation.document
        require(document.get("operation") == operation, f"expected {operation}: {document}")
        return document


def expect_status(document: dict[str, Any], status: str) -> None:
    require(document.get("status") == status, f"expected status {status}: {document}")


def expect_rejection(document: dict[str, Any], reason: str) -> None:
    expect_status(document, "rejected")
    require(document.get("reason") == reason, f"expected rejection {reason}: {document}")


def discover_selector(kast: InstalledKast, query: str, expected_name: str) -> str:
    discovered = kast.semantic(
        "symbol.discover",
        "symbol",
        "discover",
        "--query",
        query,
        "--limit",
        "1000",
    )
    require(discovered.get("status") in {"complete", "qualified"}, str(discovered))
    items = discovered.get("items")
    require(isinstance(items, list), f"symbol.discover items missing: {discovered}")
    candidates = [
        item
        for item in items
        if isinstance(item, dict)
        and item.get("type") == "declaration"
        and item.get("name") == expected_name
        and isinstance(item.get("candidateSelector"), str)
    ]
    require(candidates, f"no declaration candidate named {expected_name}: {discovered}")
    candidates.sort(key=canonical_json)
    resolved = kast.semantic(
        "symbol.resolve",
        "symbol",
        "resolve",
        "--candidate",
        candidates[0]["candidateSelector"],
    )
    expect_status(resolved, "complete")
    selector = resolved.get("exactSelector")
    require(isinstance(selector, str) and selector, f"symbol.resolve selector missing: {resolved}")
    return selector


def traversal_callers(kast: InstalledKast, selector: str) -> dict[str, Any]:
    return kast.semantic(
        "traversal.run",
        "traversal",
        "run",
        "--selector",
        selector,
        "--relation",
        "callers",
        "--maximum-depth",
        "5",
        "--maximum-results",
        "500",
    )


def topology_build(kast: InstalledKast) -> dict[str, Any]:
    return kast.semantic("topology.build", "topology", "build")


def active_indexer_pid(kast: InstalledKast) -> int:
    completed = subprocess.run(
        ["/bin/ps", "-ax", "-o", "pid=,command="],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    require(completed.returncode == 0, f"could not inspect installed indexer: {completed.stderr}")
    workspace_argument = f"--workspace-root={kast.workspace}"
    matches = []
    for line in completed.stdout.splitlines():
        fields = line.strip().split(maxsplit=1)
        if len(fields) != 2:
            continue
        if (
            "io.github.amichne.kast.indexer.KastIndexerMainKt" in fields[1]
            and workspace_argument in fields[1]
        ):
            matches.append(int(fields[0]))
    require(len(matches) == 1, f"expected one exact installed indexer, found {matches}")
    return matches[0]


def require_build_identity(document: dict[str, Any], status: str) -> tuple[str, str]:
    expect_status(document, "complete")
    require(document.get("snapshotStatus") == status, f"expected snapshotStatus {status}: {document}")
    generation = document.get("generation")
    digest = document.get("digest")
    require(
        isinstance(generation, int) and generation >= 0,
        f"topology generation missing: {document}",
    )
    require(isinstance(digest, str) and digest, f"topology digest missing: {document}")
    return str(generation), digest


def normalize_traversal(document: dict[str, Any]) -> str:
    expect_status(document, "complete")
    reached = document.get("reached")
    require(isinstance(reached, list), f"traversal reached list missing: {document}")
    normalized: list[dict[str, Any]] = []
    for value in reached:
        require(isinstance(value, dict), f"invalid traversal symbol: {value}")
        normalized.append(
            {
                "kind": value.get("kind"),
                "name": value.get("name"),
                "qualifiedIdentity": value.get("qualifiedIdentity"),
                "file": value.get("file"),
                "range": value.get("range"),
            }
        )
    normalized.sort(key=canonical_json)
    return canonical_json(normalized)


def invocation_projection(invocation: Invocation) -> dict[str, Any]:
    return {
        "argv": list(invocation.argv[1:]),
        "returncode": invocation.returncode,
        "stdoutSha256": sha256_text(invocation.stdout),
        "stderrSha256": sha256_text(invocation.stderr),
        "operation": invocation.document.get("operation") if invocation.document else None,
        "status": invocation.document.get("status") if invocation.document else None,
        "reason": invocation.document.get("reason") if invocation.document else None,
    }


def verify_document(report: dict[str, Any], registry: dict[str, Any]) -> None:
    require(report.get("schemaVersion") == 1, "unsupported report schemaVersion")
    require(report.get("kind") == "kast-pr633-installed-topology-lifecycle", "wrong report kind")
    require(report.get("operationIds") == operation_ids(registry), "report registry differs")
    require(report.get("operationRegistrySha256") == sha256_text(canonical_json(registry)), "registry digest differs")
    prerequisites = report.get("prerequisites")
    require(isinstance(prerequisites, dict), "prerequisite proof missing")
    for phase in ("beforeFirstBuild", "afterMutation"):
        observed = prerequisites.get(phase)
        require(isinstance(observed, dict), f"{phase} prerequisite proof missing")
        require(observed.get("traversal") == "topology-build-required", f"{phase} traversal remedy differs")
        require(observed.get("changePlan") == "topology-build-required", f"{phase} planning remedy differs")
    first = report.get("firstBuild")
    second = report.get("secondBuild")
    require(isinstance(first, dict) and first.get("status") == "published", "first build not published")
    require(isinstance(second, dict) and second.get("status") == "reused", "second build not reused")
    require(first.get("generation") == second.get("generation"), "reuse generation mismatch")
    require(first.get("digest") == second.get("digest"), "reuse digest mismatch")
    restart = report.get("restart")
    require(isinstance(restart, dict) and restart.get("semanticResultEqual") is True, "restart result differs")
    require(restart.get("topologyBuildInvokedBetweenStopAndTraversal") is False, "restart rebuilt topology")
    process_reuse = report.get("processReuse")
    require(
        isinstance(process_reuse, dict) and process_reuse.get("samePidAcrossCallers") is True,
        "successive public callers did not reuse one indexer process",
    )
    selectors = report.get("selectors")
    require(isinstance(selectors, dict), "selector proof missing")
    require(selectors.get("oldSelectorRejection") == "selector-stale", "old selector was not stale")
    rebuild = report.get("rebuild")
    require(isinstance(rebuild, dict) and rebuild.get("status") == "published", "rebuild not published")
    require(rebuild.get("generationChanged") is True, "topology generation did not change")
    require(rebuild.get("digestChanged") is True, "topology digest did not change")
    semantic = report.get("semanticResults")
    require(isinstance(semantic, dict), "semantic result proof missing")
    require("firstCaller" in semantic.get("beforeRestartNames", []), "firstCaller absent before restart")
    require("secondCaller" not in semantic.get("beforeRestartNames", []), "future caller present early")
    require("firstCaller" in semantic.get("afterRebuildNames", []), "firstCaller absent after rebuild")
    require("secondCaller" in semantic.get("afterRebuildNames", []), "secondCaller absent after rebuild")


def write_json(value: dict[str, Any], output: str | None) -> None:
    text = json.dumps(value, indent=2, sort_keys=True) + "\n"
    if output:
        path = Path(output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    print(text, end="")
