#!/usr/bin/env python3
"""Fail closed when CI or release workflows omit runtime-delivery proofs."""

from __future__ import annotations

import argparse
from pathlib import Path


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    return parser.parse_args()


def read(path: Path, failures: list[str]) -> str:
    if not path.is_file():
        failures.append(f"missing workflow: {path.name}")
        return ""
    return path.read_text()


def require_tokens(
    document: str,
    tokens: tuple[str, ...],
    location: str,
    failures: list[str],
) -> None:
    for token in tokens:
        if token not in document:
            failures.append(f"{location} is missing {token}")


def forbid_tokens(
    document: str,
    tokens: tuple[str, ...],
    location: str,
    failures: list[str],
) -> None:
    for token in tokens:
        if token in document:
            failures.append(f"{location} duplicates shared lifecycle token {token}")


def require_order(
    document: str,
    tokens: tuple[str, ...],
    location: str,
    failures: list[str],
) -> None:
    positions = [document.find(token) for token in tokens]
    if any(position < 0 for position in positions):
        return
    if positions != sorted(positions) or len(set(positions)) != len(positions):
        failures.append(f"{location} has the wrong order: {' -> '.join(tokens)}")


def main() -> None:
    root = arguments().root.resolve()
    failures: list[str] = []
    ci = read(root / ".github/workflows/ci.yml", failures)
    require_tokens(
        ci,
        (
            "  pull_request:",
            "  push:",
            "      - main",
            "  repository-contracts:",
            ".github/scripts/check-repository-shape.py",
            ".github/scripts/release/verify-release-contract.py",
            "packaging/test-installer.sh",
            "  kotlin:",
            "    runs-on: macos-15",
            "actions/setup-java@v5",
            "gradle/actions/setup-gradle@v6",
            "test",
            "verifyKastModuleGraph",
            "verifyForbiddenEffects",
            "verifyNoLegacyArchitecture",
            "runtimeDeliveryMvpAcceptance",
        ),
        "CI workflow",
        failures,
    )
    release = read(root / ".github/workflows/release.yml", failures)
    require_tokens(
        release,
        (
            "  workflow_dispatch:",
            "      version:",
            "        required: true",
            "  contents: write",
            "  release:",
            "    runs-on: macos-15",
            "actions/setup-java@v5",
            "gradle/actions/setup-gradle@v6",
            "packaging/test-installer.sh",
            ".github/scripts/release/build-assets.sh",
            ".github/scripts/release/publish-release.sh",
            "packaging/verify-published-runtime-delivery.sh",
        ),
        "release workflow",
        failures,
    )
    require_order(
        release,
        (
            "packaging/test-installer.sh",
            ".github/scripts/release/build-assets.sh",
            ".github/scripts/release/publish-release.sh",
            "packaging/verify-published-runtime-delivery.sh",
        ),
        "release workflow",
        failures,
    )
    build_assets = read(root / ".github/scripts/release/build-assets.sh", failures)
    require_tokens(
        build_assets,
        ("runtimeDeliveryMvpAcceptance", "verify-assets.py"),
        "release asset build",
        failures,
    )
    require_order(
        build_assets,
        ("runtimeDeliveryMvpAcceptance", "verify-assets.py"),
        "release asset build",
        failures,
    )
    installed = read(root / "packaging/test-installed-product.sh", failures)
    require_tokens(
        installed,
        ("packaging/topology_installed_acceptance.py",),
        "staged installed-product verification",
        failures,
    )
    published = read(root / "packaging/verify-published-runtime-delivery.sh", failures)
    require_tokens(
        published,
        (
            'bash "${repository_root}/install.sh" install',
            "packaging/topology_installed_acceptance.py",
        ),
        "published runtime verification",
        failures,
    )
    forbid_tokens(
        published,
        ('"${kast}" change plan', '"${kast}" topology build'),
        "published runtime verification",
        failures,
    )
    if failures:
        raise SystemExit("release-contract: " + "; ".join(failures))
    print("release-contract: ok")


if __name__ == "__main__":
    main()
