#!/usr/bin/env python3
"""Run the product gate's parser and glossary proofs without provisioning Gradle."""

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def commands(guides: tuple[str, ...]) -> tuple[tuple[str, ...], ...]:
    return (
        ("npm", "ci", "--ignore-scripts", "--engine-strict", "--prefix", "docs/tooling/markdown"),
        ("npm", "run", "check", "--prefix", "docs/tooling/markdown"),
        ("uv", "run", "--locked", "--project", "docs/tooling", "pytest", "docs/tooling"),
        ("npm", "ci", "--ignore-scripts", "--engine-strict", "--prefix", "docs/tooling/value-journey"),
        ("npm", "run", "build", "--prefix", "docs/tooling/value-journey"),
        ("git", "diff", "--exit-code", "--", "docs/public/snippets/value-journey.jsx"),
        ("uv", "run", "--locked", "--project", "docs/tooling", "python", ".github/scripts/code_kb.py",
         "check-documentation-inputs", "--repo", ".", "--docs", "openwiki", "--strict", "--format", "json"),
        (
            "uv",
            "run",
            "--locked",
            "--project",
            "docs/tooling",
            "python",
            "docs/tooling/check_glossary.py",
            "docs/glossary.json",
            "README.md",
            "docs",
            *guides,
            "--check-render",
            "docs/GLOSSARY.md",
            "--check-vale",
            "config/vale/styles/Kast",
        ),
    )


def main() -> None:
    guides = tuple(
        subprocess.check_output(
            ["git", "ls-files", "AGENTS.md", "**/AGENTS.md"],
            cwd=ROOT,
            text=True,
        ).splitlines()
    )
    environment = os.environ.copy()
    environment.update(
        NPM_CONFIG_USERCONFIG="/dev/null",
        NPM_CONFIG_GLOBALCONFIG=str(ROOT / "build/documentation/npm-global-empty"),
        NPM_CONFIG_REGISTRY="https://registry.npmjs.org",
        NPM_CONFIG_CACHE=str(ROOT / "build/documentation/npm-cache"),
    )
    empty_config = Path(environment["NPM_CONFIG_GLOBALCONFIG"])
    empty_config.parent.mkdir(parents=True, exist_ok=True)
    empty_config.write_text("")
    for command in commands(guides):
        subprocess.run(command, cwd=ROOT, env=environment, check=True)


if __name__ == "__main__":
    main()
