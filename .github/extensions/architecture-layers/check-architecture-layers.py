#!/usr/bin/env python3
"""Validate kast architecture layer dependency rules.

The checker is intentionally static: it reads settings.gradle.kts,
build.gradle.kts files, and .github/architecture-layers.json without resolving
Gradle configurations or contacting dependency repositories.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

MANIFEST_RELATIVE = Path(".github/architecture-layers.json")
SETTINGS_RELATIVE = Path("settings.gradle.kts")
BUILD_FILE = "build.gradle.kts"
CONFIGURATION_CALL_RE = re.compile(
    r"\b(?P<configuration>[A-Za-z][A-Za-z0-9_]*)\s*\("
)
PROJECT_ARGUMENT_RE = re.compile(r"^\s*project\(\s*\"(?P<project>:[^\"]+)\"\s*\)")
INCLUDE_RE = re.compile(r"include\((?P<body>.*?)\)", re.DOTALL)
PROJECT_PATH_RE = re.compile(r"\"(:[^\"]+)\"")


@dataclass(frozen=True)
class ProjectRule:
    path: str
    layer: str
    layer_ordinal: int
    external_allowlist: tuple[str, ...]


@dataclass(frozen=True)
class Finding:
    path: Path
    line: int
    message: str

    def format(self, repo: Path) -> str:
        try:
            display_path = self.path.relative_to(repo)
        except ValueError:
            display_path = self.path
        return f"{display_path}:{self.line}: {self.message}"


def load_manifest(repo: Path) -> dict:
    manifest_path = repo / MANIFEST_RELATIVE
    try:
        return json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise SystemExit(f"Missing architecture manifest: {manifest_path}")
    except json.JSONDecodeError as error:
        raise SystemExit(f"Invalid JSON in {manifest_path}: {error}")


def included_projects(repo: Path) -> set[str]:
    settings_path = repo / SETTINGS_RELATIVE
    try:
        text = settings_path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise SystemExit(f"Missing Gradle settings file: {settings_path}")

    projects: set[str] = set()
    for match in INCLUDE_RE.finditer(text):
        projects.update(PROJECT_PATH_RE.findall(match.group("body")))
    return projects


def project_rules(manifest: dict) -> dict[str, ProjectRule]:
    layers = manifest.get("layers", {})
    rules: dict[str, ProjectRule] = {}
    for project_path, payload in manifest.get("gradleProjects", {}).items():
        layer = payload.get("layer")
        if layer not in layers:
            raise SystemExit(f"Project {project_path} references unknown layer {layer!r}")
        rules[project_path] = ProjectRule(
            path=project_path,
            layer=layer,
            layer_ordinal=int(layers[layer]["ordinal"]),
            external_allowlist=tuple(payload.get("externalAllowlist", [])),
        )
    return rules


def project_dir(repo: Path, project_path: str) -> Path:
    return repo / project_path.removeprefix(":").replace(":", "/")


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def iter_dependency_calls(text: str) -> Iterable[tuple[str, str, int]]:
    for match in CONFIGURATION_CALL_RE.finditer(text):
        argument = extract_call_argument(text, match.end() - 1)
        if argument is not None:
            yield match.group("configuration"), argument, match.start()


def extract_call_argument(text: str, open_paren_offset: int) -> str | None:
    depth = 0
    quote: str | None = None
    escaped = False
    for index in range(open_paren_offset, len(text)):
        char = text[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in ("'", '"'):
            quote = char
            continue
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren_offset + 1 : index]
    return None


def dependency_token(raw: str) -> str:
    token = raw.strip()
    if token.startswith('"') or token.startswith("'"):
        quote = token[0]
        closing_quote = token.find(quote, 1)
        if closing_quote == -1:
            return token
        return token[1:closing_quote]
    return token.split("{")[0].strip().rstrip(",")


def is_allowlisted(token: str, allowlist: Iterable[str]) -> bool:
    return any(token == allowed or token.startswith(f"{allowed}.") for allowed in allowlist)


def validate_project_coverage(
    repo: Path,
    included: set[str],
    rules: dict[str, ProjectRule],
) -> list[Finding]:
    findings: list[Finding] = []
    settings_path = repo / SETTINGS_RELATIVE
    missing = sorted(included - set(rules))
    extra = sorted(set(rules) - included)
    for project_path in missing:
        findings.append(
            Finding(
                settings_path,
                1,
                f"Gradle project {project_path} is missing from .github/architecture-layers.json",
            )
        )
    for project_path in extra:
        findings.append(
            Finding(
                repo / MANIFEST_RELATIVE,
                1,
                f"Manifest contains {project_path}, but settings.gradle.kts does not include it",
            )
        )
    return findings


def validate_dependencies(
    repo: Path,
    manifest: dict,
    rules: dict[str, ProjectRule],
) -> list[Finding]:
    findings: list[Finding] = []
    layers = manifest.get("layers", {})
    production_configurations = set(
        manifest.get("rules", {}).get(
            "productionConfigurations",
            ["api", "implementation", "compileOnly", "runtimeOnly"],
        )
    )

    for source_path, source_rule in sorted(rules.items()):
        build_file = project_dir(repo, source_path) / BUILD_FILE
        if not build_file.exists():
            findings.append(
                Finding(build_file, 1, f"Missing build file for {source_path}")
            )
            continue
        text = build_file.read_text(encoding="utf-8")

        for configuration, argument, offset in iter_dependency_calls(text):
            if configuration not in production_configurations:
                continue
            project_match = PROJECT_ARGUMENT_RE.match(argument)
            if not project_match:
                continue
            target_path = project_match.group("project")
            target_rule = rules.get(target_path)
            if target_rule is None:
                findings.append(
                    Finding(
                        build_file,
                        line_number(text, offset),
                        f"{source_path} depends on unclassified project {target_path}",
                    )
                )
                continue
            if target_rule.layer_ordinal > source_rule.layer_ordinal:
                findings.append(
                    Finding(
                        build_file,
                        line_number(text, offset),
                        f"{source_path} ({source_rule.layer}) must not depend on "
                        f"{target_path} ({target_rule.layer}); dependencies must point "
                        "to the same or a lower layer",
                    )
                )

        external_policy = layers[source_rule.layer].get("externalDependencies")
        if external_policy != "allow-listed-only":
            continue
        for configuration, argument, offset in iter_dependency_calls(text):
            if configuration not in production_configurations:
                continue
            if PROJECT_ARGUMENT_RE.match(argument):
                continue
            token = dependency_token(argument)
            if token.startswith("files(") or token.startswith("fileTree("):
                continue
            if not is_allowlisted(token, source_rule.external_allowlist):
                findings.append(
                    Finding(
                        build_file,
                        line_number(text, offset),
                        f"{source_path} ({source_rule.layer}) uses production external "
                        f"dependency {token!r} without an externalAllowlist entry",
                    )
                )
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo",
        type=Path,
        default=Path.cwd(),
        help="Repository root. Defaults to the current directory.",
    )
    parser.add_argument(
        "--format",
        choices=("text", "json"),
        default="text",
        help="Output format.",
    )
    args = parser.parse_args()

    repo = args.repo.resolve()
    manifest = load_manifest(repo)
    rules = project_rules(manifest)
    included = included_projects(repo)
    findings = [
        *validate_project_coverage(repo, included, rules),
        *validate_dependencies(repo, manifest, rules),
    ]

    if args.format == "json":
        print(
            json.dumps(
                {
                    "ok": not findings,
                    "finding_count": len(findings),
                    "findings": [finding.format(repo) for finding in findings],
                    "manifest": str((repo / MANIFEST_RELATIVE).resolve()),
                },
                indent=2,
            )
        )
    elif findings:
        print("Architecture layer check failed:")
        for finding in findings:
            print(f"- {finding.format(repo)}")
    else:
        print("Architecture layer check passed.")

    return 0 if not findings else 1


if __name__ == "__main__":
    sys.exit(main())
