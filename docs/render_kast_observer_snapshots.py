#!/usr/bin/env python3
"""Render deterministic Kast observer screenshots without Codex or ChatGPT."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from typing import Any


SLUG = re.compile(r"^kast-observer-[a-z0-9-]+$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--styles", required=True, type=Path)
    parser.add_argument("--output-directory", required=True, type=Path)
    return parser.parse_args()


def executable(name: str, macos_candidates: tuple[Path, ...] = ()) -> str:
    configured = os.environ.get(f"KAST_OBSERVER_{name.upper()}")
    candidates = ([Path(configured)] if configured else []) + list(macos_candidates)
    candidates.extend(Path(candidate) for candidate in (shutil.which(name),) if candidate)
    selected = next((candidate for candidate in candidates if candidate.is_file()), None)
    if selected is None:
        raise SystemExit(
            f"Missing {name}. Install it or set KAST_OBSERVER_{name.upper()} to its executable."
        )
    return str(selected)


def browser_executable() -> str:
    configured = os.environ.get("KAST_OBSERVER_CHROME")
    candidates = [Path(configured)] if configured else []
    candidates.extend(
        (
            Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
            Path("/Applications/Chromium.app/Contents/MacOS/Chromium"),
        )
    )
    candidates.extend(
        Path(candidate)
        for name in ("google-chrome", "chromium", "chromium-browser", "chrome")
        if (candidate := shutil.which(name))
    )
    selected = next((candidate for candidate in candidates if candidate.is_file()), None)
    if selected is None:
        raise SystemExit(
            "Missing Chrome/Chromium. Install it or set KAST_OBSERVER_CHROME to its executable."
        )
    return str(selected)


def admitted_pages(document: Any) -> list[dict[str, Any]]:
    if not isinstance(document, dict) or set(document) != {"pages"}:
        raise SystemExit("Observer snapshot manifest has an unexpected root shape.")
    pages = document["pages"]
    if not isinstance(pages, list) or not pages:
        raise SystemExit("Observer snapshot manifest contains no pages.")
    admitted: list[dict[str, Any]] = []
    slugs: set[str] = set()
    for page in pages:
        if not isinstance(page, dict) or set(page) != {"slug", "title", "messages"}:
            raise SystemExit("Observer snapshot page has an unexpected shape.")
        slug, title, messages = page["slug"], page["title"], page["messages"]
        if not isinstance(slug, str) or not SLUG.fullmatch(slug) or slug in slugs:
            raise SystemExit(f"Observer snapshot page has an invalid slug: {slug!r}")
        if not isinstance(title, str) or not title.strip():
            raise SystemExit(f"Observer snapshot page {slug} has no title.")
        if (
            not isinstance(messages, list)
            or not messages
            or any(not isinstance(message, str) or not message.strip() for message in messages)
        ):
            raise SystemExit(f"Observer snapshot page {slug} has invalid messages.")
        forbidden = (
            "candidate:v",
            "exact:v",
            "sha256:",
            "canonical-signature-sha256",
            "source-selector-v",
            "continuation:",
            "/workspace",
            "/Users/",
        )
        leaked = next(
            (token for token in forbidden if any(token in message for message in messages)),
            None,
        )
        if leaked is not None:
            raise SystemExit(f"Observer snapshot page {slug} leaked {leaked}.")
        slugs.add(slug)
        admitted.append(page)
    return admitted


def markdown(page: dict[str, Any]) -> str:
    cells = "\n\n".join(
        f"::: {{.observer-message}}\n{message}\n:::" for message in page["messages"]
    )
    return (
        "::: {.snapshot-heading}\n"
        "<span class=prompt-mark>●</span> Local protocol fixture\n\n"
        f"# {page['title']}\n\n"
        "No model request · schema-admitted observer projection\n"
        ":::\n\n"
        f"{cells}\n"
    )


def render_page(
    page: dict[str, Any],
    pandoc: str,
    browser: str,
    styles: Path,
    output_directory: Path,
    temporary: Path,
) -> None:
    source = temporary / f"{page['slug']}.md"
    html = temporary / f"{page['slug']}.html"
    output = output_directory / f"{page['slug']}.png"
    source.write_text(markdown(page), encoding="utf-8")
    subprocess.run(
        [
            pandoc,
            str(source),
            "--from=gfm+fenced_divs",
            "--to=html5",
            "--standalone",
            "--embed-resources",
            "--syntax-highlighting=zenburn",
            f"--css={styles.resolve().as_uri()}",
            f"--metadata=pagetitle:{page['title']}",
            f"--output={html}",
        ],
        check=True,
    )
    subprocess.run(
        [
            browser,
            "--headless=new",
            "--disable-background-networking",
            "--disable-default-apps",
            "--disable-gpu",
            "--disable-sync",
            "--hide-scrollbars",
            "--metrics-recording-only",
            "--no-first-run",
            "--run-all-compositor-stages-before-draw",
            "--window-size=1440,1050",
            f"--screenshot={output}",
            html.resolve().as_uri(),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )
    if not output.is_file() or output.read_bytes()[:8] != PNG_SIGNATURE:
        raise SystemExit(f"Browser did not produce a valid PNG for {page['slug']}.")


def main() -> None:
    requested = arguments()
    pandoc = executable("pandoc")
    browser = browser_executable()
    pages = admitted_pages(json.loads(requested.manifest.read_text(encoding="utf-8")))
    requested.output_directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="kast-observer-snapshots-") as directory:
        temporary = Path(directory)
        for page in pages:
            render_page(
                page,
                pandoc,
                browser,
                requested.styles,
                requested.output_directory,
                temporary,
            )


if __name__ == "__main__":
    main()
