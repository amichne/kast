#!/usr/bin/env python3

import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path


BENCHMARK = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCHMARK))

import run as benchmark_run
import provenance
import score


def question(question_id):
    return next(item for item in benchmark_run.load_questions() if item["id"] == question_id)


def result(path, question_id):
    document = json.loads((BENCHMARK / path).read_text(encoding="utf-8"))
    return next(item for item in document["results"] if item["id"] == question_id)


def performance_documents():
    question_ids = ["q1", "q2"]
    kast = {
        "summary": {"questions": {"total": 2}},
        "results": [
            {
                "id": "q1",
                "returnCode": 0,
                "passed": True,
                "latencyMillis": 10.0,
                "responseBytes": 100,
            },
            {
                "id": "q2",
                "returnCode": 0,
                "passed": True,
                "latencyMillis": 30.0,
                "responseBytes": 200,
            },
        ],
    }
    graphify = {
        "graphify": {
            "questions": {"total": 2},
        },
        "results": [
            {
                "id": "q1",
                "returnCode": 0,
                "answerable": True,
                "latencyMillis": 20.0,
                "responseBytes": 300,
            },
            {
                "id": "q2",
                "returnCode": 0,
                "answerable": True,
                "latencyMillis": 40.0,
                "responseBytes": 400,
            },
        ],
    }
    admission = provenance.AdmittedProvenance(
        benchmark=provenance.BenchmarkIdentity(
            "a" * 64,
            "corpus",
            "b" * 64,
            "c" * 64,
            tuple(question_ids),
        ),
        kast=provenance.KastArtifactIdentity(
            "d" * 40,
            "e" * 64,
            "f" * 64,
            "/tmp/corpus",
            1,
            "3" * 64,
            "4" * 64,
            "5" * 64,
        ),
        graphify=provenance.GraphifyArtifactIdentity(
            "corpus",
            "1" * 64,
            "2" * 64,
        ),
        kast_document_sha256=provenance.document_sha256(kast),
        graphify_document_sha256=provenance.document_sha256(graphify),
    )
    return admission, kast, graphify


class BenchmarkComparisonTest(unittest.TestCase):
    def test_path_assertions_preserve_order_and_multiplicity(self):
        path_question = question("path-01-known-sha-chain")
        assertion = next(item for item in path_question["assertions"] if item["path"] == "result.paths")
        response = result("results/final.json", path_question["id"])["response"]

        self.assertTrue(benchmark_run.assertion_passes(response, assertion))
        reversed_response = copy.deepcopy(response)
        for path in reversed_response["result"]["paths"]:
            path["nodes"].reverse()
        self.assertFalse(benchmark_run.assertion_passes(reversed_response, assertion))

    def test_wrong_ordered_path_loses_correctness_credit(self):
        path_question = question("path-01-known-sha-chain")
        kast_result = copy.deepcopy(result("results/final.json", path_question["id"]))
        for path in kast_result["response"]["result"]["paths"]:
            path["nodes"].reverse()

        scored = score.score_kast(path_question, kast_result)

        self.assertEqual(0, scored["dimensions"]["answerCorrectness"]["score"])

    def test_arbitrary_graphify_node_cannot_answer_a_negative_question(self):
        negative = question("negative-03-complete-empty")
        graphify_result = {
            "id": negative["id"],
            "category": negative["category"],
            "returnCode": 0,
            "answerable": True,
            "stdout": "Traversal: BFS depth=2\nNODE Unrelated [src=Elsewhere.kt loc=L1]\n",
            "stderr": "",
        }

        scored = score.score_graphify(negative, graphify_result)

        self.assertEqual(0, scored["dimensions"]["answerCorrectness"]["score"])
        self.assertEqual(0, scored["dimensions"]["discoveryAnswerability"]["score"])

    def test_comparison_is_question_derived_and_provenance_bound(self):
        comparison = score.build_comparison()

        self.assertEqual(42, len(comparison["questions"]))
        self.assertEqual(list(score.DIMENSIONS), comparison["dimensions"])
        self.assertTrue(comparison["gates"]["allQuestionsScored"])
        self.assertTrue(comparison["gates"]["scoreAdvantageObserved"])
        self.assertTrue(comparison["gates"]["provenanceAdmitted"])
        self.assertEqual("SUPERIORITY_PROVEN", comparison["verdict"])
        self.assertEqual(
            {
                "questionsSha256",
                "rubricSha256",
                "manifestSha256",
                "kastResultSha256",
                "graphifyResultSha256",
                "implementationIdentitySha256",
                "kastBinaryRecordedIdentitySha256",
            },
            set(comparison["provenance"]["hashes"]),
        )
        for scored_question in comparison["questions"]:
            for system in ("kast", "graphify"):
                dimensions = scored_question["systems"][system]["dimensions"]
                self.assertEqual(set(score.DIMENSIONS), set(dimensions))
                self.assertTrue(all("reason" in item and "evidence" in item for item in dimensions.values()))

    def test_performance_comparison_reports_only_comparable_completed_work(self):
        admission, kast, graphify = performance_documents()

        performance = score.performance_comparison(
            admission,
            kast,
            graphify,
        )

        self.assertEqual(
            {
                "eligible": True,
                "reasons": [],
                "declaredQuestions": 2,
                "winnerByTotalLatency": "kast",
                "systems": {
                    "kast": {
                        "reportedQuestions": 2,
                        "capturedQuestions": 2,
                        "completedQuestions": 2,
                        "measuredQuestions": 2,
                        "totalLatencyMillis": 40.0,
                        "medianLatencyMillis": 20.0,
                        "p95LatencyMillis": 30.0,
                        "totalResponseBytes": 300,
                    },
                    "graphify": {
                        "reportedQuestions": 2,
                        "capturedQuestions": 2,
                        "completedQuestions": 2,
                        "measuredQuestions": 2,
                        "totalLatencyMillis": 60.0,
                        "medianLatencyMillis": 30.0,
                        "p95LatencyMillis": 40.0,
                        "totalResponseBytes": 700,
                    },
                },
            },
            performance,
        )

    def test_performance_comparison_rejects_incomplete_evidence(self):
        cases = []

        admission, kast, graphify = performance_documents()
        graphify["results"][0]["returnCode"] = 1
        graphify["results"][0]["answerable"] = False
        cases.append(
            (
                "GRAPHIFY_WORKLOAD_INCOMPLETE",
                admission,
                kast,
                graphify,
            )
        )

        admission, kast, graphify = performance_documents()
        del graphify["results"][0]["latencyMillis"]
        cases.append(
            (
                "GRAPHIFY_MEASUREMENTS_INCOMPLETE",
                admission,
                kast,
                graphify,
            )
        )

        admission, kast, graphify = performance_documents()
        graphify["graphify"]["questions"]["total"] = 1
        cases.append(
            (
                "GRAPHIFY_DECLARED_WORKLOAD_MISMATCH",
                admission,
                kast,
                graphify,
            )
        )

        admission, kast, graphify = performance_documents()
        graphify["results"][0]["id"] = "different"
        cases.append(
            (
                "GRAPHIFY_CAPTURED_WORKLOAD_MISMATCH",
                admission,
                kast,
                graphify,
            )
        )

        admission, kast, graphify = performance_documents()
        cases.append(
            (
                "DECLARED_WORKLOAD_EMPTY",
                provenance.AdmittedProvenance(
                    benchmark=provenance.BenchmarkIdentity(
                        "a" * 64,
                        "corpus",
                        "b" * 64,
                        "c" * 64,
                        (),
                    ),
                    kast=admission.kast,
                    graphify=admission.graphify,
                    kast_document_sha256=admission.kast_document_sha256,
                    graphify_document_sha256=admission.graphify_document_sha256,
                ),
                kast,
                graphify,
            )
        )

        for (
            reason,
            expected_admission,
            kast_document,
            graphify_document,
        ) in cases:
            with self.subTest(reason=reason):
                performance = score.performance_comparison(
                    expected_admission,
                    kast_document,
                    graphify_document,
                )

                self.assertFalse(performance["eligible"])
                self.assertIsNone(performance["winnerByTotalLatency"])
                self.assertIn(reason, performance["reasons"])

    def test_frozen_comparison_admits_comparable_completed_work(self):
        comparison = score.build_comparison()

        self.assertTrue(comparison["performance"]["eligible"])
        self.assertEqual(
            42,
            comparison["performance"]["systems"]["kast"]["measuredQuestions"],
        )
        self.assertIsNotNone(comparison["performance"]["winnerByTotalLatency"])
        self.assertEqual([], comparison["performance"]["reasons"])

    def test_check_rejects_drift_without_repairing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "comparison.json"
            output.write_text("{}\n", encoding="utf-8")

            with redirect_stdout(io.StringIO()):
                return_code = score.main(["--check", "--output", str(output)])

            self.assertEqual(1, return_code)
            self.assertEqual("{}\n", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
