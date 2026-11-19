"""The installer creates the Control JVM entry's class-data archive in staging, or none."""

import contextlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import managed_product_installation as installation
import managed_update_contract as contract

TARGET = "macos-aarch64"
CONTROL: dict[str, object] = {
    "mainClass": "io.github.amichne.kast.cli.KastControlMain",
    "classPath": ["lib/kast-cli.jar", "lib/kotlin-stdlib.jar"],
    "javaHomes": {TARGET: "host/Kast.app/Contents/jbr/Contents/Home", "linux-x86_64": "host/jbr"},
    "sharedArchive": "lib/control.jsa",
    "archiveTraining": ["--internal-entrypoint=management", "--help"],
}


class Training:
    """A scripted runtime: it records each command and writes the archive the command names."""

    def __init__(
        self, exit_code: int = 0, writes: bool = True, raises: BaseException | None = None
    ) -> None:
        self.calls: list[tuple[list[str], dict[str, str], float]] = []
        self.exit_code, self.writes, self.raises = exit_code, writes, raises

    def __call__(
        self, arguments: list[str], environment: dict[str, str], timeout: float
    ) -> subprocess.CompletedProcess[bytes]:
        self.calls.append((arguments, dict(environment), timeout))
        if self.raises is not None:
            raise self.raises
        if self.writes:
            archive = Path(arguments[1].removeprefix("-XX:ArchiveClassesAtExit="))
            _ = archive.write_bytes(b"archive")
        return subprocess.CompletedProcess(arguments, self.exit_code, b"", b"")


class ControlArchiveTest(unittest.TestCase):
    def staged(self, control: dict[str, object] | None = None) -> Path:
        # Installer reads admit only real paths, and the system temporary root may be a link.
        root = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        stage = root / "stage"
        (stage / "share/kast").mkdir(parents=True)
        (stage / "lib").mkdir()
        catalog = {"control": CONTROL if control is None else control}
        _ = (stage / "share/kast/frontend.json").write_text(json.dumps(catalog))
        return stage

    def run_stage(self, stage: Path, training: Training) -> list[tuple[str, str, str | None]]:
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            installation.stage_control_archive(stage, stage.parent / "runtime", TARGET, training)
        return [
            (event["stage"], event["outcome"], event.get("reason"))
            for event in map(json.loads, errors.getvalue().splitlines())
        ]

    def test_training_runs_the_installed_runtime_on_the_staged_class_path(self) -> None:
        stage = self.staged()
        training = Training()
        events = self.run_stage(stage, training)
        ((arguments, environment, timeout),) = training.calls
        runtime = stage.parent / "runtime"
        self.assertEqual(
            [
                str(runtime / "host/Kast.app/Contents/jbr/Contents/Home/bin/java"),
                f"-XX:ArchiveClassesAtExit={stage / 'lib/control.jsa'}",
                "-cp",
                f"{stage / 'lib/kast-cli.jar'}:{stage / 'lib/kotlin-stdlib.jar'}",
                "io.github.amichne.kast.cli.KastControlMain",
                "--internal-entrypoint=management",
                "--help",
            ],
            arguments,
        )
        # Training sees none of the caller's installation state, and its HOME is discarded.
        self.assertEqual({"HOME", "PATH", "LC_ALL"}, set(environment))
        self.assertFalse(Path(environment["HOME"]).exists())
        self.assertEqual(installation.CONTROL_ARCHIVE_TIMEOUT_SECONDS, timeout)
        self.assertEqual(b"archive", (stage / "lib/control.jsa").read_bytes())
        self.assertEqual(0o644, (stage / "lib/control.jsa").stat().st_mode & 0o777)
        self.assertEqual(
            [("CONTROL_ARCHIVE", "STARTED", None), ("CONTROL_ARCHIVE", "COMPLETED", None)], events
        )

    def test_a_failed_training_is_reported_and_leaves_no_archive(self) -> None:
        for training, reason in (
            (Training(exit_code=1), contract.Failure.CHILD_REJECTED),
            (Training(writes=False), contract.Failure.CHILD_REJECTED),
            (
                Training(raises=subprocess.TimeoutExpired(["java"], 1)),
                contract.Failure.CHILD_TIMEOUT,
            ),
            (Training(raises=OSError("missing runtime")), contract.Failure.CHILD_REJECTED),
        ):
            with self.subTest(reason=reason, exit_code=training.exit_code):
                stage = self.staged()
                events = self.run_stage(stage, training)
                self.assertFalse((stage / "lib/control.jsa").exists())
                self.assertEqual(
                    [
                        ("CONTROL_ARCHIVE", "STARTED", None),
                        ("CONTROL_ARCHIVE", "REJECTED", reason.value),
                    ],
                    events,
                )

    def test_a_payload_without_the_jvm_catalog_needs_no_archive(self) -> None:
        stage = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        training = Training()
        installation.stage_control_archive(stage, stage / "runtime", TARGET, training)
        self.assertEqual([], training.calls)

    def test_a_catalog_path_outside_its_root_or_an_unknown_target_rejects_before_any_run(
        self,
    ) -> None:
        for change in (
            {"sharedArchive": "../control.jsa"},
            {"sharedArchive": "lib/control.bin"},
            {"classPath": ["/elsewhere/kast.jar"]},
            {"javaHomes": {"linux-x86_64": "host/jbr"}},
            {"archiveTraining": "--help"},
        ):
            with self.subTest(change=change):
                stage = self.staged(CONTROL | change)
                training = Training()
                with self.assertRaises(ValueError):
                    installation.stage_control_archive(
                        stage, stage.parent / "runtime", TARGET, training
                    )
                self.assertEqual([], training.calls)


if __name__ == "__main__":
    _ = unittest.main()
