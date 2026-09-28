#!/usr/bin/env python3
"""Select the full CI gate unless a PR changes only independent public docs pages."""
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
import argparse
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
SHA = re.compile(r"[0-9a-f]{40}\Z")
PRODUCT_INPUTS = frozenset({
    "docs/public/start.mdx",
    "docs/public/reference/compatibility.mdx",
})


class Scope(str, Enum):
    PRODUCT = "product"
    PUBLIC_DOCS = "public-docs"


class Reason(str, Enum):
    NOT_PULL_REQUEST = "not-pull-request"
    INVALID_REVISION = "invalid-revision"
    CHECKOUT_MISMATCH = "checkout-mismatch"
    DIFF_UNAVAILABLE = "diff-unavailable"
    NO_CHANGES = "no-changes"
    PRODUCT_INPUT = "product-input"
    PUBLIC_DOCS_ONLY = "public-docs-only"


@dataclass(frozen=True)
class Selection:
    scope: Scope
    reason: Reason


def classify_paths(paths: tuple[str, ...]) -> Scope:
    if not paths:
        return Scope.PRODUCT
    for path in paths:
        if path in PRODUCT_INPUTS:
            return Scope.PRODUCT
        if path == "docs/public/docs.json":
            continue
        if not path.startswith("docs/public/") or not path.endswith(".mdx"):
            return Scope.PRODUCT
        if path.startswith("docs/public/images/"):
            return Scope.PRODUCT
    return Scope.PUBLIC_DOCS


def select(event: str, base: str, head: str, root: Path = ROOT) -> Selection:
    if event != "pull_request":
        return Selection(Scope.PRODUCT, Reason.NOT_PULL_REQUEST)
    if not SHA.fullmatch(base) or not SHA.fullmatch(head):
        return Selection(Scope.PRODUCT, Reason.INVALID_REVISION)
    try:
        checkout = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, check=False, text=True,
        )
        if checkout.returncode:
            return Selection(Scope.PRODUCT, Reason.DIFF_UNAVAILABLE)
        if checkout.stdout.strip() != head:
            return Selection(Scope.PRODUCT, Reason.CHECKOUT_MISMATCH)
        diff = subprocess.run(
            ["git", "diff", "--name-only", "--no-renames", "-z", f"{base}...{head}"],
            cwd=root, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False,
        )
    except OSError:
        return Selection(Scope.PRODUCT, Reason.DIFF_UNAVAILABLE)
    if diff.returncode:
        return Selection(Scope.PRODUCT, Reason.DIFF_UNAVAILABLE)
    try:
        paths = tuple(raw.decode("ascii") for raw in diff.stdout.split(b"\0") if raw)
    except UnicodeDecodeError:
        return Selection(Scope.PRODUCT, Reason.DIFF_UNAVAILABLE)
    if not paths:
        return Selection(Scope.PRODUCT, Reason.NO_CHANGES)
    scope = classify_paths(paths)
    return Selection(scope, Reason.PUBLIC_DOCS_ONLY if scope is Scope.PUBLIC_DOCS else Reason.PRODUCT_INPUT)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base", default="")
    parser.add_argument("--head", default="")
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    selection = select(args.event, args.base, args.head)
    with args.github_output.open("a", encoding="utf-8") as output:
        output.write(f"validation={selection.scope.value}\n")
    print(f"validation-scope: {selection.scope.value} ({selection.reason.value})", flush=True)


if __name__ == "__main__":
    main()
