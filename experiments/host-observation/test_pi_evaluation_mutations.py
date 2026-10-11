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
            ("provider-output-cap-removed", "pi_evaluation_guard.mjs",
             "{...event.payload,max_output_tokens:policy.providerOutputCap()}",
             "{...event.payload}",
             "provider projection replaces"),
            ("inline-declarations-charged-twice", "pi_evaluation_payload.mjs",
             "payload.input.filter(item=>item.type!=='additional_tools')",
             "payload.input",
             "inline SDK declarations consume"),
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
            ("qualified-envelope-rejected", "pi_evaluation_policy.mjs",
             "['complete','qualified','rejected_document','rejected']",
             "['complete','rejected_document','rejected']",
             "canonical qualified producer reply"),
            ("original-coverage-lost", "pi_evaluation_policy.mjs",
             "knownMinimum:detail?.originalCoverage?.knownMinimum??null",
             "knownMinimum:detail?.coverage?.knownMinimum??null",
             "canonical rejection preserves originalCoverage"),
            ("measured-input-ignored", "pi_evaluation_policy.mjs",
             "Math.max(calibration.calibratedInputCeiling,this.lastMeasuredInput)",
             "calibration.calibratedInputCeiling",
             "cached measured floor changes", "pi_evaluation_payload.test.mjs"),
            ("calibration-overrun-ignored", "pi_evaluation_policy.mjs",
             "if(this.lastMeasuredInput>this.activeInputCalibration.calibratedInputCeiling)",
             "if(false)",
             "measured input above calibration"),
            ("different-full-payload-admitted", "pi_evaluation_policy.mjs",
             "this.inputCalibrations.get(payload.payloadSha256)",
             "this.inputCalibrations.values().next().value",
             "changed fresh context", "pi_evaluation_payload.test.mjs"),
            ("unsafe-automatic-transport", "pi_evaluation_worker.mjs",
             "transport:'sse',cacheWarming:'off'",
             "transport:'auto',cacheWarming:'off'",
             "worker explicitly disables unaccounted warming"),
            ("unaccounted-background-warming", "pi_evaluation_worker.mjs",
             "transport:'sse',cacheWarming:'off'",
             "transport:'sse',cacheWarming:'streaming'",
             "worker explicitly disables unaccounted warming"),
            ("delivery-upgrades-initial-qualification", "pi_evaluation_policy.mjs",
             "this.acceptQuery(envelope.initial,true)",
             "this.acceptQuery(envelope.pages.at(-1)??envelope.initial,true)",
             "actual shared-client delivered complete and qualified"),
            ("delivery-blocker-treated-as-success", "pi_evaluation_policy.mjs",
             "if(envelope.delivery.stop!=='DELIVERED')",
             "if(false)",
             "actual shared-client blockers cancellation"),
            ("physical-rpc-count-collapsed", "pi_evaluation_delivery.mjs",
             "rpcCount:d.rpc_count",
             "rpcCount:1",
             "actual shared-client delivered complete and qualified"),
            ("failed-rpc-count-rejected", "../../cli/src/main/js/query-delivery-contract.mjs",
             "value.pages.length + 1 > value.delivery.rpc_count",
             "value.pages.length + 1 !== value.delivery.rpc_count",
             "actual shared-client blockers cancellation"),
        ]
        for entry in mutations:
            name, filename, original, replacement, selection, *suite = entry
            with self.subTest(mutation=name), tempfile.TemporaryDirectory(prefix="kast-pi-mutation-") as directory:
                root = Path(directory) / "experiments" / "host-observation"
                root.mkdir(parents=True)
                contract = Path(directory) / "cli" / "src" / "main" / "js" / "query-delivery-contract.mjs"
                contract.parent.mkdir(parents=True)
                shutil.copyfile(here.parent.parent / "cli/src/main/js/query-delivery-contract.mjs", contract)
                for source in here.glob("pi_evaluation*.mjs"):
                    shutil.copyfile(source, root / source.name)
                shutil.copyfile(here / "run_pi_evaluation.mjs", root / "run_pi_evaluation.mjs")
                shutil.copytree(here / "pi-fixtures", root / "pi-fixtures")
                target = root / filename
                text = target.read_text()
                self.assertEqual(1, text.count(original), "Mutation must change exactly one owning rule")
                target.write_text(text.replace(original, replacement))
                result = subprocess.run([node, "--test", "--test-name-pattern", selection,
                                         suite[0] if suite else "pi_evaluation.test.mjs"], cwd=root,
                                        capture_output=True, text=True, timeout=10)
                output = result.stdout + result.stderr
                self.assertNotEqual(0, result.returncode, f"Surviving mutation {name}\n{output}")
                self.assertIn("AssertionError", output, "Infrastructure failure is not a killed mutation")
                self.assertIn(selection, output)


if __name__ == "__main__":
    unittest.main()
