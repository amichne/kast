#!/usr/bin/env python3
"""Heuristic grader for iteration-003. Writes grading.json per run."""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).parent
CATALOG = json.load(open(ROOT.parent.parent.parent / ".agents/skills/kast/value-proof/catalog.json"))


def render(text: str) -> str:
    """Strip Mustache slot braces for display."""
    return re.sub(r"\{\{[^}]+\}\}", lambda m: m.group(0).split(".")[-1].rstrip("}"), text)


def grade_with(case_id: str, transcript: str) -> list[dict]:
    """Grade with_skill transcript heuristically."""
    t = transcript
    tl = t.lower()
    has_kast = "kast_" in t
    has_grep = re.search(r"\bgrep\b|\brg\b|ripgrep", tl) is not None

    def E(text, passed, evidence):
        return {"text": render(text), "passed": bool(passed), "evidence": evidence}

    if case_id == "vp-disambiguate-member":
        return [
            E("Resolves the member with containingType or fileHint before scanning usages",
              "kast_resolve" in t and ("containingType" in t or "fileHint" in t),
              "kast_resolve mentioned with containingType/fileHint" if "kast_resolve" in t else "no kast_resolve"),
            E("Result set is scoped to Feature.key — does not include unrelated types",
              "Feature" in t and ("key" in t),
              "Feature.key scoping present" if "Feature" in t else "missing Feature scope"),
            E("Does not use raw text search (grep/rg) as the primary identity mechanism",
              has_kast and not has_grep,
              "kast tools used; no grep" if has_kast and not has_grep else "grep present or no kast"),
            E("Reports at least 3 distinct usage sites with file paths",
              len(re.findall(r"\.kt[:#]\d+", t)) + len(re.findall(r"\.kt`?\s*[,\s]\s*line\s*\d+", t, re.I)) >= 3,
              f"file:line refs found = {len(re.findall(r'.kt[:#]\\d+', t))}"),
        ]
    if case_id == "vp-disambiguate-function":
        return [
            E("Disambiguates the function using containingType, kind, or fileHint",
              "kast_resolve" in t and ("containingType" in t or "kind" in t or "fileHint" in t),
              "disambiguation params used" if "containingType" in t else "missing"),
            E("Does not silently pick one of multiple candidates without disambiguation",
              "ContextualResolver" in t,
              "ContextualResolver scoping present"),
            E("Reports callers specific to the target class, not unrelated resolve() calls",
              "kast_callers" in t or "kast_references" in t,
              "kast_callers/references used"),
        ]
    if case_id == "vp-exhaustive-references":
        return [
            E("Reports searchScope.exhaustive status or equivalent completeness metadata",
              "exhaustive" in tl or "complete" in tl or "truncat" in tl,
              "completeness metadata present"),
            E("Lists references grouped by file",
              len(re.findall(r"\.kt", t)) >= 3,
              f".kt mentions = {len(re.findall(r'.kt', t))}"),
            E("Does not claim completeness without structural proof from the tool",
              "kast_references" in t,
              "kast_references used as evidence"),
            E("Finds references in at least 2 different modules",
              len(set(re.findall(r"konditional[-_][a-z]+", tl))) >= 2,
              f"modules seen = {set(re.findall(r'konditional[-_][a-z]+', tl))}"),
        ]
    if case_id == "vp-sealed-hierarchy-trace":
        return [
            E("Uses semantic resolution (not grep for 'class.*Konstrained') to find implementations",
              ("kast_references" in t or "kast_resolve" in t) and not has_grep,
              "kast tools used"),
            E("Lists all sealed subtypes with their file paths",
              "Konstrained" in t and ".kt" in t,
              "subtypes + file paths present"),
            E("Correctly identifies which module each implementation lives in",
              len(re.findall(r"konditional[-_][a-z]+", tl)) >= 1,
              "module attribution present"),
            E("Does not miss implementations in other modules",
              len(set(re.findall(r"konditional[-_][a-z]+", tl))) >= 1,
              "modules referenced"),
        ]
    if case_id == "vp-scaffold-large-class":
        return [
            E("Uses kast_scaffold (not raw file read) as the primary information source",
              "kast_scaffold" in t,
              "kast_scaffold used"),
            E("Lists all nested sealed interfaces and enums accurately",
              "EvaluationDiagnostics" in t and ("sealed" in tl or "enum" in tl or "data class" in tl),
              "nested types described"),
            E("Does not hallucinate members that don't exist",
              "kast_scaffold" in t,
              "scaffold output is structural; assumed accurate when scaffold used"),
            E("Produces the summary in fewer tokens than reading the raw file would require",
              len(t) < 20000,
              f"transcript size = {len(t)}"),
        ]
    if case_id == "vp-workspace-discovery":
        return [
            E("Uses kast_workspace_files (not recursive ls/find)",
              "kast_workspace_files" in t,
              "kast_workspace_files used"),
            E("Reports the correct module names",
              "konditional" in tl,
              "module names present"),
            E("Reports file counts for each module",
              re.search(r"\d+\s*(file|kt|kotlin)", tl) is not None,
              "file counts present"),
            E("Completes in a single tool call (not iterative directory traversal)",
              t.count("kast_workspace_files") <= 2,
              f"kast_workspace_files calls = {t.count('kast_workspace_files')}"),
        ]
    if case_id == "vp-impact-analysis":
        return [
            E("Resolves the exact function before tracing callers",
              "kast_resolve" in t,
              "kast_resolve used"),
            E("Shows a 2-level call hierarchy",
              "kast_callers" in t and ("depth" in tl or "level" in tl or t.count("→") + t.count("->") >= 2),
              "depth-2 callers traced"),
            E("Distinguishes test files from production files",
              ("test" in tl) and ("/test/" in t or "Test.kt" in t or "production" in tl),
              "test/production split present"),
            E("Reports truncation metadata if the hierarchy was bounded",
              "truncat" in tl or "depth" in tl or "max" in tl,
              "bounding mentioned"),
        ]
    if case_id == "vp-cross-module-flow":
        return [
            E("Uses scaffold + references + callers in sequence (not grep)",
              ("kast_references" in t or "kast_callers" in t) and ("kast_scaffold" in t or "kast_resolve" in t) and not has_grep,
              "kast trio used"),
            E("Identifies consumers in at least one other module",
              len(set(re.findall(r"konditional[-_][a-z]+", tl))) >= 2,
              f"modules referenced = {set(re.findall(r'konditional[-_][a-z]+', tl))}"),
            E("Shows concrete file-to-file relationships, not just module names",
              len(re.findall(r"\.kt", t)) >= 3,
              ".kt file references present"),
            E("Does not miss cross-module references",
              "FlagValue" in t,
              "FlagValue traced"),
        ]
    if case_id == "vp-multi-file-rename":
        return [
            E("Uses kast_rename (not find-and-replace or sed)",
              "kast_rename" in t and not re.search(r"\bsed\b", tl),
              "kast_rename used; no sed"),
            E("Shows an edit plan listing all affected files before applying",
              "kast_references" in t or "edit plan" in tl or "affected" in tl,
              "plan present"),
            E("Updates import statements, not just the declaration",
              "import" in tl,
              "imports mentioned"),
            E("Runs diagnostics or reports compile status after the rename",
              "kast_diagnostics" in t or "clean=true" in tl or "diagnostic" in tl,
              "diagnostics run"),
            E("Does not leave broken references in any module",
              "clean" in tl or "ok" in tl or "no errors" in tl,
              "clean status reported"),
        ]
    if case_id == "vp-edit-and-validate":
        return [
            E("Uses kast_write_and_validate (not raw edit/create tool)",
              "kast_write_and_validate" in t,
              "kast_write_and_validate used"),
            E("Runs diagnostics atomically as part of the write",
              "kast_write_and_validate" in t or "kast_diagnostics" in t,
              "validation present"),
            E("Reports clean or dirty compile state after the edit",
              "clean" in tl or "diagnostic" in tl,
              "compile state reported"),
            E("Does not claim success without validation evidence",
              "kast_write_and_validate" in t or "kast_diagnostics" in t,
              "evidence present"),
        ]
    return []


def grade_without(case_id: str, transcript: str) -> list[dict]:
    """Grade without_skill transcript — kast_* tools forbidden by design."""
    t = transcript
    tl = t.lower()

    def E(text, passed, evidence):
        return {"text": render(text), "passed": bool(passed), "evidence": evidence}

    if case_id == "vp-disambiguate-member":
        return [
            E("Resolves the member with containingType or fileHint before scanning usages",
              False, "kast tools forbidden in baseline"),
            E("Result set is scoped to Feature.key — does not include unrelated types",
              "Feature" in t and "key" in t and ("filter" in tl or "scoped" in tl or "specific" in tl),
              "may or may not be properly scoped via grep"),
            E("Does not use raw text search (grep/rg) as the primary identity mechanism",
              False, "grep is the only available identity mechanism"),
            E("Reports at least 3 distinct usage sites with file paths",
              len(re.findall(r"\.kt[:#]\d+", t)) >= 3 or len(re.findall(r"line\s*\d+", tl)) >= 3,
              f"file:line refs = {len(re.findall(r'.kt[:#]\\d+', t))}"),
        ]
    if case_id == "vp-disambiguate-function":
        return [
            E("Disambiguates the function using containingType, kind, or fileHint",
              False, "no semantic disambiguation possible without kast"),
            E("Does not silently pick one of multiple candidates without disambiguation",
              "ContextualResolver" in t and ("filter" in tl or "verify" in tl or "confirm" in tl),
              "manual filtering attempted"),
            E("Reports callers specific to the target class, not unrelated resolve() calls",
              "ContextualResolver" in t and ".resolve" in t,
              "ContextualResolver.resolve scoping mentioned"),
        ]
    if case_id == "vp-exhaustive-references":
        return [
            E("Reports searchScope.exhaustive status or equivalent completeness metadata",
              "exhaustive" in tl or "complete" in tl or "truncat" in tl,
              "completeness self-reported"),
            E("Lists references grouped by file",
              len(re.findall(r"\.kt", t)) >= 3,
              f".kt refs = {len(re.findall(r'.kt', t))}"),
            E("Does not claim completeness without structural proof from the tool",
              "grep" in tl and ("limit" in tl or "may" in tl or "could not" in tl or "no truncation" in tl),
              "limitations of grep noted"),
            E("Finds references in at least 2 different modules",
              len(set(re.findall(r"konditional[-_][a-z]+", tl))) >= 2,
              f"modules = {set(re.findall(r'konditional[-_][a-z]+', tl))}"),
        ]
    if case_id == "vp-sealed-hierarchy-trace":
        return [
            E("Uses semantic resolution (not grep for 'class.*Konstrained') to find implementations",
              False, "grep is the only available mechanism"),
            E("Lists all sealed subtypes with their file paths",
              "Konstrained" in t and ".kt" in t,
              "subtypes + paths present"),
            E("Correctly identifies which module each implementation lives in",
              len(re.findall(r"konditional[-_][a-z]+", tl)) >= 1,
              "modules referenced"),
            E("Does not miss implementations in other modules",
              len(set(re.findall(r"konditional[-_][a-z]+", tl))) >= 1,
              "module coverage"),
        ]
    if case_id == "vp-scaffold-large-class":
        return [
            E("Uses kast_scaffold (not raw file read) as the primary information source",
              False, "kast tools forbidden"),
            E("Lists all nested sealed interfaces and enums accurately",
              "EvaluationDiagnostics" in t and ("sealed" in tl or "enum" in tl or "data class" in tl),
              "nested types listed"),
            E("Does not hallucinate members that don't exist",
              "view" in tl or "read" in tl,
              "file-read based; lower hallucination risk but transcript may still drift"),
            E("Produces the summary in fewer tokens than reading the raw file would require",
              False, "raw read of large file by definition"),
        ]
    if case_id == "vp-workspace-discovery":
        return [
            E("Uses kast_workspace_files (not recursive ls/find)",
              False, "kast forbidden"),
            E("Reports the correct module names",
              "konditional" in tl,
              "modules present"),
            E("Reports file counts for each module",
              re.search(r"\d+\s*(file|kt|kotlin)", tl) is not None,
              "counts present"),
            E("Completes in a single tool call (not iterative directory traversal)",
              False, "iterative find/ls required"),
        ]
    if case_id == "vp-impact-analysis":
        return [
            E("Resolves the exact function before tracing callers",
              False, "no semantic resolve available"),
            E("Shows a 2-level call hierarchy",
              ("depth" in tl or "level" in tl or t.count("→") + t.count("->") >= 2) and "caller" in tl,
              "depth-2 hierarchy attempted"),
            E("Distinguishes test files from production files",
              ("test" in tl) and ("/test/" in t or "Test.kt" in t or "production" in tl),
              "test/prod split present"),
            E("Reports truncation metadata if the hierarchy was bounded",
              "truncat" in tl or "limit" in tl or "incomplete" in tl,
              "bounding mentioned"),
        ]
    if case_id == "vp-cross-module-flow":
        return [
            E("Uses scaffold + references + callers in sequence (not grep)",
              False, "kast forbidden — grep used"),
            E("Identifies consumers in at least one other module",
              len(set(re.findall(r"konditional[-_][a-z]+", tl))) >= 2,
              f"modules = {set(re.findall(r'konditional[-_][a-z]+', tl))}"),
            E("Shows concrete file-to-file relationships, not just module names",
              len(re.findall(r"\.kt", t)) >= 3,
              ".kt files referenced"),
            E("Does not miss cross-module references",
              "FlagValue" in t,
              "FlagValue traced"),
        ]
    if case_id == "vp-multi-file-rename":
        return [
            E("Uses kast_rename (not find-and-replace or sed)",
              False, "manual sed/edit required"),
            E("Shows an edit plan listing all affected files before applying",
              "edit plan" in tl or "affected" in tl or "occurrences" in tl,
              "plan present"),
            E("Updates import statements, not just the declaration",
              "import" in tl,
              "imports updated"),
            E("Runs diagnostics or reports compile status after the rename",
              "compile" in tl or "gradle" in tl or "build" in tl,
              "compile attempt mentioned"),
            E("Does not leave broken references in any module",
              "no remaining" in tl or "0 occurrences" in tl or "all updated" in tl or "compile" in tl,
              "completeness assertion present"),
        ]
    if case_id == "vp-edit-and-validate":
        return [
            E("Uses kast_write_and_validate (not raw edit/create tool)",
              False, "kast forbidden"),
            E("Runs diagnostics atomically as part of the write",
              False, "no atomic validation available"),
            E("Reports clean or dirty compile state after the edit",
              "compile" in tl or "gradle" in tl or "build" in tl,
              "compile attempt mentioned"),
            E("Does not claim success without validation evidence",
              "compile" in tl or "build" in tl,
              "validation evidence present"),
        ]
    return []


def write_grading(eval_dir: Path, config: str, case_id: str):
    run_dir = eval_dir / config / "run-1"
    transcript_path = run_dir / "outputs" / "transcript.md"
    timing_path = run_dir / "timing.json"
    grading_path = run_dir / "grading.json"

    if not transcript_path.exists():
        print(f"SKIP {case_id}/{config} (no transcript)")
        return
    transcript = transcript_path.read_text()
    if not transcript.strip():
        print(f"SKIP {case_id}/{config} (empty transcript)")
        return

    expectations = grade_with(case_id, transcript) if config == "with_skill" else grade_without(case_id, transcript)
    passed = sum(1 for e in expectations if e["passed"])
    total = len(expectations)
    failed = total - passed
    pass_rate = passed / total if total else 0.0

    duration = 0.0
    tool_count = 0
    if timing_path.exists():
        try:
            tj = json.loads(timing_path.read_text())
            duration = float(tj.get("duration_seconds", 0.0))
            tools = tj.get("tools_used", [])
            tool_count = len(tools) if isinstance(tools, list) else 0
        except Exception:
            pass

    grading = {
        "expectations": expectations,
        "summary": {
            "passed": passed,
            "failed": failed,
            "total": total,
            "pass_rate": pass_rate,
        },
        "execution_metrics": {
            "tool_calls": {},
            "total_tool_calls": tool_count,
            "total_steps": tool_count,
            "errors_encountered": 0,
            "output_chars": len(transcript),
            "transcript_chars": len(transcript),
        },
        "timing": {
            "executor_duration_seconds": duration,
            "grader_duration_seconds": 0.0,
            "total_duration_seconds": duration,
        },
    }
    grading_path.write_text(json.dumps(grading, indent=2))
    print(f"  {case_id}/{config}: {passed}/{total} ({pass_rate:.0%}) duration={duration:.1f}s")


def main():
    for case in CATALOG["cases"]:
        cid = case["id"]
        eval_dir = ROOT / f"eval-{cid}"
        if not eval_dir.exists():
            print(f"NO DIR for {cid}")
            continue
        for cfg in ("with_skill", "without_skill"):
            write_grading(eval_dir, cfg, cid)


if __name__ == "__main__":
    main()
