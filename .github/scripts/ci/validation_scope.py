#!/usr/bin/env python3
"""Select CI checks from an exact diff; unclassified inputs retain every check."""

import argparse
import re
import subprocess
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SHA = re.compile(r"[0-9a-f]{40}\Z")
# Audited authored site inputs. Adding a page requires reviewing its product consumers.
# Installer examples, generated schemas, and OpenWiki dependencies stay outside.
# The source-inventory regression rejects new references to these narrow inputs.
DOCUMENTATION_PATHS = frozenset(
    {
        "docs/public/agent-harnesses.mdx",
        "docs/public/explanation.mdx",
        "docs/public/explanation/query-lifecycle.mdx",
        "docs/public/how-to.mdx",
        "docs/public/how-to/complete-engineering-task.mdx",
        "docs/public/how-to/inspect-diagnostics.mdx",
        "docs/public/how-to/read-retained-evidence.mdx",
        "docs/public/index.mdx",
        "docs/public/reference.mdx",
        "docs/public/reference/api.mdx",
        "docs/public/reference/callback-observations.mdx",
        "docs/public/reference/callbacks.mdx",
        "docs/public/reference/composition.mdx",
        "docs/public/reference/evidence-boundaries.mdx",
        "docs/public/reference/integrations.mdx",
        "docs/public/reference/internals/architecture.mdx",
        "docs/public/reference/internals/background-startup.mdx",
        "docs/public/reference/lifecycles.mdx",
        "docs/public/reference/limits.mdx",
        "docs/public/reference/management.mdx",
        "docs/public/reference/mcp-catalog.mdx",
        "docs/public/reference/models/diagnostic.mdx",
        "docs/public/reference/models/live-evidence.mdx",
        "docs/public/reference/models/mcp-change-result.mdx",
        "docs/public/reference/models/mcp-investigation-result.mdx",
        "docs/public/reference/models/mcp-read-result.mdx",
        "docs/public/reference/models/mcp-tool-call-result.mdx",
        "docs/public/reference/models/query-item.mdx",
        "docs/public/reference/models/relation-fact.mdx",
        "docs/public/reference/models/tool-rpc-reply.mdx",
        "docs/public/reference/program-tracing.mdx",
        "docs/public/reference/query-pipelines.mdx",
        "docs/public/reference/rpc-catalog.mdx",
        "docs/public/reference/schemas.mdx",
        "docs/public/reference/search.mdx",
        "docs/public/reference/source-map.mdx",
        "docs/public/reference/staleness.mdx",
        "docs/public/reference/troubleshooting.mdx",
        "docs/public/reference/walkthrough.mdx",
        "docs/public/troubleshooting.mdx",
        "docs/public/tutorials.mdx",
        "docs/public/tutorials/first-query.mdx",
    }
)

# Bound to settings.gradle.kts by the source-inventory regression. Unknown modules
# and fixtures must not inherit a narrow plan from a source-set-shaped filename.
PRODUCT_MODULES = frozenset(
    {
        "kernel",
        "distribution/contract",
        "distribution/cli",
        "distribution/managed",
        "protocol/contract",
        "protocol/registry",
        "protocol/wire",
        "workspace/contract",
        "workspace/intellij-read",
        "symbol/contract",
        "symbol/service",
        "symbol/intellij",
        "source/contract",
        "source/service",
        "source/intellij",
        "relation/contract",
        "relation/service",
        "relation/intellij",
        "traversal/contract",
        "traversal/service",
        "query/contract",
        "query/service",
        "query/protocol",
        "topology/contract",
        "topology/build",
        "topology/service",
        "topology/intellij",
        "diagnostic/contract",
        "diagnostic/service",
        "diagnostic/intellij",
        "change/contract",
        "change/plan",
        "change/protocol",
        "change/apply",
        "change/verify",
        "change/recovery",
        "change/intellij",
        "evidence/contract",
        "evidence/sqlite",
        "evidence/topology-sqlite",
        "runtime/hosted",
        "app-server",
        "cli",
    }
)
PRODUCT_SOURCE_PREFIXES = tuple(
    f"{module}/src/{source_set}/kotlin/" for module in PRODUCT_MODULES for source_set in ("main", "test")
)

# Candidate preflight examines this inventory against the resolved native task graph.
NATIVE_INPUT_MODULES = frozenset({
    "change/apply", "change/contract", "change/recovery", "change/verify", "diagnostic/contract",
    "distribution/cli", "distribution/contract", "distribution/managed", "evidence/contract", "evidence/sqlite",
    "kernel", "relation/contract", "symbol/contract", "traversal/contract", "workspace/contract",
})
QUALIFICATION_MODULES = NATIVE_INPUT_MODULES | {"cli"}


class Check(str, Enum):
    SOURCE = "source"
    PRODUCT = "product"
    PORTABLE = "portable"
    DOCUMENTATION = "documentation"
    GATE_GRAPH = "gate_graph"


ALL_CHECKS = frozenset(Check)
PRODUCT_CHECKS = frozenset({Check.SOURCE, Check.PRODUCT, Check.PORTABLE})
SOURCE_CHECKS = frozenset({Check.SOURCE})
DOCUMENTATION_CHECKS = frozenset({Check.DOCUMENTATION})


class Scope(str, Enum):
    FULL = "FULL"
    PRODUCT = "PRODUCT"
    DOCUMENTATION = "DOCUMENTATION"
    SOURCE = "SOURCE"


SCOPE_CHECKS = {
    Scope.FULL: ALL_CHECKS,
    Scope.PRODUCT: PRODUCT_CHECKS,
    Scope.DOCUMENTATION: DOCUMENTATION_CHECKS,
    Scope.SOURCE: SOURCE_CHECKS,
}


class Reason(str, Enum):
    FULL_EVENT = "full-event"
    INVALID_REVISION = "invalid-revision"
    CHECKOUT_MISMATCH = "checkout-mismatch"
    DIFF_UNAVAILABLE = "diff-unavailable"
    NO_CHANGES = "no-changes"
    CHANGED_INPUTS = "changed-inputs"


@dataclass(frozen=True)
class Selection:
    checks: frozenset[Check]
    reason: Reason

    @property
    def scope(self) -> Scope:
        return next((scope for scope, checks in SCOPE_CHECKS.items() if checks == self.checks), Scope.FULL)


def path_checks(path: str) -> frozenset[Check]:
    # Only known independent inputs may omit a check. New build/configuration
    # paths, included-build logic, and unusual filenames keep the full gate.
    if not re.fullmatch(r"[A-Za-z0-9_./-]+", path) or any(part in ("", ".", "..") for part in path.split("/")):
        return ALL_CHECKS
    if path.startswith((".", "build-logic/")):
        return ALL_CHECKS
    if path in DOCUMENTATION_PATHS:
        return DOCUMENTATION_CHECKS
    if path.endswith(".kt") and path.startswith(PRODUCT_SOURCE_PREFIXES):
        owner = path.split("/src/", 1)[0]
        return PRODUCT_CHECKS if owner in QUALIFICATION_MODULES else SOURCE_CHECKS
    return ALL_CHECKS


def classify_paths(paths: tuple[str, ...]) -> frozenset[Check]:
    if not paths:
        return ALL_CHECKS
    classifications = frozenset(path_checks(path) for path in paths)
    # A mixed closure is uncertain, even when each isolated path is known.
    return next(iter(classifications)) if len(classifications) == 1 else ALL_CHECKS


def classify_diff(raw: bytes) -> frozenset[Check]:
    """Only regular-file add/edit/delete observations can authorize a narrow lane."""
    fields = raw.split(b"\0")
    if fields[-1] != b"" or len(fields) % 2 != 1:
        return ALL_CHECKS
    paths: list[str] = []
    for metadata, path in zip(fields[0:-1:2], fields[1:-1:2], strict=True):
        match = re.fullmatch(rb":(100644|000000) (100644|000000) ([0-9a-f]{40}) ([0-9a-f]{40}) ([AMD])", metadata)
        if match is None:
            return ALL_CHECKS
        old_mode, new_mode, old_sha, new_sha, status = match.groups()
        zero = b"0" * 40
        expected_modes = {b"M": (b"100644", b"100644"), b"A": (b"000000", b"100644"), b"D": (b"100644", b"000000")}
        if (
            (old_mode, new_mode) != expected_modes[status]
            or (old_sha == zero) != (status == b"A")
            or (new_sha == zero) != (status == b"D")
        ):
            return ALL_CHECKS
        try:
            paths.append(path.decode("ascii"))
        except UnicodeDecodeError:
            return ALL_CHECKS
    return classify_paths(tuple(paths))


def select(event: str, base: str, head: str, root: Path = ROOT) -> Selection:
    if event not in {"pull_request", "push"}:
        return Selection(ALL_CHECKS, Reason.FULL_EVENT)
    if not SHA.fullmatch(base) or not SHA.fullmatch(head) or "0" * 40 in (base, head):
        return Selection(ALL_CHECKS, Reason.INVALID_REVISION)
    try:
        checkout = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=root,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            text=True,
        )
        if checkout.returncode:
            return Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE)
        if checkout.stdout.strip() != head:
            return Selection(ALL_CHECKS, Reason.CHECKOUT_MISMATCH)
        # PRs compare against the merge base. Pushes compare the entire before/head
        # range, including multiple commits, deletions, and both sides of renames.
        revisions = f"{base}{'...' if event == 'pull_request' else '..'}{head}"
        diff = subprocess.run(
            ["git", "diff", "--raw", "--abbrev=40", "--no-renames", "--no-ext-diff", "-z", revisions, "--"],
            cwd=root,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    except OSError:
        return Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE)
    if diff.returncode:
        return Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE)
    if not diff.stdout:
        return Selection(ALL_CHECKS, Reason.NO_CHANGES)
    checks = classify_diff(diff.stdout)
    # Main retains qualified candidates for developer promotion after source-only PR feedback.
    if event == "push" and checks == SOURCE_CHECKS:
        checks = PRODUCT_CHECKS
    return Selection(checks, Reason.CHANGED_INPUTS)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base", default="")
    parser.add_argument("--head", default="")
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    selection = select(args.event, args.base, args.head)
    with args.github_output.open("a", encoding="utf-8") as output:
        for check in Check:
            output.write(f"{check.value}={'run' if check in selection.checks else 'skip'}\n")
        output.write(f"scope={selection.scope.value}\n")
    selected = ",".join(check.value for check in Check if check in selection.checks)
    print(f"validation-scope: {selected} ({selection.reason.value})", flush=True)


if __name__ == "__main__":
    main()
