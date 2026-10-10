"""Kill specific policy regressions; no providers, Pi or native tools involved."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class PiEvaluationMutationTest(unittest.TestCase):
    def test_decision_mutations_are_detected(self):
        here = Path(__file__).parent
        node = shutil.which("node")
        self.assertIsNotNone(node, "Node.js is required; missing tooling is not mutation proof")
        mutations = [
            ("terminal-rejection-gate", "pi_evaluation_policy.mjs",
             "return this.stop(Outcome.REJECTION);",
             "return decision(true,'RESULT_RECEIVED',this.phase);",
             "recorded terminal dense rejection"),
            ("shared-final-budget", "pi_evaluation_policy.mjs",
             "const remaining=bound.reportedTokens-this.phaseUsage[this.phase].totalTokens;",
             "const remaining=this.config.work.reportedTokens-this.usage.totalTokens;",
             "recorded 30k negative"),
            ("cached-input-not-charged", "pi_evaluation_policy.mjs",
             "this.phaseUsage[this.lastRequestPhase][key]+=usage[key];",
             "this.phaseUsage[this.lastRequestPhase][key]+=key==='totalTokens'?usage.input+usage.output:usage[key];",
             "each case keeps independent usage"),
            ("delivery-semantic-rerun", "pi_evaluation_policy.mjs",
             "if(this.phase==='WORK') allowed=",
             "if(this.phase==='WORK'||this.phase==='DELIVERY') allowed=",
             "delivery phase guard blocks"),
            ("changed-read-grant", "pi_evaluation_policy.mjs",
             "isDeepStrictEqual(args?.request,this.evidenceRequest)",
             "args?.request?.type==='READ_RESULT'",
             "evidence-only opt-in"),
            ("received-result-coaching", "pi_evaluation_controller.mjs",
             "await session.agent.continue();",
             "await session.prompt('coaching');",
             "supported received-result controller"),
        ]
        for name, filename, original, replacement, selection in mutations:
            with self.subTest(mutation=name), tempfile.TemporaryDirectory(prefix="kast-pi-mutation-") as directory:
                root = Path(directory)
                for source in here.glob("pi_evaluation*.mjs"):
                    shutil.copyfile(source, root / source.name)
                shutil.copyfile(here / "run_pi_evaluation.mjs", root / "run_pi_evaluation.mjs")
                target = root / filename
                text = target.read_text()
                self.assertEqual(1, text.count(original), "Mutation must change exactly one owning rule")
                target.write_text(text.replace(original, replacement))
                result = subprocess.run([node, "--test", "--test-name-pattern", selection,
                                         "pi_evaluation.test.mjs"], cwd=root,
                                        capture_output=True, text=True, timeout=10)
                output = result.stdout + result.stderr
                self.assertNotEqual(0, result.returncode, f"Surviving mutation {name}\n{output}")
                self.assertIn("AssertionError", output, "Infrastructure failure is not a killed mutation")
                self.assertIn(selection, output)


if __name__ == "__main__":
    unittest.main()
