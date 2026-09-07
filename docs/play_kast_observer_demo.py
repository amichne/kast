#!/usr/bin/env python3
"""Play deterministic production-projected Kast presentations as a native TUI demo."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
WHITE = "\033[38;2;238;240;242m"
MINT = "\033[38;2;83;214;182m"
YELLOW = "\033[38;2;242;209;125m"
GRAY = "\033[38;2;144;151;160m"
BLUE = "\033[38;2;107;184;255m"
INLINE_CODE = re.compile(r"`([^`]*)`")
LINK = re.compile(r"\[([^]]+)]\(<[^>]+>\)")
BOLD_SPAN = re.compile(r"\*\*([^*]+)\*\*")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--page", default="all")
    parser.add_argument("--initial-delay", type=float, default=1.5)
    parser.add_argument("--step-delay", type=float, default=1.2)
    parser.add_argument("--hold-seconds", type=float, default=2.5)
    return parser.parse_args()


def inline(markdown: str) -> str:
    projected = LINK.sub(lambda match: f"{MINT}{match.group(1)}{RESET}", markdown)
    projected = INLINE_CODE.sub(lambda match: f"{YELLOW}{match.group(1)}{RESET}", projected)
    projected = BOLD_SPAN.sub(lambda match: f"{BOLD}{WHITE}{match.group(1)}{RESET}", projected)
    if projected.startswith("_") and projected.endswith("_"):
        projected = f"{DIM}{GRAY}{projected[1:-1]}{RESET}"
    return projected


def plain(markdown: str) -> str:
    value = LINK.sub(lambda match: match.group(1), markdown)
    return INLINE_CODE.sub(lambda match: match.group(1), value).replace("**", "")


def render_table(lines: list[str]) -> str:
    rows = [[cell.strip() for cell in line.strip().strip("|").split("|")] for line in lines]
    content = [rows[0], *rows[2:]]
    widths = [max(len(plain(row[index])) for row in content) for index in range(len(rows[0]))]
    rendered: list[str] = []
    for row_index, row in enumerate(content):
        cells: list[str] = []
        for column, cell in enumerate(row):
            padding = " " * (widths[column] - len(plain(cell)))
            if row_index == 0:
                value = f"{BOLD}{GRAY}{plain(cell)}{RESET}"
            elif column == 0 and plain(rows[0][0]) == "Depth":
                value = f"{BLUE}{plain(cell)}{RESET}"
            else:
                value = inline(cell)
            cells.append(value + padding)
        rendered.append("    " + f" {DIM}{GRAY}│{RESET} ".join(cells))
        if row_index == 0:
            rule = f"{DIM}{GRAY}" + "────┼────".join("─" * width for width in widths) + RESET
            rendered.append("    " + rule)
    return "\n".join(rendered)


def highlighted_source(source: str, language: str) -> str:
    bat = shutil.which("bat")
    if bat is None:
        return "\n".join(
            "    "
            + (
                f"{MINT}{line}{RESET}"
                if language == "diff" and line.startswith("+")
                else f"{YELLOW}{line}{RESET}"
                if language == "diff" and line.startswith("-")
                else line
            )
            for line in source.splitlines()
        )
    environment = dict(os.environ)
    environment.pop("NO_COLOR", None)
    environment.update({"TERM": "xterm-256color", "COLORTERM": "truecolor"})
    rendered = subprocess.run(
        [bat, "--color=always", f"--language={language}", "--style=plain"],
        input=source,
        text=True,
        stdout=subprocess.PIPE,
        check=True,
        env=environment,
    ).stdout.rstrip()
    return "\n".join(f"    {line}" for line in rendered.splitlines())


def render(markdown: str) -> str:
    lines = markdown.splitlines()
    output: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        if line.startswith("```"):
            language = line[3:].strip() or "text"
            end = index + 1
            while end < len(lines) and not lines[end].startswith("```"):
                end += 1
            output.append(highlighted_source("\n".join(lines[index + 1 : end]), language))
            index = end + 1
            continue
        if line.startswith("|"):
            end = index
            while end < len(lines) and lines[end].startswith("|"):
                end += 1
            output.append(render_table(lines[index:end]))
            index = end
            continue
        if line.startswith("**Kast · "):
            output.append(f"{BOLD}{MINT}•  {line[2:-2]}{RESET}")
        elif line.startswith("> "):
            output.append(f"   {YELLOW}{line[2:]}{RESET}")
        elif line:
            output.append("   " + inline(line))
        else:
            output.append("")
        index += 1
    return "\n".join(output)


def pages(manifest: Path, slug: str) -> list[dict[str, object]]:
    document = json.loads(manifest.read_text(encoding="utf-8"))
    pages = document.get("pages") if isinstance(document, dict) else None
    if not isinstance(pages, list):
        raise SystemExit("Observer manifest has no pages.")
    selected = (
        [candidate for candidate in pages if isinstance(candidate, dict)]
        if slug == "all"
        else [
            candidate
            for candidate in pages
            if isinstance(candidate, dict) and candidate.get("slug") == slug
        ]
    )
    if not selected or any(not isinstance(candidate.get("items"), list) for candidate in selected):
        raise SystemExit(f"Observer manifest has no page named {slug}.")
    return selected


def render_changes(changes: object) -> str:
    if not isinstance(changes, list) or not changes:
        raise SystemExit("Native file-change presentation contains no changes.")
    rendered: list[str] = []
    for candidate in changes:
        if not isinstance(candidate, dict):
            raise SystemExit("Native file-change presentation is malformed.")
        path = candidate.get("path")
        kind = candidate.get("kind")
        diff = candidate.get("diff")
        if not all(isinstance(value, str) and value for value in (path, kind, diff)):
            raise SystemExit("Native file-change presentation is malformed.")
        rendered.append(
            f"   {YELLOW}{kind}{RESET} · {MINT}{path}{RESET}\n\n"
            + highlighted_source(diff, "diff")
        )
    return "\n\n".join(rendered)


def render_item(item: object) -> str:
    if not isinstance(item, dict):
        raise SystemExit("Observer item is malformed.")
    if item.get("presentation") == "markdown" and isinstance(item.get("markdown"), str):
        return render(item["markdown"])
    if item.get("presentation") == "file-changes":
        return (
            f"   {BOLD}{WHITE}Native file diff{RESET}\n\n"
            + render_changes(item.get("changes"))
        )
    raise SystemExit("Observer item has no supported presentation.")


def write(value: str) -> None:
    sys.stdout.write(value)
    sys.stdout.flush()


def main() -> None:
    requested = arguments()
    selected_pages = pages(requested.manifest, requested.page)
    write("\033[?1049h\033[?25l\033[2J\033[H\033]0;Kast observer demo\007")
    try:
        time.sleep(requested.initial_delay)
        for page_index, selected in enumerate(selected_pages):
            if page_index:
                write("\033[2J\033[H")
            write(f"{BOLD}{WHITE}Kast native tool presentation{RESET}\n")
            write(f"{MINT}● Mutation authority enabled{RESET}")
            write(f"{DIM}{GRAY} · deterministic production projection{RESET}\n")
            write(f"{DIM}{GRAY}{selected['title']}{RESET}\n\n")
            for item in selected["items"]:
                if not isinstance(item, dict) or not isinstance(item.get("operation"), str):
                    raise SystemExit("Observer item has no operation.")
                operation = item["operation"]
                write(f"{BLUE}›{RESET}  Kast · {operation}")
                time.sleep(requested.step_delay)
                write(f"\r\033[2K{MINT}✓{RESET}  {BOLD}Kast · {operation}{RESET}\n")
                write(render_item(item) + "\n\n")
                time.sleep(requested.step_delay)
            write(f"{MINT}✓ Compact native rows; expand for bounded semantic evidence.{RESET}\n")
            time.sleep(requested.hold_seconds)
        write("\033[2J\033[H")
        write(f"{BOLD}{WHITE}Kast native TUI · complete{RESET}\n\n")
        write(f"{MINT}✓ 9 canonical operations demonstrated{RESET}\n")
        write(f"{MINT}✓ Mutation catalog explicitly enabled{RESET}\n")
        write(f"{MINT}✓ Applied edits open as native file diffs{RESET}\n\n")
        write(f"{DIM}{GRAY}Canonical JSON remains model-facing; observer views stay bounded.{RESET}\n")
        time.sleep(requested.hold_seconds)
    finally:
        write("\033[0m\033[?25h\033[?1049l")


if __name__ == "__main__":
    main()
