#!/usr/bin/env python3
"""Fail when the tracked repository retains Rust product ownership."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


SELF = ".github/scripts/check-no-rust-product.py"
RETIRED_ROOT_FILES = frozenset({"Cargo.lock", "Cargo.toml", "install.sh"})
RETIRED_SCRIPT_FILES = frozenset(
    {
        "scripts/install-git-hooks.sh",
        "scripts/rust-agent-metadata.sh",
        "scripts/verify-setup-bundle.sh",
    }
)
RETAINED_BINARY_NAMES = frozenset({"kast", "kastctl"})
FORBIDDEN_REFERENCES = (
    ("CLI_RS", re.compile(r"(?i)(?:^|[^A-Za-z0-9_])cli-rs(?:[^A-Za-z0-9_]|$)")),
    ("RUST_LANGUAGE", re.compile(r"(?i)(?:^|[^A-Za-z0-9_])rust(?:[^A-Za-z0-9_]|$)")),
    (
        "RUST_TOOLCHAIN",
        re.compile(
            r"(?i)(?:^|[^A-Za-z0-9_])"
            r"(?:cargo|rustup|rustfmt|clippy|cargo-deny|rust-cache|rust-cli|rust-agent)"
            r"(?:[^A-Za-z0-9_]|$)"
        ),
    ),
    ("KASTCTL", re.compile(r"(?i)(?:^|[^A-Za-z0-9_])kastctl(?:[^A-Za-z0-9_]|$)")),
    ("TOON", re.compile(r"(?:^|[^A-Za-z0-9_])TOON(?:[^A-Za-z0-9_]|$)")),
    ("RAW_DEVELOPER_RPC", re.compile(r"(?i)\b(?:raw\s+)?developer\s+rpc\b")),
    ("DIRECT_SQLITE_CLI", re.compile(r"(?i)(?:^|[^A-Za-z0-9_])sqlite3(?:[^A-Za-z0-9_]|$)")),
)


@dataclass(frozen=True, order=True)
class Violation:
    code: str
    path: str
    line: int | None = None

    def render(self) -> str:
        location = self.path if self.line is None else f"{self.path}:{self.line}"
        return f"{self.code} {location}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify that no tracked Rust product surface remains."
    )
    parser.add_argument("--root", required=True, type=Path, help="Repository root.")
    return parser.parse_args()


def tracked_paths(root: Path) -> tuple[str, ...]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"cannot read tracked files: {message}")
    return tuple(
        sorted(path for path in result.stdout.decode("utf-8").split("\0") if path)
    )


def is_reference_surface(path: str) -> bool:
    return path != SELF


def path_violations(path: str) -> list[Violation]:
    violations: list[Violation] = []
    relative = Path(path)
    if path.startswith("cli-rs/"):
        violations.append(Violation("RUST_PRODUCT_TREE", path))
        return violations
    if relative.name in {"Cargo.lock", "Cargo.toml"} or relative.suffix == ".rs":
        violations.append(Violation("RUST_SOURCE_OR_CARGO_STATE", path))
    if path in RETIRED_ROOT_FILES | RETIRED_SCRIPT_FILES:
        violations.append(Violation("RUST_OWNED_ENTRYPOINT", path))
    if relative.name in RETAINED_BINARY_NAMES and relative.suffix == "":
        violations.append(Violation("RETAINED_PRODUCT_BINARY", path))
    if (
        relative.name.startswith("kast-")
        and "".join(relative.suffixes) in {".tar.gz", ".zip"}
    ):
        violations.append(Violation("RETAINED_PRODUCT_ARCHIVE", path))
    return violations


def reference_violations(root: Path, path: str) -> list[Violation]:
    if path == SELF or not is_reference_surface(path):
        return []
    try:
        contents = (root / path).read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return []
    contents = contents.replace(SELF, "")
    violations: list[Violation] = []
    lines = contents.splitlines()
    for code, pattern in FORBIDDEN_REFERENCES:
        first_match = next(
            (
                line_number
                for line_number, line in enumerate(lines, start=1)
                if pattern.search(line)
            ),
            None,
        )
        if first_match is not None:
            violations.append(Violation(code, path, first_match))
    return violations


def main() -> int:
    root = parse_args().root.resolve()
    try:
        paths = tracked_paths(root)
    except RuntimeError as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 2

    violations = sorted(
        {
            violation
            for path in paths
            for violation in (
                path_violations(path) + reference_violations(root, path)
            )
        }
    )
    if violations:
        print("ok: false")
        print(f"violations: {len(violations)}")
        for violation in violations:
            print(f"  - {violation.render()}")
        return 1

    print("ok: true")
    print(f"trackedFiles: {len(paths)}")
    print("rustProductOwners: 0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
