#!/usr/bin/env python3
"""Play one deterministic Kast observer fixture as a color terminal demo."""

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
    parser.add_argument("--page", default="kast-observer-semantic-impact")
    parser.add_argument("--initial-delay", type=float, default=3.0)
    parser.add_argument("--hold-seconds", type=float, default=8.0)
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


def highlighted_kotlin(source: str) -> str:
    bat = shutil.which("bat")
    if bat is None:
        return "\n".join(f"    {line}" for line in source.splitlines())
    environment = dict(os.environ)
    environment.pop("NO_COLOR", None)
    environment.update({"TERM": "xterm-256color", "COLORTERM": "truecolor"})
    rendered = subprocess.run(
        [bat, "--color=always", "--language=kotlin", "--style=plain"],
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
            end = index + 1
            while end < len(lines) and not lines[end].startswith("```"):
                end += 1
            output.append(highlighted_kotlin("\n".join(lines[index + 1 : end])))
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


def page(manifest: Path, slug: str) -> dict[str, object]:
    document = json.loads(manifest.read_text(encoding="utf-8"))
    pages = document.get("pages") if isinstance(document, dict) else None
    if not isinstance(pages, list):
        raise SystemExit("Observer manifest has no pages.")
    selected = next(
        (candidate for candidate in pages if isinstance(candidate, dict) and candidate.get("slug") == slug),
        None,
    )
    if selected is None or not isinstance(selected.get("messages"), list):
        raise SystemExit(f"Observer manifest has no page named {slug}.")
    return selected


def write(value: str) -> None:
    sys.stdout.write(value)
    sys.stdout.flush()


def main() -> None:
    requested = arguments()
    selected = page(requested.manifest, requested.page)
    messages = selected["messages"]
    write("\033[?1049h\033[?25l\033[2J\033[H\033]0;Kast observer demo\007")
    try:
        time.sleep(requested.initial_delay)
        write(f"{BOLD}{WHITE}Kast observer projection{RESET}\n")
        write(f"{DIM}{GRAY}Offline protocol fixture · no model request{RESET}\n\n")
        time.sleep(1.2)
        for message in messages:
            write(render(str(message)) + "\n\n")
            time.sleep(2.4)
        write(f"{MINT}✓ Structured for people; canonical JSON remains model-only.{RESET}\n")
        time.sleep(requested.hold_seconds)
    finally:
        write("\033[0m\033[?25h\033[?1049l")


if __name__ == "__main__":
    main()
