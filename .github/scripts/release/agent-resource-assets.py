#!/usr/bin/env python3
import argparse
import hashlib
import io
import json
import sys
import tarfile
from pathlib import Path


PROVIDERS = {
    "codex": (
        ".agents/plugins/marketplace.json",
        "plugins/kast/.codex-plugin/plugin.json",
        "plugins/kast/hooks/hooks.json",
    ),
    "claude": (
        ".claude-plugin/marketplace.json",
        "plugins/kast/.claude-plugin/plugin.json",
        "plugins/kast/hooks/hooks.json",
    ),
    "copilot": (
        ".github/plugin/marketplace.json",
        "plugins/kast/plugin.json",
        "plugins/kast/hooks.json",
    ),
}
SOURCE_FILES = ("SKILL.md", "marketplace.json", "plugin.json", "hooks.json")
PROVENANCE = "kast-agent-resources-provenance.json"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def version_from_tag(tag: str) -> str:
    if not tag.startswith("v") or len(tag) == 1:
        raise ValueError(f"release tag must start with v: {tag}")
    return tag[1:]


def source_digest(source: Path) -> str:
    value = hashlib.sha256()
    paths = [source / "SKILL.md", source / "developer/SKILL.md"]
    paths.extend(
        source / provider / name
        for provider in sorted(PROVIDERS)
        for name in SOURCE_FILES[1:]
    )
    for path in paths:
        value.update(path.relative_to(source).as_posix().encode())
        value.update(b"\0")
        value.update(path.read_bytes())
        value.update(b"\0")
    return value.hexdigest()


def rendered_files(source: Path, provider: str, version: str) -> dict[str, bytes]:
    marketplace, plugin, hooks = PROVIDERS[provider]
    replacements = {
        marketplace: source / provider / "marketplace.json",
        plugin: source / provider / "plugin.json",
        hooks: source / provider / "hooks.json",
        "plugins/kast/skills/developer/SKILL.md": source / "developer/SKILL.md",
        "plugins/kast/skills/kast/SKILL.md": source / "SKILL.md",
    }
    rendered = {
        target: path.read_text(encoding="utf-8")
        .replace("${KAST_VERSION}", version)
        .encode()
        for target, path in replacements.items()
    }
    for path, contents in rendered.items():
        lowered = contents.lower()
        if b"${kast_version}" in lowered or b"kagent" in lowered:
            raise ValueError(f"unrendered or retired identity in {provider}/{path}")
        if path.endswith(".json"):
            json.loads(contents)
    return rendered


def tar_bytes(files: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for path, contents in sorted(files.items()):
            info = tarfile.TarInfo(path)
            info.size = len(contents)
            info.mode = 0o644
            info.mtime = 0
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            archive.addfile(info, io.BytesIO(contents))
    return output.getvalue()


def build(source: Path, output: Path, tag: str, git_sha: str) -> None:
    version = version_from_tag(tag)
    output.mkdir(parents=True, exist_ok=True)
    assets = []
    for provider in sorted(PROVIDERS):
        name = f"kast-{provider}-{tag}.tar"
        contents = tar_bytes(rendered_files(source, provider, version))
        asset_digest = digest(contents)
        (output / name).write_bytes(contents)
        (output / f"{name}.sha256sum").write_text(
            f"{asset_digest}  {name}\n", encoding="utf-8"
        )
        assets.append(
            {"name": name, "provider": provider, "sha256": asset_digest}
        )
    provenance = {
        "assets": assets,
        "gitSha": git_sha,
        "schemaVersion": 1,
        "source": "cli-rs/resources/kast",
        "sourceSha256": source_digest(source),
        "tag": tag,
    }
    (output / PROVENANCE).write_text(
        json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def verify(release_dir: Path, tag: str, git_sha: str) -> None:
    version = version_from_tag(tag)
    provenance = json.loads((release_dir / PROVENANCE).read_text(encoding="utf-8"))
    if provenance.get("tag") != tag or provenance.get("gitSha") != git_sha:
        raise ValueError("agent resource provenance does not match this release")
    entries = provenance.get("assets")
    if not isinstance(entries, list) or not all(
        isinstance(entry, dict) for entry in entries
    ):
        raise ValueError("agent resource provenance has no assets")
    if provenance.get("schemaVersion") != 1 or provenance.get("source") != (
        "cli-rs/resources/kast"
    ):
        raise ValueError("agent resource provenance has the wrong schema")
    by_provider = {entry.get("provider"): entry for entry in entries}
    if set(by_provider) != set(PROVIDERS):
        raise ValueError("agent resource provenance has the wrong providers")

    expected_files = {PROVENANCE}
    for provider in sorted(PROVIDERS):
        name = f"kast-{provider}-{tag}.tar"
        sidecar = f"{name}.sha256sum"
        expected_files.update((name, sidecar))
        contents = (release_dir / name).read_bytes()
        asset_digest = digest(contents)
        if by_provider[provider] != {
            "name": name,
            "provider": provider,
            "sha256": asset_digest,
        }:
            raise ValueError(f"provenance mismatch for {name}")
        if (release_dir / sidecar).read_text(encoding="utf-8") != (
            f"{asset_digest}  {name}\n"
        ):
            raise ValueError(f"checksum mismatch for {name}")
        verify_archive(contents, provider, version)

    present = {path.name for path in release_dir.iterdir() if path.is_file()}
    if present != expected_files:
        raise ValueError("agent resource directory contains unexpected files")


def verify_archive(contents: bytes, provider: str, version: str) -> None:
    expected = {
        *PROVIDERS[provider],
        "plugins/kast/skills/developer/SKILL.md",
        "plugins/kast/skills/kast/SKILL.md",
    }
    with tarfile.open(fileobj=io.BytesIO(contents), mode="r:") as archive:
        members = archive.getmembers()
        if {member.name for member in members} != expected or len(members) != len(
            expected
        ):
            raise ValueError(f"{provider} archive has the wrong files")
        for member in members:
            if (
                not member.isreg()
                or member.mtime != 0
                or member.uid != 0
                or member.gid != 0
                or member.mode != 0o644
                or member.uname
                or member.gname
            ):
                raise ValueError(f"{provider} archive metadata is not deterministic")
            payload = archive.extractfile(member).read()
            if b"${KAST_VERSION}" in payload or b"kagent" in payload.lower():
                raise ValueError(f"{provider}/{member.name} has a retired identity")
        plugin = json.loads(archive.extractfile(PROVIDERS[provider][1]).read())
        if plugin.get("name") != "kast" or plugin.get("version") != version:
            raise ValueError(f"{provider} plugin identity does not match {version}")


def main() -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    build_parser = commands.add_parser("build")
    build_parser.add_argument("--source", type=Path, required=True)
    build_parser.add_argument("--output", type=Path, required=True)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--release-dir", type=Path, required=True)
    for command in (build_parser, verify_parser):
        command.add_argument("--tag", required=True)
        command.add_argument("--git-sha", required=True)
    args = parser.parse_args()
    try:
        if args.command == "build":
            build(args.source, args.output, args.tag, args.git_sha)
        else:
            verify(args.release_dir, args.tag, args.git_sha)
    except (
        OSError,
        KeyError,
        TypeError,
        ValueError,
        json.JSONDecodeError,
        tarfile.TarError,
    ) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
