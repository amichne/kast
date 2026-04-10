#!/usr/bin/env python3
"""Tool-usage assertion library for kast agent evals.

Reads a JSON-lines tool-call transcript and evaluates assertions defined in
eval YAML/JSON definitions.  Each assertion function returns a standardised
result dict so the harness can aggregate pass/fail across an entire suite.

Transcript format (one JSON object per line)::

    {"tool": "kast-resolve.sh", "args": {"workspace-root": "/p", "file": "F.kt"}, "output": "…"}
    {"hook": "post-resolve", "tool": "kast-resolve.sh", …}
    {"event": "bootstrap", …}

CLI usage::

    python assert-tool-usage.py <transcript.jsonl> <assertions.json>

Exits 0 when every assertion passes, 1 otherwise.
"""

from __future__ import annotations

import json
import sys
from typing import Any, Dict, List, Sequence

# ── result helpers ────────────────────────────────────────────────────

AssertionResult = Dict[str, Any]


def _pass(assertion: str, expected: str, actual: str, message: str) -> AssertionResult:
    return {"assertion": assertion, "passed": True, "expected": expected, "actual": actual, "message": message}


def _fail(assertion: str, expected: str, actual: str, message: str) -> AssertionResult:
    return {"assertion": assertion, "passed": False, "expected": expected, "actual": actual, "message": message}


# ── transcript helpers ────────────────────────────────────────────────

def _tool_names(transcript: List[Dict[str, Any]]) -> List[str]:
    """Extract the ordered list of tool names from the transcript."""
    return [entry["tool"] for entry in transcript if "tool" in entry]


# ── assertion functions ───────────────────────────────────────────────

def assert_tool_used(transcript: List[Dict[str, Any]], tool_name: str) -> AssertionResult:
    """Assert that *tool_name* appears at least once in the transcript."""
    tools = _tool_names(transcript)
    if tool_name in tools:
        return _pass("tool_used", tool_name, tool_name, f"Tool '{tool_name}' was used")
    return _fail("tool_used", tool_name, str(tools), f"Tool '{tool_name}' was never used")


def assert_tool_not_used(transcript: List[Dict[str, Any]], tool_name: str) -> AssertionResult:
    """Assert that *tool_name* does **not** appear in the transcript."""
    tools = _tool_names(transcript)
    if tool_name not in tools:
        return _pass("tool_not_used", f"not {tool_name}", f"not {tool_name}", f"Tool '{tool_name}' was correctly absent")
    count = tools.count(tool_name)
    return _fail("tool_not_used", f"not {tool_name}", f"{tool_name} (x{count})", f"Tool '{tool_name}' was used {count} time(s)")


def assert_flag_used(transcript: List[Dict[str, Any]], tool_name: str, flag: str) -> AssertionResult:
    """Assert that an invocation of *tool_name* includes *flag* in its args keys."""
    for entry in transcript:
        if entry.get("tool") == tool_name:
            args = entry.get("args", {})
            if flag in args:
                return _pass("flag_used", f"{tool_name} with {flag}", f"{tool_name} with {flag}",
                             f"Flag '{flag}' found in '{tool_name}' args")
    return _fail("flag_used", f"{tool_name} with {flag}", "flag absent",
                  f"No invocation of '{tool_name}' contained flag '{flag}'")


def assert_bootstrap_count(transcript: List[Dict[str, Any]], expected: int) -> AssertionResult:
    """Assert the number of ``event == "bootstrap"`` entries equals *expected*."""
    actual = sum(1 for e in transcript if e.get("event") == "bootstrap")
    if actual == expected:
        return _pass("bootstrap_count", str(expected), str(actual), f"Bootstrap count is {actual}")
    return _fail("bootstrap_count", str(expected), str(actual),
                  f"Expected {expected} bootstrap event(s), got {actual}")


def assert_tool_sequence(transcript: List[Dict[str, Any]], expected_sequence: Sequence[str]) -> AssertionResult:
    """Assert the exact ordered sequence of tool names matches *expected_sequence*."""
    actual = _tool_names(transcript)
    expected_list = list(expected_sequence)
    if actual == expected_list:
        return _pass("tool_sequence", str(expected_list), str(actual), "Tool sequence matches exactly")
    return _fail("tool_sequence", str(expected_list), str(actual), "Tool sequence does not match")


def assert_tool_sequence_contains(transcript: List[Dict[str, Any]], subsequence: Sequence[str]) -> AssertionResult:
    """Assert *subsequence* appears **in order** (not necessarily contiguously) within the tool names."""
    tools = _tool_names(transcript)
    subseq = list(subsequence)
    it = iter(tools)
    matched: List[str] = []
    for expected_tool in subseq:
        for tool in it:
            if tool == expected_tool:
                matched.append(tool)
                break
        else:
            return _fail("tool_sequence_contains", str(subseq), str(tools),
                          f"Subsequence broken after matching {matched}; missing '{expected_tool}'")
    return _pass("tool_sequence_contains", str(subseq), str(tools), "Subsequence found in tool names")


def assert_output_contains(transcript: List[Dict[str, Any]], tool_name: str, substring: str) -> AssertionResult:
    """Assert that an invocation of *tool_name* has *substring* in its output."""
    for entry in transcript:
        if entry.get("tool") == tool_name:
            output = entry.get("output", "")
            if substring in output:
                return _pass("output_contains", f"'{substring}' in {tool_name} output",
                             f"found in output", f"Substring found in '{tool_name}' output")
    return _fail("output_contains", f"'{substring}' in {tool_name} output",
                  "substring absent", f"No invocation of '{tool_name}' contained '{substring}' in output")


def assert_hook_fired(transcript: List[Dict[str, Any]], hook_name: str) -> AssertionResult:
    """Assert that at least one transcript entry has ``hook == hook_name``."""
    for entry in transcript:
        if entry.get("hook") == hook_name:
            return _pass("hook_fired", hook_name, hook_name, f"Hook '{hook_name}' fired")
    return _fail("hook_fired", hook_name, "not fired", f"Hook '{hook_name}' was never fired")


# ── dispatcher ────────────────────────────────────────────────────────

_DISPATCH: Dict[str, Any] = {
    "tool_used": lambda t, a: assert_tool_used(t, a["tool"]),
    "tool_not_used": lambda t, a: assert_tool_not_used(t, a["tool"]),
    "flag_used": lambda t, a: assert_flag_used(t, a["tool"], a["flag"]),
    "bootstrap_count": lambda t, a: assert_bootstrap_count(t, int(a["expected"])),
    "tool_sequence": lambda t, a: assert_tool_sequence(t, a["sequence"]),
    "tool_sequence_contains": lambda t, a: assert_tool_sequence_contains(t, a["sequence"]),
    "output_contains": lambda t, a: assert_output_contains(t, a["tool"], a["substring"]),
    "hook_fired": lambda t, a: assert_hook_fired(t, a["hook"]),
}


def evaluate(transcript: List[Dict[str, Any]], assertions: List[Dict[str, Any]]) -> List[AssertionResult]:
    """Run every assertion against *transcript* and return ordered results."""
    results: List[AssertionResult] = []
    for assertion_def in assertions:
        atype = assertion_def["type"]
        handler = _DISPATCH.get(atype)
        if handler is None:
            results.append(_fail(atype, "known type", atype, f"Unknown assertion type '{atype}'"))
        else:
            results.append(handler(transcript, assertion_def))
    return results


# ── CLI entrypoint ────────────────────────────────────────────────────

def _load_transcript(path: str) -> List[Dict[str, Any]]:
    """Read a JSON-lines transcript file."""
    entries: List[Dict[str, Any]] = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            stripped = line.strip()
            if stripped:
                entries.append(json.loads(stripped))
    return entries


def main(argv: List[str] | None = None) -> int:
    """CLI entrypoint.  Returns 0 on all-pass, 1 on any failure."""
    args = argv if argv is not None else sys.argv[1:]
    if len(args) != 2:
        print(f"Usage: {sys.argv[0]} <transcript.jsonl> <assertions.json>", file=sys.stderr)
        return 2

    transcript = _load_transcript(args[0])
    with open(args[1], encoding="utf-8") as fh:
        assertions = json.load(fh)

    results = evaluate(transcript, assertions)
    json.dump(results, sys.stdout, indent=2)
    print()  # trailing newline

    return 0 if all(r["passed"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
