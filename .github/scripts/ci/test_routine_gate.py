import unittest
from routine_gate import inspect, inspect_native_inputs, REQUIRED


class RoutineGateTest(unittest.TestCase):
    def test_native_input_inventory_matches_exact_compilation_owners(self):
        owners = {
            "change:apply", "change:contract", "change:recovery", "change:verify", "diagnostic:contract",
            "distribution:cli", "distribution:contract", "distribution:managed", "evidence:contract", "evidence:sqlite",
            "kernel", "relation:contract", "symbol:contract", "traversal:contract", "workspace:contract",
        }
        tasks = {f":{owner}:compileKotlin" for owner in owners}
        self.assertEqual([], inspect_native_inputs(self.output(tasks)))
        self.assertTrue(inspect_native_inputs(self.output(tasks | {":query:contract:compileKotlin"})))
        for task in tasks:
            self.assertTrue(inspect_native_inputs(self.output(tasks - {task})))
        self.assertTrue(inspect_native_inputs("BUILD SUCCESSFUL"))

    def output(self, tasks):
        return "\n".join(f"{task} SKIPPED" for task in sorted(tasks))

    def test_qualified_candidate_must_also_schedule_source_obligations(self):
        self.assertIn(":sourceValidationGate", REQUIRED)
        self.assertIn(":query:service:scopedCheck", REQUIRED)
        self.assertIn(":distribution:cli:check", REQUIRED)

    def test_required_proofs_remain_in_routine_gate(self):
        self.assertEqual("complete", inspect(self.output(REQUIRED))["status"])

    def test_removing_required_proof_rejects(self):
        for task in REQUIRED:
            with self.subTest(task=task):
                self.assertEqual("rejected", inspect(self.output(REQUIRED - {task}))["status"])

    def test_empty_or_incidental_output_cannot_prove_gate(self):
        self.assertEqual("rejected", inspect("BUILD SUCCESSFUL\nrequested :productBuildGate SKIPPED")["status"])


if __name__ == "__main__":
    unittest.main()
