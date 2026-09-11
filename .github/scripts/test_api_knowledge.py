#!/usr/bin/env python3
"""Behavioral tests for the disclosure contract; no Dokka or semantic extraction claims."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import api_knowledge as api


def declaration(dri: str = "example/Parser.parse(String)") -> dict:
    return {
        "projectPath": ":kernel", "sourceSet": "main", "dri": dri,
        "name": "Parser.parse", "signature": "fun parse(raw: String): ParseResult",
        "sourcePath": "kernel/src/main/kotlin/example/Parser.kt", "sourceSha256": "a" * 64,
        "sections": [
            {"kind": "description", "text": "Parse one boundary value.\n\nA successful parse preserves proof."},
            {"kind": "param", "text": "raw: untrusted input; do not pre-normalize."},
            {"kind": "return", "text": "Either an admitted value or a closed rejection."},
            {"kind": "throws", "text": "CancellationException: propagate cancellation."},
            {"kind": "implementation", "text": "IMPLEMENTATION_ONLY\n\nThe adapter reads saved content."},
            {"kind": "sample", "text": "SAMPLE_ONLY\n\n```kotlin\nparse(\"demo\")\n```"},
            {"kind": "custom", "text": "@ownership: request-local; this unknown tag remains on the card."},
        ],
    }


def payload(*items: dict) -> dict:
    return {"formatVersion": 1, "producer": "hand-authored-contract-fixture/1", "declarations": list(items or [declaration()])}


def encode(value: object) -> bytes:
    return json.dumps(value).encode()


class DisclosureTest(unittest.TestCase):
    def admitted(self, value: dict) -> api.Generated:
        result = api.generate(encode(value))
        self.assertIsInstance(result, api.Generated, result)
        return result

    def documents(self, value: dict) -> dict[str, dict]:
        return {path: json.loads(data) for path, data in self.admitted(value).files if path.endswith(".json")}

    def rejected(self, value: object, code: api.FailureCode = api.FailureCode.INPUT) -> None:
        result = api.generate(encode(value))
        self.assertIsInstance(result, api.Rejected, result)
        self.assertEqual(code, result.code)

    def test_index_has_only_descriptors_and_no_bodies(self):
        docs = self.documents(payload())
        common = {"formatVersion", "snapshot", "producer", "path", "kind", "title", "summary", "summary_is_excerpt"}
        for resource in docs.values():
            if resource["kind"] != "index":
                continue
            self.assertEqual(common | {"children"}, set(resource))
            for child in resource["children"]:
                self.assertEqual({"path", "kind", "title", "summary", "summary_is_excerpt"}, set(child))
            text = json.dumps(resource)
            for forbidden in ["IMPLEMENTATION_ONLY", "SAMPLE_ONLY", "CancellationException", "fun parse", "do not pre-normalize"]:
                self.assertNotIn(forbidden, text)

    def test_contract_obligations_never_move_to_detail(self):
        docs = self.documents(payload())
        card = next(doc for doc in docs.values() if doc["kind"] == "card")
        self.assertEqual(["description", "param", "return", "throws", "custom"], [s["kind"] for s in card["sections"]])
        self.assertNotIn("IMPLEMENTATION_ONLY", json.dumps(card))
        self.assertNotIn("SAMPLE_ONLY", json.dumps(card))
        self.assertEqual(2, len(card["details"]))
        for detail in card["details"]:
            self.assertEqual(card["path"], docs[detail["path"]]["owner"]["path"])

    def test_every_input_section_has_exactly_one_payload_owner(self):
        item = declaration()
        docs = self.documents(payload(item))
        sections = []
        for doc in docs.values():
            if doc["kind"] == "card":
                sections.extend(doc["sections"])
            elif doc["kind"] == "detail":
                sections.append(doc["section"])
        expected = [{"ordinal": i, **section} for i, section in enumerate(item["sections"])]
        self.assertEqual(expected, sorted(sections, key=lambda s: s["ordinal"]))

    def test_navigation_walk_reaches_every_resource_once(self):
        docs = self.documents(payload())
        seen: set[str] = set()
        pending = ["index.json"]
        while pending:
            path = pending.pop()
            self.assertNotIn(path, seen, "Ownership traversal must not cycle or multiply-own resources")
            seen.add(path)
            doc = docs[path]
            edges = doc.get("children", doc.get("details", []))
            for summary in edges:
                target = docs[summary["path"]]
                self.assertEqual(summary, {key: target[key] for key in summary})
                self.assertIn(target["kind"], {"index", "card"} if doc["kind"] == "index" else {"detail"})
                pending.append(summary["path"])
        self.assertEqual(set(docs), seen)

    def test_all_markdown_navigation_links_resolve(self):
        import posixpath
        import re
        files = dict(self.admitted(payload()).files)
        for path, data in files.items():
            if path.endswith(".md"):
                for link in re.findall(r"\]\(([^)]+)\)", data.decode()):
                    self.assertIn(posixpath.normpath(posixpath.join(posixpath.dirname(path), link)), files)

    def test_more_than_one_full_level_of_fanout_is_bounded_without_omission(self):
        items = [declaration(f"overload/{i}") for i in range(api.FANOUT ** 2 + 1)]
        docs = self.documents(payload(*items))
        self.assertEqual(len(items), sum(d["kind"] == "card" for d in docs.values()))
        self.assertTrue(all(len(d["children"]) <= api.FANOUT for d in docs.values() if d["kind"] == "index"))

    def test_many_modules_do_not_overflow_root(self):
        items = []
        for i in range(api.FANOUT + 1):
            item = declaration()
            item["projectPath"] = f":module-{i}"
            items.append(item)
        docs = self.documents(payload(*items))
        self.assertLessEqual(len(docs["index.json"]["children"]), api.FANOUT)
        self.assertEqual(len(items), sum(d["kind"] == "card" for d in docs.values()))

    def test_overloads_and_source_sets_remain_distinct(self):
        first, second, third = declaration(), declaration("example/Parser.parse(Int)"), declaration()
        third["sourceSet"] = "test"
        docs = self.documents(payload(first, second, third))
        self.assertEqual(3, len({d["declarationId"] for d in docs.values() if d["kind"] == "card"}))

    def test_duplicate_identity_rejected(self):
        self.rejected(payload(declaration(), declaration()), api.FailureCode.DUPLICATE)

    def test_duplicate_json_key_rejected(self):
        result = api.generate(b'{"formatVersion": 1, "formatVersion": 1, "producer": "fixture", "declarations": []}')
        self.assertEqual(api.FailureCode.INPUT, result.code)

    def test_order_independent_reproducible_bytes(self):
        first, second = declaration(), declaration("other")
        self.assertEqual(self.admitted(payload(first, second)), self.admitted(payload(second, first)))

    def test_all_resources_share_input_snapshot(self):
        docs = self.documents(payload())
        self.assertEqual(1, len({d["snapshot"] for d in docs.values()}))
        changed = payload()
        changed["declarations"][0]["sourceSha256"] = "b" * 64
        self.assertNotEqual(docs["index.json"]["snapshot"], self.documents(changed)["index.json"]["snapshot"])

    def test_card_identity_survives_doc_edit(self):
        original, changed = payload(), payload()
        changed["declarations"][0]["sections"][0]["text"] = "Revised documentation."
        cards = lambda p: {d["declarationId"] for d in self.documents(p).values() if d["kind"] == "card"}
        self.assertEqual(cards(original), cards(changed))
        self.assertNotEqual(self.admitted(original), self.admitted(changed))

    def test_undocumented_declaration_is_present(self):
        item = declaration()
        item["sections"] = []
        card = next(d for d in self.documents(payload(item)).values() if d["kind"] == "card")
        self.assertEqual("undocumented", card["documentationStatus"])
        self.assertEqual([], card["sections"])

    def test_long_summary_is_explicit_excerpt_but_complete_contract_survives(self):
        item = declaration()
        text = "長" * (api.SUMMARY_CHARACTERS + 1)
        item["sections"][0]["text"] = text
        card = next(d for d in self.documents(payload(item)).values() if d["kind"] == "card")
        self.assertTrue(card["summary_is_excerpt"])
        self.assertEqual(api.SUMMARY_CHARACTERS, len(card["summary"]))
        self.assertEqual(text, card["sections"][0]["text"])

    def test_summary_cannot_inject_markdown_structure(self):
        item = declaration()
        item["sections"][0]["text"] = "[read](../../secret) <script> # heading\n```code```"
        for path, data in self.admitted(payload(item)).files:
            if path.endswith("index.md"):
                self.assertNotIn("[read](../../secret)", data.decode())
                self.assertNotIn("<script>", data.decode())
                self.assertNotIn("\n```", data.decode())

    def test_contract_overflow_rejected_not_hidden_or_truncated(self):
        item = declaration()
        item["sections"][1]["text"] = "x" * api.PAGE_BYTES["card"]
        self.rejected(payload(item), api.FailureCode.LIMIT)

    def test_detail_overflow_rejected_not_truncated(self):
        item = declaration()
        item["sections"][4]["text"] = "x" * api.PAGE_BYTES["detail"]
        self.rejected(payload(item), api.FailureCode.LIMIT)

    def test_unknown_fields_rejected_at_every_ingress_level(self):
        for target in ["root", "declaration", "section"]:
            with self.subTest(target=target):
                value = payload()
                obj = value if target == "root" else value["declarations"][0]
                if target == "section":
                    obj = obj["sections"][0]
                obj["body"] = "must not smuggle in a second disclosure channel"
                self.rejected(value)

    def test_wrong_scalar_shapes_and_nonfinite_values_rejected(self):
        for wrong in [None, [], {}, True, 4, float("nan")]:
            with self.subTest(wrong=wrong):
                value = payload()
                value["declarations"][0]["name"] = wrong
                self.rejected(value)

    def test_invalid_inventory_and_versions_rejected(self):
        for value in [None, [], {}, {**payload(), "declarations": []}, {**payload(), "formatVersion": True},
                      {**payload(), "formatVersion": 2}]:
            with self.subTest(value=value):
                self.rejected(value)

    def test_unsafe_paths_and_digest_rejected(self):
        for field, value in [("sourcePath", "../outside.kt"), ("sourcePath", "/absolute.kt"),
                             ("sourcePath", "a//b.kt"), ("sourcePath", "a\\b.kt"),
                             ("sourcePath", "a/%2e%2e/b.kt"), ("sourceSha256", "head"),
                             ("projectPath", ":../oops"), ("sourceSet", "../main")]:
            with self.subTest(field=field, value=value):
                item = declaration()
                item[field] = value
                self.rejected(payload(item))

    def test_unknown_section_never_disappears(self):
        item = declaration()
        item["sections"].append({"kind": "unknown", "text": "Do not lose this."})
        self.rejected(payload(item))

    def test_invalid_unicode_and_input_budget_rejected(self):
        for raw in [b"\xff", b"[", b"x" * (api.MAX_INPUT_BYTES + 1)]:
            self.assertIsInstance(api.generate(raw), api.Rejected)
        item = declaration()
        item["sections"][0]["text"] = "\ud800"
        self.rejected(payload(item))

    def test_cli_stages_and_checks_without_overwriting(self):
        script = str(Path(api.__file__))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "input.json", root / "bundle"
            source.write_bytes(encode(payload()))
            run = lambda *extra: subprocess.run([sys.executable, script, str(source), str(output), *extra], capture_output=True)
            self.assertEqual(0, run().returncode)
            self.assertEqual(0, run("--check").returncode)
            self.assertEqual(1, run().returncode, "Never overwrite an existing tree")
            (output / "index.md").write_text("leaked implementation")
            self.assertEqual(1, run("--check").returncode)

    def test_parity_rejects_stale_files_missing_files_and_symlinks(self):
        generated = self.admitted(payload())
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for path, data in generated.files:
                target = output / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            self.assertIsNone(api.check(generated, output))
            stale = output / "stale.md"
            stale.write_text("old API")
            self.assertEqual(api.FailureCode.OUTPUT, api.check(generated, output).code)
            stale.unlink()
            stale.symlink_to(output / "index.md")
            self.assertEqual(api.FailureCode.OUTPUT, api.check(generated, output).code)
            stale.unlink()
            (output / "index.md").unlink()
            self.assertEqual(api.FailureCode.OUTPUT, api.check(generated, output).code)


if __name__ == "__main__":
    unittest.main()
