"""Receipt/oracle checks. These never claim to replace native IntelliJ evidence."""
from dataclasses import replace
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import reproduce_semantic_queries as r


class SemanticReproductionTest(unittest.TestCase):
    def setUp(self):
        self.expected = json.loads((r.FIXTURE / "expected.json").read_text())

    def test_native_pin_rejection_retains_bounded_stage(self):
        with self.assertRaisesRegex(ValueError, '^PIN_CAPTURE_REJECTED:CONFIGURATION_CAPTURE$'):
            r.reject_native_pin(dict(type='PIN_CAPTURE_REJECTED', stage='CONFIGURATION_CAPTURE'))
        for value in (dict(type='PIN_CAPTURE_REJECTED', stage='unknown'),
                      dict(type='PIN_CAPTURE_REJECTED', stage='MODEL_CAPTURE', message='payload'), {}):
            with self.assertRaisesRegex(ValueError, '^INVALID_NATIVE_PIN_REJECTION$'):
                r.reject_native_pin(value)

    def test_native_pin_selects_one_exact_executable_without_command_arguments(self):
        from argparse import Namespace
        launcher = Path('/Applications/IntelliJ IDEA.app/Contents/MacOS/idea')
        for rows, requested, selected, expected in (
            (f'123 {launcher}\n456 {launcher}-other\n', None, 123, 'PIN_CAPTURE_REJECTED:PROJECT_ADMISSION'),
            ('', None, None, 'EXACT_RUNNING_HOST_UNAVAILABLE'),
            (f'123 {launcher}\n456 {launcher}\n', None, None, 'EXACT_RUNNING_HOST_UNAVAILABLE'),
            (f'123 {launcher}-other\n', None, None, 'EXACT_RUNNING_HOST_UNAVAILABLE'),
            (f'123 {launcher}\n456 {launcher}\n', 456, 456, 'PIN_CAPTURE_REJECTED:PROJECT_ADMISSION'),
            (f'123 {launcher}\n456 {launcher}-other\n', 456, None, 'EXACT_RUNNING_HOST_UNAVAILABLE'),
            (f'123 {launcher}\n', 789, None, 'EXACT_RUNNING_HOST_UNAVAILABLE'),
            (f'123 {launcher}\n', 0, None, 'INVALID_NATIVE_HOST_PID'),
        ):
            with self.subTest(rows=rows), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                args = Namespace(output=root / 'pin', cli=Path(r.__file__), fixture=root,
                                 idea_contents=launcher.parent.parent, host_pid=requested)
                def reject_project(command, cwd):
                    self.assertEqual([launcher, 'ideScript', args.output / 'pin.kts'], command)
                    self.assertEqual(dict(project=str(root), hostPid=selected, qualificationSlice=None),
                                     json.loads((args.output / 'input.json').read_text()))
                    r.write(args.output / 'pin-rejection.json',
                            dict(type='PIN_CAPTURE_REJECTED', stage='PROJECT_ADMISSION'))
                    return dict(exitCode=0)
                with patch.object(r.subprocess, 'check_output', return_value=rows) as processes, \
                     patch.object(r, 'capture', side_effect=reject_project) as script:
                    with self.assertRaisesRegex(ValueError, '^' + expected + '$'):
                        r.pin(args)
                    processes.assert_called_once_with(['ps', '-ww', '-axo', 'pid=,comm='], text=True)
                    self.assertEqual(1 if expected.startswith('PIN_CAPTURE_REJECTED') else 0, script.call_count)

    def test_public_routes_validate_against_each_published_schema(self):
        import jsonschema
        resources = r.REPO / "app-server/src/main/resources/io/github/amichne/kast/appserver/query"
        for case in r.cases(self.expected):
            tool, command, request = r.invocation(case, r.ToolSurface.PUBLIC)
            schema = json.loads((resources / (tool + ".parameters.json")).read_text())
            with self.subTest(case=case.name):
                jsonschema.Draft202012Validator(schema).validate(request)
                self.assertEqual(["tool", tool], command)
        case = r.Case("ordered", r.search("helper"), steps=(
            dict(type="FILTER", visibility=["PRIVATE"]), dict(type="EXPAND", relation="CALLERS"),
            dict(type="DISTINCT")), select=())
        tool, command, request = r.invocation(case, r.ToolSurface.PUBLIC)
        self.assertEqual("query_symbols", tool)
        self.assertEqual("RUN", request["request"]["type"])
        self.assertEqual(["WHERE", "EXPAND_RELATION", "DISTINCT_SYMBOLS"],
                         [step["type"] for step in request["request"]["steps"]])
        self.assertEqual({"type": "VISIBILITY", "values": ["PRIVATE"]},
                         request["request"]["steps"][0]["predicate"])
        self.assertEqual({"type": "SYMBOLS", "fields": []}, request["request"]["output"])

    def test_retired_query_route_is_not_admitted(self):
        with self.assertRaises(ValueError):
            r.ToolSurface.admit([dict(name="query")])
        with self.assertRaises(ValueError):
            r.ToolSurface.admit([dict(name="query"), dict(name="query_symbols")])

    def test_incomplete_empty_is_not_a_complete_negative(self):
        case = r.Case("negative", r.search("UnusedMarker"))
        complete = dict(status="complete", items=[], failures=[])
        incomplete = dict(status="qualified", items=[], failures=[], qualification=dict(knownMinimum=0, limitations=["relation-incomplete"]))
        self.assertEqual("reproduced", r.assess(case, complete, r.FIXTURE, self.expected)["finding"])
        self.assertEqual("not reproduced", r.assess(case, incomplete, r.FIXTURE, self.expected)["finding"])
        self.assertEqual("reproduced", r.assess(replace(case, reported="relation-incomplete-zero"), incomplete, r.FIXTURE, self.expected)["finding"])

    def test_equal_counts_with_wrong_identities_fail(self):
        case = r.Case("same-name", r.search("sharedOperation"), tuple(self.expected["sameName"]))
        items = [dict(type="exact-symbol", kind="function", ref=f"exact:v3:test-{i}",
                      signature=dict(qualifiedIdentity="wrong")) for i in range(5)]
        result = r.assess(case, dict(status="complete", items=items), r.FIXTURE, self.expected)
        self.assertFalse(result["assertions"]["exactIdentities"])
        self.assertEqual("not reproduced", result["finding"])

    def test_reported_all_failures_are_independent_and_selected_alias_result_is_small(self):
        cases = r.cases(self.expected)
        all_cases = [c for c in cases if c.source["type"] == "ALL"]
        self.assertEqual(3, len(all_cases))
        self.assertEqual(["DIRECTORY", "DIRECTORY", "PACKAGE"], [c.source["scope"]["type"] for c in all_cases])
        self.assertEqual(["RECURSIVE", "DIRECT", "DIRECT"], [c.source["scope"]["containment"] for c in all_cases])
        self.assertEqual(("repro.core.TraceLabel",), all_cases[2].identities)
        self.assertEqual(["TYPE_ALIAS"], all_cases[2].source["kinds"])

    def test_authored_occurrence_locations_are_exact_and_distinct(self):
        for key, count in (("helperOccurrences", 5), ("aliasOccurrences", 6)):
            locations = r.expected_occurrences(r.FIXTURE, self.expected[key])
            self.assertEqual(count, len(set(locations)))
            for file, start, end in locations:
                self.assertEqual(self.expected[key]["text"], Path(file).read_text()[start:end])

    def test_returned_token_must_be_preserved_verbatim(self):
        case = r.Case("roundtrip", dict(type="REFS", refs=["exact:v3:issued"]), tokens=("exact:v3:issued",), select=())
        item = dict(type="exact-symbol", kind="function", ref="exact:v3:replacement")
        result = r.assess(case, dict(status="complete", items=[item]), r.FIXTURE, self.expected)
        self.assertFalse(result["assertions"]["opaqueReferencesPreserved"])

    def test_stale_authority_is_a_missing_replay_precondition(self):
        case = r.Case("roundtrip", dict(type="REFS", refs=["exact:v3:issued"]))
        result = r.assess(case, dict(status="rejected", rejection=dict(type="reference-rejected", reason="stale-authority")), r.FIXTURE, self.expected)
        self.assertEqual("blocked", result["finding"])
        self.assertIn("Stable host", result["prerequisite"])

    def test_preserved_token_does_not_excuse_changed_projection(self):
        issued = dict(type="exact-symbol", ref="exact:v3:issued", name="helper")
        case = r.Case("projection", dict(type="REFS", refs=["exact:v3:issued"]), select=("NAME",), issued=(issued,))
        result = r.assess(case, dict(status="complete", items=[{**issued, "name": "changed"}]), r.FIXTURE, self.expected)
        self.assertFalse(result["assertions"]["issuedProjectionsPreserved"])


if __name__ == "__main__":
    unittest.main()
