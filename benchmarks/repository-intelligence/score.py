#!/usr/bin/env python3
"""Derive the frozen Kast-versus-Graphify comparison from captured evidence."""

import argparse
import hashlib
import json
import math
import re
import statistics
from collections import defaultdict
from pathlib import Path

import provenance
import run as benchmark_run


BENCHMARK = Path(__file__).resolve().parent
MANIFEST = BENCHMARK / "manifest.json"
QUESTIONS = BENCHMARK / "questions.jsonl"
RUBRIC = BENCHMARK / "rubric.md"
KAST_RESULTS = BENCHMARK / "results/final.json"
GRAPHIFY_RESULTS = BENCHMARK / "results/graphify-final.json"
DEFAULT_OUTPUT = BENCHMARK / "results/comparison.json"
DIMENSIONS = (
    "answerCorrectness",
    "identityPrecision",
    "relationFidelity",
    "evidenceAndProvenance",
    "scopeAndUncertainty",
    "discoveryAnswerability",
    "architecturalUsefulness",
)
NEGATIVE_DISPOSITIONS = {"AMBIGUOUS", "EMPTY", "QUALIFIED_EMPTY"}
EXACT_KOTLIN_CATEGORIES = {"exact_identity", "directional_path", "impact"}
NODE_PATTERN = re.compile(r"^NODE (?P<label>.+?) \[src=(?P<source>.*?) loc=(?P<location>[^ ]+)")
REASON_CATALOG = {
    "ARCHITECTURE_NOT_EVIDENCED": "Architectural usefulness is not requested or not evidenced.",
    "ARCHITECTURE_TYPED": "The answer provides a typed architectural finding, path, impact, or context relation.",
    "DISCOVERY_EXACT": "Bounded discovery terminates in exact identity or an explicit typed disposition.",
    "DISCOVERY_UNPROVEN": "The question does not prove exact discovery termination.",
    "EXACT_IDENTITY_OR_DISPOSITION": "The answer preserves exact canonical identities or an explicit non-selection disposition.",
    "EXACT_IDENTITY_UNPROVEN": "The captured answer does not prove exact identity handling.",
    "GRAPHIFY_ARCHITECTURE_PARTIAL": "Relevant graph material is present, but no named metric with evidence subgraph is returned.",
    "GRAPHIFY_ARCHITECTURE_UNPROVEN": "No evidenced architectural finding is returned.",
    "GRAPHIFY_DISCOVERY_PARTIAL": "Relevant material is discoverable, but discovery does not terminate in exact identity.",
    "GRAPHIFY_DISCOVERY_UNPROVEN": "The output does not prove bounded discovery to an exact identity or typed disposition.",
    "GRAPHIFY_EVIDENCE_MISSING": "No relevant source-located evidence satisfies the question.",
    "GRAPHIFY_EXPECTATION_MISSING": "The output does not contain the expected relevant material under the hard assertions.",
    "GRAPHIFY_IDENTITY_PARTIAL": "A name-and-source pair is visible, but no overload-safe canonical identity is returned.",
    "GRAPHIFY_IDENTITY_UNPROVEN": "No exact name-and-source identity pair is established.",
    "GRAPHIFY_LOCATED_MATERIAL": "Relevant node material has a source location, but relation evidence is not retrievable.",
    "GRAPHIFY_NEGATIVE_UNSUPPORTED": "The output has no explicit empty or ambiguity disposition; arbitrary nodes cannot answer a negative.",
    "GRAPHIFY_RELEVANT_UNTYPED": "Relevant expected material is present, but the untyped output cannot satisfy every hard assertion.",
    "GRAPHIFY_SCOPE_MISSING": "Generation, scope, coverage, bounds, and truncation are absent.",
    "GRAPHIFY_SCOPE_PARTIAL": "Traversal depth is visible, but generation, coverage, semantic scope, and completeness are absent.",
    "GRAPHIFY_UNDIRECTED": "The captured Graphify graph is undirected and the output exposes no typed relation path or occurrence.",
    "HARD_ASSERTIONS_FAIL": "At least one frozen hard assertion fails.",
    "HARD_ASSERTIONS_PASS": "Every frozen hard assertion passes.",
    "SCOPE_COMPLETE": "Generation, scope, coverage, bounds, and truncation are visible.",
    "SCOPE_PARTIAL": "Only part of generation, scope, coverage, bounds, and truncation is visible.",
    "SEMANTIC_EVIDENCE_PARTIAL": "The captured answer does not carry complete source or derivation evidence.",
    "SEMANTIC_EVIDENCE_PROVEN": "The answer carries source or derivation evidence for its semantic claim.",
    "TYPED_RELATION_PARTIAL": "The captured answer lacks part of the requested typed relation contract.",
    "TYPED_RELATION_PROVEN": "All requested relations are represented by typed direction, kind, or projection facts.",
}


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def identity_sha256(value):
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def dimension(score, reason, *evidence):
    return {"score": score, "reason": reason, "evidence": list(evidence)}


def walk(value):
    yield value
    if isinstance(value, dict):
        for nested in value.values():
            yield from walk(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from walk(nested)


def has_field(value, field, predicate=lambda item: bool(item)):
    return any(
        isinstance(item, dict) and field in item and predicate(item[field])
        for item in walk(value)
    )


def expected_status(question):
    return next(
        (
            assertion["value"]
            for assertion in question["assertions"]
            if assertion["path"] == "result.status" and assertion["op"] == "eq"
        ),
        None,
    )


def relation_expected(question):
    if question["intent"] in {"path", "incoming_impact", "outgoing_impact", "architecture", "context_relationship"}:
        return True
    return any(
        isinstance(value, dict)
        and any(key in value for key in ("kind", "direction", "relationKinds"))
        for assertion in question["assertions"]
        for value in walk(assertion.get("value"))
    )


def kast_assertions_pass(question, record):
    response = record.get("response", {})
    return record.get("returnCode") == 0 and all(
        benchmark_run.assertion_passes(response, assertion)
        for assertion in question["assertions"]
    )


def score_kast(question, record):
    result = record.get("response", {}).get("result", {})
    result_pointer = "kastResult"
    assertion_pointer = "assertions"
    hard_pass = kast_assertions_pass(question, record)
    actual_status = result.get("status")
    disposition = expected_status(question)
    correctness = (
        dimension(2, "HARD_ASSERTIONS_PASS", assertion_pointer, result_pointer)
        if hard_pass
        else dimension(
            (
                1
                if not question.get("critical")
                and record.get("returnCode") == 0
                and actual_status == disposition
                else 0
            ),
            "HARD_ASSERTIONS_FAIL",
            assertion_pointer,
            result_pointer,
        )
    )

    exact_identity = has_field(result, "canonicalKey") or (
        actual_status in NEGATIVE_DISPOSITIONS and hard_pass
    )
    identity = dimension(
        2 if hard_pass and exact_identity else 1 if hard_pass else 0,
        (
            "EXACT_IDENTITY_OR_DISPOSITION"
            if hard_pass and exact_identity
            else "EXACT_IDENTITY_UNPROVEN"
        ),
        result_pointer,
    )

    typed_relation = any(
        isinstance(item, dict)
        and (
            ("kind" in item and "direction" in item)
            or ("relationKinds" in item and "direction" in item)
            or ("projection" in item and ("metric" in item or "type" in item))
        )
        for item in walk(result)
    )
    relation = dimension(
        2 if hard_pass and (not relation_expected(question) or typed_relation) else 1 if hard_pass else 0,
        (
            "TYPED_RELATION_PROVEN"
            if hard_pass and (not relation_expected(question) or typed_relation)
            else "TYPED_RELATION_PARTIAL"
        ),
        result_pointer,
    )

    proven_evidence = (
        has_field(result, "evidenceClass")
        and (
            not relation_expected(question)
            or has_field(result, "occurrences")
            or has_field(result, "derivation")
            or has_field(result, "supportingSubgraph")
            or has_field(result, "sourceLocation")
        )
    )
    evidence = dimension(
        2 if hard_pass and proven_evidence else 1 if hard_pass and has_field(result, "evidenceClass") else 0,
        (
            "SEMANTIC_EVIDENCE_PROVEN"
            if proven_evidence
            else "SEMANTIC_EVIDENCE_PARTIAL"
        ),
        result_pointer,
    )

    scope_fields = ("graphGeneration", "scope", "coverage", "bounds", "truncated")
    visible_scope = sum(field in result for field in scope_fields)
    scope = dimension(
        2 if visible_scope == len(scope_fields) else 1 if visible_scope else 0,
        "SCOPE_COMPLETE" if visible_scope == len(scope_fields) else "SCOPE_PARTIAL",
        result_pointer,
    )

    discovery_applicable = question["intent"] in {"resolve", "architecture", "context_relationship"}
    discovery = dimension(
        2 if discovery_applicable and hard_pass and exact_identity else 1 if discovery_applicable and hard_pass else 0,
        (
            "DISCOVERY_EXACT"
            if discovery_applicable and hard_pass and exact_identity
            else "DISCOVERY_UNPROVEN"
        ),
        result_pointer,
    )

    architectural_evidence = (
        question["category"] == "architecture" and has_field(result, "findings")
    ) or (
        question["category"] in {"directional_path", "impact"}
        and (has_field(result, "paths") or has_field(result, "edges"))
    ) or (
        question["category"] == "context" and has_field(result, "contextRelations")
    )
    architecture = dimension(
        2 if hard_pass and architectural_evidence else 0,
        (
            "ARCHITECTURE_TYPED"
            if hard_pass and architectural_evidence
            else "ARCHITECTURE_NOT_EVIDENCED"
        ),
        result_pointer,
    )
    dimensions = dict(
        zip(
            DIMENSIONS,
            (correctness, identity, relation, evidence, scope, discovery, architecture),
        )
    )
    return {
        "dimensions": dimensions,
        "points": sum(item["score"] for item in dimensions.values()),
        "criticalFailure": bool(question.get("critical") and correctness["score"] < 2),
    }


def graphify_nodes(stdout):
    nodes = []
    for line_number, line in enumerate(stdout.splitlines(), start=1):
        match = NODE_PATTERN.match(line)
        if match:
            nodes.append(
                {
                    "label": match.group("label"),
                    "source": match.group("source"),
                    "location": match.group("location"),
                    "line": line_number,
                }
            )
    return nodes


def normalized(value):
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def expected_entities(question):
    names = set()
    paths = set()
    exact_pairs = set()
    ordered_paths = []
    for assertion in question["assertions"]:
        value = assertion.get("value")
        for item in walk(value):
            if not isinstance(item, dict):
                continue
            name = next(
                (
                    item[key]
                    for key in ("name", "targetName", "sourceName")
                    if isinstance(item.get(key), str)
                ),
                None,
            )
            path = next(
                (
                    item[key]
                    for key in ("path", "sourcePath")
                    if isinstance(item.get(key), str)
                ),
                None,
            )
            if name:
                names.add(name)
            if path:
                paths.add(path)
            if name and path:
                exact_pairs.add((name, path))
            path_nodes = item.get("nodes")
            if isinstance(path_nodes, list):
                sequence = [
                    node["name"]
                    for node in path_nodes
                    if isinstance(node, dict) and isinstance(node.get("name"), str)
                ]
                if sequence:
                    ordered_paths.append(sequence)
    return names, paths, exact_pairs, ordered_paths


def ordered_subsequence(expected, actual):
    candidates = iter(actual)
    return all(any(normalized(item) in normalized(candidate) for candidate in candidates) for item in expected)


def score_graphify(question, record):
    stdout = record.get("stdout", "")
    nodes = graphify_nodes(stdout)
    names, paths, exact_pairs, ordered_paths = expected_entities(question)
    matching_names = {
        name
        for name in names
        if any(normalized(name) in normalized(node["label"]) for node in nodes)
    }
    matching_paths = {path for path in paths if any(node["source"] == path for node in nodes)}
    matching_pairs = {
        pair
        for pair in exact_pairs
        if any(
            normalized(pair[0]) in normalized(node["label"]) and node["source"] == pair[1]
            for node in nodes
        )
    }
    ordered_path = bool(ordered_paths) and any(
        ordered_subsequence(sequence, [node["label"] for node in nodes])
        for sequence in ordered_paths
    )
    relevant = bool(matching_names or matching_paths)
    disposition = expected_status(question)
    output_pointer = "graphifyOutput"
    assertion_pointer = "assertions"
    positive = disposition == "ANSWERED"
    partial_answer = positive and (
        ordered_path if question["intent"] == "path" and ordered_paths else relevant
    )
    correctness = dimension(
        1 if partial_answer else 0,
        (
            "GRAPHIFY_RELEVANT_UNTYPED"
            if partial_answer
            else (
                "GRAPHIFY_NEGATIVE_UNSUPPORTED"
                if disposition in NEGATIVE_DISPOSITIONS
                else "GRAPHIFY_EXPECTATION_MISSING"
            )
        ),
        assertion_pointer,
        output_pointer,
    )
    identity = dimension(
        1 if positive and matching_pairs else 0,
        (
            "GRAPHIFY_IDENTITY_PARTIAL"
            if positive and matching_pairs
            else "GRAPHIFY_IDENTITY_UNPROVEN"
        ),
        output_pointer,
    )
    relation = dimension(
        0,
        "GRAPHIFY_UNDIRECTED",
        "graphifyMetadata",
        output_pointer,
    )
    located_material = any(node["source"] and node["location"] for node in nodes) and relevant
    evidence = dimension(
        1 if located_material else 0,
        (
            "GRAPHIFY_LOCATED_MATERIAL"
            if located_material
            else "GRAPHIFY_EVIDENCE_MISSING"
        ),
        output_pointer,
    )
    partial_scope = "Traversal:" in stdout and "depth=" in stdout
    scope = dimension(
        1 if partial_scope else 0,
        (
            "GRAPHIFY_SCOPE_PARTIAL"
            if partial_scope
            else "GRAPHIFY_SCOPE_MISSING"
        ),
        output_pointer,
    )
    discovery_applicable = question["intent"] in {"resolve", "architecture", "context_relationship"}
    discovery = dimension(
        1 if discovery_applicable and positive and relevant else 0,
        (
            "GRAPHIFY_DISCOVERY_PARTIAL"
            if discovery_applicable and positive and relevant
            else "GRAPHIFY_DISCOVERY_UNPROVEN"
        ),
        output_pointer,
    )
    architecture = dimension(
        1 if question["category"] == "architecture" and partial_answer else 0,
        (
            "GRAPHIFY_ARCHITECTURE_PARTIAL"
            if question["category"] == "architecture" and partial_answer
            else "GRAPHIFY_ARCHITECTURE_UNPROVEN"
        ),
        output_pointer,
    )
    dimensions = dict(
        zip(
            DIMENSIONS,
            (correctness, identity, relation, evidence, scope, discovery, architecture),
        )
    )
    return {
        "dimensions": dimensions,
        "points": sum(item["score"] for item in dimensions.values()),
        "criticalFailure": bool(question.get("critical") and correctness["score"] < 2),
    }


def aggregate(scored_questions, system):
    categories = defaultdict(lambda: {"questions": 0, "points": 0, "maximumPoints": 0})
    for question in scored_questions:
        category = categories[question["category"]]
        category["questions"] += 1
        category["points"] += question["systems"][system]["points"]
        category["maximumPoints"] += len(DIMENSIONS) * 2
    return dict(sorted(categories.items()))


def valid_latency(value):
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
        and value >= 0
    )


def performance_system(system, expected_ids, document):
    expected_count = len(expected_ids)
    expected_set = set(expected_ids)
    records = document.get("results", [])
    if not isinstance(records, list):
        records = []
    records = [record for record in records if isinstance(record, dict)]
    if system == "kast":
        reported = (
            document.get("summary", {})
            .get("questions", {})
            .get("total")
        )
        completed = sum(
            record.get("returnCode") == 0 and record.get("passed") is True
            for record in records
        )
    else:
        reported = (
            document.get("graphify", {})
            .get("questions", {})
            .get("total")
        )
        completed = sum(
            record.get("returnCode") == 0 and record.get("answerable") is True
            for record in records
        )
    measured = [
        record
        for record in records
        if valid_latency(record.get("latencyMillis"))
        and isinstance(record.get("responseBytes"), int)
        and not isinstance(record.get("responseBytes"), bool)
        and record["responseBytes"] >= 0
    ]
    prefix = system.upper()
    reasons = []
    if reported != expected_count:
        reasons.append(f"{prefix}_DECLARED_WORKLOAD_MISMATCH")
    record_ids = [record.get("id") for record in records]
    if (
        len(records) != expected_count
        or len(record_ids) != len(set(record_ids))
        or set(record_ids) != expected_set
    ):
        reasons.append(f"{prefix}_CAPTURED_WORKLOAD_MISMATCH")
    if completed != expected_count:
        reasons.append(f"{prefix}_WORKLOAD_INCOMPLETE")
    if len(measured) != expected_count:
        reasons.append(f"{prefix}_MEASUREMENTS_INCOMPLETE")

    latencies = [record["latencyMillis"] for record in measured]
    p95_index = max(0, (95 * len(latencies) + 99) // 100 - 1)
    return {
        "reportedQuestions": reported,
        "capturedQuestions": len(records),
        "completedQuestions": completed,
        "measuredQuestions": len(measured),
        "totalLatencyMillis": round(sum(latencies), 3) if latencies else None,
        "medianLatencyMillis": (
            round(statistics.median(latencies), 3) if latencies else None
        ),
        "p95LatencyMillis": (
            round(sorted(latencies)[p95_index], 3) if latencies else None
        ),
        "totalResponseBytes": (
            sum(record["responseBytes"] for record in measured)
            if measured
            else None
        ),
    }, reasons


def performance_comparison(
    admission,
    kast_document,
    graphify_document,
):
    expected_ids = list(admission.benchmark.question_ids)
    reasons = (
        []
        if isinstance(admission, provenance.AdmittedProvenance)
        else [failure.code.value for failure in admission.failures]
    )
    if provenance.document_sha256(kast_document) != admission.kast_document_sha256:
        reasons.append(
            provenance.ProvenanceFailureCode.KAST_CAPTURE_DOCUMENT_MISMATCH.value
        )
    if (
        provenance.document_sha256(graphify_document)
        != admission.graphify_document_sha256
    ):
        reasons.append(
            provenance.ProvenanceFailureCode.GRAPHIFY_CAPTURE_DOCUMENT_MISMATCH.value
        )
    if not expected_ids:
        reasons.append("DECLARED_WORKLOAD_EMPTY")
    if len(expected_ids) != len(set(expected_ids)):
        reasons.append("DECLARED_WORKLOAD_NOT_UNIQUE")
    systems = {}
    for system, document in (
        ("kast", kast_document),
        ("graphify", graphify_document),
    ):
        systems[system], system_reasons = performance_system(
            system,
            expected_ids,
            document,
        )
        reasons.extend(system_reasons)

    reasons = list(dict.fromkeys(reasons))
    winner = None
    if not reasons:
        kast_latency = systems["kast"]["totalLatencyMillis"]
        graphify_latency = systems["graphify"]["totalLatencyMillis"]
        winner = (
            "kast"
            if kast_latency < graphify_latency
            else "graphify" if graphify_latency < kast_latency else "tie"
        )
    return {
        "eligible": not reasons,
        "reasons": reasons,
        "declaredQuestions": len(expected_ids),
        "winnerByTotalLatency": winner,
        "systems": systems,
    }


def build_comparison():
    snapshot = provenance.load_benchmark_snapshot(MANIFEST, QUESTIONS, RUBRIC)
    questions = list(snapshot.questions)
    manifest = snapshot.manifest
    kast_document = load_json(KAST_RESULTS)
    graphify_document = load_json(GRAPHIFY_RESULTS)
    admission = provenance.admit_captures(
        snapshot.identity,
        manifest,
        kast_document,
        graphify_document,
        provenance.source_tree_sha256(BENCHMARK.parents[1]),
        provenance.current_source_index_sha256(kast_document, manifest),
    )
    kast_results = {record["id"]: record for record in kast_document["results"]}
    graphify_results = {record["id"]: record for record in graphify_document["results"]}
    question_ids = {question["id"] for question in questions}
    if set(kast_results) != question_ids or set(graphify_results) != question_ids:
        raise RuntimeError("frozen result ids do not exactly match the frozen question ids")

    scored_questions = [
        {
            "id": question["id"],
            "category": question["category"],
            "critical": question.get("critical", False),
            "expectedDisposition": expected_status(question),
            "evidenceSources": {
                "assertions": f"questions[id={question['id']}].assertions",
                "kastResult": f"results/final.json#results[id={question['id']}].response.result",
                "graphifyOutput": f"results/graphify-final.json#results[id={question['id']}].stdout",
                "graphifyMetadata": "results/graphify-final.json#graphify",
            },
            "systems": {
                "kast": score_kast(question, kast_results[question["id"]]),
                "graphify": score_graphify(question, graphify_results[question["id"]]),
            },
        }
        for question in questions
    ]
    category_scores = {
        system: aggregate(scored_questions, system)
        for system in ("kast", "graphify")
    }
    overall = {
        system: sum(question["systems"][system]["points"] for question in scored_questions)
        for system in ("kast", "graphify")
    }
    exact_kotlin = {
        system: sum(
            question["systems"][system]["points"]
            for question in scored_questions
            if question["category"] in EXACT_KOTLIN_CATEGORIES
        )
        for system in ("kast", "graphify")
    }
    critical_failures = [
        question["id"]
        for question in scored_questions
        if question["systems"]["kast"]["criticalFailure"]
    ]
    corpus_commit = snapshot.identity.corpus_commit
    binary_digest = (
        admission.kast.binary_sha256
        if isinstance(admission, provenance.AdmittedProvenance)
        else None
    )
    all_questions_scored = all(
        set(question["systems"][system]["dimensions"]) == set(DIMENSIONS)
        for question in scored_questions
        for system in ("kast", "graphify")
    )
    score_advantage = overall["kast"] > overall["graphify"]
    exact_advantage = exact_kotlin["kast"] > exact_kotlin["graphify"]
    gates = {
        "provenanceAdmitted": isinstance(admission, provenance.AdmittedProvenance),
        "allQuestionsScored": all_questions_scored,
        "kastHasZeroCriticalFailures": not critical_failures,
        "kastExactKotlinScoreStrictlyHigher": exact_advantage,
        "scoreAdvantageObserved": score_advantage,
    }
    superiority_proven = all(gates.values())
    if superiority_proven:
        verdict = "SUPERIORITY_PROVEN"
        status = "surpasses Graphify on the frozen benchmark"
    else:
        verdict = "SUPERIORITY_NOT_ESTABLISHED"
        status = (
            "score advantage observed; capture provenance is not admitted"
            if score_advantage and not gates["provenanceAdmitted"]
            else "the frozen evidence does not establish superiority"
        )

    recorded_binary_identity = {
        "implementationCommit": kast_document.get("implementationCommit"),
        "recordedPath": kast_document.get("kastBinary"),
    }
    hashes = {
        "questionsSha256": snapshot.identity.questions_sha256,
        "rubricSha256": snapshot.identity.rubric_sha256,
        "manifestSha256": snapshot.identity.manifest_sha256,
        "kastResultSha256": admission.kast_document_sha256,
        "graphifyResultSha256": admission.graphify_document_sha256,
        "implementationIdentitySha256": identity_sha256(
            {"implementationCommit": kast_document.get("implementationCommit")}
        ),
        "kastBinaryRecordedIdentitySha256": identity_sha256(recorded_binary_identity),
    }
    maximum = len(scored_questions) * len(DIMENSIONS) * 2
    performance = performance_comparison(
        admission,
        kast_document,
        graphify_document,
    )
    return {
        "schemaVersion": 3,
        "verdict": verdict,
        "status": status,
        "corpusCommit": corpus_commit,
        "dimensions": list(DIMENSIONS),
        "reasonCatalog": REASON_CATALOG,
        "maximumPointsPerQuestion": len(DIMENSIONS) * 2,
        "provenance": {
            "admission": admission.document(),
            "hashes": hashes,
            "kast": {
                "implementationCommit": kast_document.get("implementationCommit"),
                "binaryRecordedPath": kast_document.get("kastBinary"),
                "binarySha256": binary_digest,
                "binaryEvidence": "HASHED_BYTES" if binary_digest else "RECORDED_PATH_ONLY",
            },
            "graphify": {
                "version": graphify_document.get("graphify", {}).get("version"),
                "directed": graphify_document.get("graphify", {}).get("directed"),
                "nodes": graphify_document.get("graphify", {}).get("nodes"),
                "edges": graphify_document.get("graphify", {}).get("edges"),
                "graphSha256": graphify_document.get("graphify", {}).get("graphSha256"),
            },
            "limitations": [
                failure.code.value
                for failure in (
                    () if isinstance(admission, provenance.AdmittedProvenance)
                    else admission.failures
                )
            ],
        },
        "questions": scored_questions,
        "categories": {
            category: {
                "questions": category_scores["kast"][category]["questions"],
                "maximumPoints": category_scores["kast"][category]["maximumPoints"],
                "kastPoints": category_scores["kast"][category]["points"],
                "graphifyPoints": category_scores["graphify"][category]["points"],
            }
            for category in category_scores["kast"]
        },
        "systems": {
            "kast": {
                "overallPoints": overall["kast"],
                "overallMaximumPoints": maximum,
                "exactKotlinPoints": exact_kotlin["kast"],
                "criticalFailures": critical_failures,
            },
            "graphify": {
                "overallPoints": overall["graphify"],
                "overallMaximumPoints": maximum,
                "exactKotlinPoints": exact_kotlin["graphify"],
            },
        },
        "performance": performance,
        "gates": gates,
    }


def encoded_comparison():
    return (json.dumps(build_comparison(), indent=2, sort_keys=True) + "\n").encode()


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--check", action="store_true")
    action.add_argument("--write", action="store_true")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    generated = encoded_comparison()
    if args.check:
        if not args.output.is_file() or args.output.read_bytes() != generated:
            print(
                json.dumps(
                    {
                        "comparison": {
                            "status": "STALE",
                            "output": str(args.output),
                            "help": "Run score.py --write to regenerate the frozen comparison.",
                        }
                    },
                    sort_keys=True,
                )
            )
            return 1
        print(json.dumps({"comparison": {"status": "CURRENT", "output": str(args.output)}}))
        return 0
    if args.write:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(generated)
    comparison = json.loads(generated)
    print(
        json.dumps(
            {
                "comparison": {
                    "status": comparison["status"],
                    "verdict": comparison["verdict"],
                    "kastPoints": comparison["systems"]["kast"]["overallPoints"],
                    "graphifyPoints": comparison["systems"]["graphify"]["overallPoints"],
                    "performanceEligible": comparison["performance"]["eligible"],
                    "performanceWinner": comparison["performance"][
                        "winnerByTotalLatency"
                    ],
                    "output": str(args.output) if args.write else None,
                }
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except provenance.ProvenanceError as error:
        print(json.dumps(error.document(), sort_keys=True))
        raise SystemExit(1)
    except (
        AttributeError,
        OSError,
        RuntimeError,
        TypeError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
    ) as error:
        print(json.dumps({"error": {"code": "COMPARISON_FAILED", "message": str(error)}}))
        raise SystemExit(1)
