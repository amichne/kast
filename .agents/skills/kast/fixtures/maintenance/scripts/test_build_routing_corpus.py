import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("build-routing-corpus.py")
SPEC = importlib.util.spec_from_file_location("build_routing_corpus", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ParseHtmlExportTest(unittest.TestCase):
    def write_html(self, body: str) -> Path:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "copilot-session-sample.html"
        path.write_text(body, encoding="utf-8")
        return path

    def test_html_export_uses_visible_prompt_and_flags_semantic_abandonment(self) -> None:
        path = self.write_html(
            """
            <html>
              <head><title>Copilot CLI Session</title></head>
              <body>
                <div>#1</div><div>User</div><div>interactive</div><div>38s</div>
                <div>Help me understand this Kotlin file and trace where EventBean.date is set.</div>
                <div>#2</div><div>skill - kast</div><div>39s</div><div>{"skill":"kast"}</div>
                <div>#3</div><div>bash - resolve EventBean</div><div>40s</div>
                <div>$ "$KAST_CLI_PATH" skill resolve '{"symbol":"EventBean"}'</div>
                <div>#4</div><div>bash - inspect references</div><div>41s</div>
                <div>$ "$KAST_CLI_PATH" skill references '{"symbol":"EventBean"}'</div>
                <div>#5</div><div>bash - fallback grep</div><div>42s</div>
                <div>$ grep -rn "EventBean\\(" features/timeline/src/main</div>
              </body>
            </html>
            """.strip(),
        )

        cases = MODULE.parse_html_export(path)

        self.assertEqual(1, len(cases))
        case = cases[0]
        self.assertEqual(
            "Help me understand this Kotlin file and trace where EventBean.date is set.",
            case.prompt,
        )
        self.assertEqual("semantic-abandonment", case.classification)
        self.assertEqual(["kast"], case.loaded_skills)
        self.assertEqual({"skill": 1, "bash": 3}, case.tool_counts)
        self.assertIn("kast_command_blocks=2", case.evidence)
        self.assertIn("grep_like_commands=1", case.evidence)

    def test_html_export_flags_trigger_miss_from_user_prompt(self) -> None:
        path = self.write_html(
            """
            <html>
              <head><title>Copilot CLI Session</title></head>
              <body>
                <div>#1</div><div>User</div><div>interactive</div><div>12s</div>
                <div>Help me understand this Kotlin file and trace the flow through the processors.</div>
                <div>#2</div><div>bash - list files</div><div>18s</div>
                <div>$ rg "Processor" features/timeline/src/main</div>
              </body>
            </html>
            """.strip(),
        )

        cases = MODULE.parse_html_export(path)

        self.assertEqual(1, len(cases))
        case = cases[0]
        self.assertEqual(
            "Help me understand this Kotlin file and trace the flow through the processors.",
            case.prompt,
        )
        self.assertEqual("trigger-miss", case.classification)
        self.assertEqual([], case.loaded_skills)
        self.assertEqual({"bash": 1}, case.tool_counts)

    def test_html_export_flags_schema_friction_before_grep_fallback(self) -> None:
        path = self.write_html(
            """
            <html>
              <head><title>Copilot CLI Session</title></head>
              <body>
                <div>#1</div><div>User</div><div>interactive</div><div>12s</div>
                <div>Keep tracing this Kotlin symbol with Kast after my jq projection came back empty.</div>
                <div>#2</div><div>skill - kast</div><div>13s</div><div>{"skill":"kast"}</div>
                <div>#3</div><div>bash - references with jq</div><div>14s</div>
                <div>$ "$KAST_CLI_PATH" skill references '{"symbol":"EventBean"}' | jq '.references[].file_path'</div>
                <div>empty result; maybe wrapper fields are snake_case but nested filePath is camelCase</div>
              </body>
            </html>
            """.strip(),
        )

        cases = MODULE.parse_html_export(path)

        self.assertEqual(1, len(cases))
        case = cases[0]
        self.assertEqual("schema-friction", case.classification)
        self.assertIn("schema_shape_frictions=1", case.evidence)

    def test_html_export_flags_mutation_validation_failure(self) -> None:
        path = self.write_html(
            """
            <html>
              <head><title>Copilot CLI Session</title></head>
              <body>
                <div>#1</div><div>User</div><div>interactive</div><div>12s</div>
                <div>Use Kast write-and-validate to replace this Kotlin range.</div>
                <div>#2</div><div>skill - kast</div><div>13s</div><div>{"skill":"kast"}</div>
                <div>#3</div><div>bash - write and validate</div><div>14s</div>
                <div>$ "$KAST_CLI_PATH" skill write-and-validate '{"type":"REPLACE_RANGE_REQUEST"}'</div>
                <div>VALIDATION_ERROR: Missing expected hash for edited file</div>
              </body>
            </html>
            """.strip(),
        )

        cases = MODULE.parse_html_export(path)

        self.assertEqual(1, len(cases))
        case = cases[0]
        self.assertEqual("mutation-validation-friction", case.classification)
        self.assertIn("mutation_validation_failures=1", case.evidence)

    def test_html_export_flags_maintenance_thrash_after_skill_load(self) -> None:
        path = self.write_html(
            """
            <html>
              <head><title>Copilot CLI Session</title></head>
              <body>
                <div>#1</div><div>User</div><div>interactive</div><div>12s</div>
                <div>Trace this Kotlin flow through the processors.</div>
                <div>#2</div><div>skill - kast</div><div>13s</div><div>{"skill":"kast"}</div>
                <div>#3</div><div>view - read contract fixture</div><div>14s</div>
                <div>/repo/.agents/skills/kast/fixtures/maintenance/references/wrapper-openapi.yaml</div>
              </body>
            </html>
            """.strip(),
        )

        cases = MODULE.parse_html_export(path)

        self.assertEqual(1, len(cases))
        case = cases[0]
        self.assertEqual("maintenance-thrash", case.classification)
        self.assertIn("contract_reference_reads=1", case.evidence)

    def test_semantic_abandonment_becomes_promotion_candidate(self) -> None:
        cases = [
            MODULE.RoutingCase(
                source_type="session-export-html",
                source_name="copilot-session-sample.html",
                session_id=None,
                prompt="Audit EventBean construction in Kotlin.",
                classification="semantic-abandonment",
                loaded_skills=["kast"],
                custom_agent=None,
                tool_counts={"skill": 1, "bash": 2},
                available_tools=[],
                evidence=[],
            ),
        ]

        promotions = MODULE.build_promotion_candidates(cases)

        self.assertEqual(1, len(promotions["evals"]))
        self.assertEqual("kast", promotions["evals"][0]["expected_skill"])
        self.assertEqual(
            "semantic-abandonment",
            promotions["evals"][0]["derived_from"]["classification"],
        )


if __name__ == "__main__":
    unittest.main()
