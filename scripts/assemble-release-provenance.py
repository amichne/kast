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
    "codex-plugin",
    "gradle-ro-cache",
    "headless-linux-x64",
    "idea",
    "openapi",
    "runtime-manifest",
    "runtime-compatibility",
    "ubuntu-debian-headless-x86_64",
}
OPTIONAL_PLATFORMS: set[str] = set()
SUPPORTED_PLATFORMS = REQUIRED_PLATFORMS | OPTIONAL_PLATFORMS


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Assemble release build provenance from downloaded artifact directories."
    )
    parser.add_argument("--output", required=True, help="Path to write combined build-provenance.json")
    parser.add_argument("--tag", required=True, help="Release tag that every tag-bound entry must prove")
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


def validate(entries: list[dict], *, release_tag: str) -> None:
    seen_platforms = [entry.get("platformId") for entry in entries]
    platform_set = set(seen_platforms)
    missing_provenance = REQUIRED_PLATFORMS - platform_set
    unexpected_provenance = platform_set - SUPPORTED_PLATFORMS
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
            asset_name.endswith(".zip")
            or asset_name.endswith(".tar.gz")
            or asset_name.endswith(".tar.zst")
            or asset_name.endswith(".json")
            or asset_name.endswith(".yaml")
        ):
            fail(f"provenance entry for {platform} has no supported assetName")
        if (
            not isinstance(asset_digest, str)
            or not asset_digest.startswith("sha256:")
            or asset_digest == "sha256:"
        ):
            fail(f"provenance entry for {platform} has no SHA-256 assetDigest")
        if platform == "idea":
            if entry.get("pluginId") != "io.github.amichne.kast":
                fail("IDEA provenance must name pluginId io.github.amichne.kast")
            signer = entry.get("signerCertificateSha256")
            if not isinstance(signer, str) or len(signer) != 64 or any(
                character not in "0123456789abcdef" for character in signer
            ):
                fail("IDEA provenance signerCertificateSha256 must be lowercase SHA-256")
            if entry.get("signatureVerified") is not True:
                fail("IDEA provenance signatureVerified must be true")
            if entry.get("ref") != f"refs/tags/{release_tag}":
                fail(f"IDEA provenance ref must be refs/tags/{release_tag}")
            release_sha = entry.get("sha")
            if not isinstance(release_sha, str) or len(release_sha) != 40 or any(
                character not in "0123456789abcdef" for character in release_sha
            ):
                fail("IDEA provenance sha must be a full lowercase Git commit SHA")
            if entry.get("verificationTasks") != [
                ":backend-idea:verifyPluginStructure",
                ":backend-idea:verifyPluginXmlPresent",
                ":backend-idea:verifyPlugin",
                ":backend-idea:verifyPluginSignature",
            ]:
                fail("IDEA provenance must carry the complete signed compatibility gate")
        if platform == "codex-plugin":
            if entry.get("ref") != f"refs/tags/{release_tag}":
                fail(f"Codex plugin provenance ref must be refs/tags/{release_tag}")
            release_sha = entry.get("sha")
            if not isinstance(release_sha, str) or len(release_sha) != 40 or any(
                character not in "0123456789abcdef" for character in release_sha
            ):
                fail("Codex plugin provenance sha must be a full lowercase Git commit SHA")
            if entry.get("pluginVersion") != release_tag.removeprefix("v"):
                fail("Codex plugin provenance pluginVersion must match the release tag")
            if entry.get("generatorCommand") != "kast developer codex generate --release":
                fail("Codex plugin provenance must name the release-mode Rust generator")


def main() -> None:
    args = parse_args()
    entries = load_entries(args.roots)
    validate(entries, release_tag=args.tag)
    entries.sort(key=lambda item: item["platformId"])

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"builds": entries}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
