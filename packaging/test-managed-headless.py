from __future__ import annotations

import hashlib
import io
import json
import subprocess
import tempfile
import unittest
import zipfile
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Literal

import managed_update_contract as contract
from managed_headless import (
    AssemblyManifest,
    AssemblyRecord,
    Component,
    ComponentDecision,
    ComponentDisposition,
    ComponentReason,
    Failure,
    Input,
    Rejected,
    add_bootstrap,
    admit,
    configure_removed_plugins,
    select_assembly_components,
    select_components,
    wrapper,
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


@dataclass(frozen=True, slots=True)
class RuntimeModeFixture:
    ideaHome: str
    profile: str
    pid: int
    processStartEpochMillis: int
    hostLifetime: str
    buildIdentity: contract.RuntimeBuild
    autoReload: str
    mode: str


@dataclass(frozen=True, slots=True)
class RecordedPluginTargetFixture:
    root: str
    type: Literal["RECORDED"] = "RECORDED"


@dataclass(frozen=True, slots=True)
class SelectedIdeFixture:
    home: str
    bundle: str
    executable: str
    hostPluginTarget: RecordedPluginTargetFixture
    type: Literal["resolved"] = "resolved"


class ManagedHeadlessTest(unittest.TestCase):
    def test_removed_plugins_use_the_recorded_private_profile_and_preserve_existing_disabled_ids(
        self,
    ) -> None:
        removed = Component("plugins/optional", ("optional",), ("optional.model",), ())
        manifest = AssemblyManifest(
            1,
            "a" * 64,
            "b" * 64,
            "c" * 64,
            "d" * 64,
            ("required",),
            (
                ComponentDecision(
                    removed,
                    ComponentDisposition.EXCLUDE,
                    ComponentReason.EXPLICIT_NON_ROOT_CANDIDATE,
                ),
            ),
            (),
            (),
            (),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            state = root / "state"
            plugins = state / "home/private-profile/plugins"
            plugins.mkdir(parents=True)
            selected_path = state / "home/.local/share/kast/installation/config/selected-ide.json"
            selected_path.parent.mkdir(parents=True)
            selected = SelectedIdeFixture(
                "/owned/idea",
                "/owned/idea",
                "/owned/idea/bin/idea",
                RecordedPluginTargetFixture(str(plugins)),
            )
            _ = selected_path.write_text(json.dumps(asdict(selected)))
            disabled = plugins.parent / "disabled_plugins.txt"
            _ = disabled.write_text("existing\n")
            assert configure_removed_plugins(state, manifest) is None
            assert disabled.read_text() == "existing\noptional\n"
            outside = root / "other-profile/plugins"
            outside.mkdir(parents=True)
            sentinel = outside.parent / "disabled_plugins.txt"
            _ = sentinel.write_text("protected\n")
            _ = selected_path.write_text(
                json.dumps(
                    asdict(
                        replace(
                            selected, hostPluginTarget=RecordedPluginTargetFixture(str(outside))
                        )
                    )
                )
            )
            assert configure_removed_plugins(state, manifest) == Rejected(Failure.SHARED_STATE)
            assert sentinel.read_text() == "protected\n"

    def test_component_archive_adapter_keeps_vendor_bytes_and_records_exact_removed_inputs(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="hc", dir="/tmp") as temporary:
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
            selected = select_assembly_components(
                home, replace(candidate, excluded_plugins=("optional",)), installer
            )
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

    def test_wrapper_preserves_literal_arguments_and_rejects_absent_assembly(self) -> None:
        with tempfile.TemporaryDirectory(prefix="h", dir="/tmp") as temporary:
            root = Path(temporary).resolve()
            home = root / "idea $(false); 'literal'"
            state = root / "state with spaces"
            home.mkdir()
            executable = state / "home/.local/bin/kast"
            executable.parent.mkdir(parents=True)
            _ = executable.write_text(
                '#!/bin/sh\nprintf "%s\\n" "$HOME" "$KAST_MANAGED_HEADLESS_STATE"\n'
                'printf "<%s>\\n" "$@"\n'
            )
            executable.chmod(0o700)
            command = root / "kast"
            _ = command.write_text(wrapper(home, state))
            command.chmod(0o700)
            literal = "$(false); 'argument'"
            rejected = subprocess.run(
                [str(command), literal], capture_output=True, text=True, check=False
            )
            assert rejected.returncode == 2
            assert rejected.stdout == ""
            _ = (home / "kast-headless.json").write_text("assembly admission belongs to Control\n")
            result = subprocess.run(
                [str(command), literal], capture_output=True, text=True, check=False
            )
            assert result.returncode == 0
            assert result.stdout.splitlines() == [str(state / "home"), str(state), f"<{literal}>"]

    def test_runtime_mode_survives_the_installer_projection(self) -> None:
        wire = RuntimeModeFixture(
            "/owned/idea",
            "/owned/state/home/profile",
            42,
            100,
            "host",
            contract.RuntimeBuild("0.0.0", "a" * 40, "b" * 40),
            "DISABLED",
            "MANAGED_HEADLESS",
        )
        parsed = contract.parse_runtime(asdict(wire))
        assert not isinstance(parsed, contract.Failure)
        assert getattr(parsed, "mode", None) == "MANAGED_HEADLESS"
        assert (
            contract.parse_runtime(asdict(replace(wire, mode="UNPROVEN")))
            == contract.Failure.HOST_RESPONSE_INVALID
        )

    def test_headless_auto_reload_never_grants_an_installer_strategy(self) -> None:
        wire = RuntimeModeFixture(
            "/owned/idea",
            "/owned/state/home/profile",
            42,
            100,
            "host",
            contract.RuntimeBuild("0.0.0", "a" * 40, "b" * 40),
            "ENABLED",
            "MANAGED_HEADLESS",
        )
        assert contract.parse_runtime(asdict(wire)) == contract.Failure.HOST_RESPONSE_INVALID

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
