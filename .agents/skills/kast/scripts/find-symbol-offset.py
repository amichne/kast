#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


def utf16_units(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def build_symbol_patterns(symbol: str) -> list[re.Pattern[str]]:
    stripped = symbol.strip("`")
    patterns = [
        re.compile(rf"(?<![A-Za-z0-9_`]){re.escape(stripped)}(?![A-Za-z0-9_`])"),
    ]
    if stripped == symbol:
        patterns.append(re.compile(re.escape(f"`{symbol}`")))
    return patterns


def build_declaration_patterns(symbol: str) -> list[tuple[int, re.Pattern[str]]]:
    stripped = symbol.strip("`")
    token = rf"`?{re.escape(stripped)}`?"
    return [
        (
            0,
            re.compile(
                rf"\b(?:class|object|interface|typealias)\s+{token}\b|"
                rf"\b(?:enum|annotation|value)\s+class\s+{token}\b",
            ),
        ),
        (
            1,
            re.compile(rf"\bfun\b[^\n{{=]*{token}\s*\("),
        ),
        (
            2,
            re.compile(rf"\b(?:val|var)\s+{token}\b"),
        ),
    ]


@dataclass(frozen=True)
class Candidate:
    priority: int
    offset: int
    line: int
    column: int
    context: str


def classify(line: str, start: int, end: int, declaration_patterns: list[tuple[int, re.Pattern[str]]]) -> int:
    for priority, pattern in declaration_patterns:
        for match in pattern.finditer(line):
            if match.start() <= start and end <= match.end():
                return priority
    stripped = line.lstrip()
    if stripped.startswith("import "):
        return 4
    if stripped.startswith("package "):
        return 5
    return 6


def find_candidates(path: Path, symbol: str) -> list[Candidate]:
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    symbol_patterns = build_symbol_patterns(symbol)
    declaration_patterns = build_declaration_patterns(symbol)
    seen_offsets: set[int] = set()
    candidates: list[Candidate] = []
    line_offset_utf16 = 0

    for line_number, line in enumerate(lines, start=1):
        line_without_newline = line.rstrip("\r\n")
        matches = []
        for pattern in symbol_patterns:
            matches.extend(pattern.finditer(line_without_newline))
        matches.sort(key=lambda match: (match.start(), match.end()))

        for match in matches:
            column = utf16_units(line_without_newline[:match.start()]) + 1
            offset = line_offset_utf16 + column - 1
            if offset in seen_offsets:
                continue
            seen_offsets.add(offset)
            candidates.append(
                Candidate(
                    priority=classify(
                        line_without_newline,
                        match.start(),
                        match.end(),
                        declaration_patterns,
                    ),
                    offset=offset,
                    line=line_number,
                    column=column,
                    context=line_without_newline,
                ),
            )

        line_offset_utf16 += utf16_units(line)

    candidates.sort(key=lambda item: (item.priority, item.line, item.column, item.context))
    return candidates


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Find declaration-first UTF-16 offsets for a symbol inside a Kotlin file.",
    )
    parser.add_argument("file_path")
    parser.add_argument("--symbol", required=True)
    args = parser.parse_args()

    path = Path(args.file_path).resolve()
    if not path.is_file():
        parser.error(f"File not found: {path}")

    for candidate in find_candidates(path, args.symbol):
        print(f"{candidate.offset}\t{candidate.line}\t{candidate.column}\t{candidate.context}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
