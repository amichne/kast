import unittest
from routine_gate import inspect, REQUIRED, QUALIFICATION


class RoutineGateTest(unittest.TestCase):
    def output(self, tasks):
        return "\n".join(f"{task} SKIPPED" for task in sorted(tasks))

    def test_required_proofs_remain_in_routine_gate(self):
        self.assertEqual("complete", inspect(self.output(REQUIRED))["status"])

    def test_each_qualification_task_rejects_even_with_required_proofs(self):
        for task in QUALIFICATION:
            with self.subTest(task=task):
                report = inspect(self.output(REQUIRED | {task}))
                self.assertEqual([{"condition": "RUNTIME_QUALIFICATION_REQUIRED", "task": task}], report["findings"])

    def test_removing_required_proof_rejects(self):
        for task in REQUIRED:
            with self.subTest(task=task):
                self.assertEqual("rejected", inspect(self.output(REQUIRED - {task}))["status"])

    def test_empty_or_incidental_output_cannot_prove_gate(self):
        self.assertEqual("rejected", inspect("BUILD SUCCESSFUL\nrequested :productBuildGate SKIPPED")["status"])


if __name__ == "__main__":
    unittest.main()
