#!/usr/bin/env python3
"""Run the product gate's documentation integrity and assessment commands."""

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def readability_commands(root: Path) -> tuple[tuple[str, ...], ...]:
    # This optional tooling is introduced by the reader-journey change. A surviving
    # runner OR test keeps the checks selected, including on partial deletion.
    directory = root / "config/vale/readability"
    if not any((directory / name).exists() for name in ("audit.py", "test_audit.py")):
        return ()
    uv = ("uv", "run", "--locked", "--project", "docs/internal/tooling")
    return (
        (*uv, "pytest", "-c", "config/vale/readability/pytest.ini", "config/vale/readability"),
        (*uv, "pyright", "--project", "config/vale/readability/pyrightconfig.json"),
        (*uv, "ruff", "check", "--config", "docs/internal/tooling/pyproject.toml",
         "config/vale/readability/audit.py", "config/vale/readability/test_audit.py"),
        (*uv, "ruff", "format", "--check", "--config", "docs/internal/tooling/pyproject.toml",
         "config/vale/readability/audit.py", "config/vale/readability/test_audit.py"),
    )


def commands(guides: tuple[str, ...]) -> tuple[tuple[str, ...], ...]:
    return (
        ("npm", "ci", "--ignore-scripts", "--engine-strict", "--prefix", "docs/internal/tooling/markdown"),
        ("npm", "run", "check", "--prefix", "docs/internal/tooling/markdown"),
        ("uv", "run", "--locked", "--project", "docs/internal/tooling", "pytest", "-c", "docs/internal/tooling/pyproject.toml", "docs/internal/tooling", "docs/internal/tests"),
        ("uv", "run", "--locked", "--project", "docs/internal/tooling", "pyright",
         "--project", "docs/internal/tooling/pyproject.toml"),
        ("uv", "run", "--locked", "--project", "docs/internal/tooling", "ruff", "check",
         "--config", "docs/internal/tooling/pyproject.toml", "docs/internal/tooling",
         "docs/internal/navigation.py", "docs/internal/tests/test_navigation.py", "bin/kast-repo"),
        ("uv", "run", "--locked", "--project", "docs/internal/tooling", "ruff", "format", "--check",
         "--config", "docs/internal/tooling/pyproject.toml", "docs/internal/tooling",
         "docs/internal/navigation.py", "docs/internal/tests/test_navigation.py", "bin/kast-repo"),
        ("python3", "docs/internal/tooling/callables/build.py", "--check"),
        ("node", "--test", "docs/internal/tooling/callables/loader.test.mjs"),
        ("uv", "run", "--locked", "--project", "packaging", "python", "packaging/tests/test-installer-entrypoint.py"),
        ("npm", "ci", "--ignore-scripts", "--engine-strict", "--prefix", "docs/internal/tooling/value-journey"),
        ("npm", "run", "build", "--prefix", "docs/internal/tooling/value-journey"),
        ("git", "diff", "--exit-code", "--", "docs/public/snippets/value-journey.jsx",
         "docs/public/images/value-journey.svg"),
        ("./gradlew", "verifyKnowledgeBase"),
        ("uv", "run", "--locked", "--project", "docs/internal/tooling", "python",
         "docs/internal/tooling/okf_public.py", "--check"),
        ("./gradlew", "verifyKnowledgeClaims"),
        ("uv", "run", "--locked", "--project", "docs/internal/tooling", "python",
         "docs/internal/tooling/reader_contracts.py", "--report",
         "build/reports/documentation/reader-contracts.json"),
        (
            "uv",
            "run",
            "--locked",
            "--project",
            "docs/internal/tooling",
            "python",
            "docs/internal/tooling/check_glossary.py",
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
    for command in (*commands(guides), *readability_commands(ROOT)):
        subprocess.run(command, cwd=ROOT, env=environment, check=True)


if __name__ == "__main__":
    main()
