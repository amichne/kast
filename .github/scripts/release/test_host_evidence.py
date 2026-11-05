"""Real ZIPs expose wrong SDK, source, descriptor and duplicate metadata."""

import hashlib
import io
import json
import tempfile
import unittest
import warnings
import zipfile
from dataclasses import asdict, replace
from pathlib import Path

import developer_catalog as catalog
import dual_candidate as candidate
import host_evidence as proof
import host_targets as hosts

QUERY_SCHEMA_BYTES = b"independent authored query-schema fixture"
OPERATION_REGISTRY_BYTES = b"independent authored registry fixture"


class HostEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.archive = Path(temporary.name) / "host.zip"
        self.target = hosts.HostTarget(
            "263", "263.123.45", "263.123.45-IJ", catalog.RecordReference("unused", "a" * 64)
        )
        self.identity = proof.BuildIdentity("0.0.3", "a" * 40, "b" * 40)
        self.contract = proof.consumer.HostedContract(
            "kast.ide-hosted.runtime.v3",
            "sha256:" + "c" * 64,
            "sha256:" + "d" * 64,
            (
                "workspace.lifecycle",
                "query.run",
                "source.read",
                "diagnostic.check",
                "change.plan",
                "change.apply",
                "change.recover",
            ),
        )
        self.resources = proof.SourceResources(
            "sha256:" + hashlib.sha256(QUERY_SCHEMA_BYTES).hexdigest(),
            "sha256:" + hashlib.sha256(OPERATION_REGISTRY_BYTES).hexdigest(),
        )

    def archive_fixture(
        self, property_change: str = "", descriptor_line: str = "263", duplicate: bool = False
    ) -> None:
        properties = (
            "\n".join(
                (
                    f"ideBuild={self.target.ideBuild}",
                    f"kotlinBuild={self.target.kotlinPluginBuild}",
                    f"version={self.identity.version}",
                    f"sourceRevision={self.identity.buildRevision}",
                    f"sourceTree={self.identity.sourceTree}",
                    f"registryDigest={self.resources.operationRegistrySha256}",
                    f"schemaDigest={self.resources.querySchemaSha256}",
                )
            )
            + "\n"
        )
        if property_change:
            key = property_change.partition("=")[0]
            properties = (
                "\n".join(
                    line for line in properties.splitlines() if not line.startswith(key + "=")
                )
                + "\n"
                + property_change
                + "\n"
            )
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as plugin:
            plugin.writestr(
                "META-INF/plugin.xml",
                "<idea-plugin><id>io.github.amichne.kast.ide-hosted</id>"
                "<version>0.0.3</version>"
                f'<idea-version since-build="{descriptor_line}" '
                f'until-build="{descriptor_line}.*"/></idea-plugin>',
            )
            plugin.writestr("hosted-contract.json", json.dumps(asdict(self.contract)))
            plugin.writestr("kast-hosted-query.properties", properties)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    plugin.writestr("kast-hosted-query.properties", properties)
        with zipfile.ZipFile(self.archive, "w") as outer:
            outer.writestr("kast-ide-hosted/lib/kast-ide-hosted.jar", jar.getvalue())

    def test_actual_compiled_properties_admit(self) -> None:
        self.archive_fixture()
        self.assertEqual(
            proof.consumer.Admission.ADMITTED,
            proof.inspect(self.archive, self.target, self.identity, self.contract, self.resources),
        )

    def test_sdk_substitution_and_descriptor_widening_reject(self) -> None:
        for changed in (
            "ideBuild=262.123.45",
            "ideBuild=263.123.46",
            "kotlinBuild=263.123.46-IJ",
            "registryDigest=sha256:" + "e" * 64,
        ):
            with self.subTest(changed=changed):
                self.archive_fixture(property_change=changed)
                self.assertEqual(
                    candidate.Failure.TARGET_MISMATCH,
                    proof.inspect(
                        self.archive, self.target, self.identity, self.contract, self.resources
                    ),
                )
        self.archive_fixture(descriptor_line="262")
        self.assertEqual(
            candidate.Failure.TARGET_MISMATCH,
            proof.inspect(self.archive, self.target, self.identity, self.contract, self.resources),
        )

    def test_source_substitution_and_duplicate_properties_reject(self) -> None:
        self.archive_fixture(property_change="sourceRevision=" + "e" * 40)
        self.assertEqual(
            candidate.Failure.PAYLOAD_MISMATCH,
            proof.inspect(self.archive, self.target, self.identity, self.contract, self.resources),
        )
        self.archive_fixture(duplicate=True)
        self.assertEqual(
            candidate.Failure.PAYLOAD_MISMATCH,
            proof.inspect(self.archive, self.target, self.identity, self.contract, self.resources),
        )

    def test_compile_sdk_declaration_is_not_a_running_ide_patch_constraint(self) -> None:
        self.archive_fixture()
        # No live IDE is selected here: this boundary proves only the compiled target.
        changed = replace(self.target, ideBuild="263.123.46", kotlinPluginBuild="263.123.46-IJ")
        self.assertEqual(
            candidate.Failure.TARGET_MISMATCH,
            proof.inspect(self.archive, changed, self.identity, self.contract, self.resources),
        )


if __name__ == "__main__":
    unittest.main()
