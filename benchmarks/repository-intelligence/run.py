#!/usr/bin/env python3
"""Run the frozen-corpus Kast repository-intelligence evaluation."""

import argparse
import json
import os
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BENCHMARK = Path(__file__).resolve().parent
MANIFEST = BENCHMARK / "manifest.json"
QUESTIONS = BENCHMARK / "questions.jsonl"
DEFAULT_CORPUS = ROOT.parent / "kast-repository-intelligence-corpus"
DEFAULT_KAST = ROOT / "cli-rs/target/debug/kast"
DEFAULT_OUTPUT = BENCHMARK / "results/latest.json"
VOLATILE_KEYS = {"elapsedMillis", "latencyMillis", "generatedAt", "startedAt", "finishedAt"}


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--assert", dest="assert_all", action="store_true")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--corpus", type=Path, default=Path(os.environ.get("BENCHMARK_CORPUS", DEFAULT_CORPUS)))
    parser.add_argument("--kast", type=Path, default=Path(os.environ.get("KAST_BIN", DEFAULT_KAST)))
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_questions():
    return [
        json.loads(line)
        for line in QUESTIONS.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def git(corpus, *args):
    process = subprocess.run(
        ["git", "-C", str(corpus), *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if process.returncode:
        raise RuntimeError(process.stderr.strip() or process.stdout.strip())
    return process.stdout.strip()


def validate_inputs(manifest, questions, corpus, kast):
    if len(questions) < 40:
        raise RuntimeError(f"question corpus has {len(questions)} entries; at least 40 are required")
    categories = Counter(question["category"] for question in questions)
    required = set(manifest["questionCategories"])
    if set(categories) != required:
        raise RuntimeError(f"question categories differ: expected {sorted(required)}, got {sorted(categories)}")
    ids = [question["id"] for question in questions]
    if len(ids) != len(set(ids)):
        raise RuntimeError("question ids must be unique")
    if not corpus.is_dir():
        raise RuntimeError(f"frozen corpus is missing: {corpus}")
    actual = git(corpus, "rev-parse", "HEAD")
    if actual != manifest["corpus"]["commit"]:
        raise RuntimeError(f"frozen corpus is {actual}, expected {manifest['corpus']['commit']}")
    if git(corpus, "status", "--porcelain", "--untracked-files=no"):
        raise RuntimeError("frozen corpus has tracked changes")
    if not kast.is_file():
        raise RuntimeError(f"branch-built Kast CLI is missing: {kast}")
    return categories


def values_at(value, path):
    values = [value]
    for segment in path.split("."):
        next_values = []
        for current in values:
            if segment == "*" and isinstance(current, list):
                next_values.extend(current)
            elif isinstance(current, dict) and segment in current:
                next_values.append(current[segment])
        values = next_values
    return values


def is_subset(expected, actual):
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(
            key in actual and is_subset(value, actual[key]) for key, value in expected.items()
        )
    if isinstance(expected, list):
        return isinstance(actual, list) and all(
            any(is_subset(item, candidate) for candidate in actual) for item in expected
        )
    return expected == actual


def assertion_passes(response, assertion):
    values = values_at(response, assertion["path"])
    operation = assertion["op"]
    expected = assertion.get("value")
    if operation == "eq":
        return any(value == expected for value in values)
    if operation == "contains":
        return any(isinstance(value, list) and expected in value for value in values)
    if operation == "containsMatch":
        return any(
            isinstance(value, list) and any(is_subset(expected, item) for item in value)
            for value in values
        )
    if operation == "minLength":
        return any(hasattr(value, "__len__") and len(value) >= expected for value in values)
    if operation == "absent":
        return not values
    if operation == "truthy":
        return any(bool(value) for value in values)
    raise RuntimeError(f"unsupported assertion operation: {operation}")


def normalize(value, corpus):
    if isinstance(value, dict):
        return {
            key: normalize(item, corpus)
            for key, item in sorted(value.items())
            if key not in VOLATILE_KEYS
        }
    if isinstance(value, list):
        return [normalize(item, corpus) for item in value]
    if isinstance(value, str):
        return value.replace(str(corpus), "$BENCHMARK_CORPUS")
    return value


def query(kast, corpus, question):
    request = {
        "jsonrpc": "2.0",
        "id": question["id"],
        "method": "repository/query",
        "params": {
            "question": question["question"],
            "intent": question["intent"],
            "scope": question.get("scope", {}),
            "limits": {"depth": 6, "results": 50, "evidence": 5},
        },
    }
    started = time.perf_counter()
    process = subprocess.run(
        [
            str(kast),
            "--output",
            "json",
            "rpc",
            "--workspace-root",
            str(corpus),
            "--request",
            json.dumps(request, separators=(",", ":")),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    elapsed = round((time.perf_counter() - started) * 1000, 3)
    try:
        response = json.loads(process.stdout)
    except json.JSONDecodeError:
        response = {
            "transportFailure": True,
            "stdout": process.stdout.strip(),
            "stderr": process.stderr.strip(),
        }
    failures = [
        assertion
        for assertion in question["assertions"]
        if not assertion_passes(response, assertion)
    ]
    return {
        "id": question["id"],
        "category": question["category"],
        "phase": question["phase"],
        "critical": question.get("critical", False),
        "passed": process.returncode == 0 and not failures,
        "failedAssertions": failures,
        "returnCode": process.returncode,
        "responseBytes": len(process.stdout.encode()),
        "latencyMillis": elapsed,
        "response": response,
    }


def run_once(kast, corpus, questions):
    return [query(kast, corpus, question) for question in questions]


def semantic_projection(results, corpus):
    return [
        {
            "id": result["id"],
            "passed": result["passed"],
            "failedAssertions": result["failedAssertions"],
            "response": normalize(result["response"], corpus),
        }
        for result in results
    ]


def summarize(results, categories, deterministic):
    passed = Counter(result["category"] for result in results if result["passed"])
    critical_failures = [
        result["id"] for result in results if result["critical"] and not result["passed"]
    ]
    return {
        "status": "pass" if all(result["passed"] for result in results) and deterministic else "fail",
        "questions": {"passed": sum(passed.values()), "total": len(results)},
        "categories": {
            category: {"passed": passed[category], "total": count}
            for category, count in sorted(categories.items())
        },
        "criticalFailures": critical_failures,
        "deterministic": deterministic,
    }


def self_test():
    questions = load_questions()
    assert len(questions) >= 40
    sample = {"result": {"status": "ANSWERED", "nodes": [{"name": "parse", "path": "A.kt"}]}}
    assert assertion_passes(sample, {"path": "result.status", "op": "eq", "value": "ANSWERED"})
    assert assertion_passes(
        sample,
        {"path": "result.nodes", "op": "containsMatch", "value": {"name": "parse"}},
    )
    assert not assertion_passes(
        sample,
        {"path": "result.nodes", "op": "containsMatch", "value": {"path": "B.kt"}},
    )
    print(json.dumps({"selfTest": {"ok": True, "questions": len(questions)}}, sort_keys=True))
    return 0


def main(argv=None):
    args = parse_args(argv)
    if args.repeat < 1:
        raise RuntimeError("--repeat must be at least one")
    if args.self_test:
        return self_test()
    manifest = load_json(MANIFEST)
    questions = load_questions()
    categories = validate_inputs(manifest, questions, args.corpus.resolve(), args.kast.resolve())
    runs = [
        run_once(args.kast.resolve(), args.corpus.resolve(), questions)
        for _ in range(args.repeat)
    ]
    projections = [semantic_projection(run, args.corpus.resolve()) for run in runs]
    deterministic = all(projection == projections[0] for projection in projections[1:])
    summary = summarize(runs[-1], categories, deterministic)
    output = {
        "schemaVersion": 1,
        "corpusCommit": manifest["corpus"]["commit"],
        "implementationCommit": git(ROOT, "rev-parse", "HEAD"),
        "kastBinary": str(args.kast.resolve()),
        "summary": summary,
        "results": runs[-1],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"benchmark": summary, "output": str(args.output)}, sort_keys=True))
    return 1 if args.assert_all and summary["status"] != "pass" else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, KeyError) as error:
        print(json.dumps({"error": {"code": "BENCHMARK_FAILED", "message": str(error)}}))
        raise SystemExit(1)
