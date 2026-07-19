#!/usr/bin/env python3
"""Verify the closed-world contract for a published Kast Codex plugin ZIP."""

from __future__ import annotations

import argparse
import json
import re
import stat
import sys
import zipfile
from pathlib import PurePosixPath


REQUIRED_FILES = {
    "marketplace.json",
    ".agents/plugins/marketplace.json",
    "plugins/kast/.codex-plugin/plugin.json",
    "plugins/kast/hooks/hooks.json",
    "plugins/kast/scripts/kast-codex-hook",
    "plugins/kast/skills/kast-codex/SKILL.md",
    "plugins/kast/skills/kast-codex/agents/openai.yaml",
    "plugins/kast/skills/kast-codex/references/commands.md",
    "plugins/kast/skills/kast-codex/references/examples.md",
    "plugins/kast/assets/codex-exposure.toon",
    "plugins/kast/assets/hook-recovery-messages.toon",
    "plugins/kast/assets/kast.svg",
}
FORBIDDEN_FILE_NAMES = {".mcp.json", ".app.json", "commands.json"}
FORBIDDEN_MANIFEST_KEYS = {"agents", "apps", "hooks", "mcpServers"}
LAUNCHER = "plugins/kast/scripts/kast-codex-hook"
HOOKS = {
    "SessionStart": "session-start",
    "SubagentStart": "subagent-start",
    "PreToolUse": "pre-tool-use",
    "PostToolUse": "post-tool-use",
    "Stop": "stop",
}
SKILL = "plugins/kast/skills/kast-codex/SKILL.md"
OPENAI_METADATA = "plugins/kast/skills/kast-codex/agents/openai.yaml"


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify a release-ready Kast Codex plugin archive."
    )
    parser.add_argument("--archive", required=True, help="Codex plugin ZIP to verify")
    parser.add_argument(
        "--version",
        required=True,
        help="Expected plugin semantic version without a leading v",
    )
    return parser.parse_args()


def read_json(archive: zipfile.ZipFile, path: str) -> dict:
    try:
        payload = json.loads(archive.read(path).decode("utf-8"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"invalid JSON in {path}: {error}")
    if not isinstance(payload, dict):
        fail(f"{path} must contain a JSON object")
    return payload


def validate_member(member: zipfile.ZipInfo) -> None:
    name = member.filename
    path = PurePosixPath(name)
    if not name or "\\" in name or path.is_absolute() or ".." in path.parts:
        fail(f"unsafe archive path: {name!r}")
    if path.name in FORBIDDEN_FILE_NAMES:
        fail(f"forbidden Codex plugin payload: {name}")
    if len(path.parts) >= 3 and path.parts[:3] == ("plugins", "kast", "agents"):
        fail(f"custom agent profiles are forbidden: {name}")

    unix_mode = member.external_attr >> 16
    if unix_mode and not (stat.S_ISREG(unix_mode) or stat.S_ISDIR(unix_mode)):
        fail(f"archive member must be a regular file or directory: {name}")


def validate_hooks(archive: zipfile.ZipFile) -> None:
    payload = read_json(archive, "plugins/kast/hooks/hooks.json")
    hooks = payload.get("hooks")
    if not isinstance(hooks, dict) or set(hooks) != set(HOOKS):
        fail(f"hooks.json must define exactly {sorted(HOOKS)}")
    for codex_event, rust_event in HOOKS.items():
        groups = hooks[codex_event]
        if not isinstance(groups, list) or len(groups) != 1:
            fail(f"{codex_event} must contain exactly one hook group")
        group = groups[0]
        if not isinstance(group, dict) or set(group) != {"hooks"}:
            fail(f"{codex_event} hook group has an invalid shape")
        commands = group["hooks"]
        expected = {
            "type": "command",
            "command": f'"$PLUGIN_ROOT/scripts/kast-codex-hook" {rust_event}',
        }
        if commands != [expected]:
            fail(f"{codex_event} must route only to the generated Rust hook event")


def validate_skill(archive: zipfile.ZipFile) -> None:
    try:
        skill = archive.read(SKILL).decode("utf-8")
        metadata = archive.read(OPENAI_METADATA).decode("utf-8")
    except (KeyError, UnicodeDecodeError) as error:
        fail(f"invalid kast-codex skill metadata: {error}")
    if not skill.startswith("---\n"):
        fail("kast-codex SKILL.md must start with YAML frontmatter")
    parts = skill.split("---\n", 2)
    if len(parts) != 3:
        fail("kast-codex SKILL.md frontmatter must have a closing delimiter")
    frontmatter_match = re.fullmatch(
        r'name:\s*([a-z0-9-]+)\ndescription:\s*("(?:[^"\\]|\\.)*")\n?',
        parts[1],
    )
    if frontmatter_match is None:
        fail("kast-codex SKILL.md frontmatter must define exactly name and a quoted description")
    name, encoded_description = frontmatter_match.groups()
    try:
        description = json.loads(encoded_description)
    except json.JSONDecodeError as error:
        fail(f"kast-codex SKILL.md description is invalid: {error}")
    if name != "kast-codex":
        fail("kast-codex SKILL.md frontmatter must declare name: kast-codex")
    if not isinstance(description, str) or not description.strip():
        fail("kast-codex SKILL.md frontmatter must declare a non-empty description")
    metadata_match = re.fullmatch(
        r'interface:\n'
        r'  display_name:\s*("(?:[^"\\]|\\.)*")\n'
        r'  short_description:\s*("(?:[^"\\]|\\.)*")\n'
        r'  default_prompt:\s*("(?:[^"\\]|\\.)*")\n\n'
        r'policy:\n'
        r'  allow_implicit_invocation:\s*(true|false)\n?',
        metadata,
    )
    if metadata_match is None:
        fail("kast-codex openai.yaml does not match the closed metadata schema")
    try:
        display_name, short_description, default_prompt = (
            json.loads(value) for value in metadata_match.groups()[:3]
        )
    except json.JSONDecodeError as error:
        fail(f"kast-codex openai.yaml contains an invalid quoted scalar: {error}")
    if not all(value.strip() for value in [display_name, short_description, default_prompt]):
        fail("kast-codex openai.yaml interface values must be non-empty strings")
    if "$kast-codex" not in default_prompt:
        fail("kast-codex openai.yaml default prompt must mention $kast-codex")
    if metadata_match.group(4) != "true":
        fail("kast-codex openai.yaml must enable implicit invocation")


def validate_archive(path: str, expected_version: str) -> None:
    if not expected_version or expected_version.startswith("v"):
        fail("--version must be a semantic version without a leading v")

    try:
        archive = zipfile.ZipFile(path)
    except (FileNotFoundError, zipfile.BadZipFile) as error:
        fail(f"invalid Codex plugin ZIP: {error}")

    with archive:
        members = archive.infolist()
        member_names = [member.filename for member in members]
        duplicates = sorted({name for name in member_names if member_names.count(name) > 1})
        if duplicates:
            fail(f"duplicate archive members: {duplicates}")
        for member in members:
            validate_member(member)

        file_names = {member.filename for member in members if not member.is_dir()}
        missing = sorted(REQUIRED_FILES - file_names)
        if missing:
            fail(f"missing required Codex plugin files: {missing}")
        unexpected = sorted(file_names - REQUIRED_FILES)
        if unexpected:
            fail(f"unexpected Codex plugin files: {unexpected}")

        manifest = read_json(archive, "plugins/kast/.codex-plugin/plugin.json")
        if manifest.get("name") != "kast":
            fail("plugin manifest name must be kast")
        if manifest.get("version") != expected_version:
            fail(
                "plugin manifest version mismatch: "
                f"expected {expected_version}, got {manifest.get('version')!r}"
            )
        forbidden_manifest_keys = sorted(FORBIDDEN_MANIFEST_KEYS & set(manifest))
        if forbidden_manifest_keys:
            fail(f"plugin manifest contains forbidden keys: {forbidden_manifest_keys}")
        for key in ["description", "author", "homepage", "repository", "license", "skills", "interface"]:
            if key not in manifest:
                fail(f"plugin manifest is missing required field {key}")
        if manifest.get("skills") != "./skills/":
            fail("plugin manifest skills path must be ./skills/")
        interface = manifest.get("interface")
        if not isinstance(interface, dict):
            fail("plugin manifest interface must be an object")
        if interface.get("privacyPolicyURL") != "https://kast.michne.com/privacy/":
            fail("plugin manifest privacyPolicyURL is invalid")
        if interface.get("termsOfServiceURL") != "https://kast.michne.com/terms/":
            fail("plugin manifest termsOfServiceURL is invalid")

        marketplace = read_json(archive, "marketplace.json")
        discovery_marketplace = read_json(archive, ".agents/plugins/marketplace.json")
        if discovery_marketplace != marketplace:
            fail("Codex discovery marketplace must match root marketplace.json")
        if marketplace.get("name") != "kast":
            fail("marketplace name must be kast")
        plugins = marketplace.get("plugins")
        if not isinstance(plugins, list) or len(plugins) != 1:
            fail("marketplace must contain exactly one plugin")
        plugin = plugins[0]
        if not isinstance(plugin, dict) or plugin.get("name") != "kast":
            fail("marketplace plugin name must be kast")
        source = plugin.get("source")
        if source != {"source": "local", "path": "./plugins/kast"}:
            fail("marketplace plugin source must be local ./plugins/kast")
        policy = plugin.get("policy")
        if policy != {"installation": "AVAILABLE", "authentication": "ON_INSTALL"}:
            fail("marketplace policy must be AVAILABLE with ON_INSTALL authentication")
        if plugin.get("category") != "Productivity":
            fail("marketplace category must be Productivity")

        validate_hooks(archive)
        validate_skill(archive)

        exposure = archive.read("plugins/kast/assets/codex-exposure.toon").decode("utf-8")
        version_match = re.search(r"(?m)^version:\s*['\"]?([^'\"\s]+)['\"]?\s*$", exposure)
        if version_match is None:
            fail("codex-exposure.toon must contain a top-level version field")
        if version_match.group(1) != expected_version:
            fail(
                "Codex exposure version mismatch: "
                f"expected {expected_version}, got {version_match.group(1)}"
            )

        launcher = archive.getinfo(LAUNCHER)
        launcher_mode = launcher.external_attr >> 16
        if not stat.S_ISREG(launcher_mode) or launcher_mode & 0o111 == 0:
            fail("kast-codex-hook must be a regular executable file")

    print(f"Verified Kast Codex plugin package {path} at version {expected_version}")


def main() -> None:
    args = parse_args()
    validate_archive(args.archive, args.version)


if __name__ == "__main__":
    main()
