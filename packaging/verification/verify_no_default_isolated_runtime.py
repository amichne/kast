#!/usr/bin/env python3
"""Prove the default installed and released product has no isolated-runtime authority."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import tarfile


class RetirementRejected(Exception):
    pass


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path)
    parser.add_argument("--release-directory", type=Path)
    parser.add_argument("--installed-product", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--negative-report", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def reject(message: str) -> None:
    raise RetirementRejected(message)


def require_absent(document: str, tokens: tuple[str, ...], owner: str) -> None:
    for token in tokens:
        if token in document:
            reject(f"{owner} retains retired authority: {token}")


def source_checks(root: Path) -> list[str]:
    installer = (root / "install.sh").read_text()
    require_absent(
        installer,
        (
            "KAST_RUNTIME_ARCHIVE",
            "install_runtime_archive",
            "verify_runtime_manifest",
            'runtime_name="kast-semantic-runtime-',
            "kast-complete",
        ),
        "public installer",
    )
    publisher = (root / ".github/scripts/release/publish-release.sh").read_text()
    require_absent(
        publisher,
        ("kast-semantic-runtime-", "verify-assets.py"),
        "release publisher",
    )
    workflow = (root / ".github/workflows/release.yml").read_text()
    require_absent(
        workflow,
        ("verify-published-runtime-delivery.sh",),
        "release workflow",
    )
    build = (root / "build.gradle.kts").read_text()
    require_absent(
        build,
        (
            "semanticRuntimeArchive",
            "assembleKastSemanticRuntimeDist",
            "generateKastControlMetadata",
            "verifyKastSemanticRuntimeDistLayout",
        ),
        "default Gradle delivery",
    )
    composition = (
        root
        / "cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledKastCliComposition.kt"
    ).read_text()
    require_absent(
        composition,
        ("SemanticRuntimeManifest", "InstalledControlResource.SEMANTIC_RUNTIME"),
        "installed CLI composition",
    )
    metadata = (
        root / "cli/src/main/kotlin/io/github/amichne/kast/cli/projection/CliLocalMetadata.kt"
    ).read_text()
    require_absent(metadata, ("semantic runtime",), "installed CLI metadata")
    documentation = "\n".join(
        (root / path).read_text()
        for path in (
            "README.md",
            "docs/public/start.md",
            "docs/public/explanation/how-kast-works.md",
        )
    )
    require_absent(
        documentation,
        (
            "starts or reuses one indexer",
            "runtime archive into its content-addressed store",
            "The JetBrains application can remain closed",
        ),
        "public documentation",
    )
    return [
        "INSTALLER_ARCHIVE_AUTHORITY_ABSENT",
        "RELEASE_RUNTIME_ASSET_ABSENT",
        "DEFAULT_GRADLE_RUNTIME_EDGE_ABSENT",
        "CLI_MANIFEST_AUTHORITY_ABSENT",
        "UNSUPPORTED_FALLBACK_DOCUMENTATION_ABSENT",
    ]


def physical_checks(release: Path, installed: Path) -> tuple[list[str], int]:
    assets = sorted(path.name for path in release.iterdir() if path.is_file())
    payloads = [name for name in assets if not name.endswith(".sha256")]
    if len(payloads) != 2 or not any("ide-plugin" in name for name in payloads):
        reject(f"hosted release payload set is not control plus plugin: {payloads}")
    if any("semantic-runtime" in name for name in assets):
        reject("hosted release contains a semantic-runtime asset")
    control = next(release / name for name in payloads if name.startswith("kast-control-"))
    with tarfile.open(control, "r:gz") as archive:
        names = archive.getnames()
    if any("semantic-runtime" in name or "idea-home" in name for name in names):
        reject("hosted control archive contains isolated-runtime authority")
    forbidden = [
        path
        for path in installed.rglob("*")
        if "semantic-runtime" in path.name or path.name == "idea-home"
    ]
    if forbidden:
        reject(f"staged installed product contains retired payload: {forbidden[0]}")
    return [
        "TWO_HOSTED_PAYLOADS",
        "CONTROL_MANIFEST_ABSENT",
        "PRIVATE_IDEA_HOME_ABSENT",
        "INSTALLED_RUNTIME_ARCHIVE_ABSENT",
    ], len(payloads)


def write(path: Path, document: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(document, separators=(",", ":")) + "\n")
    temporary.replace(path)


def self_test(report: Path | None) -> None:
    fixtures = (
        ("installer", 'export KAST_RUNTIME_ARCHIVE="$runtime_archive"'),
        ("publisher", 'runtime="kast-semantic-runtime-1.2.3.zip"'),
        ("workflow", "verify-published-runtime-delivery.sh"),
        ("gradle", "semanticRuntimeArchive"),
        ("cli", "SemanticRuntimeManifest"),
    )
    for owner, token in fixtures:
        try:
            require_absent(token, (token,), owner)
        except RetirementRejected:
            continue
        reject(f"negative fixture was admitted: {owner}")
    document = {
        "schemaVersion": 1,
        "taskId": "KVP-036",
        "outcome": "REJECTED",
        "rejectedFixtureCount": len(fixtures),
    }
    if report is not None:
        write(report, document)
    print(f"KVP-036 rejected all {len(fixtures)} isolated-runtime authority misuses")


def verify(args: argparse.Namespace) -> None:
    if not all((args.root, args.release_directory, args.installed_product, args.report)):
        reject("--root, --release-directory, --installed-product, and --report are required")
    root = args.root.resolve()
    source = source_checks(root)
    physical, asset_count = physical_checks(
        args.release_directory.resolve(), args.installed_product.resolve(),
    )
    checks = source + physical
    document = {
        "schemaVersion": 1,
        "taskId": "KVP-036",
        "outcome": "COMPLETE",
        "assetCount": asset_count,
        "retiredAuthorityCount": len(checks),
        "checks": checks,
    }
    write(args.report, document)
    print(json.dumps(document, separators=(",", ":")))


def main() -> None:
    args = arguments()
    if args.self_test:
        self_test(args.negative_report)
    else:
        verify(args)


if __name__ == "__main__":
    try:
        main()
    except RetirementRejected as failure:
        raise SystemExit(f"isolated-runtime-retirement: {failure}")
