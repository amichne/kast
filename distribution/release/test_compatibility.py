#!/usr/bin/env python3
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from compatibility import Cause, Rejected, Version, canonical, compare, digest, latest_stable, snapshot, verify_release, write


def contract(version="1.0.0"):
    return {
        "schemaVersion": 1, "productVersion": version, "sourceRevision": "a" * 40,
        "inputs": {"schemaDigest": "sha256:" + "b" * 64, "stateManifestDigest": "sha256:" + "e" * 64},
        "contract": {
            "schema": {
                "schemaVersion": 1, "wireSchema": {"version": 1},
                "operationRegistry": {"operations": [{"operationId": "source.read", "intents": []}]},
                "cliProjection": {"commands": ["source read --anchor"]},
                "serverProjection": {"tools": [{"name": "source_read", "approvalPolicy": "none", "inputSchema": {"type": "object", "properties": {"anchor": {"type": "string"}}}, "outputSchema": {"type": "object"}}]},
            },
            "commands": {"start": {"options": ["--help", "--idea-home=<path>"]}},
            "persistedState": {"cache": {"owner.kt": "sha256:" + "c" * 64}},
            "processContract": {"successExit": 0},
        },
    }


class CompatibilityTest(unittest.TestCase):
    def assert_rejected(self, expected, call):
        with self.assertRaises(Rejected) as caught:
            call()
        self.assertEqual(expected, caught.exception.cause)

    def test_same_major_preserves_contract_and_allows_new_commands_and_description(self):
        prior = contract()
        candidate = contract("1.1.0")
        candidate["contract"]["commands"]["doctor"] = {"options": ["--help"]}
        candidate["contract"]["schema"]["serverProjection"]["tools"][0]["description"] = "Readable source"
        self.assertEqual("compatible", compare(prior, candidate)["status"])
        self.assertEqual(canonical(compare(prior, candidate)), canonical(compare(prior, candidate)))

    def test_every_promised_surface_fails_closed_on_removal_or_change(self):
        changes = [
            lambda c: c["schema"]["operationRegistry"]["operations"].clear(),
            lambda c: c["schema"]["cliProjection"]["commands"].clear(),
            lambda c: c["schema"]["serverProjection"]["tools"][0].update(approvalPolicy="explicit"),
            lambda c: c["schema"]["serverProjection"]["tools"][0]["inputSchema"]["properties"]["anchor"].update(type="integer"),
            lambda c: c["schema"]["serverProjection"]["tools"][0]["inputSchema"]["properties"]["anchor"].update(minLength=10),
            lambda c: c["commands"]["start"]["options"].remove("--idea-home=<path>"),
            lambda c: c["persistedState"]["cache"].update({"owner.kt": "sha256:" + "d" * 64}),
            lambda c: c["processContract"].update(successExit=1),
        ]
        for change in changes:
            with self.subTest(change=changes.index(change)):
                candidate = contract("1.1.0")
                change(candidate["contract"])
                with self.assertRaises(Rejected):
                    compare(contract(), candidate)

    def test_schema_property_names_are_not_mistaken_for_annotations(self):
        prior = contract()
        prior["contract"]["schema"]["serverProjection"]["tools"][0]["inputSchema"]["properties"]["description"] = {"type": "string"}
        candidate = copy.deepcopy(prior)
        candidate["productVersion"] = "1.1.0"
        del candidate["contract"]["schema"]["serverProjection"]["tools"][0]["inputSchema"]["properties"]["description"]
        self.assert_rejected(Cause.BREAKING_CHANGE, lambda: compare(prior, candidate))

    def test_next_major_requires_explicit_authority_and_reports_changes(self):
        candidate = contract("2.0.0")
        candidate["contract"]["processContract"]["successExit"] = 1
        self.assert_rejected(Cause.MAJOR_CHANGE_NOT_AUTHORIZED, lambda: compare(contract(), candidate))
        accepted = compare(contract(), candidate, allow_next_major=True)
        self.assertEqual("next-major", accepted["status"])
        self.assertTrue(accepted["changes"])
        self.assert_rejected(Cause.VERSION_NOT_FORWARD, lambda: compare(contract(), contract()))

    def test_latest_stable_is_numeric_and_excludes_previews_and_drafts(self):
        def release(tag, **extra):
            return {"tag_name": tag, "draft": False, "prerelease": False, **extra}
        releases = [release("v1.9.0"), release("v1.10.0"), release("v0.99.0"), release("v1.99.0", prerelease=True), release("v1.98.0", draft=True), release("v1")]
        self.assertEqual("v1.10.0", latest_stable(releases, 1)["tag_name"])
        self.assertIsNone(latest_stable([release("v0.32.2")], 1))

    def test_incomplete_or_ambiguous_snapshots_do_not_manufacture_compatibility(self):
        candidate = contract()
        del candidate["contract"]["persistedState"]
        self.assert_rejected(Cause.INVALID_DOCUMENT, lambda: snapshot(candidate))
        candidate = contract()
        candidate["contract"]["schema"]["serverProjection"]["tools"] *= 2
        self.assert_rejected(Cause.INVALID_DOCUMENT, lambda: snapshot(candidate))
        self.assert_rejected(Cause.INVALID_DOCUMENT, lambda: Version.parse("1.0.0-rc.1"))

    def test_first_stable_boundary_requires_release_catalog_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "candidate.json"
            catalog = [{"tag_name": "v0.32.2", "draft": False, "prerelease": False}]
            with patch("compatibility.run", return_value=json.dumps([catalog])):
                for version, expected in [("0.32.2", "pre-stable"), ("1.0.0", "first-stable")]:
                    write(path, contract(version))
                    result = verify_release(path, "amichne/kast", False)
                    self.assertEqual(expected, result["comparison"]["status"])
                    self.assertEqual({"state": "absent"}, result["baseline"])
                    self.assertEqual(digest(canonical(catalog)), result["releaseCatalogDigest"])
                write(path, contract("1.1.0"))
                self.assert_rejected(Cause.FIRST_STABLE_VERSION_INVALID, lambda: verify_release(path, "amichne/kast", False))

    def test_existing_stable_baseline_must_be_immutable_complete_and_digest_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "candidate.json"
            write(path, contract("1.1.0"))
            baseline_bytes = canonical(contract()) + b"\n"
            asset = {"name": "kast-compatibility-v1.0.0.json", "digest": digest(baseline_bytes)}
            release = {"tag_name": "v1.0.0", "draft": False, "prerelease": False, "immutable": True, "assets": [asset]}

            def release_tool(command, **kwargs):
                if command[1] == "api":
                    return json.dumps([[release]])
                self.assertEqual(["gh", "release", "download", "v1.0.0"], command[:4])
                (Path(command[-1]) / asset["name"]).write_bytes(baseline_bytes)
                return ""

            with patch("compatibility.run", side_effect=release_tool):
                result = verify_release(path, "amichne/kast", False)
                self.assertEqual("compatible", result["comparison"]["status"])
                self.assertEqual(asset["digest"], result["baseline"]["digest"])
                release["assets"] = []
                self.assert_rejected(Cause.BASELINE_UNAVAILABLE, lambda: verify_release(path, "amichne/kast", False))
                release["assets"] = [asset]
                release["immutable"] = False
                self.assert_rejected(Cause.BASELINE_UNAVAILABLE, lambda: verify_release(path, "amichne/kast", False))
                release["immutable"] = True
                asset["digest"] = "sha256:" + "0" * 64
                self.assert_rejected(Cause.BASELINE_IDENTITY_MISMATCH, lambda: verify_release(path, "amichne/kast", False))


if __name__ == "__main__":
    unittest.main()
