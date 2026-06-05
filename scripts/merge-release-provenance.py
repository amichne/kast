#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path


REQUIRED_PLATFORMS = {
    "cli-linux-arm64",
    "cli-linux-x64",
    "cli-macos-arm64",
    "cli-macos-x64",
    "headless",
    "intellij",
}
OPTIONAL_PLATFORMS = {
    "devin-headless-linux-x64",
    "ubuntu-debian-headless-x86_64",
}
SUPPORTED_PLATFORMS = REQUIRED_PLATFORMS | OPTIONAL_PLATFORMS


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Append optional offline bundle provenance to build-provenance.json."
    )
    parser.add_argument("--base", required=True, help="Existing build-provenance.json")
    parser.add_argument("--output", required=True, help="Path to write merged provenance")
    parser.add_argument("roots", nargs="+", help="Roots containing build-provenance-*.json files")
    return parser.parse_args()


def load_payload(path: Path) -> list[dict]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        fail(f"unable to read provenance file {path}: {error}")
    except json.JSONDecodeError as error:
        fail(f"invalid JSON in provenance file {path}: {error}")
    builds = payload.get("builds")
    if not isinstance(builds, list):
        fail(f"provenance file must contain a builds array: {path}")
    for entry in builds:
        if not isinstance(entry, dict):
            fail(f"provenance builds entries must be objects: {path}")
    return builds


def load_append_entries(roots: list[str]) -> list[dict]:
    entries: list[dict] = []
    for root_name in roots:
        root = Path(root_name)
        if not root.exists():
            continue
        for path in sorted(root.rglob("build-provenance-*.json")):
            entries.extend(load_payload(path) if path.name == "build-provenance.json" else [load_single_entry(path)])
    return entries


def load_single_entry(path: Path) -> dict:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        fail(f"unable to read provenance file {path}: {error}")
    except json.JSONDecodeError as error:
        fail(f"invalid JSON in provenance file {path}: {error}")
    if not isinstance(payload, dict):
        fail(f"provenance file is not a JSON object: {path}")
    return payload


def stable_values(values: set[object]) -> list[object]:
    return sorted(values, key=lambda value: str(value))


def validate_entry(entry: dict, source: str) -> tuple[str, str, str]:
    platform = entry.get("platformId")
    asset_name = entry.get("assetName")
    asset_digest = entry.get("assetDigest")
    if platform not in SUPPORTED_PLATFORMS:
        fail(f"unexpected provenance platform in {source}: {platform}")
    if not isinstance(asset_name, str) or not (
        asset_name.endswith(".zip") or asset_name.endswith(".tar.gz")
    ):
        fail(f"provenance entry for {platform} has no supported assetName")
    if (
        not isinstance(asset_digest, str)
        or not asset_digest.startswith("sha256:")
        or asset_digest == "sha256:"
    ):
        fail(f"provenance entry for {platform} has no SHA-256 assetDigest")
    return platform, asset_name, asset_digest


def main() -> None:
    args = parse_args()
    base_path = Path(args.base)
    output_path = Path(args.output)
    merged: dict[str, dict] = {}

    for entry in load_payload(base_path):
        platform, _, _ = validate_entry(entry, str(base_path))
        if platform in merged:
            fail(f"duplicate base provenance platform: {platform}")
        merged[platform] = entry

    missing_required = REQUIRED_PLATFORMS - set(merged)
    if missing_required:
        fail(f"base provenance missing required platforms: {stable_values(missing_required)}")

    for entry in load_append_entries(args.roots):
        platform, _, _ = validate_entry(entry, "append roots")
        if platform in REQUIRED_PLATFORMS:
            fail(f"cannot append required provenance platform: {platform}")
        if platform not in OPTIONAL_PLATFORMS:
            fail(f"cannot append unsupported provenance platform: {platform}")
        merged[platform] = entry

    output_path.parent.mkdir(parents=True, exist_ok=True)
    builds = [merged[platform] for platform in sorted(merged)]
    output_path.write_text(json.dumps({"builds": builds}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
