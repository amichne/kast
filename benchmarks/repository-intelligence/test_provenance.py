#!/usr/bin/env python3

import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch


BENCHMARK = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCHMARK))

import provenance
import score


def admissible_documents():
    question_ids = ("q1",)
    identity = provenance.BenchmarkIdentity(
        "a" * 64,
        "b" * 40,
        "c" * 64,
        "d" * 64,
        question_ids,
    )
    source_commit = "e" * 40
    graph_sha256 = "f" * 64
    executable_sha256 = "1" * 64
    source_tree_sha256 = "3" * 64
    cargo_sha256 = "4" * 64
    rustc_sha256 = "5" * 64
    environment_sha256 = "6" * 64
    runtime_sha256 = "7" * 64
    source_index_sha256 = "8" * 64
    corpus_inputs = {"files": 1, "sha256": "9" * 64}
    target = Path("/tmp/kast-benchmark-target")
    workspace_root = "/tmp/kast-benchmark-corpus"
    source_index = Path(
        "/tmp/kast-state/workspaces/fixture/cache/source-index.db"
    )
    coverage = {
        "accounted": 1,
        "complete": True,
        "excluded": 0,
        "eligibilityProven": True,
        "indexed": 1,
        "limitations": [],
        "pendingUpdateCount": 0,
        "stale": 0,
        "failed": 0,
        "total": 1,
    }
    coverage_sha256 = provenance.document_sha256(
        [{"id": "q1", "coverage": coverage}]
    )
    executable = "kast.exe" if sys.platform == "win32" else "kast"
    binary = target / "release" / executable
    manifest = {
        "corpus": {
            "commit": identity.corpus_commit,
            "contextInputs": corpus_inputs,
        },
        "systems": {
            "kast": {
                "capture": {
                    "schemaVersion": provenance.PROVENANCE_SCHEMA_VERSION,
                    "sourceTreeSha256": source_tree_sha256,
                    "binarySha256": "2" * 64,
                    "cargo": {
                        "executableSha256": cargo_sha256,
                        "versionOutput": "cargo fixture",
                    },
                    "rustc": {
                        "executableSha256": rustc_sha256,
                        "versionOutput": "rustc fixture",
                    },
                    "sourceIndexPath": str(source_index),
                    "sourceIndexSha256": source_index_sha256,
                    "processEnvironment": {
                        "base": "empty",
                        "set": {"KAST_HOME": "/tmp/kast-state"},
                        "remove": ["KAST_CACHE_HOME", "KAST_WORKSPACE_ID"],
                        "isolatedHomeAndWorkingDirectory": False,
                    },
                }
            },
            "graphify": {
                "finalRebuild": {
                    "corpusCommit": identity.corpus_commit,
                    "graphSha256": graph_sha256,
                    "directed": False,
                    "nodes": 1,
                    "edges": 0,
                },
                "capture": {
                    "schemaVersion": provenance.PROVENANCE_SCHEMA_VERSION,
                    "versionOutput": "graphify 0.9.22",
                    "executableSha256": executable_sha256,
                    "runtimeSha256": runtime_sha256,
                    "environmentSha256": environment_sha256,
                    "query": provenance.GRAPHIFY_QUERY_CONFIGURATION,
                    "processEnvironment": provenance.GRAPHIFY_PROCESS_ENVIRONMENT,
                },
            }
        },
    }
    kast = {
        "schemaVersion": provenance.CAPTURE_SCHEMA_VERSION,
        "corpusCommit": identity.corpus_commit,
        "implementationCommit": source_commit,
        "kastBinary": str(binary),
        "workspaceRoot": workspace_root,
        "graphGeneration": 1,
        "coverageSha256": coverage_sha256,
        "corpusInputs": corpus_inputs,
        "sourceIndexPath": str(source_index),
        "sourceIndexSha256": source_index_sha256,
        "summary": {"questions": {"total": 1}},
        "provenance": provenance.capture_provenance(
            identity,
            {
                "kind": "KAST_BUILD",
                "sourceCommit": source_commit,
                "sourceTreeSha256": source_tree_sha256,
                "sourceStatus": "CLEAN",
                "builder": {
                    "cargo": {
                        "executablePath": "/usr/bin/cargo",
                        "executableSha256": cargo_sha256,
                        "versionOutput": "cargo fixture",
                    },
                    "rustc": {
                        "executablePath": "/usr/bin/rustc",
                        "executableSha256": rustc_sha256,
                        "versionOutput": "rustc fixture",
                    },
                },
                "corpusInputs": corpus_inputs,
                "processEnvironment": {
                    "base": "empty",
                    "set": {"KAST_HOME": "/tmp/kast-state"},
                    "remove": ["KAST_CACHE_HOME", "KAST_WORKSPACE_ID"],
                    "isolatedHomeAndWorkingDirectory": False,
                },
                "buildCommand": [
                    "/usr/bin/cargo",
                    "build",
                    "--manifest-path",
                    "/checkout/cli-rs/Cargo.toml",
                    "--locked",
                    "--release",
                    "--bin",
                    "kast",
                    "--target-dir",
                    str(target),
                ],
                "binaryPath": str(binary),
                "binarySha256": "2" * 64,
                "workspaceRoot": workspace_root,
                "graphGeneration": 1,
                "coverageSha256": coverage_sha256,
                "sourceIndexPath": str(source_index),
                "sourceIndexSha256": source_index_sha256,
            },
        ),
        "results": [
            {
                "id": "q1",
                "returnCode": 0,
                "passed": True,
                "latencyMillis": 1.0,
                "responseBytes": 1,
                "response": {
                    "result": {
                        "workspaceIdentity": {"canonicalRoot": workspace_root},
                        "generation": 1,
                        "inventoryGeneration": 1,
                        "graphGeneration": 1,
                        "coverage": coverage,
                    }
                },
            }
        ],
    }
    graphify = {
        "schemaVersion": provenance.CAPTURE_SCHEMA_VERSION,
        "corpusCommit": identity.corpus_commit,
        "graphify": {
            "version": "graphify 0.9.22",
            "directed": False,
            "graphSha256": graph_sha256,
            "nodes": 1,
            "edges": 0,
            "questions": {"total": 1},
        },
        "provenance": provenance.capture_provenance(
            identity,
            {
                "kind": "GRAPHIFY_GRAPH",
                "corpusCommit": identity.corpus_commit,
                "graphSha256": graph_sha256,
                "directed": False,
                "nodes": 1,
                "edges": 0,
                "executableSha256": executable_sha256,
                "runtimeSha256": runtime_sha256,
                "environmentSha256": environment_sha256,
                "versionOutput": "graphify 0.9.22",
                "query": provenance.GRAPHIFY_QUERY_CONFIGURATION,
                "processEnvironment": provenance.GRAPHIFY_PROCESS_ENVIRONMENT,
            },
        ),
        "results": [
            {
                "id": "q1",
                "returnCode": 0,
                "answerable": True,
                "latencyMillis": 1.0,
                "responseBytes": 1,
            }
        ],
    }
    return identity, manifest, kast, graphify


def initialize_source_checkout(directory):
    root = directory / "kast"
    (root / "cli-rs").mkdir(parents=True)
    (root / "cli-rs/Cargo.toml").write_text("[package]\nname='kast'\n", encoding="utf-8")
    subprocess.run(["git", "init", "-q", str(root)], check=True)
    subprocess.run(["git", "-C", str(root), "config", "user.name", "Test"], check=True)
    subprocess.run(
        ["git", "-C", str(root), "config", "user.email", "test@example.invalid"],
        check=True,
    )
    subprocess.run(["git", "-C", str(root), "add", "cli-rs/Cargo.toml"], check=True)
    subprocess.run(["git", "-C", str(root), "commit", "-qm", "fixture"], check=True)
    return root


def fake_tool(directory, name, version, marker=None, build=False):
    path = directory / name
    lines = [
        f"#!{sys.executable}",
        "from pathlib import Path",
        "import sys",
        "if sys.argv[1:] == ['--version', '--verbose']:",
        f"    print({version!r})",
        "    raise SystemExit(0)",
    ]
    if marker is not None and build:
        lines.append(f"Path({str(marker)!r}).write_text('ran')")
    if build:
        lines.extend(
            [
                "target = Path(sys.argv[sys.argv.index('--target-dir') + 1])",
                "binary = target / 'release' / ('kast.exe' if sys.platform == 'win32' else 'kast')",
                "binary.parent.mkdir(parents=True, exist_ok=True)",
                "binary.write_bytes(b'harness-built-kast')",
            ]
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    path.chmod(0o755)
    return path


def build_authority(root, cargo, rustc):
    return {
        "schemaVersion": provenance.PROVENANCE_SCHEMA_VERSION,
        "sourceTreeSha256": provenance.source_tree_sha256(root),
        "binarySha256": provenance.sha256_bytes(b"harness-built-kast"),
        "cargo": {
            "executableSha256": provenance.sha256_file(cargo),
            "versionOutput": "cargo fixture",
        },
        "rustc": {
            "executableSha256": provenance.sha256_file(rustc),
            "versionOutput": "rustc fixture",
        },
    }


class BenchmarkProvenanceTest(unittest.TestCase):
    def test_nonempty_legacy_hashes_do_not_admit_capture(self):
        identity, manifest, _, _ = admissible_documents()
        kast = {
            "corpusCommit": "corpus",
            "summary": {"questions": {"total": 1}},
            "provenance": {
                "questionsSha256": "questions",
                "kastBinarySha256": "plausible-but-unbound",
            },
            "results": [
                {
                    "id": "q1",
                    "returnCode": 0,
                    "passed": True,
                    "latencyMillis": 1.0,
                    "responseBytes": 1,
                }
            ],
        }
        graphify = {
            "corpusCommit": "corpus",
            "graphify": {
                "questions": {"total": 1},
                "graphSha256": "plausible-but-unbound",
            },
            "provenance": {"questionsSha256": "questions"},
            "results": [
                {
                    "id": "q1",
                    "returnCode": 0,
                    "answerable": True,
                    "latencyMillis": 1.0,
                    "responseBytes": 1,
                }
            ],
        }
        admission = provenance.admit_captures(
            identity,
            manifest,
            kast,
            graphify,
            manifest["systems"]["kast"]["capture"]["sourceTreeSha256"],
            "8" * 64,
        )

        performance = score.performance_comparison(
            admission,
            kast,
            graphify,
        )

        self.assertFalse(performance["eligible"])
        self.assertIn("KAST_BUILD_RECEIPT_MISSING", performance["reasons"])
        self.assertIn("GRAPHIFY_GRAPH_RECEIPT_MISSING", performance["reasons"])

    def test_matching_receipts_admit_quality_and_performance(self):
        identity, manifest, kast, graphify = admissible_documents()

        admission = provenance.admit_captures(
            identity,
            manifest,
            kast,
            graphify,
            manifest["systems"]["kast"]["capture"]["sourceTreeSha256"],
            "8" * 64,
        )
        performance = score.performance_comparison(
            admission,
            kast,
            graphify,
        )

        self.assertIsInstance(admission, provenance.AdmittedProvenance)
        self.assertTrue(performance["eligible"])

    @unittest.skipIf(os.name == "nt", "directory symlinks require extra privileges")
    def test_kast_build_command_accepts_canonical_target_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            real = root / "real"
            real.mkdir()
            alias = root / "alias"
            alias.symlink_to(real, target_is_directory=True)
            target = alias / "target"
            executable = "kast.exe" if os.name == "nt" else "kast"
            binary = real / "target" / "release" / executable
            command = [
                "/usr/bin/cargo",
                "build",
                "--manifest-path",
                "/checkout/cli-rs/Cargo.toml",
                "--locked",
                "--release",
                "--bin",
                "kast",
                "--target-dir",
                str(target),
            ]

            self.assertTrue(
                provenance._valid_kast_build_command(command, str(binary))
            )
            self.assertFalse(
                provenance._valid_kast_build_command(
                    command,
                    str(real / "other" / "release" / executable),
                )
            )
            relative_command = [*command[:-1], str(Path.cwd() / "relative-target")]
            self.assertFalse(
                provenance._valid_kast_build_command(
                    relative_command,
                    str(Path("relative-target") / "release" / executable),
                )
            )

    def test_benchmark_and_graph_receipt_drift_are_rejected(self):
        cases = []
        identity, manifest, kast, graphify = admissible_documents()
        kast["provenance"]["benchmark"]["questionsSha256"] = "0" * 64
        cases.append(
            (
                provenance.ProvenanceFailureCode.KAST_BENCHMARK_IDENTITY_MISMATCH,
                identity,
                manifest,
                kast,
                graphify,
            )
        )
        identity, manifest, kast, graphify = admissible_documents()
        graphify["provenance"]["artifact"]["graphSha256"] = "0" * 64
        cases.append(
            (
                provenance.ProvenanceFailureCode.GRAPHIFY_GRAPH_MISMATCH,
                identity,
                manifest,
                kast,
                graphify,
            )
        )
        identity, manifest, kast, graphify = admissible_documents()
        kast["provenance"]["artifact"]["binarySha256"] = "0" * 64
        cases.append(
            (
                provenance.ProvenanceFailureCode.KAST_BUILD_BINARY_MISMATCH,
                identity,
                manifest,
                kast,
                graphify,
            )
        )
        identity, manifest, kast, graphify = admissible_documents()
        kast["results"][0]["response"]["result"]["graphGeneration"] = 2
        cases.append(
            (
                provenance.ProvenanceFailureCode.KAST_EXECUTION_IDENTITY_MISMATCH,
                identity,
                manifest,
                kast,
                graphify,
            )
        )
        identity, manifest, kast, graphify = admissible_documents()
        kast["results"][0]["response"]["result"]["coverage"]["complete"] = False
        cases.append(
            (
                provenance.ProvenanceFailureCode.KAST_EXECUTION_IDENTITY_MISMATCH,
                identity,
                manifest,
                kast,
                graphify,
            )
        )
        identity, manifest, kast, graphify = admissible_documents()
        kast["results"][0]["response"]["result"]["coverage"]["limitations"] = [
            "partial"
        ]
        cases.append(
            (
                provenance.ProvenanceFailureCode.KAST_EXECUTION_IDENTITY_MISMATCH,
                identity,
                manifest,
                kast,
                graphify,
            )
        )
        identity, manifest, kast, graphify = admissible_documents()
        graphify["provenance"]["artifact"]["environmentSha256"] = "0" * 64
        cases.append(
            (
                provenance.ProvenanceFailureCode.GRAPHIFY_EXECUTABLE_MISMATCH,
                identity,
                manifest,
                kast,
                graphify,
            )
        )
        identity, manifest, kast, graphify = admissible_documents()
        kast["provenance"]["artifact"]["sourceIndexSha256"] = "9" * 64
        cases.append(
            (
                provenance.ProvenanceFailureCode.KAST_BUILD_AUTHORITY_MISMATCH,
                identity,
                manifest,
                kast,
                graphify,
            )
        )

        for code, identity, manifest, kast, graphify in cases:
            with self.subTest(code=code.value):
                admission = provenance.admit_captures(
                    identity,
                    manifest,
                    kast,
                    graphify,
                    manifest["systems"]["kast"]["capture"]["sourceTreeSha256"],
                    "8" * 64,
                )

                self.assertIsInstance(admission, provenance.RejectedProvenance)
                self.assertIn(code, {failure.code for failure in admission.failures})

    def test_admission_cannot_be_reused_for_mutated_capture(self):
        identity, manifest, kast, graphify = admissible_documents()
        admission = provenance.admit_captures(
            identity,
            manifest,
            kast,
            graphify,
            manifest["systems"]["kast"]["capture"]["sourceTreeSha256"],
            "8" * 64,
        )
        kast["results"][0]["latencyMillis"] = 2.0

        performance = score.performance_comparison(
            admission,
            kast,
            graphify,
        )

        self.assertFalse(performance["eligible"])
        self.assertIn(
            provenance.ProvenanceFailureCode.KAST_CAPTURE_DOCUMENT_MISMATCH.value,
            performance["reasons"],
        )

    def test_mixed_capture_schema_is_rejected(self):
        identity, manifest, kast, graphify = admissible_documents()
        graphify["schemaVersion"] -= 1

        admission = provenance.admit_captures(
            identity,
            manifest,
            kast,
            graphify,
            manifest["systems"]["kast"]["capture"]["sourceTreeSha256"],
            "8" * 64,
        )

        self.assertIsInstance(admission, provenance.RejectedProvenance)
        self.assertIn(
            provenance.ProvenanceFailureCode.CAPTURE_SCHEMA_MISMATCH,
            {failure.code for failure in admission.failures},
        )

    def test_manifest_authority_and_capture_shapes_fail_closed(self):
        identity, manifest, kast, graphify = admissible_documents()
        del manifest["systems"]["graphify"]["capture"]["environmentSha256"]

        with self.assertRaises(provenance.ProvenanceError) as missing_authority:
            provenance.admit_captures(
                identity,
                manifest,
                kast,
                graphify,
                manifest["systems"]["kast"]["capture"]["sourceTreeSha256"],
                "8" * 64,
            )
        self.assertEqual(
            "GRAPHIFY_CAPTURE_AUTHORITY_INVALID",
            missing_authority.exception.code,
        )

        with self.assertRaises(provenance.ProvenanceError) as invalid_capture:
            provenance.admit_captures(
                identity,
                admissible_documents()[1],
                [],
                graphify,
                "3" * 64,
                "8" * 64,
            )
        self.assertEqual("CAPTURE_DOCUMENT_INVALID", invalid_capture.exception.code)

    def test_benchmark_snapshot_hashes_the_bytes_it_parses(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            manifest = directory / "manifest.json"
            questions = directory / "questions.jsonl"
            rubric = directory / "rubric.md"
            manifest_bytes = b'{"corpus":{"commit":"frozen"}}\n'
            questions_bytes = b'{"id":"q1"}\n'
            rubric_bytes = b"rubric\n"
            manifest.write_bytes(manifest_bytes)
            questions.write_bytes(questions_bytes)
            rubric.write_bytes(rubric_bytes)

            snapshot = provenance.load_benchmark_snapshot(
                manifest,
                questions,
                rubric,
            )
            manifest.write_text('{"corpus":{"commit":"changed"}}\n')

            self.assertEqual("frozen", snapshot.identity.corpus_commit)
            self.assertEqual(
                provenance.sha256_bytes(manifest_bytes),
                snapshot.identity.manifest_sha256,
            )
            self.assertEqual(
                provenance.sha256_bytes(questions_bytes),
                snapshot.identity.questions_sha256,
            )
            self.assertEqual(
                provenance.sha256_bytes(rubric_bytes),
                snapshot.identity.rubric_sha256,
            )

    def test_kast_build_receipt_binds_clean_source_and_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            root = initialize_source_checkout(directory)
            cargo = fake_tool(
                directory,
                "cargo",
                "cargo fixture",
                build=True,
            )
            rustc = fake_tool(directory, "rustc", "rustc fixture")
            authority = build_authority(root, cargo, rustc)
            receipt = provenance.build_kast_release(
                root,
                directory / "target",
                authority,
                str(cargo),
                str(rustc),
            )

            provenance.verify_kast_build_receipt(root, receipt)
            self.assertEqual(
                subprocess.run(
                    ["git", "-C", str(root), "rev-parse", "HEAD"],
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout.strip(),
                receipt.source_commit,
            )
            self.assertEqual(
                provenance.sha256_file(receipt.binary_path),
                receipt.binary_sha256,
            )

            receipt.binary_path.write_bytes(b"mutated")
            with self.assertRaisesRegex(
                provenance.ProvenanceError,
                "changed during benchmark capture",
            ) as raised:
                provenance.verify_kast_build_receipt(root, receipt)
            self.assertEqual("KAST_BUILD_RECEIPT_STALE", raised.exception.code)

    def test_dirty_kast_source_is_rejected_before_cargo(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            root = initialize_source_checkout(directory)
            (root / "untracked").write_text("dirty", encoding="utf-8")
            marker = directory / "cargo-ran"
            cargo = fake_tool(
                directory,
                "cargo",
                "cargo fixture",
                marker,
                build=True,
            )
            rustc = fake_tool(directory, "rustc", "rustc fixture")

            with self.assertRaises(provenance.ProvenanceError) as raised:
                provenance.build_kast_release(
                    root,
                    directory / "target",
                    {
                        "sourceTreeSha256": "0" * 64,
                        "binarySha256": "0" * 64,
                        "cargo": {},
                        "rustc": {},
                    },
                    str(cargo),
                    str(rustc),
                )

            self.assertEqual("KAST_SOURCE_DIRTY", raised.exception.code)
            self.assertFalse(marker.exists())

    def test_graphify_environment_mutation_stales_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = Path(directory) / "environment"
            (environment / "bin").mkdir(parents=True)
            (environment / "pyvenv.cfg").write_text("fixture\n", encoding="utf-8")
            runtime = environment / "bin/python3"
            runtime.symlink_to(sys.executable)
            executable = environment / "bin/graphify"
            executable.write_text(
                f"#!{runtime}\nprint('fixture')\n",
                encoding="utf-8",
            )
            executable.chmod(0o755)
            module = environment / "graphify.py"
            module.write_text("VERSION = 1\n", encoding="utf-8")
            graph = Path(directory) / "graph.json"
            graph.write_text('{"directed": false, "links": [], "nodes": []}\n')
            (
                captured_runtime,
                runtime_sha256,
                environment_root,
                environment_sha256,
            ) = provenance._graphify_environment(executable)
            capture = provenance.GraphifyCapture(
                executable=executable,
                executable_sha256=provenance.sha256_file(executable),
                runtime=captured_runtime,
                runtime_sha256=runtime_sha256,
                environment_root=environment_root,
                environment_sha256=environment_sha256,
                version_output="graphify fixture",
                source_graph=graph,
                graph_bytes=graph.read_bytes(),
                graph_sha256=provenance.sha256_file(graph),
                directed=False,
                nodes=0,
                edges=0,
                corpus_commit="corpus",
            )
            module.write_text("VERSION = 2\n", encoding="utf-8")

            with self.assertRaises(provenance.ProvenanceError) as raised:
                provenance.verify_graphify_capture(capture, graph)

            self.assertEqual(
                "GRAPHIFY_CAPTURE_RECEIPT_STALE",
                raised.exception.code,
            )

    def test_process_environment_and_source_index_are_content_bound(self):
        with patch.dict(
            os.environ,
            {
                "HOME": "/injected",
                "PYTHONHOME": "/injected",
                "PYTHONOPTIMIZE": "2",
                "PYTHONPATH": "/injected",
                "PYTHONUSERBASE": "/injected",
                "VIRTUAL_ENV": "/injected",
            },
        ):
            with tempfile.TemporaryDirectory() as directory:
                environment = provenance.process_environment(
                    provenance.GRAPHIFY_PROCESS_ENVIRONMENT,
                    Path(directory),
                )
                self.assertEqual(
                    {
                        **provenance.GRAPHIFY_PROCESS_ENVIRONMENT["set"],
                        "HOME": str(Path(directory).resolve()),
                        "TMPDIR": str(Path(directory).resolve()),
                    },
                    environment,
                )

        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "source-index.db"
            with closing(sqlite3.connect(database)) as connection:
                connection.execute("CREATE TABLE proof(value TEXT)")
                connection.execute("INSERT INTO proof VALUES ('first')")
                connection.commit()
            before = provenance.sqlite_snapshot_sha256(database)
            with closing(sqlite3.connect(database)) as connection:
                connection.execute("INSERT INTO proof VALUES ('second')")
                connection.commit()
            after = provenance.sqlite_snapshot_sha256(database)

        self.assertNotEqual(before, after)

    def test_source_index_path_is_rejected_before_sqlite_access(self):
        _, manifest, kast, _ = admissible_documents()
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            state = directory / "state"
            state.mkdir()
            expected = state / "source-index.db"
            expected.touch()
            outside = directory / "outside.db"
            outside.touch()
            authority = manifest["systems"]["kast"]["capture"]
            authority["processEnvironment"]["set"]["KAST_HOME"] = str(state)
            authority["sourceIndexPath"] = str(expected)
            escaped = state / ".." / outside.name
            kast["sourceIndexPath"] = str(escaped)
            kast["provenance"]["artifact"]["sourceIndexPath"] = str(escaped)

            with patch.object(provenance, "sqlite_snapshot_sha256") as hasher:
                self.assertIsNone(
                    provenance.current_source_index_sha256(kast, manifest)
                )
                hasher.assert_not_called()

            expected.unlink()
            expected.symlink_to(outside)
            kast["sourceIndexPath"] = str(expected)
            kast["provenance"]["artifact"]["sourceIndexPath"] = str(expected)
            with patch.object(provenance, "sqlite_snapshot_sha256") as hasher:
                self.assertIsNone(
                    provenance.current_source_index_sha256(kast, manifest)
                )
                hasher.assert_not_called()

    def test_context_inputs_are_tracked_content_bound_files(self):
        with tempfile.TemporaryDirectory() as directory:
            corpus = Path(directory)
            subprocess.run(["git", "init", "-q", str(corpus)], check=True)
            subprocess.run(
                ["git", "-C", str(corpus), "config", "user.name", "Test"],
                check=True,
            )
            subprocess.run(
                [
                    "git",
                    "-C",
                    str(corpus),
                    "config",
                    "user.email",
                    "test@example.invalid",
                ],
                check=True,
            )
            (corpus / ".gitignore").write_text("ignored.md\n", encoding="utf-8")
            tracked = corpus / "docs.md"
            tracked.write_text("frozen\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(corpus), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(corpus), "commit", "-qm", "fixture"],
                check=True,
            )
            expected = provenance.corpus_input_identity(corpus)
            manifest = {
                "corpus": {
                    "commit": subprocess.run(
                        ["git", "-C", str(corpus), "rev-parse", "HEAD"],
                        check=True,
                        capture_output=True,
                        text=True,
                    ).stdout.strip(),
                    "contextInputs": expected,
                }
            }

            self.assertEqual(
                expected,
                provenance.validate_corpus_inputs(manifest, corpus),
            )
            tracked.write_text("changed\n", encoding="utf-8")
            with self.assertRaises(provenance.ProvenanceError) as changed:
                provenance.validate_corpus_inputs(manifest, corpus)
            self.assertEqual("KAST_CORPUS_INPUT_IDENTITY_MISMATCH", changed.exception.code)
            tracked.write_text("frozen\n", encoding="utf-8")
            (corpus / "ignored.md").write_text("injected\n", encoding="utf-8")
            with self.assertRaises(provenance.ProvenanceError) as ignored:
                provenance.validate_corpus_inputs(manifest, corpus)
            self.assertEqual("KAST_CORPUS_INPUT_UNTRACKED", ignored.exception.code)

    def test_graph_digest_mismatch_fails_before_queries(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            graph = directory / "graph.json"
            graph.write_text(
                json.dumps({"directed": False, "links": [], "nodes": []}) + "\n",
                encoding="utf-8",
            )
            query_marker = directory / "query-ran"
            graphify = directory / "graphify"
            graphify.write_text(
                "\n".join(
                    [
                        f"#!{sys.executable}",
                        "import pathlib",
                        "import sys",
                        "if sys.argv[1:] == ['--version']:",
                        "    print('graphify 0.9.22')",
                        "else:",
                        f"    pathlib.Path({str(query_marker)!r}).write_text('query')",
                        "    print('NODE fake [src=fake.py loc=L1]')",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            graphify.chmod(0o755)
            output = directory / "capture.json"

            process = subprocess.run(
                [
                    sys.executable,
                    str(BENCHMARK / "run_graphify.py"),
                    "--graph",
                    str(graph),
                    "--graphify",
                    str(graphify),
                    "--output",
                    str(output),
                ],
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(1, process.returncode, process.stdout)
            error = json.loads(process.stdout)["error"]
            self.assertEqual("GRAPHIFY_BASELINE_IDENTITY_MISMATCH", error["code"])
            self.assertEqual("graphSha256", error["details"]["field"])
            self.assertFalse(query_marker.exists())
            self.assertFalse(output.exists())
            self.assertNotIn("Traceback", process.stdout)


if __name__ == "__main__":
    unittest.main()
