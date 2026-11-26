"""Run the shipped kast-recipe command against a strictly scripted kast executable.

The fake replays only the expected calls in order. An unexpected call, an extra call, or an unconsumed
expectation fails the case. The recipe itself, its paging, parsing and refusals, run unchanged.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RECIPE = ROOT / "agent-tools/skills/kast/scripts/kast-recipe"
BUDGET = "16000000"

FAKE = """#!{python}
import json, os, sys
plan_path = os.environ["FAKE_KAST_PLAN"]
log_path = os.environ["FAKE_KAST_LOG"]
plan = json.load(open(plan_path))
with open(log_path) as log:
    calls = [json.loads(line) for line in log if line.strip()]
argv = sys.argv[1:]
stdin = sys.stdin.read() if not sys.stdin.isatty() else ""
with open(log_path, "a") as log:
    log.write(json.dumps({{"argv": argv, "stdin": stdin}}) + "\\n")
if len(calls) >= len(plan):
    sys.stderr.write("unexpected kast call: %r\\n" % (argv,))
    sys.exit(97)
expected = plan[len(calls)]
if argv[:len(expected["prefix"])] != expected["prefix"]:
    sys.stderr.write("call %d: expected prefix %r, got %r\\n" % (len(calls), expected["prefix"], argv))
    sys.exit(98)
sys.stdout.write(expected["stdout"])
sys.exit(expected.get("status", 0))
"""


class KastRecipeCase(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(tempfile.mkdtemp(prefix="kast-recipe-test-"))
        self.addCleanup(lambda: subprocess.run(["rm", "-rf", str(self.directory)], check=False))
        self.fake = self.directory / "kast"
        self.fake.write_text(FAKE.format(python=sys.executable))
        self.fake.chmod(0o700)
        self.log = self.directory / "calls.jsonl"
        self.log.write_text("")

    def run_recipe(self, plan: list[dict], *arguments: str) -> tuple[subprocess.CompletedProcess[str], list[dict]]:
        plan_path = self.directory / "plan.json"
        plan_path.write_text(json.dumps(plan))
        self.log.write_text("")
        environment = {
            "PATH": os.environ["PATH"],
            "KAST_RECIPE_KAST": str(self.fake),
            "FAKE_KAST_PLAN": str(plan_path),
            "FAKE_KAST_LOG": str(self.log),
        }
        result = subprocess.run(
            ["sh", str(RECIPE), *arguments], cwd=self.directory, env=environment, capture_output=True, text=True,
            stdin=subprocess.DEVNULL, timeout=60,
        )
        calls = [json.loads(line) for line in self.log.read_text().splitlines() if line.strip()]
        self.assertEqual(len(plan), len(calls), f"unconsumed or extra kast calls: {calls}\n{result.stderr}")
        return result, calls


VERDICT = (
    '{"type":"complete","status":"complete","exhaustive":true,"failures":0,"omissions":0,'
    '"total":2,"excluded_callback_calls":4,"evidence_complete":true}'
)


class VerdictTest(KastRecipeCase):
    def test_reads_every_page_and_keeps_the_retained_handle(self) -> None:
        plan = [
            {"prefix": ["query", "--ref", "exact:v5:A", "--relation", "callers", "--retain",
                        "--max-returned-bytes", BUDGET, "--jq"],
             "stdout": f'["1 result:v1:0d6e2c5a-7b3f-4c1d-9e8a-6f5b4a3c2d1e",{VERDICT},[{{"name":"first"}}]]'},
            {"prefix": ["result", "read", "--ref", "result:v1:0d6e2c5a-7b3f-4c1d-9e8a-6f5b4a3c2d1e", "--cursor", "1", "--output", "symbols",
                        "--max-returned-bytes", BUDGET, "--jq"],
             "stdout": '["null result:v1:0d6e2c5a-7b3f-4c1d-9e8a-6f5b4a3c2d1e",[{"name":"second"}]]'},
        ]
        result, _ = self.run_recipe(plan, "verdict", "--ref", "exact:v5:A", "--relation", "callers")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {"verdict": json.loads(VERDICT), "result": "result:v1:0d6e2c5a-7b3f-4c1d-9e8a-6f5b4a3c2d1e", "items": [{"name": "first"}, {"name": "second"}]},
            json.loads(result.stdout),
        )

    def test_flags_the_recipe_owns_are_refused_before_any_kast_call(self) -> None:
        for flag in ("--jq", "--json", "--retain"):
            result, _ = self.run_recipe([], "verdict", "--ref", "exact:v5:A", flag)
            self.assertEqual(64, result.returncode)
            self.assertEqual({"type": "recipe_rejected", "reason": "FLAG_OWNED_BY_RECIPE"}, json.loads(result.stdout))

    def test_a_kast_rejection_passes_through_with_its_status(self) -> None:
        rejection = '{"type":"rejected","failure":"INVALID_COMMAND"}'
        plan = [{"prefix": ["query"], "stdout": rejection, "status": 2}]
        result, _ = self.run_recipe(plan, "verdict", "--name", "x")
        self.assertEqual(2, result.returncode)
        self.assertEqual(json.loads(rejection), json.loads(result.stdout))


class CallSitesTest(KastRecipeCase):
    def test_lines_come_from_kast_and_documentation_links_are_counted_not_listed(self) -> None:
        (self.directory / "a.kt").write_text("fun a() = Unit\ncall()\n * See [call].\n")
        rows = "a.kt|candidate:v5:x|CODE;a.kt|candidate:v5:y|CODE"
        plan = [
            {"prefix": ["query", "--ref", "exact:v5:A", "--relation", "references", "--output", "occurrences",
                        "--relation-in", "src", "--retain", "--max-returned-bytes", BUDGET, "--jq"],
             "stdout": f'["null result:v1:0d6e2c5a-7b3f-4c1d-9e8a-6f5b4a3c2d1e",{VERDICT},"{rows}"]'},
            {"prefix": ["source", "read", "--ref", "candidate:v5:x", "--jq"], "stdout": "2"},
            {"prefix": ["source", "read", "--ref", "candidate:v5:y", "--jq"], "stdout": "3"},
        ]
        result, _ = self.run_recipe(plan, "call-sites", "--ref", "exact:v5:A", "--relation-in", "src")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {"verdict": json.loads(VERDICT), "sites": [{"file": "a.kt", "line": 2, "context": "CODE"}],
             "documentation_links": 1},
            json.loads(result.stdout),
        )

    def test_flags_outside_the_relation_scope_are_refused(self) -> None:
        result, _ = self.run_recipe([], "call-sites", "--ref", "exact:v5:A", "--in", "src")
        self.assertEqual(64, result.returncode)
        self.assertEqual("FLAG_NOT_A_RELATION_SCOPE", json.loads(result.stdout)["reason"])


class ImpactTest(KastRecipeCase):
    def test_issued_seed_is_copied_unchanged_and_a_qualified_run_reports_its_frontiers(self) -> None:
        seed = '{"enclosing":"exact:v5:e","callable":"exact:v5:c","anchor":{"start":10,"end":25}}'
        qualified = ('{"type":"qualified","document":{"retention":{"kind":"retained",'
                     '"reference":"result:v1:5f0b6c1e-2a3d-4e5f-8a9b-0c1d2e3f4a5b"}}}')
        terminal = '"terminal":{"type":"UNRESOLVED_FLOW","cause":"%s"}'
        findings = "{%s,%s,%s}" % (terminal % "EXTERNAL_CALL", terminal % "EXTERNAL_CALL", terminal % "UNSUPPORTED_PROPERTY")
        plan = [
            {"prefix": ["query", "--ref", "exact:v5:A", "--relation", "callers", "--output", "occurrences",
                        "--max-returned-bytes", BUDGET, "--jq"], "stdout": seed},
            {"prefix": ["query", "--request", "-", "--jq"], "stdout": qualified, "status": 10},
            {"prefix": ["query", "--request", "-", "--json"], "stdout": findings, "status": 10},
        ]
        result, calls = self.run_recipe(plan, "impact", "--ref", "exact:v5:A")
        self.assertEqual(10, result.returncode, result.stderr)
        request = json.loads(calls[1]["stdin"])["request"]
        self.assertEqual([json.loads(seed)], request["source"]["seeds"])
        self.assertEqual("KOTLIN_FORWARD_V1", request["source"]["flow"])
        self.assertEqual("RETAIN", request["retention"])
        self.assertEqual("result:v1:5f0b6c1e-2a3d-4e5f-8a9b-0c1d2e3f4a5b", json.loads(calls[2]["stdin"])["request"]["result"])
        self.assertEqual(
            {"type": "qualified", "result": "result:v1:5f0b6c1e-2a3d-4e5f-8a9b-0c1d2e3f4a5b", "frontiers": [
                {"frontier": "UNRESOLVED_FLOW:EXTERNAL_CALL", "count": 2},
                {"frontier": "UNRESOLVED_FLOW:UNSUPPORTED_PROPERTY", "count": 1}]},
            json.loads(result.stdout),
        )

    def test_no_seed_and_invalid_inputs_are_refused(self) -> None:
        plan = [{"prefix": ["query"], "stdout": "null"}]
        result, _ = self.run_recipe(plan, "impact", "--ref", "exact:v5:A", "--seed", "3")
        self.assertEqual("PRODUCER_SEED_UNAVAILABLE", json.loads(result.stdout)["reason"])
        for arguments, reason in ((("--seed", "0"), "SEED_INDEX_INVALID"), (("--flow", "JAVA"), "FLOW_INVALID")):
            result, _ = self.run_recipe([], "impact", "--ref", "exact:v5:A", *arguments)
            self.assertEqual(64, result.returncode)
            self.assertEqual(reason, json.loads(result.stdout)["reason"])


if __name__ == "__main__":
    unittest.main()
