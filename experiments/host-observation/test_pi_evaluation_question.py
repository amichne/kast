"""Independent JSON Schema oracle for pure canonical question admission."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import unittest

from jsonschema import Draft202012Validator


class PiQuestionAdmissionTest(unittest.TestCase):
    def test_canonical_questions_and_malformed_scope_match_independent_schema(self):
        here = Path(__file__).parent
        schema = json.loads((here / "pi-fixtures/query-question.schema.json").read_text())
        validator = Draft202012Validator(schema)
        public = json.loads((here.parents[1] / "cli/src/test/resources/query-presentation-native.json").read_text())
        seed = json.loads((here / "pi-fixtures/query-delivery-cases.json").read_text())["cases"]["rejectedProof"]["result"]["initial"]["document"]["rejection"]["detail"]["evidence"]["question"]
        cases = [seed] + [item["reply"]["document"]["question"] for item in public["cases"].values()]
        self.assertTrue(all(validator.is_valid(case) for case in cases))
        for key, value in [("from", True), ("output", True), ("completion", {}), ("steps", [True]), ("steps", [None]), ("extra", "unknown")]:
            case = copy.deepcopy(seed)
            case[key] = value
            cases.append(case)
        for fields in [["UNKNOWN"], ["name", "name"], [None], "name"]:
            case = copy.deepcopy(seed)
            case["output"]["fields"] = fields
            cases.append(case)
        case = copy.deepcopy(seed)
        case["from"]["offset"] = -1
        cases.append(case)
        expected = [validator.is_valid(case) for case in cases]
        node = shutil.which("node")
        self.assertIsNotNone(node)
        script = f"""
import {{admittedQuestion}} from {json.dumps((here / 'pi_evaluation_question.mjs').resolve().as_uri())};
import fs from 'node:fs';
console.log(JSON.stringify(JSON.parse(fs.readFileSync(0,'utf8')).map(admittedQuestion)));
"""
        result = subprocess.run([node, "--input-type=module", "-e", script], input=json.dumps(cases), text=True, capture_output=True, timeout=10, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), expected)


if __name__ == "__main__":
    unittest.main()
