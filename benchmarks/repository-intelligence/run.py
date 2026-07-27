#!/usr/bin/env python3
"""Run the frozen-corpus Kast repository-intelligence evaluation."""

import argparse
import json
import os
import subprocess
import tempfile
import time
from collections import Counter
from pathlib import Path

import provenance


ROOT = Path(__file__).resolve().parents[2]
BENCHMARK = Path(__file__).resolve().parent
MANIFEST = BENCHMARK / "spec/manifest.json"
QUESTIONS = BENCHMARK / "spec/questions.jsonl"
DEFAULT_CORPUS = ROOT.parent / "kast-repository-intelligence-corpus"
DEFAULT_OUTPUT = BENCHMARK / "results/latest.json"
VOLATILE_KEYS = {"elapsedMillis", "latencyMillis", "generatedAt", "startedAt", "finishedAt"}
RUBRIC = BENCHMARK / "spec/rubric.md"


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--assert", dest="assert_all", action="store_true")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--corpus", type=Path, default=Path(os.environ.get("BENCHMARK_CORPUS", DEFAULT_CORPUS)))
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


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


def validate_inputs(manifest, questions, corpus):
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
    if git(corpus, "status", "--porcelain"):
        raise RuntimeError("frozen corpus has tracked or untracked changes")
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
        if not isinstance(actual, list):
            return False
        candidates = iter(actual)
        return all(
            any(is_subset(item, candidate) for candidate in candidates)
            for item in expected
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


def query(kast, corpus, question, environment, manifest):
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
    guards_live_context = question["intent"] == "context_relationship"
    if guards_live_context:
        provenance.validate_corpus_inputs(manifest, corpus)
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
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    elapsed = round((time.perf_counter() - started) * 1000, 3)
    if guards_live_context:
        provenance.validate_corpus_inputs(manifest, corpus)
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


def run_once(kast, corpus, questions, environment, manifest):
    return [
        query(kast, corpus, question, environment, manifest)
        for question in questions
    ]


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


def source_references(value):
    references = set()

    def visit(current):
        if isinstance(current, dict):
            path = current.get("sourcePath", current.get("path"))
            if isinstance(path, str) and "/" in path:
                location = current.get("sourceLocation", current.get("declarationRange", {}))
                line = location.get("line") if isinstance(location, dict) else current.get("line")
                references.add((path, line if isinstance(line, int) else None))
            for nested in current.values():
                visit(nested)
        elif isinstance(current, list):
            for nested in current:
                visit(nested)

    visit(value)
    return sorted(references, key=lambda reference: (reference[0], reference[1] or 0))


def inline(value):
    return str(value).replace("`", "'").replace("\n", " ")


def render_markdown_report(output):
    lines = [
        "# Kast Repository Intelligence Report",
        "",
        f"- Corpus commit: `{output['corpusCommit']}`",
        f"- Implementation commit: `{output['implementationCommit']}`",
        f"- Benchmark status: `{output['summary']['status'].upper()}`",
        f"- Questions: {output['summary']['questions']['passed']}/{output['summary']['questions']['total']}",
        "",
    ]
    for category, title in [
        ("architecture", "Architecture"),
        ("context", "Repository context"),
    ]:
        lines.extend([f"## {title}", ""])
        for record in (item for item in output["results"] if item["category"] == category):
            result = record.get("response", {}).get("result", {})
            lines.extend(
                [
                    f"### {inline(record['id'])}",
                    "",
                    f"- Status: `{inline(result.get('status', 'TRANSPORT_FAILURE'))}`",
                    f"- Question: {inline(result.get('question', 'unavailable'))}",
                    f"- Graph generation: `{inline(result.get('graphGeneration', 'unavailable'))}`",
                ]
            )
            for finding in result.get("findings", []):
                lines.append(
                    f"- Finding: `{inline(finding.get('name', finding.get('type', 'unknown')))}`"
                    f" — {inline(finding.get('summary', ''))}"
                )
            for relation in result.get("contextRelations", []):
                location = relation.get("sourceLocation", {})
                line = f":{location['line']}" if "line" in location else ""
                lines.append(
                    f"- Relation: `{inline(relation.get('sourcePath', 'unknown'))}{line}` "
                    f"{inline(relation.get('kind', 'RELATED'))} "
                    f"`{inline(relation.get('targetName', 'unknown'))}` "
                    f"({inline(relation.get('evidenceClass', 'unknown'))})"
                )
            lines.extend(["", "Source references:", ""])
            references = source_references(result)
            lines.extend(
                f"- `{inline(path)}{f':{line}' if line is not None else ''}`"
                for path, line in references[:50]
            )
            if len(references) > 50:
                lines.append(
                    f"- {len(references) - 50} additional references omitted by the presentation bound"
                )
            descriptor = {
                "bounds": result.get("bounds"),
                "graphGeneration": result.get("graphGeneration"),
                "intent": result.get("intent"),
                "ordering": result.get("ordering"),
                "queryPlan": result.get("queryPlan"),
                "question": result.get("question"),
                "scope": result.get("scope"),
            }
            lines.extend(["", "Reproducible query descriptor:", ""])
            lines.extend(
                f"    {line}" for line in json.dumps(descriptor, indent=2, sort_keys=True).splitlines()
            )
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


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
    report = render_markdown_report(
        {
            "corpusCommit": "corpus",
            "implementationCommit": "implementation",
            "summary": {"status": "pass", "questions": {"passed": 1, "total": 1}},
            "results": [
                {
                    "id": "context",
                    "category": "context",
                    "response": {
                        "result": {
                            "status": "ANSWERED",
                            "question": "Which document?",
                            "graphGeneration": 1,
                            "contextRelations": [
                                {
                                    "sourcePath": "docs/example.md",
                                    "sourceLocation": {"line": 3},
                                    "kind": "DOCUMENTS",
                                    "targetName": "Example",
                                    "evidenceClass": "extracted",
                                }
                            ],
                        }
                    },
                }
            ],
        }
    )
    assert "`docs/example.md:3` DOCUMENTS `Example`" in report
    assert "Reproducible query descriptor:" in report
    print(json.dumps({"selfTest": {"ok": True, "questions": len(questions)}}, sort_keys=True))
    return 0


def main(argv=None):
    args = parse_args(argv)
    if args.repeat < 1:
        raise RuntimeError("--repeat must be at least one")
    if args.self_test:
        return self_test()
    snapshot = provenance.load_benchmark_snapshot(MANIFEST, QUESTIONS, RUBRIC)
    manifest = snapshot.manifest
    questions = list(snapshot.questions)
    corpus = args.corpus.resolve()
    categories = validate_inputs(manifest, questions, corpus)
    corpus_inputs = provenance.validate_corpus_inputs(manifest, corpus)
    authority = provenance.kast_capture_authority(manifest)
    environment = provenance.process_environment(
        authority["processEnvironment"]
    )
    with tempfile.TemporaryDirectory(prefix="kast-benchmark-build-") as directory:
        receipt = provenance.build_kast_release(
            ROOT,
            Path(directory) / "target",
            authority,
        )
        source_index = provenance.validate_source_index_identity(
            authority,
            provenance.source_index_identity(
                receipt.binary_path,
                corpus,
                environment,
            ),
        )
        runs = [
            run_once(
                receipt.binary_path,
                corpus,
                questions,
                environment,
                manifest,
            )
            for _ in range(args.repeat)
        ]
        projections = [semantic_projection(run, corpus) for run in runs]
        deterministic = all(
            projection == projections[0] for projection in projections[1:]
        )
        summary = summarize(runs[-1], categories, deterministic)
        validate_inputs(manifest, questions, corpus)
        final_corpus_inputs = provenance.validate_corpus_inputs(manifest, corpus)
        if final_corpus_inputs != corpus_inputs:
            raise provenance.ProvenanceError(
                "KAST_CORPUS_INPUT_CHANGED",
                "Live repository-context inputs changed during benchmark capture.",
            )
        final_source_index = provenance.validate_source_index_identity(
            authority,
            provenance.source_index_identity(
                receipt.binary_path,
                corpus,
                environment,
            ),
        )
        if final_source_index != source_index:
            raise provenance.ProvenanceError(
                "KAST_SOURCE_INDEX_CHANGED",
                "Kast source-index content changed during benchmark capture.",
                details={
                    "expected": source_index,
                    "actual": final_source_index,
                },
            )
        execution = provenance.validate_kast_execution(
            {"results": runs[-1]},
            corpus,
        )
        provenance.verify_kast_build_receipt(ROOT, receipt)
        output = {
            "schemaVersion": provenance.CAPTURE_SCHEMA_VERSION,
            "corpusCommit": snapshot.identity.corpus_commit,
            "implementationCommit": receipt.source_commit,
            "kastBinary": str(receipt.binary_path),
            **source_index,
            "corpusInputs": corpus_inputs,
            **execution,
            "provenance": provenance.capture_provenance(
                snapshot.identity,
                receipt.artifact(
                    execution,
                    source_index,
                    corpus_inputs,
                    authority["processEnvironment"],
                ),
            ),
            "summary": summary,
            "results": runs[-1],
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(output, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        markdown_output = args.output.with_suffix(".md")
        markdown_output.write_text(render_markdown_report(output), encoding="utf-8")
    print(
        json.dumps(
            {
                "benchmark": summary,
                "markdownOutput": str(markdown_output),
                "output": str(args.output),
            },
            sort_keys=True,
        )
    )
    return 1 if args.assert_all and summary["status"] != "pass" else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except provenance.ProvenanceError as error:
        print(json.dumps(error.document(), sort_keys=True))
        raise SystemExit(1)
    except (AttributeError, OSError, RuntimeError, TypeError, ValueError, KeyError) as error:
        print(json.dumps({"error": {"code": "BENCHMARK_FAILED", "message": str(error)}}))
        raise SystemExit(1)
