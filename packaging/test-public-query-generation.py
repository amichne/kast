#!/usr/bin/env python3
"""Focused rejection checks for the authored public-tool generator."""

import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GENERATOR = ROOT / "packaging/generate-public-query.py"
SCHEMA = ROOT / "app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json"
module_spec = importlib.util.spec_from_file_location("public_query_generator", GENERATOR)
generator = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(generator)


class PublicQueryGenerationTest(unittest.TestCase):
    def setUp(self):
        self.authority = json.loads(SCHEMA.read_text())

    def test_missing_and_invalid_control_defaults_reject(self):
        for replacement in (None, 0, 1001):
            with self.subTest(replacement=replacement):
                authority = copy.deepcopy(self.authority)
                depth = authority["$defs"]["WalkDepth"]
                if replacement is None:
                    del depth["default"]
                else:
                    depth["default"] = replacement
                with self.assertRaisesRegex(ValueError, "default"):
                    generator.validate_authority(authority)

    def test_conflicting_requiredness_rejects(self):
        self.authority["$defs"]["Walk"]["required"].append("maximumDepth")
        with self.assertRaisesRegex(ValueError, "permit omission"):
            generator.validate_authority(self.authority)

    def test_unresolved_reference_rejects(self):
        self.authority["$defs"]["Walk"]["properties"]["maximumDepth"]["$ref"] = "#/$defs/NoSuchDepth"
        with self.assertRaisesRegex(ValueError, "unresolved"):
            generator.validate_authority(self.authority)

    def test_unbound_advertised_tool_rejects(self):
        self.authority["tools"].append({"name": "unknown", "operation": "unknown.run", "schema": {}})
        with self.assertRaisesRegex(ValueError, "Unbound advertised tool"):
            generator.validate_authority(self.authority)

    def test_unbound_support_tool_rejects(self):
        self.authority["supportTools"][0]["binding"] = "UnknownRequest"
        with self.assertRaisesRegex(ValueError, "Unbound support tool"):
            generator.validate_authority(self.authority)


if __name__ == "__main__":
    unittest.main()
