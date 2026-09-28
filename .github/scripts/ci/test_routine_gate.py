import unittest
from routine_gate import inspect, REQUIRED


class RoutineGateTest(unittest.TestCase):
    def output(self, tasks):
        return "\n".join(f"{task} SKIPPED" for task in sorted(tasks))

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
