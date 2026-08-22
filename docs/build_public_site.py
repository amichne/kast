#!/usr/bin/env python3
"""Build the reader site without publishing repository-only source files."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/public"
SITE = ROOT / "site"
CONFIG = ROOT / "zensical.toml"

REPOSITORY_ONLY_NAMES = {
    "AGENTS.md",
    "likec4.config.json",
    "model.c4",
    "specification.c4",
    "views.c4",
}

EXPECTED_OUTPUTS = {
    "index.html",
    "start/index.html",
    "questions/workspace-readiness/index.html",
    "questions/declaration-identity/index.html",
    "questions/code-connections/index.html",
    "questions/semantic-validity/index.html",
    "questions/safe-change/index.html",
    "concepts/evidence-boundaries/index.html",
    "reference/cli/index.html",
    "explanation/how-kast-works/index.html",
    "architecture/likec4-views.mjs",
    "javascripts/accessibility.js",
    "stylesheets/extra.css",
}


def ignored_source(_directory: str, names: list[str]) -> set[str]:
    return set(names) & REPOSITORY_ONLY_NAMES


def require_clean_publication() -> None:
    missing = sorted(relative for relative in EXPECTED_OUTPUTS if not (SITE / relative).is_file())
    if missing:
        raise RuntimeError(f"public site is missing expected output: {missing}")

    leaked = sorted(
        output.relative_to(SITE).as_posix()
        for output in SITE.rglob("*")
        if output.is_file()
        and (
            "AGENTS" in output.relative_to(SITE).parts
            or output.suffix == ".c4"
            or output.name == "likec4.config.json"
        )
    )
    if leaked:
        raise RuntimeError(f"public site contains repository-only sources: {leaked}")

    for index_name in ("search.json", "sitemap.xml"):
        index = SITE / index_name
        if index.is_file() and "AGENTS" in index.read_text():
            raise RuntimeError(f"public site index contains repository guidance: {index_name}")


def main() -> None:
    staging = Path(tempfile.mkdtemp(prefix="zensical-stage-", dir=ROOT))
    config_handle = tempfile.NamedTemporaryFile(
        prefix=".zensical-build-",
        suffix=".toml",
        dir=ROOT,
        delete=False,
    )
    generated_config = Path(config_handle.name)
    config_handle.close()

    try:
        shutil.copytree(SOURCE, staging, dirs_exist_ok=True, ignore=ignored_source)

        source_config = CONFIG.read_text()
        authority = 'docs_dir = "docs/public"'
        if source_config.count(authority) != 1:
            raise RuntimeError("Zensical docs_dir authority changed")
        staged_config = source_config.replace(
            authority,
            f'docs_dir = "{staging.relative_to(ROOT).as_posix()}"',
        )
        generated_config.write_text(staged_config)

        subprocess.run(
            [
                "zensical",
                "build",
                "--config-file",
                str(generated_config),
                "--clean",
                "--strict",
            ],
            cwd=ROOT,
            check=True,
        )
        require_clean_publication()
    finally:
        shutil.rmtree(staging)
        generated_config.unlink(missing_ok=True)

    print("public-site: strict build excludes repository-only sources")


if __name__ == "__main__":
    main()
