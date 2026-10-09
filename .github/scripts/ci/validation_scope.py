#!/usr/bin/env python3
"""Select CI checks from an exact diff; unclassified inputs retain every check."""
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
import argparse
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
SHA = re.compile(r"[0-9a-f]{40}\Z")
PRODUCT_DOCS = frozenset({
    "docs/public/start.mdx",
    "docs/public/reference/compatibility.mdx",
    "docs/public/reference/callables.openapi.json",
})


class Check(str, Enum):
    PRODUCT = "product"
    PORTABLE = "portable"
    DOCUMENTATION = "documentation"
    GATE_GRAPH = "gate_graph"


ALL_CHECKS = frozenset(Check)
PRODUCT_CHECKS = frozenset({Check.PRODUCT, Check.PORTABLE})


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


def path_checks(path: str) -> frozenset[Check]:
    # Only known independent inputs may omit a check. New build/configuration
    # paths, included-build logic, and unusual filenames keep the full gate.
    if (not path or any(ord(character) < 32 or ord(character) > 126 for character in path)
            or any(part in ("", ".", "..") for part in path.split("/"))
            or path.startswith((".github/", "build-logic/"))
            or path.endswith((".gradle", ".gradle.kts"))):
        return ALL_CHECKS
    if path in PRODUCT_DOCS:
        return PRODUCT_CHECKS | {Check.DOCUMENTATION}
    if path == "docs/public/docs.json" or (
        path.startswith("docs/public/") and path.endswith(".mdx")
        and not path.startswith("docs/public/images/")
    ):
        return PRODUCT_CHECKS | {Check.DOCUMENTATION}
    if (path.startswith("packaging/") or path in {"install.sh", "README.md"}
            or (path.endswith(".kt") and any(
                source_set in path for source_set in ("/src/main/kotlin/", "/src/test/kotlin/")
            ))):
        # Portable tests may inspect product source as well as packaging scripts.
        return PRODUCT_CHECKS
    return ALL_CHECKS


def classify_paths(paths: tuple[str, ...]) -> frozenset[Check]:
    return frozenset().union(*(path_checks(path) for path in paths)) if paths else ALL_CHECKS


def select(event: str, base: str, head: str, root: Path = ROOT) -> Selection:
    if event not in {"pull_request", "push"}:
        return Selection(ALL_CHECKS, Reason.FULL_EVENT)
    if not SHA.fullmatch(base) or not SHA.fullmatch(head) or "0" * 40 in (base, head):
        return Selection(ALL_CHECKS, Reason.INVALID_REVISION)
    try:
        checkout = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, check=False, text=True,
        )
        if checkout.returncode:
            return Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE)
        if checkout.stdout.strip() != head:
            return Selection(ALL_CHECKS, Reason.CHECKOUT_MISMATCH)
        # PRs compare against the merge base. Pushes compare the entire before/head
        # range, including multiple commits, deletions, and both sides of renames.
        revisions = f"{base}{'...' if event == 'pull_request' else '..'}{head}"
        diff = subprocess.run(
            ["git", "diff", "--name-only", "--no-renames", "--no-ext-diff", "-z", revisions, "--"],
            cwd=root, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False,
        )
    except OSError:
        return Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE)
    if diff.returncode:
        return Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE)
    try:
        paths = tuple(raw.decode("ascii") for raw in diff.stdout.split(b"\0") if raw)
    except UnicodeDecodeError:
        return Selection(ALL_CHECKS, Reason.DIFF_UNAVAILABLE)
    if not paths:
        return Selection(ALL_CHECKS, Reason.NO_CHANGES)
    return Selection(classify_paths(paths), Reason.CHANGED_INPUTS)


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
    selected = ",".join(check.value for check in Check if check in selection.checks)
    print(f"validation-scope: {selected} ({selection.reason.value})", flush=True)


if __name__ == "__main__":
    main()
