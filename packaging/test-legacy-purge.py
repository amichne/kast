"""Known-too-old installations are purged only with proof; everything else keeps its bytes."""

import hashlib
import json
import os
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import unittest
import uuid
import zipfile
from collections.abc import Callable
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import override
from unittest.mock import patch as mock_patch

import legacy_purge as purge
import managed_product_installation as activation
import managed_update_journal as journal

RUNTIME = "0.51.3-" + ("macos-aarch64" if sys.platform == "darwin" else "linux-x86_64")
WORKSPACE = "/workspace/legacy"
CONTAINER = hashlib.sha256(WORKSPACE.encode()).hexdigest()[:32]


@dataclass(frozen=True)
class EndpointDocument:
    socket: str
    type: str = "KAST_IDE_ENDPOINT"
    root: str = WORKSPACE
    hostPid: int = 4242
    host: str = "2c682b44-1977-4b79-8d52-05ef3913b432"
    protocol: int = 2
    querySchema: str = "kast.public.query.v1"
    operations: tuple[str, ...] = ("QUERY",)


class ScriptedEffects:
    """Process boundary double: rejects unexpected and excess calls."""

    def __init__(
        self,
        test: unittest.TestCase,
        *,
        uninstall: Callable[[], subprocess.CompletedProcess[bytes]] | None = None,
        observations: list[tuple[purge.Observed, ...]] | None = None,
        starts: dict[int, str] | None = None,
        survivors: frozenset[int] = frozenset(),
    ) -> None:
        self.test = test
        self.uninstall = uninstall
        self.observations = list(observations or [])
        self.starts = dict(starts or {})
        self.survivors = survivors
        self.signals: list[tuple[int, signal.Signals]] = []
        self.runs: list[list[str]] = []

    def run(
        self, arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]:
        if self.uninstall is None or self.runs:
            raise AssertionError(f"unexpected child {arguments}")
        self.runs.append(arguments)
        return self.uninstall()

    def observe(self) -> tuple[purge.Observed, ...]:
        if not self.observations:
            raise AssertionError("unexpected process observation")
        return self.observations.pop(0)

    def start(self, pid: int) -> str | None:
        return self.starts.get(pid)

    def signal(self, pid: int, number: signal.Signals) -> None:
        self.signals.append((pid, number))
        if pid not in self.survivors:
            _ = self.starts.pop(pid, None)

    def pause(self, seconds: float) -> None:
        pass

    def finished(self) -> None:
        self.test.assertEqual([], self.observations, "unconsumed observations")


class LegacyPurgeTest(unittest.TestCase):
    @override
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="kast-legacy-purge-")
        self.addCleanup(temporary.cleanup)
        self.home = Path(temporary.name).resolve()
        self.share = self.home / ".local/share"
        self.root = self.share / "kast"
        self.launcher = self.home / ".local/bin/kast"
        self.store = self.share / "kast-workspaces"
        jetbrains = (
            self.home / "Library/Application Support/JetBrains"
            if sys.platform == "darwin"
            else self.home / ".local/share/JetBrains"
        )
        profile = jetbrains / "IntelliJIdea2026.2"
        self.plugins = profile / "plugins" if sys.platform == "darwin" else profile
        self.plugin = self.plugins / "kast-ide-hosted"
        self.unrelated_plugin = self.plugins / "IdeaVIM/lib/IdeaVim.jar"
        self.reset_lock = self.share / (
            ".kast-reset-" + hashlib.sha256(str(self.root).encode()).hexdigest()[:32] + ".json.lock"
        )
        self.sockets = Path("/tmp") / ("kast-" + uuid.uuid4().hex)
        self.addCleanup(self.remove_socket_root)
        self.install_legacy("0.51.3")
        self.make_residue()

    def remove_socket_root(self) -> None:
        if self.sockets.exists():
            for directory, _, _ in os.walk(self.sockets):
                os.chmod(directory, 0o700)
            shutil.rmtree(self.sockets)

    def install_legacy(self, version: str) -> None:
        active = self.root / "installation"
        (active / "bin").mkdir(parents=True)
        executable = active / "bin/kast-complete"
        _ = executable.write_text("#!/bin/sh\nexit 0\n")
        executable.chmod(0o700)
        manifest = {
            "schemaVersion": 3,
            "semanticVersion": version,
            "installationRoot": str(active),
            "payloadIdentity": "sha256:" + "a" * 64,
            "stateRoot": str(active / "state"),
            "configuration": str(active / "config/environment"),
            "workspaceRegistry": str(active / "config/workspaces.json"),
            "externalAnchors": [],
            "payloadFiles": [
                {
                    "path": "bin/kast-complete",
                    "sha256": "sha256:" + hashlib.sha256(executable.read_bytes()).hexdigest(),
                    "mode": 0o700,
                }
            ],
        }
        _ = (active / "installation.json").write_text(json.dumps(manifest))
        self.launcher.parent.mkdir(parents=True, exist_ok=True)
        _ = self.launcher.write_bytes(b"legacy native launcher")
        self.launcher.chmod(0o755)
        receipt: dict[str, object] = {
            "schemaVersion": 2,
            "installationRoot": str(self.root),
            "executable": str(self.launcher),
            "executableSha256": hashlib.sha256(self.launcher.read_bytes()).hexdigest(),
            "channel": "STABLE",
            "registrations": [],
        }
        _ = (self.root / "management.json").write_text(json.dumps(receipt))

    def make_residue(self) -> None:
        host = self.store / "runtimes" / RUNTIME / "host/Kast.app/Contents/MacOS"
        host.mkdir(parents=True)
        _ = (host / "idea").write_bytes(b"runtime")
        # Published runtimes are read-only: an ordinary recursive delete fails on them.
        for directory in (host, *host.parents):
            if directory == self.store / "runtimes":
                break
            directory.chmod(0o500)
        state = self.store / "containers" / CONTAINER / "state"
        state.mkdir(parents=True)
        _ = (state / "gradle-home.json").write_text("{}")
        (self.store / "container.lock").touch()
        self.reset_lock.touch()
        (self.plugin / "lib").mkdir(parents=True)
        with zipfile.ZipFile(self.plugin / "lib/kast-ide-hosted.jar", "w") as archive:
            archive.writestr(
                "META-INF/plugin.xml",
                "<idea-plugin><id>io.github.amichne.kast.ide-hosted</id>"
                "<version>0.0.4154</version></idea-plugin>",
            )
        self.unrelated_plugin.parent.mkdir(parents=True)
        _ = self.unrelated_plugin.write_bytes(b"unrelated")
        scope = self.sockets / "h" / CONTAINER
        scope.mkdir(parents=True)
        (scope / "owner.lock").touch()
        server = socket.socket(socket.AF_UNIX)
        server.bind(str(scope / "host.sock"))
        server.close()
        descriptor = EndpointDocument(str(scope.resolve() / "host.sock"))
        _ = (scope / "endpoint.json").write_text(json.dumps(asdict(descriptor)))

    def removed(self) -> subprocess.CompletedProcess[bytes]:
        # The legacy owner removes its root and launcher, then reports REMOVED.
        shutil.rmtree(self.root)
        self.launcher.unlink()
        outcome = {"type": "REMOVED", "root": str(self.root), "operation": "UNINSTALL_FORCE"}
        return subprocess.CompletedProcess([], 0, (json.dumps(outcome) + "\n").encode(), b"")

    def ide(self, start: str = "ide-start") -> purge.Observed:
        return purge.Observed(
            4242,
            start,
            (
                str(self.home / "unrelated.txt"),
                str(self.plugin / "lib/kast-ide-hosted.jar") + " (deleted)",
                str(self.sockets / "h" / CONTAINER / "owner.lock"),
            ),
        )

    def unrelated(self) -> purge.Observed:
        return purge.Observed(4343, "other-start", (str(self.unrelated_plugin), "/usr/lib/libc.so"))

    def snapshot(self) -> dict[str, bytes | None]:
        return {
            str(path.relative_to(self.home)): None if path.is_dir() else path.read_bytes()
            for path in sorted(self.home.rglob("*"))
            if not path.is_socket()
        }

    def test_known_too_old_installation_is_purged_until_activation_sees_no_previous(self) -> None:
        effects = ScriptedEffects(
            self,
            uninstall=self.removed,
            observations=[(self.ide(), self.unrelated()), (self.unrelated(),)],
            starts={4242: "ide-start", 4343: "other-start"},
        )

        result = purge.purge(self.root, self.home, False, effects)

        expected = sorted(
            str(path)
            for path in (
                self.store / "runtimes" / RUNTIME,
                self.store / "containers" / CONTAINER,
                self.store / "container.lock",
                self.reset_lock,
                self.plugin,
                self.sockets,
            )
        )
        self.assertEqual(purge.Purged(str(self.root), "0.51.3", (4242,), tuple(expected)), result)
        self.assertEqual([[str(self.launcher), "uninstall", "--force", "--json"]], effects.runs)
        self.assertEqual([(4242, signal.SIGTERM)], effects.signals)
        effects.finished()
        # Independent downstream rule: ordinary activation now sees no previous installation.
        self.assertEqual(journal.NoPrevious("NONE"), activation.admit_prior(self.root))
        self.assertEqual([], [path.name for path in (self.store / "runtimes").iterdir()])
        self.assertEqual([], [path.name for path in (self.store / "containers").iterdir()])
        for path in (self.plugin, self.sockets, self.reset_lock, self.launcher):
            self.assertFalse(os.path.lexists(path), path)
        self.assertEqual(b"unrelated", self.unrelated_plugin.read_bytes())

    def test_dry_run_reports_the_plan_without_effects(self) -> None:
        before = self.snapshot()
        effects = ScriptedEffects(self, observations=[(self.ide(), self.unrelated())])

        result = purge.purge(self.root, self.home, True, effects)

        self.assertIsInstance(result, purge.Planned)
        assert isinstance(result, purge.Planned)
        self.assertEqual(("0.51.3", (4242,)), (result.version, result.holders))
        self.assertEqual([], effects.signals)
        self.assertEqual(before, self.snapshot())
        effects.finished()

    def test_unproven_endpoint_descriptors_are_retained(self) -> None:
        scope = self.sockets / "h" / CONTAINER
        document = EndpointDocument(str(scope.resolve() / "host.sock"))
        invalid = (
            replace(document, socket="/tmp/unrelated/host.sock"),
            replace(document, root="/workspace/other"),
            replace(document, type="OTHER_ENDPOINT"),
            replace(document, hostPid=0),
            replace(document, host="not-a-host-identity"),
        )
        for descriptor in invalid:
            with self.subTest(descriptor=descriptor):
                _ = (scope / "endpoint.json").write_text(json.dumps(asdict(descriptor)))
                before = self.snapshot()
                effects = ScriptedEffects(self, observations=[(self.ide(),)])

                result = purge.purge(self.root, self.home, True, effects)

                self.assertEqual(
                    purge.Rejected(purge.Stage.ADMISSION, purge.Failure.RESIDUE_UNPROVEN), result
                )
                self.assertEqual(before, self.snapshot())
                self.assertTrue((scope / "endpoint.json").exists())
                effects.finished()

    def test_only_published_releases_up_to_the_cutoff_are_known_too_old(self) -> None:
        accepted = {"0.51.3": (51, 3), "0.51.0": (51, 0), "0.1.0": (1, 0), "0.50.4": (50, 4)}
        for raw, (minor, patch) in accepted.items():
            with self.subTest(raw=raw):
                self.assertEqual(purge.LegacyVersion(minor, patch), purge.LegacyVersion.parse(raw))
        for raw in (
            "0.51.4",
            "0.52.0",
            "0.0.4154",
            "0.0.459222",
            "1.0.0",
            "00.51.3",
            "0.51.3-rc1",
            51,
        ):
            with self.subTest(raw=raw):
                self.assertIsNone(purge.LegacyVersion.parse(raw))

    def test_newer_or_developer_versions_reject_before_any_effect(self) -> None:
        for version in ("0.51.4", "0.0.4154"):
            with self.subTest(version=version):
                shutil.rmtree(self.root)
                self.launcher.unlink(missing_ok=True)
                self.install_legacy(version)
                before = self.snapshot()
                effects = ScriptedEffects(self)

                result = purge.purge(self.root, self.home, False, effects)

                self.assertEqual(
                    purge.Rejected(purge.Stage.ADMISSION, purge.Failure.VERSION_NOT_KNOWN_TOO_OLD),
                    result,
                )
                self.assertEqual(before, self.snapshot())

    def test_product_installation_is_left_to_ordinary_activation(self) -> None:
        selection = self.root / "installation/share/kast/product-selection.json"
        selection.parent.mkdir(parents=True)
        _ = selection.write_text("{}")
        before = self.snapshot()

        result = purge.purge(self.root, self.home, False, ScriptedEffects(self))

        self.assertEqual(purge.NotApplicable(), result)
        self.assertEqual(before, self.snapshot())

    def test_unbound_launcher_or_changed_payload_rejects_before_force_uninstall(self) -> None:
        changes: dict[str, Callable[[], object]] = {
            "launcher": lambda: self.launcher.write_bytes(b"replaced launcher"),
            "payload": lambda: (self.root / "installation/bin/kast-complete").write_text("changed"),
        }
        for name, change in changes.items():
            with self.subTest(change=name):
                shutil.rmtree(self.root)
                self.launcher.unlink(missing_ok=True)
                self.install_legacy("0.51.3")
                _ = change()
                before = self.snapshot()

                result = purge.purge(self.root, self.home, False, ScriptedEffects(self))

                self.assertEqual(
                    purge.Rejected(purge.Stage.ADMISSION, purge.Failure.INSTALLATION_UNPROVEN),
                    result,
                )
                self.assertEqual(before, self.snapshot())

    def test_unattributable_store_entries_reject_before_force_uninstall(self) -> None:
        changes: dict[str, Callable[[], object]] = {
            "foreign entry": lambda: (self.store / "notes.txt").write_text("user file"),
            "product runtime beside containers": lambda: (
                self.store / "runtimes" / ("b" * 64)
            ).mkdir(),
        }
        for name, change in changes.items():
            with self.subTest(change=name):
                _ = change()
                before = self.snapshot()

                result = purge.purge(self.root, self.home, False, ScriptedEffects(self))

                self.assertEqual(
                    purge.Rejected(purge.Stage.ADMISSION, purge.Failure.RESIDUE_UNPROVEN), result
                )
                self.assertEqual(before, self.snapshot())
                for path in (self.store / "notes.txt", self.store / "runtimes" / ("b" * 64)):
                    if path.is_dir():
                        path.rmdir()
                    elif path.exists():
                        path.unlink()

    def test_rejected_force_uninstall_stops_before_processes_and_residue(self) -> None:
        def retained() -> subprocess.CompletedProcess[bytes]:
            outcome = {"type": "RESET_RETAINED", "operation": "UNINSTALL_FORCE"}
            return subprocess.CompletedProcess([], 1, json.dumps(outcome).encode(), b"")

        effects = ScriptedEffects(self, uninstall=retained)
        before = self.snapshot()

        result = purge.purge(self.root, self.home, False, effects)

        self.assertEqual(
            purge.Rejected(purge.Stage.FORCE_UNINSTALL, purge.Failure.UNINSTALL_REJECTED), result
        )
        self.assertEqual([], effects.signals)
        self.assertEqual(before, self.snapshot())

    def test_holder_that_survives_kill_keeps_every_residue_entry(self) -> None:
        effects = ScriptedEffects(
            self,
            uninstall=self.removed,
            observations=[(self.ide(),)],
            starts={4242: "ide-start"},
            survivors=frozenset({4242}),
        )

        result = purge.purge(self.root, self.home, False, effects)

        self.assertEqual(purge.Rejected(purge.Stage.HOLDERS, purge.Failure.HOLDER_SURVIVED), result)
        self.assertEqual([(4242, signal.SIGTERM), (4242, signal.SIGKILL)], effects.signals)
        for path in (self.store / "runtimes" / RUNTIME, self.plugin, self.sockets, self.reset_lock):
            self.assertTrue(os.path.lexists(path), path)
        effects.finished()

    def test_reused_process_identifier_is_never_signalled(self) -> None:
        effects = ScriptedEffects(
            self,
            uninstall=self.removed,
            observations=[(self.ide("ide-start"),), ()],
            starts={4242: "new-process-start"},
        )

        result = purge.purge(self.root, self.home, False, effects)

        self.assertIsInstance(result, purge.Purged)
        self.assertEqual([], effects.signals)
        self.assertEqual(stat.S_IFREG, stat.S_IFMT(os.lstat(self.unrelated_plugin).st_mode))
        effects.finished()


class SystemEffectsTest(unittest.TestCase):
    def test_linux_reused_pid_during_file_observation_is_not_a_holder(self) -> None:
        with tempfile.TemporaryDirectory(prefix="kast-legacy-proc-") as temporary:
            proc = Path(temporary)
            for pid in (4242, 4343):
                (proc / str(pid) / "fd").mkdir(parents=True)
                _ = (proc / str(pid) / "maps").write_text(
                    "1000-2000 r--p 0 00:00 1 /legacy/plugin.jar\n"
                )
            scandir = os.scandir

            def scan(path: str | Path):
                return scandir(proc if path == "/proc" else path)

            replaced = [True, False]

            def start(pid: int) -> str:
                return "new" if pid == 4242 and replaced.pop() else "old"

            with (
                mock_patch.object(purge.os, "scandir", side_effect=scan),
                mock_patch.object(purge.SystemEffects, "linux_start", side_effect=start),
            ):
                observed = purge.SystemEffects.observe_linux()

        selected, _ = purge.holders(observed, ("/legacy",))
        self.assertEqual((purge.Observed(4343, "old", ("/legacy/plugin.jar",)),), selected)

    def test_darwin_reused_pid_during_file_observation_is_not_a_holder(self) -> None:
        listing = subprocess.CompletedProcess(
            [], 0, b"p4242\nn/legacy/plugin.jar\np4343\nn/legacy/stable.jar\n", b""
        )
        with (
            mock_patch.object(
                purge.SystemEffects,
                "darwin_starts",
                side_effect=[{4242: "old", 4343: "stable"}, {4242: "new", 4343: "stable"}],
            ),
            mock_patch.object(purge.subprocess, "run", return_value=listing),
        ):
            observed = purge.SystemEffects.observe_darwin()

        selected, _ = purge.holders(observed, ("/legacy",))
        self.assertEqual((purge.Observed(4343, "stable", ("/legacy/stable.jar",)),), selected)

    def test_real_holder_is_observed_with_identity_and_retired_by_sigterm(self) -> None:
        with tempfile.TemporaryDirectory(prefix="kast-legacy-holder-") as temporary:
            plugin = Path(temporary).resolve() / "plugins/kast-ide-hosted"
            (plugin / "lib").mkdir(parents=True)
            jar = plugin / "lib/kast-ide-hosted.jar"
            _ = jar.write_bytes(b"jar")
            child = subprocess.Popen(
                [
                    sys.executable,
                    "-c",
                    "import sys,time; f=open(sys.argv[1],'rb'); "
                    "print('ready',flush=True); time.sleep(60)",
                    str(jar),
                ],
                stdout=subprocess.PIPE,
            )
            self.addCleanup(child.kill)
            self.assertEqual(b"ready\n", child.stdout.readline() if child.stdout else b"")
            effects = purge.SystemEffects()

            selected, _ = purge.holders(effects.observe(), (str(plugin),))

            self.assertEqual([child.pid], [item.pid for item in selected])
            self.assertIsNotNone(selected[0].start)
            self.assertTrue(purge.retire(selected[0], effects))
            self.assertEqual(-signal.SIGTERM, child.wait(timeout=10))
            self.assertIsNone(effects.start(child.pid))


if __name__ == "__main__":
    _ = unittest.main()
