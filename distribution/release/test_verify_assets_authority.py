#!/usr/bin/env python3

from __future__ import annotations

import copy
from contextlib import redirect_stderr
import io
from unittest import mock
import subprocess
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("verify_assets.py")
SPEC = importlib.util.spec_from_file_location("verify_assets", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
verify_assets = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = verify_assets
SPEC.loader.exec_module(verify_assets)


SOURCE_REVISION = "a" * 40
VERSION = "1.2.3"


def canonical_document(report: bytes) -> dict[str, object]:
    guide_content = "# Canonical guidance\n"
    return {
        "schemaVersion": 1,
        "productVersion": VERSION,
        "sourceRevision": SOURCE_REVISION,
        "architectureVerification": {
            "schemaVersion": 1,
            "taskPath": ":verifyKastArchitecture",
            "status": "ACCEPTED",
            "findings": [],
            "reportSha256": "sha256:" + hashlib.sha256(report).hexdigest(),
        },
        "architecturePolicy": {
            "schemaVersion": 2,
            "modules": [
                {
                    "id": "KERNEL",
                    "projectPath": ":kernel",
                    "lifecycle": "ACTIVE",
                    "role": "PURE_CONTRACT",
                    "cost": "LOW",
                    "roleConvention": {"kind": "REQUIRED", "pluginId": "kast.pure"},
                    "allowedProjectDependencies": [],
                    "allowedEffects": [],
                    "allowedScopedEffects": [],
                },
                {
                    "id": "CLI",
                    "projectPath": ":cli",
                    "lifecycle": "ACTIVE",
                    "role": "APPLICATION",
                    "cost": "MEDIUM",
                    "roleConvention": {"kind": "REQUIRED", "pluginId": "kast.application"},
                    "allowedProjectDependencies": [":kernel"],
                    "allowedEffects": ["FILESYSTEM_READ"],
                    "allowedScopedEffects": [
                        {"effect": "PROCESS_EXECUTION", "callerClasses": ["CliAdapter"]}
                    ],
                },
            ],
        },
        "observedProjectDependencies": [
            {"consumerProjectPath": ":cli", "dependencyProjectPath": ":kernel"}
        ],
        "observedExportedProjectDependencies": [],
        "agentGuides": [
            {
                "path": "AGENTS.md",
                "scopeDirectory": ".",
                "sha256": "sha256:" + hashlib.sha256(guide_content.encode()).hexdigest(),
                "content": guide_content,
            }
        ],
        "moduleGuideBindings": [
            {
                "projectPath": ":kernel",
                "moduleDirectory": "kernel",
                "governingAgentGuidePaths": ["AGENTS.md"],
                "descendantAgentGuidePaths": [],
            },
            {
                "projectPath": ":cli",
                "moduleDirectory": "cli",
                "governingAgentGuidePaths": ["AGENTS.md"],
                "descendantAgentGuidePaths": [],
            },
        ],
    }


class RegenerationObservationTest(unittest.TestCase):
    def test_generation_reports_success_and_failure_without_source_payloads(self):
        for exit_code in (0, 1):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                (root / '.github/scripts/release').mkdir(parents=True)
                (root / '.github/scripts/release/admit-source.sh').touch()
                (root / 'gradlew').touch()
                reports = root / 'build/reports/kast-architecture'
                reports.mkdir(parents=True)
                for name in ('kast-module-knowledge.json', 'verifyKastArchitecture.json'):
                    (reports / name).write_text('{}')
                observation = io.StringIO()
                responses = [subprocess.CompletedProcess([], 0, SOURCE_REVISION, ''),
                             subprocess.CompletedProcess([], exit_code, '', '')]
                with mock.patch.object(verify_assets.subprocess, 'run', side_effect=responses), redirect_stderr(observation):
                    if exit_code:
                        with self.assertRaises(verify_assets.ReleaseRejected):
                            verify_assets.regenerate_module_knowledge(root, VERSION, SOURCE_REVISION)
                    else:
                        verify_assets.regenerate_module_knowledge(root, VERSION, SOURCE_REVISION)
                events = [json.loads(line) for line in observation.getvalue().splitlines()]
                self.assertEqual(['started', 'rejected' if exit_code else 'passed'], [event['outcome'] for event in events])
                self.assertEqual({'stage', 'outcome', 'elapsedMilliseconds'}, set(events[1]))
                self.assertGreaterEqual(events[1]['elapsedMilliseconds'], 0)


class ModuleKnowledgeAuthorityTest(unittest.TestCase):
    def test_source_generated_knowledge_is_the_semantic_authority(self) -> None:
        report_bytes = b'{"schemaVersion":1,"status":"ACCEPTED","findings":[]}\n'
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            report = root / "verifyKastArchitecture.json"
            report.write_bytes(report_bytes)
            canonical = root / "canonical.json"
            document = canonical_document(report_bytes)
            canonical.write_text(
                json.dumps(document, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            artifact = root / "kast-module-knowledge-v1.2.3.json"
            artifact.write_bytes(canonical.read_bytes())
            verify_assets.write_checksum(artifact)
            verify_assets.verify_module_knowledge(
                artifact, canonical, report, VERSION, SOURCE_REVISION
            )

            def mutate(label: str, operation) -> None:
                with self.subTest(label=label):
                    changed = copy.deepcopy(document)
                    operation(changed)
                    artifact.write_text(
                        json.dumps(changed, separators=(",", ":")) + "\n",
                        encoding="utf-8",
                    )
                    verify_assets.write_checksum(artifact)
                    verify_assets.verify_checksum(artifact)
                    with self.assertRaises(verify_assets.ReleaseRejected):
                        verify_assets.verify_module_knowledge(
                            artifact, canonical, report, VERSION, SOURCE_REVISION
                        )

            mutations = {
                "module id": lambda value: value["architecturePolicy"]["modules"][0].update(id="CHANGED"),
                "module set": lambda value: value["architecturePolicy"]["modules"].pop(),
                "module order": lambda value: value["architecturePolicy"]["modules"].reverse(),
                "module role": lambda value: value["architecturePolicy"]["modules"][0].update(role="APPLICATION"),
                "module lifecycle": lambda value: value["architecturePolicy"]["modules"][0].update(lifecycle="RETIRED"),
                "module cost": lambda value: value["architecturePolicy"]["modules"][0].update(cost="HIGH"),
                "module convention": lambda value: value["architecturePolicy"]["modules"][0]["roleConvention"].update(pluginId="attacker"),
                "allowed effects": lambda value: value["architecturePolicy"]["modules"][0]["allowedEffects"].append("PROCESS_EXECUTION"),
                "scoped effects": lambda value: value["architecturePolicy"]["modules"][1]["allowedScopedEffects"][0]["callerClasses"].append("Attacker"),
                "dependencies": lambda value: value["architecturePolicy"]["modules"][0]["allowedProjectDependencies"].append(":cli"),
                "guide contents": lambda value: value["agentGuides"][0].update(content="# Attacker\n", sha256="sha256:" + hashlib.sha256(b"# Attacker\n").hexdigest()),
                "guide bindings": lambda value: value["moduleGuideBindings"][0]["governingAgentGuidePaths"].clear(),
                "architecture report contents": lambda value: value["architectureVerification"].update(findings=[{"code": "ATTACK"}]),
                "architecture report digest": lambda value: value["architectureVerification"].update(reportSha256="sha256:" + "f" * 64),
            }
            for label, operation in mutations.items():
                mutate(label, operation)

            artifact.write_bytes(canonical.read_bytes())
            verify_assets.write_checksum(artifact)
            report.write_bytes(report_bytes + b" ")
            with self.assertRaises(verify_assets.ReleaseRejected):
                verify_assets.verify_module_knowledge(
                    artifact, canonical, report, VERSION, SOURCE_REVISION
                )


if __name__ == "__main__":
    unittest.main()
