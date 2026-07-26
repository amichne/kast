#!/usr/bin/env python3
"""Capture Graphify answers and Kast's pre-change exact path on the frozen corpus."""

import argparse
import json
import sqlite3
import subprocess
import sys
import time
from pathlib import Path


BENCHMARK = Path(__file__).resolve().parent
MANIFEST = BENCHMARK / "manifest.json"
QUESTIONS = BENCHMARK / "questions.jsonl"
DEFAULT_GRAPH = (
    BENCHMARK.parent.parent.parent
    / "kast-repository-intelligence-graphify-corpus/graphify-out/graph.json"
)
DEFAULT_OUTPUT = BENCHMARK / "baselines/graphify-initial.json"
EXPECTED_PATH = [
    "semanticGraphOperation",
    "buildSemanticGraphSnapshot",
    "semanticGraphScopeFingerprint",
    "sha256",
    "parse",
]


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--graph", type=Path, default=DEFAULT_GRAPH)
    parser.add_argument("--database", type=Path)
    parser.add_argument("--graphify", default="graphify")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--assert-kast-regression", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args(argv)


def load_questions():
    return [
        json.loads(line)
        for line in QUESTIONS.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def exact_calls_path(database):
    query = """
        SELECT source.name, target.name, edge.kind, file.path,
               edge.start_offset, edge.end_offset, edge.line
        FROM semantic_edge_occurrences edge
        JOIN semantic_symbols source ON source.id = edge.source_id
        JOIN semantic_symbols target ON target.id = edge.target_id
        JOIN semantic_files file ON file.id = edge.source_file_id
        WHERE source.name = ? AND target.name = ? AND edge.kind = 'CALLS'
        ORDER BY file.path, edge.start_offset
    """
    edges = []
    with sqlite3.connect(database) as connection:
        for source, target in zip(EXPECTED_PATH, EXPECTED_PATH[1:]):
            rows = connection.execute(query, (source, target)).fetchall()
            if len(rows) != 1:
                raise RuntimeError(
                    f"expected one CALLS occurrence for {source} -> {target}, got {len(rows)}"
                )
            row = rows[0]
            edges.append(
                {
                    "source": row[0],
                    "target": row[1],
                    "kind": row[2],
                    "occurrence": {
                        "path": row[3],
                        "startOffset": row[4],
                        "endOffset": row[5],
                        "line": row[6],
                    },
                }
            )
        files = connection.execute("SELECT COUNT(*) FROM semantic_files").fetchone()[0]
        symbols = connection.execute("SELECT COUNT(*) FROM semantic_symbols").fetchone()[0]
        occurrences = connection.execute(
            "SELECT COUNT(*) FROM semantic_edge_occurrences"
        ).fetchone()[0]
    return {
        "status": "pass",
        "path": EXPECTED_PATH,
        "edges": edges,
        "semanticFiles": files,
        "semanticSymbols": symbols,
        "edgeOccurrences": occurrences,
    }


def graphify_query(graphify, graph, question):
    command = [
        graphify,
        "query",
        question["question"],
        "--budget",
        "1200",
        "--graph",
        str(graph),
    ]
    if question["intent"] == "path":
        command.insert(3, "--dfs")
    started = time.perf_counter()
    process = subprocess.run(command, capture_output=True, text=True, check=False)
    return {
        "id": question["id"],
        "category": question["category"],
        "returnCode": process.returncode,
        "answerable": process.returncode == 0 and "NODE " in process.stdout,
        "latencyMillis": round((time.perf_counter() - started) * 1000, 3),
        "responseBytes": len(process.stdout.encode()),
        "stdout": process.stdout,
        "stderr": process.stderr,
    }


def self_test():
    assert list(zip(EXPECTED_PATH, EXPECTED_PATH[1:]))[-1] == ("sha256", "parse")
    assert len(load_questions()) >= 40
    print(json.dumps({"selfTest": {"ok": True, "questions": len(load_questions())}}))
    return 0


def main(argv=None):
    args = parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.graph.is_file():
        raise RuntimeError(f"Graphify graph is missing: {args.graph}")
    if args.assert_kast_regression and (not args.database or not args.database.is_file()):
        raise RuntimeError("--database must name the frozen corpus source-index.db")

    graph = json.loads(args.graph.read_text(encoding="utf-8"))
    questions = load_questions()
    answers = [graphify_query(args.graphify, args.graph, question) for question in questions]
    exact_regression = exact_calls_path(args.database) if args.database else None
    categories = sorted({question["category"] for question in questions})
    output = {
        "schemaVersion": 1,
        "corpusCommit": json.loads(MANIFEST.read_text())["corpus"]["commit"],
        "graphify": {
            "version": subprocess.run(
                [args.graphify, "--version"], capture_output=True, text=True, check=False
            ).stdout.splitlines()[0],
            "directed": graph.get("directed", False),
            "nodes": len(graph.get("nodes", [])),
            "edges": len(graph.get("links", [])),
            "questions": {
                "answerable": sum(answer["answerable"] for answer in answers),
                "total": len(answers),
                "byCategory": {
                    category: {
                        "answerable": sum(
                            answer["answerable"]
                            for answer in answers
                            if answer["category"] == category
                        ),
                        "total": sum(
                            answer["category"] == category for answer in answers
                        ),
                    }
                    for category in categories
                },
            },
        },
        "kastExactCallsRegression": exact_regression,
        "results": answers,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n")
    print(
        json.dumps(
            {
                "graphify": output["graphify"],
                "kastExactCallsRegression": exact_regression and exact_regression["status"],
                "output": str(args.output),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, sqlite3.Error, ValueError, KeyError) as error:
        print(json.dumps({"error": {"code": "BASELINE_FAILED", "message": str(error)}}))
        raise SystemExit(1)
