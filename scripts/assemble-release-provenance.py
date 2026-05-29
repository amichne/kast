#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path


EXPECTED_PLATFORMS = {
    "cli-linux-arm64",
    "cli-linux-x64",
    "cli-macos-arm64",
    "cli-macos-x64",
    "headless",
    "ubuntu-debian-headless-x86_64",
    "ubuntu-debian-x86_64",
    "intellij",
    "standalone",
}


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Assemble release build provenance from downloaded artifact directories."
    )
    parser.add_argument("--output", required=True, help="Path to write combined build-provenance.json")
    parser.add_argument("roots", nargs="+", help="Downloaded artifact roots to scan recursively")
    return parser.parse_args()


def load_entries(roots: list[str]) -> list[dict]:
    files: list[Path] = []
    for root_name in roots:
        root = Path(root_name)
        if not root.exists():
            continue
        files.extend(sorted(root.rglob("build-provenance-*.json")))

    entries: list[dict] = []
    for path in files:
        with path.open(encoding="utf-8") as handle:
            payload = json.load(handle)
        if not isinstance(payload, dict):
            fail(f"provenance file is not a JSON object: {path}")
        entries.append(payload)
    return entries


def stable_values(values: set[object]) -> list[object]:
    return sorted(values, key=lambda value: str(value))


def validate(entries: list[dict]) -> None:
    seen_platforms = [entry.get("platformId") for entry in entries]
    platform_set = set(seen_platforms)
    missing_provenance = EXPECTED_PLATFORMS - platform_set
    unexpected_provenance = platform_set - EXPECTED_PLATFORMS
    duplicate_provenance = stable_values({
        platform for platform in platform_set if seen_platforms.count(platform) > 1
    })

    if missing_provenance or unexpected_provenance or duplicate_provenance:
        fail(
            "provenance platform mismatch: "
            f"missing={stable_values(missing_provenance)} "
            f"unexpected={stable_values(unexpected_provenance)} "
            f"duplicate={duplicate_provenance}"
        )

    for entry in entries:
        platform = entry.get("platformId", "<unknown>")
        asset_name = entry.get("assetName")
        asset_digest = entry.get("assetDigest")
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


def main() -> None:
    args = parse_args()
    entries = load_entries(args.roots)
    validate(entries)
    entries.sort(key=lambda item: item["platformId"])

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"builds": entries}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
