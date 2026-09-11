"""Receipt/oracle checks. These never claim to replace native IntelliJ evidence."""
from dataclasses import replace
import json
from pathlib import Path
import unittest

import reproduce_semantic_queries as r


class SemanticReproductionTest(unittest.TestCase):
    def setUp(self):
        self.expected = json.loads((r.FIXTURE / "expected.json").read_text())

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
        self.assertEqual(["filter_visibility", "expand_relation", "distinct_symbols"],
                         [step["type"] for step in request["steps"]])
        self.assertEqual([], request["return_fields"])

    def test_legacy_replay_preserves_original_request_and_ambiguous_catalog_rejects(self):
        case = r.Case("legacy", r.search("helper"))
        tool, command, request = r.invocation(case, r.ToolSurface.LEGACY)
        self.assertEqual(("query", ["query", "run"], case.request()), (tool, command, request))
        with self.assertRaises(ValueError):
            r.ToolSurface.admit([dict(name="query"), dict(name="search_classes")])

    def test_incomplete_empty_is_not_a_complete_negative(self):
        case = r.Case("negative", r.search("UnusedMarker"))
        complete = dict(status="complete", items=[], failures=[])
        incomplete = dict(status="qualified", items=[], failures=[], qualification=dict(knownMinimum=0, limitations=["relation-incomplete"]))
        self.assertEqual("reproduced", r.assess(case, complete, r.FIXTURE, self.expected)["finding"])
        self.assertEqual("not reproduced", r.assess(case, incomplete, r.FIXTURE, self.expected)["finding"])
        self.assertEqual("reproduced", r.assess(replace(case, reported="relation-incomplete-zero"), incomplete, r.FIXTURE, self.expected)["finding"])

    def test_equal_counts_with_wrong_identities_fail(self):
        case = r.Case("same-name", r.search("sharedOperation"), tuple(self.expected["sameName"]))
        items = [dict(type="exact-symbol", kind="function", ref=dict(kind="exact-symbol", token=f"exact:v3:test-{i}"),
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
        item = dict(type="exact-symbol", kind="function", ref=dict(kind="exact-symbol", token="exact:v3:replacement"))
        result = r.assess(case, dict(status="complete", items=[item]), r.FIXTURE, self.expected)
        self.assertFalse(result["assertions"]["opaqueReferencesPreserved"])

    def test_stale_authority_is_a_missing_replay_precondition(self):
        case = r.Case("roundtrip", dict(type="REFS", refs=["exact:v3:issued"]))
        result = r.assess(case, dict(status="rejected", rejection=dict(type="reference-rejected", reason="stale-authority")), r.FIXTURE, self.expected)
        self.assertEqual("blocked", result["finding"])
        self.assertIn("Stable host", result["prerequisite"])

    def test_preserved_token_does_not_excuse_changed_projection(self):
        issued = dict(type="exact-symbol", ref=dict(kind="exact-symbol", token="exact:v3:issued"), name="helper")
        case = r.Case("projection", dict(type="REFS", refs=["exact:v3:issued"]), select=("NAME",), issued=(issued,))
        result = r.assess(case, dict(status="complete", items=[{**issued, "name": "changed"}]), r.FIXTURE, self.expected)
        self.assertFalse(result["assertions"]["issuedProjectionsPreserved"])


if __name__ == "__main__":
    unittest.main()
