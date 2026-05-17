#!/usr/bin/env python3
"""Deterministic tool-call extractor for evaluation transcripts.

Replaces LLM-grader-based tool counting (which silently regressed to 0 in
iteration-003). Reads `outputs/transcript.md` and emits
`outputs/tool_calls.jsonl` — one JSON object per invocation.

Recognised invocation shapes (in order of priority):

1. Copilot CLI JSONL event streams emitted by `--output-format json`,
   especially assistant messages with `toolRequests`.

2. Fenced JSON tool blocks emitted by Copilot/Claude harnesses:
       ```tool:call
       {"tool": "kast_resolve", "arguments": { ... }}
       ```
   Variants: ```tool_use, ```tool, ```call.

3. Anthropic-style XML blocks:
       <tool_use><name>kast_resolve</name><input>{...}</input></tool_use>

4. Inline named-call markers we emit in run_instructions:
       [tool_call name="kast_resolve" args="{...}"]

5. Bash invocations of `kast` and `kast_*` commands inside fenced ```bash
   blocks (so the ground-truth grader can also count CLI invocations).

6. Bash invocations of grep/rg/find/ls inside fenced ```bash blocks.

Pure prose mentions ("I'd run kast_resolve") are NOT counted. The parser
explicitly distinguishes invocation from narration by requiring either a
structured marker or a fenced shell block. This is the whole reason the
LLM grader was unreliable.

Usage:
    python3 parse_tool_calls.py --run-dir <path>
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable

KAST_CLI_PATTERN = re.compile(
    r"\b(kast(?:_[a-z_]+)?|kast)\b\s+([\w\-:.]+)?",
    re.IGNORECASE,
)
GREP_PATTERN = re.compile(r"\b(grep|rg|ripgrep|find|ls)\b")
FENCED_BLOCK_PATTERN = re.compile(
    r"```(?P<lang>tool[_:]?call|tool[_:]?use|tool|call|bash|shell|sh|json)\s*\n(?P<body>.*?)\n```",
    re.DOTALL | re.IGNORECASE,
)
XML_TOOL_PATTERN = re.compile(
    r"<tool_use[^>]*>\s*<name>(?P<name>[^<]+)</name>\s*(?:<input>(?P<input>.*?)</input>)?",
    re.DOTALL | re.IGNORECASE,
)
INLINE_MARKER_PATTERN = re.compile(
    r"\[tool_call\s+name=\"(?P<name>[^\"]+)\"(?:\s+args=\"(?P<args>[^\"]*)\")?\s*\]",
    re.IGNORECASE,
)
KAST_TOOL_NAME = re.compile(r"^kast(_[a-z_][a-z_0-9]*)?$", re.IGNORECASE)


@dataclass(frozen=True)
class ToolCall:
    tool: str
    source: str  # "fenced_json" | "xml" | "marker" | "bash_kast" | "bash_search"
    line: int
    args_excerpt: str = ""

    def to_dict(self) -> dict:
        d = asdict(self)
        return d


def _as_list(value) -> list:
    if isinstance(value, list):
        return value
    if value is None:
        return []
    return [value]


def _line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _extract_jsonl_events(text: str) -> Iterable[ToolCall]:
    for idx, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped.startswith("{"):
            continue
        try:
            event = json.loads(stripped)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        yield from _extract_copilot_event(event, idx)


def _extract_copilot_event(event: dict, line: int) -> Iterable[ToolCall]:
    event_type = str(event.get("type", ""))
    data = event.get("data")
    if not isinstance(data, dict):
        return

    for key in ("toolRequests", "tool_calls", "toolCalls", "toolUse", "tool_uses"):
        for request in _as_list(data.get(key)):
            if not isinstance(request, dict):
                continue
            tool = _tool_name_from_json(request)
            if tool:
                yield ToolCall(
                    tool=tool,
                    source="jsonl_tool_request",
                    line=line,
                    args_excerpt=_excerpt(json.dumps(request)),
                )

    if "tool" not in event_type.lower() or event_type.lower().endswith("tools_updated"):
        return
    tool = _tool_name_from_json(data)
    if tool:
        yield ToolCall(
            tool=tool,
            source="jsonl_tool_event",
            line=line,
            args_excerpt=_excerpt(json.dumps(data)),
        )


def _extract_fenced_json(text: str) -> Iterable[ToolCall]:
    for m in FENCED_BLOCK_PATTERN.finditer(text):
        lang = m.group("lang").lower()
        body = m.group("body")
        line = _line_of(text, m.start())
        if lang in {"bash", "shell", "sh"}:
            yield from _extract_bash_block(body, base_line=line)
            continue
        if lang == "json":
            try:
                payload = json.loads(body)
            except json.JSONDecodeError:
                continue
            tool = _tool_name_from_json(payload)
            if tool:
                yield ToolCall(tool=tool, source="fenced_json", line=line, args_excerpt=_excerpt(body))
            continue
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            payload = None
        if isinstance(payload, dict):
            tool = _tool_name_from_json(payload)
            if tool:
                yield ToolCall(tool=tool, source="fenced_json", line=line, args_excerpt=_excerpt(body))
        else:
            name_m = re.search(r"\"(?:name|tool)\"\s*:\s*\"([^\"]+)\"", body)
            if name_m:
                yield ToolCall(tool=name_m.group(1), source="fenced_json", line=line, args_excerpt=_excerpt(body))


def _tool_name_from_json(payload: dict) -> str | None:
    for key in ("tool", "name", "tool_name", "toolName"):
        value = payload.get(key)
        if isinstance(value, str) and value:
            return value
    return None


def _extract_xml(text: str) -> Iterable[ToolCall]:
    for m in XML_TOOL_PATTERN.finditer(text):
        name = (m.group("name") or "").strip()
        if not name:
            continue
        line = _line_of(text, m.start())
        args = (m.group("input") or "").strip()
        yield ToolCall(tool=name, source="xml", line=line, args_excerpt=_excerpt(args))


def _extract_markers(text: str) -> Iterable[ToolCall]:
    for m in INLINE_MARKER_PATTERN.finditer(text):
        line = _line_of(text, m.start())
        yield ToolCall(tool=m.group("name"), source="marker", line=line, args_excerpt=_excerpt(m.group("args") or ""))


def _extract_bash_block(body: str, base_line: int) -> Iterable[ToolCall]:
    for offset, raw_line in _logical_lines(body):
        line = base_line + offset
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        first = stripped.split()[0] if stripped.split() else ""
        # `kast`, `kast_x`, or invocation via kast CLI subcommand
        kast_m = re.match(r"^(?P<name>kast(?:_[a-z_]+)?|kast)\b", first)
        if kast_m:
            tool = kast_m.group("name")
            # If a second token looks like a subcommand, append it: e.g. "kast resolve"
            tokens = stripped.split()
            if len(tokens) > 1 and tool == "kast" and re.match(r"^[a-z_][a-z_0-9-]*$", tokens[1]):
                tool = f"kast_{tokens[1]}"
            yield ToolCall(tool=tool, source="bash_kast", line=line, args_excerpt=_excerpt(stripped))
            continue
        # grep/rg/find/ls — only count when the line looks like an invocation,
        # not "rg is fast" prose.
        if GREP_PATTERN.match(stripped):
            yield ToolCall(tool=stripped.split()[0], source="bash_search", line=line, args_excerpt=_excerpt(stripped))


def _logical_lines(body: str):
    # Split on newlines but skip continuation lines (trailing backslash).
    lines = body.split("\n")
    accum = []
    base = 0
    for idx, raw in enumerate(lines):
        if accum:
            accum.append(raw.lstrip())
        else:
            base = idx
            accum.append(raw)
        if accum[-1].rstrip().endswith("\\"):
            accum[-1] = accum[-1].rstrip()[:-1]
            continue
        yield base, " ".join(accum)
        accum = []


def _excerpt(text: str, limit: int = 240) -> str:
    snippet = " ".join(text.split())
    return snippet if len(snippet) <= limit else snippet[: limit - 1] + "…"


def extract(transcript: str) -> list[ToolCall]:
    calls: list[ToolCall] = []
    calls.extend(_extract_jsonl_events(transcript))
    calls.extend(_extract_fenced_json(transcript))
    calls.extend(_extract_xml(transcript))
    calls.extend(_extract_markers(transcript))
    # De-duplicate by (line, tool) so a fenced bash block re-counted as both
    # bash_kast and a json wrapper for the same call doesn't double-up.
    seen: set[tuple[int, str]] = set()
    out: list[ToolCall] = []
    for call in sorted(calls, key=lambda c: (c.line, c.tool)):
        key = (call.line, call.tool)
        if key in seen:
            continue
        seen.add(key)
        out.append(call)
    return out


def write_jsonl(calls: list[ToolCall], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for call in calls:
            fh.write(json.dumps(call.to_dict()) + "\n")


def summary(calls: list[ToolCall]) -> dict:
    by_tool: dict[str, int] = {}
    kast_count = 0
    search_count = 0
    for call in calls:
        by_tool[call.tool] = by_tool.get(call.tool, 0) + 1
        if KAST_TOOL_NAME.match(call.tool):
            kast_count += 1
        if call.source == "bash_search" or call.tool in {"grep", "rg", "ripgrep", "find", "ls"}:
            search_count += 1
    return {
        "tool_calls": by_tool,
        "total_tool_calls": len(calls),
        "kast_calls": kast_count,
        "grep_or_find_calls": search_count,
    }


def parse_run_dir(run_dir: Path) -> dict:
    transcript_path = run_dir / "outputs" / "transcript.md"
    if not transcript_path.exists():
        raise FileNotFoundError(f"Transcript not found: {transcript_path}")
    text = transcript_path.read_text(encoding="utf-8", errors="replace")
    calls = extract(text)
    jsonl_path = run_dir / "outputs" / "tool_calls.jsonl"
    write_jsonl(calls, jsonl_path)
    payload = summary(calls)
    payload["transcript_chars"] = len(text)
    payload["tool_call_log"] = str(jsonl_path.relative_to(run_dir))
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description="Parse an evaluation transcript into a structured tool-call log.")
    parser.add_argument("--run-dir", type=Path, required=True, help="Run directory containing outputs/transcript.md")
    parser.add_argument("--print-summary", action="store_true")
    args = parser.parse_args()
    try:
        payload = parse_run_dir(args.run_dir)
    except FileNotFoundError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if args.print_summary:
        print(json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
