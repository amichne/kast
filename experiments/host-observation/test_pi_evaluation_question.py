"""Independent JSON Schema oracle for pure canonical partial-boundary admission."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import unittest

from jsonschema import Draft202012Validator


class PiQuestionAdmissionTest(unittest.TestCase):
    def assert_node_admission(self, owner, cases, expected):
        here = Path(__file__).parent
        node = shutil.which("node")
        self.assertIsNotNone(node)
        script = f"""
import {{{owner}}} from {json.dumps((here / 'pi_evaluation_question.mjs').resolve().as_uri())};
import fs from 'node:fs';
console.log(JSON.stringify(JSON.parse(fs.readFileSync(0,'utf8')).map({owner})));
"""
        result = subprocess.run([node, "--input-type=module", "-e", script], input=json.dumps(cases), text=True, capture_output=True, timeout=10, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), expected)

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
        self.assert_node_admission("admittedQuestion", cases, expected)

    def test_complete_result_boundary_and_nested_type_mutations_match_independent_schema(self):
        here = Path(__file__).parent
        validator = Draft202012Validator(json.loads((here / "pi-fixtures/query-result.schema.json").read_text()))
        public = json.loads((here.parents[1] / "cli/src/test/resources/query-presentation-native.json").read_text())
        seed = json.loads((here / "pi-fixtures/query-delivery-cases.json").read_text())["cases"]["rejectedProof"]["result"]
        originals = [seed["initial"]["document"], seed["pages"][0]["document"]]
        originals += [case["reply"]["document"] for case in public["cases"].values()]
        self.assertTrue(all(validator.is_valid(case) for case in originals))
        cases = copy.deepcopy(originals)

        def mutations(value, path=()):
            if isinstance(value, dict):
                yield path + ("unknown_property",), True
                for key, child in value.items():
                    yield from mutations(child, path + (key,))
            elif isinstance(value, list):
                yield path, True
                if value:
                    yield from mutations(value[0], path + (0,))
            else:
                yield path, "wrong-type" if isinstance(value, (bool, int, float)) else True

        # One authoritative example per supported boundary covers rejection,
        # exact-symbol, reference-occurrence and nested ownership/signature data.
        for original in [originals[0], originals[1], public["cases"]["occurrences"]["reply"]["document"]]:
            compact = copy.deepcopy(original)
            if "items" in compact:
                compact["items"] = compact["items"][:1]
            for path, replacement in mutations(compact):
                case = copy.deepcopy(compact)
                target = case
                for segment in path[:-1]:
                    target = target[segment]
                target[path[-1]] = replacement
                cases.append(case)
        expected = [validator.is_valid(case) for case in cases]
        self.assertTrue(any(expected) and not all(expected))
        self.assert_node_admission("admittedQueryDocument", cases, expected)


if __name__ == "__main__":
    unittest.main()
