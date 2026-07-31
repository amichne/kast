#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import NoReturn


SHA256 = re.compile(r"sha256:[0-9a-f]{64}")


def fail(message: str) -> NoReturn:
    raise SystemExit(f"error: {message}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Render SHA256SUMS from build provenance and uploaded release metadata."
    )
    parser.add_argument("--tag", required=True)
    parser.add_argument("--provenance", required=True, type=Path)
    parser.add_argument("--assets", required=True, type=Path)
    parser.add_argument("--sidecars", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def load_object(path: Path) -> dict:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read JSON object {path}: {error}")
    if not isinstance(payload, dict):
        fail(f"JSON value is not an object: {path}")
    return payload


def expected_sidecar(asset_name: str) -> str | None:
    if asset_name.endswith(".tar.gz"):
        return f"{asset_name}.sha256"
    if asset_name.endswith(".tar.zst"):
        return f"{asset_name.removesuffix('.tar.zst')}.sha256"
    return None


def expected_assets(tag: str) -> dict[str, str]:
    return {
        "cli-linux-arm64": f"kast-{tag}-linux-arm64.zip",
        "cli-linux-x64": f"kast-{tag}-linux-x64.zip",
        "cli-macos-arm64": f"kast-{tag}-macos-arm64.zip",
        "cli-macos-x64": f"kast-{tag}-macos-x64.zip",
        "gradle-ro-cache": "gradle-ro-dep-cache.tar.zst",
        "headless-linux-x64": "kast-headless-linux-x64.tar.zst",
        "openapi": "openapi.yaml",
        "runtime-manifest": "kast-runtime-manifest.json",
        "setup-linux-arm64": f"kast-linux-arm64-{tag}.tar.gz",
        "setup-linux-x64": f"kast-linux-x64-{tag}.tar.gz",
        "setup-macos-arm64": f"kast-macos-arm64-{tag}.tar.gz",
        "setup-macos-x64": f"kast-macos-x64-{tag}.tar.gz",
        "ubuntu-debian-headless-x86_64": (
            f"kast-ubuntu-debian-headless-x86_64-{tag}.tar.gz"
        ),
    }


def provenance_assets(payload: dict, tag: str) -> dict[str, str]:
    builds = payload.get("builds")
    if not isinstance(builds, list) or not builds:
        fail("build provenance must contain a non-empty builds array")
    required_names = expected_assets(tag)
    expected: dict[str, str] = {}
    platforms: set[str] = set()
    for entry in builds:
        if not isinstance(entry, dict):
            fail("build provenance entries must be objects")
        platform = entry.get("platformId")
        name = entry.get("assetName")
        digest = entry.get("assetDigest")
        if not isinstance(platform, str) or not platform:
            fail("build provenance entry has no platformId")
        if platform in platforms:
            fail(f"duplicate build provenance platform: {platform}")
        platforms.add(platform)
        required_name = required_names.get(platform)
        if required_name is None:
            fail(f"unexpected build provenance platform: {platform}")
        if not isinstance(name, str) or not name:
            fail(f"build provenance entry {platform} has no assetName")
        if name != required_name:
            fail(
                f"provenance asset mismatch for {platform}: "
                f"expected {required_name}, got {name}"
            )
        if name in expected:
            fail(f"duplicate build provenance asset: {name}")
        if not isinstance(digest, str) or SHA256.fullmatch(digest) is None:
            fail(f"build provenance entry {platform} has an invalid assetDigest")
        expected[name] = digest
    return expected


def release_assets(payload: dict) -> dict[str, dict]:
    assets = payload.get("assets")
    if not isinstance(assets, list):
        fail("release metadata must contain an assets array")
    indexed: dict[str, dict] = {}
    for asset in assets:
        if not isinstance(asset, dict):
            fail("release asset entries must be objects")
        name = asset.get("name")
        if not isinstance(name, str) or not name:
            fail("release asset entry has no name")
        if name in indexed:
            fail(f"duplicate release asset: {name}")
        indexed[name] = asset
    return indexed


def release_product_assets(remote: dict[str, dict], tag: str) -> set[str]:
    plugin_name = f"kast-idea-{tag}.zip"
    return {
        name
        for name in remote
        if (
            name.endswith(".zip")
            or name.endswith(".tar.gz")
            or name.endswith(".tar.zst")
            or name == "kast-runtime-manifest.json"
            or name == "openapi.yaml"
        )
        and name != plugin_name
        and not name.endswith(".tar.gz.sha256")
        and not name.endswith(".tar.zst.sha256")
    }


def require_uploaded(
    remote_assets: dict[str, dict], name: str, expected_digest: str
) -> None:
    remote = remote_assets.get(name)
    if remote is None:
        fail(f"release asset is missing: {name}")
    if remote.get("state") != "uploaded":
        fail(f"release asset is not uploaded: {name}")
    if remote.get("digest") != expected_digest:
        fail(f"release asset digest does not match provenance: {name}")


def read_sidecar(path: Path, asset_name: str) -> str:
    try:
        lines = [
            line.strip()
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    except OSError as error:
        fail(f"cannot read checksum sidecar {path}: {error}")
    if len(lines) != 1:
        fail(f"checksum sidecar must contain one entry: {path.name}")
    parts = lines[0].split()
    if len(parts) != 2 or parts[1] != asset_name:
        fail(f"checksum sidecar does not name {asset_name}: {path.name}")
    digest = f"sha256:{parts[0]}"
    if SHA256.fullmatch(digest) is None:
        fail(f"checksum sidecar has an invalid digest: {path.name}")
    return digest


def render(
    *, tag: str, provenance: Path, assets: Path, sidecars: Path, output: Path
) -> None:
    if not tag.startswith("v"):
        fail(f"release tag must start with v: {tag}")
    expected = provenance_assets(load_object(provenance), tag)
    remote = release_assets(load_object(assets))
    unexpected_products = sorted(release_product_assets(remote, tag) - set(expected))
    if unexpected_products:
        fail(f"unexpected release product asset: {unexpected_products}")
    expected_sidecars = {
        sidecar: asset
        for asset in expected
        if (sidecar := expected_sidecar(asset)) is not None
    }
    actual_sidecars = {path.name for path in sidecars.glob("*.sha256") if path.is_file()}
    if actual_sidecars != set(expected_sidecars):
        missing = sorted(set(expected_sidecars) - actual_sidecars)
        unexpected = sorted(actual_sidecars - set(expected_sidecars))
        fail(f"checksum sidecar set mismatch: missing={missing} unexpected={unexpected}")

    for name, digest in expected.items():
        require_uploaded(remote, name, digest)
    for sidecar_name, asset_name in expected_sidecars.items():
        sidecar_path = sidecars / sidecar_name
        if read_sidecar(sidecar_path, asset_name) != expected[asset_name]:
            fail(f"checksum sidecar digest does not match provenance: {sidecar_name}")
        sidecar_digest = "sha256:" + hashlib.sha256(sidecar_path.read_bytes()).hexdigest()
        require_uploaded(remote, sidecar_name, sidecar_digest)

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "".join(
            f"{expected[name].removeprefix('sha256:')}  {name}\n"
            for name in sorted(expected)
        ),
        encoding="utf-8",
    )


def main() -> None:
    args = parse_args()
    render(
        tag=args.tag,
        provenance=args.provenance,
        assets=args.assets,
        sidecars=args.sidecars,
        output=args.output,
    )


if __name__ == "__main__":
    main()
