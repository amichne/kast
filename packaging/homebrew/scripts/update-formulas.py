#!/usr/bin/env python3
import json
import os
import re
from pathlib import Path


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
VERSION_RE = re.compile(r"^v?(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)$")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"error: {message}")


def required_env(name: str) -> str:
    value = os.environ.get(name)
    require(value is not None and value.strip(), f"{name} is required")
    return value.strip()


def required_sha(name: str) -> str:
    value = required_env(name).lower()
    require(SHA256_RE.fullmatch(value) is not None, f"{name} must be a 64-character lowercase sha256")
    require(value != "0" * 64, f"{name} must not be the placeholder sha256")
    return value


def release_version() -> str:
    raw = required_env("VERSION")
    match = VERSION_RE.fullmatch(raw)
    require(match is not None, "VERSION must be a semver release such as v1.2.3")
    return match.group(1)


def replace_version(content: str, version: str, formula_name: str) -> str:
    updated, count = re.subn(r'ARTIFACT_VERSION = ".*?"', f'ARTIFACT_VERSION = "{version}"', content)
    require(count == 1, f"{formula_name} must contain exactly one artifact version constant")
    return updated


def replace_cask_version(content: str, version: str, cask_name: str) -> str:
    updated, count = re.subn(r'artifact_version = ".*?"', f'artifact_version = "{version}"', content)
    require(count == 1, f"{cask_name} must contain exactly one artifact version")
    return updated


def replace_sha256s(content: str, replacements: list[str], package_name: str) -> str:
    matches = list(re.finditer(r'sha256 ".*?"', content))
    require(len(matches) == len(replacements), f"{package_name} must contain exactly {len(replacements)} sha256 stanzas")

    pieces: list[str] = []
    cursor = 0
    for match, replacement in zip(matches, replacements):
        pieces.append(content[cursor:match.start()])
        pieces.append(f'sha256 "{replacement}"')
        cursor = match.end()
    pieces.append(content[cursor:])
    return "".join(pieces)


def update_release_state(root: Path, version: str) -> None:
    release_state = root / "release-state.json"
    require(release_state.is_file(), "release-state.json is missing")

    state = json.loads(release_state.read_text(encoding="utf-8"))
    require(state.get("schema_version") == 1, "release-state.json schema_version must be 1")
    require(
        isinstance(state.get("source_index_schema_version"), int) and state["source_index_schema_version"] > 0,
        "release-state.json source_index_schema_version must be a positive integer",
    )
    state["current_release"] = f"v{version}"
    release_state.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")


def update_readme(root: Path, version: str) -> None:
    readme = root / "README.md"
    require(readme.is_file(), "README.md is missing")

    content = readme.read_text(encoding="utf-8")
    content, cli_count = re.subn(
        r"/v\d+\.\d+\.\d+/kast-v\d+\.\d+\.\d+-macos-arm64\.zip",
        f"/v{version}/kast-v{version}-macos-arm64.zip",
        content,
    )
    content, plugin_count = re.subn(
        r"/v\d+\.\d+\.\d+/kast-intellij-v\d+\.\d+\.\d+\.zip",
        f"/v{version}/kast-intellij-v{version}.zip",
        content,
    )
    require(cli_count == 1, "README.md must contain exactly one CLI mirror example")
    require(plugin_count == 1, "README.md must contain exactly one plugin mirror example")
    readme.write_text(content, encoding="utf-8")


def main() -> None:
    root = Path(os.environ.get("KAST_TAP_ROOT", Path(__file__).resolve().parents[1]))
    version = release_version()

    kast_formula = root / "Formula" / "kast.rb"
    plugin_cask = root / "Casks" / "kast-plugin.rb"
    require(kast_formula.is_file(), "Formula/kast.rb is missing")
    require(plugin_cask.is_file(), "Casks/kast-plugin.rb is missing")

    kast = replace_version(kast_formula.read_text(encoding="utf-8"), version, "Formula/kast.rb")
    kast = replace_sha256s(
        kast,
        [
            required_sha("SHA256_MACOS_X64"),
            required_sha("SHA256_MACOS_ARM64"),
            required_sha("SHA256_LINUX_X64"),
            required_sha("SHA256_LINUX_ARM64"),
        ],
        "Formula/kast.rb",
    )
    kast_formula.write_text(kast, encoding="utf-8")

    plugin = replace_cask_version(plugin_cask.read_text(encoding="utf-8"), version, "Casks/kast-plugin.rb")
    plugin = replace_sha256s(plugin, [required_sha("SHA256_PLUGIN")], "Casks/kast-plugin.rb")
    plugin_cask.write_text(plugin, encoding="utf-8")
    update_release_state(root, version)
    update_readme(root, version)


if __name__ == "__main__":
    main()
