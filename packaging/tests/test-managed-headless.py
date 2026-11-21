from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from dataclasses import asdict, replace
from pathlib import Path

from managed_headless import (
    AssemblyRecord,
    Component,
    Failure,
    Input,
    Rejected,
    SupportedIdeReleaseLine,
    add_bootstrap,
    admit,
    host_dependency_roots,
    prepare_runtime,
    select_assembly_components,
    select_components,
)


def inputs(root: Path) -> Input:
    archive = root / "idea.tar.gz"
    _ = archive.write_bytes(b"distribution fixture")
    bootstrap = root / "bootstrap.zip"
    with zipfile.ZipFile(bootstrap, "w") as output:
        output.writestr("kast-managed-headless/lib/kast-managed-headless.jar", b"bootstrap")
    return Input(
        root / "new",
        archive,
        hashlib.sha256(archive.read_bytes()).hexdigest(),
        bootstrap,
        hashlib.sha256(bootstrap.read_bytes()).hexdigest(),
        root,
        "0.0.0",
        "262.10315.125",
    )


class ManagedHeadlessTest(unittest.TestCase):
    def test_component_rejection_preserves_its_stage_and_reason(self) -> None:
        with tempfile.TemporaryDirectory(prefix="h", dir="/tmp") as temporary:
            candidate = inputs(Path(temporary).resolve())
            admitted = admit(candidate)
            assert not isinstance(admitted, Rejected)
            home = candidate.root / "runtime/host"
            home.mkdir(parents=True)
            try:
                result = prepare_runtime(admitted, home)
                assert isinstance(result, Rejected)
                assert asdict(result) == {
                    "reason": "INVALID_COMPONENT",
                    "component": {
                        "reason": "INSTALLER_CONTRACT_UNSUPPORTED",
                        "stage": "RECORDS",
                        "requested": "0.0.0",
                        "platform": "macos-aarch64",
                        "selectable": (),
                        "transfer": None,
                    },
                }
                assert not (candidate.root / "components/plugins").exists()
            finally:
                for path in (*home.rglob("*"), home):
                    if not path.is_symlink():
                        path.chmod(path.stat().st_mode | 0o700)

    def test_reviewed_full_vendor_builds_preserve_identity_and_reject_the_next_line(self) -> None:
        with tempfile.TemporaryDirectory(prefix="h", dir="/tmp") as temporary:
            candidate = inputs(Path(temporary).resolve())
            for build in ("262.10315.125", "263.7307.200"):
                with self.subTest(build=build):
                    selected = replace(candidate, build=build)
                    result = admit(selected)
                    assert not isinstance(result, Rejected)
                    assert result.inputs == selected
                    assert result.release_line.value == build.split(".")[0]
                    assert not selected.root.exists()
            for build in ("264.1.1", "263/../../other", "263.*", "263.bad.1", "2630.1.1"):
                assert admit(replace(candidate, build=build)) == Rejected(Failure.INVALID_INPUT)
            assert not candidate.root.exists()

    def test_component_archive_roots_follow_the_selected_build_line(self) -> None:
        for line in ("262", "263"):
            with (
                self.subTest(line=line),
                tempfile.TemporaryDirectory(prefix="h", dir="/tmp") as temporary,
            ):
                root = Path(temporary).resolve()
                candidate = replace(inputs(root), build=f"{line}.1.2")
                home = root / "idea"
                required = home / "plugins/required/lib/required.jar"
                required.parent.mkdir(parents=True)
                with zipfile.ZipFile(required, "w") as jar:
                    jar.writestr(
                        "META-INF/plugin.xml", "<idea-plugin><id>required</id></idea-plugin>"
                    )
                for archive_line in ("262", "263"):
                    host = io.BytesIO()
                    with zipfile.ZipFile(host, "w") as jar:
                        jar.writestr(
                            "META-INF/plugin.xml",
                            "<idea-plugin><id>io.github.amichne.kast.ide-hosted</id>"
                            f"<depends>dependency.{archive_line}</depends></idea-plugin>",
                        )
                    with zipfile.ZipFile(
                        root / f"kast-ide-hosted-v0.0.0-idea-{archive_line}.zip", "w"
                    ) as archive:
                        archive.writestr("kast/lib/host.jar", host.getvalue())
                installer = root / "installer"
                _ = installer.write_bytes(b"installer input")
                admitted = admit(candidate)
                assert not isinstance(admitted, Rejected)
                result = select_assembly_components(home, admitted, installer)
                assert not isinstance(result, Rejected)
                assert result.hostRoots == (f"dependency.{line}",)
                assert host_dependency_roots(root, "0.0.0") == ("dependency.262",)
                assert host_dependency_roots(root, "0.0.0", SupportedIdeReleaseLine.IDEA_263) == (
                    "dependency.263",
                )
                assert required.is_file()
                if line == "263":
                    (root / "kast-ide-hosted-v0.0.0-idea-263.zip").unlink()
                    assert select_assembly_components(home, admitted, installer) == Rejected(
                        Failure.INVALID_COMPONENT
                    )
                    assert required.is_file()
                    assert host_dependency_roots(root, "0.0.0") == ("dependency.262",)

    def test_component_archive_adapter_keeps_vendor_bytes_and_records_exact_removed_inputs(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="h", dir="/tmp") as temporary:
            root = Path(temporary).resolve()
            candidate = inputs(root)
            home = root / "idea"
            required = home / "plugins/required/lib/required.jar"
            optional = home / "plugins/optional/lib/optional.jar"
            required.parent.mkdir(parents=True)
            optional.parent.mkdir(parents=True)
            with zipfile.ZipFile(required, "w") as jar:
                jar.writestr(
                    "META-INF/plugin.xml",
                    '<idea-plugin><id>required</id><module value="required.alias"/></idea-plugin>',
                )
            with zipfile.ZipFile(optional, "w") as jar:
                jar.writestr(
                    "META-INF/plugin.xml",
                    '<idea-plugin><id>optional</id><module value="optional.model"/></idea-plugin>',
                )
            host = io.BytesIO()
            with zipfile.ZipFile(host, "w") as jar:
                jar.writestr(
                    "META-INF/plugin.xml",
                    "<idea-plugin><id>io.github.amichne.kast.ide-hosted</id><depends>required.alias</depends></idea-plugin>",
                )
            with zipfile.ZipFile(root / "kast-ide-hosted-v0.0.0-idea-262.zip", "w") as archive:
                archive.writestr("kast/lib/host.jar", host.getvalue())
            installer = root / "installer"
            _ = installer.write_bytes(b"installer input")
            required_bytes = required.read_bytes()
            optional_digest = hashlib.sha256(optional.read_bytes()).hexdigest()
            admitted = admit(replace(candidate, excluded_plugins=("optional",)))
            assert not isinstance(admitted, Rejected)
            selected = select_assembly_components(home, admitted, installer)
            assert not isinstance(selected, Rejected)
            assert selected.hostRoots == ("required.alias",)
            assert required.read_bytes() == required_bytes
            assert not optional.parent.parent.exists()
            assert len(selected.removedFiles) == 1
            assert selected.removedFiles[0].sha256 == optional_digest
            encoded: object = json.loads(json.dumps(asdict(selected)))
            assert isinstance(encoded, dict)
            assert encoded["acceptance"] == "NOT_RUN"
            assert encoded["distributionSha256"] == candidate.archive_sha256

    def test_included_required_dependency_rejects_removal_before_file_effects(self) -> None:
        from managed_headless import component_inventory

        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            for identifier in ("consumer", "optional"):
                jar = home / "plugins" / identifier / "lib/plugin.jar"
                jar.parent.mkdir(parents=True)
                with zipfile.ZipFile(jar, "w") as archive:
                    if identifier == "consumer":
                        archive.writestr(
                            "META-INF/plugin.xml",
                            '<idea-plugin xmlns:xi="http://www.w3.org/2001/XInclude">'
                            '<id>consumer</id><xi:include href="required.xml"/></idea-plugin>',
                        )
                        archive.writestr(
                            "META-INF/required.xml",
                            '<idea-plugin><dependencies><module name="optional.model"/>'
                            "</dependencies></idea-plugin>",
                        )
                    else:
                        archive.writestr(
                            "META-INF/plugin.xml",
                            '<idea-plugin><id>optional</id><module value="optional.model"/>'
                            "</idea-plugin>",
                        )
            components = component_inventory(home)
            assert select_components(components, ("optional",), ()) == Rejected(
                Failure.COMPONENT_DEPENDENCY
            )
            assert (home / "plugins/optional/lib/plugin.jar").is_file()

    def test_component_selection_removes_whole_optional_plugin_and_preserves_roots(self) -> None:
        java = Component("plugins/java", ("com.intellij.java",), (), ())
        kotlin = Component("plugins/Kotlin", ("org.jetbrains.kotlin",), (), ("com.intellij.java",))
        gradle = Component(
            "plugins/gradle-plugin", ("com.intellij.gradle",), ("org.jetbrains.plugins.gradle",), ()
        )
        completion = Component(
            "plugins/fullLine",
            ("org.jetbrains.completion.full.line",),
            ("intellij.fullLine.core",),
            (),
        )
        roots = ("com.intellij.java", "org.jetbrains.kotlin", "org.jetbrains.plugins.gradle")
        components = (java, kotlin, gradle, completion)
        selected = select_components(components, ("org.jetbrains.completion.full.line",), roots)
        assert not isinstance(selected, Rejected)
        assert selected.kept == (java, kotlin, gradle)
        assert selected.removed == (completion,)
        assert select_components(components, ("org.jetbrains.kotlin",), roots) == Rejected(
            Failure.REQUIRED_COMPONENT
        )
        assert select_components(components, ("com.intellij.gradle",), roots) == Rejected(
            Failure.REQUIRED_COMPONENT
        )

    def test_component_selection_rejects_unknown_and_required_module_provider(self) -> None:
        completion = Component("plugins/fullLine", ("completion",), ("completion.model",), ())
        consumer = Component("plugins/consumer", ("consumer",), (), ("completion.model",))
        assert select_components((completion, consumer), ("absent",), ()) == Rejected(
            Failure.UNKNOWN_COMPONENT
        )
        assert select_components((completion, consumer), ("completion",), ()) == Rejected(
            Failure.COMPONENT_DEPENDENCY
        )

    def test_admission_rejects_existing_state_and_wrong_bytes_before_creation(self) -> None:
        with tempfile.TemporaryDirectory(prefix="h", dir="/tmp") as temporary:
            root = Path(temporary).resolve()
            candidate = inputs(root)
            admitted = admit(candidate)
            assert not isinstance(admitted, Rejected)
            assert admitted.inputs == candidate
            assert admit(replace(candidate, archive_sha256="0" * 64)) == Rejected(
                Failure.DIGEST_MISMATCH
            )
            assert not candidate.root.exists()
            candidate.root.mkdir()
            assert admit(candidate) == Rejected(Failure.EXISTING_TARGET)

    def test_bootstrap_preserves_vendor_bytes_and_record_matches_kotlin_shape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = inputs(root)
            home = root / "idea"
            home.mkdir()
            vendor = home / "vendor.jar"
            _ = vendor.write_bytes(b"vendor")
            assert add_bootstrap(home, candidate.bootstrap) == (
                "333c04dd151a2a6831c039cb9a651df29198be8a04e16ce861d4b6a34a11c954"
            )
            assert vendor.read_bytes() == b"vendor"
            assert add_bootstrap(home, candidate.bootstrap) == Rejected(Failure.EXISTING_TARGET)
            record = asdict(AssemblyRecord(1, str(home), "a" * 64, "b" * 64, "262.1", "262.1-IJ"))
            assert set(record) == {
                "schemaVersion",
                "installation",
                "distributionSha256",
                "bootstrapSha256",
                "platformBuild",
                "kotlinPluginBuild",
            }
            assert record["schemaVersion"] == 1

    def test_runtime_seal_preserves_bytes_execute_bits_and_external_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            tmp_path = Path(temporary)
            from managed_headless import seal_runtime

            home = tmp_path / "runtime"
            home.mkdir()
            executable = home / "idea"
            _ = executable.write_bytes(b"vendor executable")
            executable.chmod(0o755)
            external = tmp_path / "external"
            _ = external.write_bytes(b"protected")
            external.chmod(0o600)
            (home / "external-link").symlink_to(external)
            seal_runtime(home)
            assert executable.read_bytes() == b"vendor executable"
            assert executable.stat().st_mode & 0o777 == 0o555
            assert external.stat().st_mode & 0o777 == 0o600
            assert home.stat().st_mode & 0o222 == 0
            home.chmod(0o755)


if __name__ == "__main__":
    _ = unittest.main()
