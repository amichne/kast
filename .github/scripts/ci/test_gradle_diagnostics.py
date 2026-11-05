"""A daemon dump requires invocation association and bounded, complete output."""

import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import gradle_diagnostics as diagnostics


def available_jdk():
    if supplied := os.environ.get("JAVA_HOME"):
        return Path(supplied)
    if executable := shutil.which("java"):
        candidate = Path(executable).resolve().parent.parent
        if (candidate / "release").is_file() and all(
            (candidate / "bin" / tool).is_file() for tool in ("java", "javac", "jcmd")
        ):
            return candidate
    return None


JDK = available_jdk()


def candidates(log):
    observed = diagnostics.DaemonPids()
    with log.open("rb") as source:
        while block := source.read(65536):
            observed.observe(block)
    return observed


class DaemonDiagnosticsTest(unittest.TestCase):
    def test_path_discovery_requires_a_resolved_jdk_not_system_launcher_stubs(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, {}, clear=True):
            root = Path(temporary)
            (root / "bin").mkdir()
            for tool in ("java", "javac", "jcmd"):
                (root / "bin" / tool).touch()
            with patch.object(shutil, "which", return_value=str(root / "bin/java")):
                self.assertIsNone(available_jdk())
                (root / "release").touch()
                self.assertEqual(root.resolve(), available_jdk())
            launcher = root / "launcher"
            launcher.symlink_to(root / "bin/java")
            with patch.object(shutil, "which", return_value=str(launcher)):
                self.assertEqual(root.resolve(), available_jdk())

    @unittest.skipUnless(JDK is not None, "a JDK is required for the live JVM boundary")
    def test_owned_jvm_thread_dump_retains_stack_without_environment(self):
        assert JDK is not None
        java = JDK
        self.assertTrue((java / "bin/jcmd").is_file(), "the declared JDK must provide jcmd")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "GradleDaemon.java"
            source.write_text("""package org.gradle.launcher.daemon.bootstrap;
public class GradleDaemon {
    public static void main(String[] args) throws Exception {
        java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),
            "ready".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Thread.sleep(60000);
    }
}
""")
            compiled = diagnostics.capture(
                (str(java / "bin/javac"), "-d", str(root), str(source)),
                seconds=15,
                limit=10000,
            )
            self.assertEqual(diagnostics.Captured(b"", 0), compiled, "fixture compilation failed")
            ready = root / "ready"
            token = "f" * 32
            process = subprocess.Popen(
                (
                    str(java / "bin/java"),
                    "-Xmx64m",
                    "-cp",
                    str(root),
                    "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                    str(ready),
                ),
                env={
                    **os.environ,
                    diagnostics.OWNER_KEY: token,
                    "KAST_DIAGNOSTIC_PRIVATE_SENTINEL": "must-not-be-retained",
                },
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            try:
                deadline = time.monotonic() + 10
                while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists(), "fixture JVM did not become ready")
                observed = diagnostics.DaemonPids()
                observed.observe(
                    f"The client will now receive all logging from the daemon (pid: {process.pid}).\n".encode()
                )
                destination = root / "threads.txt"
                self.assertEqual(
                    diagnostics.Outcome.RETAINED,
                    diagnostics.snapshot(observed, destination, token, java),
                )
                dump = destination.read_text()
                self.assertIn("org.gradle.launcher.daemon.bootstrap.GradleDaemon.main", dump)
                self.assertNotIn("must-not-be-retained", dump)
                self.assertNotIn(token, dump)
                self.assertIsNone(process.poll())
            finally:
                process.kill()
                process.wait(timeout=5)

    def test_fragmented_pid_and_large_logs_keep_bounded_candidates(self):
        observed = diagnostics.DaemonPids()
        line = b"The client will now receive all logging from the daemon (pid: 123).\n"
        for byte in line:
            observed.observe(bytes((byte,)))
        for _ in range(40):
            observed.observe(b"unrelated output\n" * 4096)
        self.assertEqual(123, observed.single())
        observed.observe(line)
        self.assertEqual(123, observed.single())
        observed.observe(line.replace(b"123", b"456"))
        self.assertIsNone(observed.single())

    def test_exact_capture_limit_succeeds_and_one_extra_byte_rejects(self):
        for size in (100, 101):
            result = diagnostics.capture(
                (sys.executable, "-c", f"import sys; sys.stdout.buffer.write(b'x' * {size})"),
                seconds=2,
                limit=100,
            )
            self.assertEqual(
                diagnostics.Captured(b"x" * 100, 0)
                if size == 100
                else diagnostics.Outcome.TRUNCATED,
                result,
            )

    def test_exited_helper_cannot_leave_a_pipe_holding_descendant(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper_pid, child_pid = root / "helper", root / "child"
            leader = (
                "import os, subprocess, sys\nfrom pathlib import Path\n"
                "child = subprocess.Popen([sys.executable, '-c', 'import signal; signal.pause()'])\n"
                f"Path({str(helper_pid)!r}).write_text(str(os.getpid()))\n"
                f"Path({str(child_pid)!r}).write_text(str(child.pid))\n"
            )
            control = subprocess.Popen(
                (sys.executable, "-c", "import signal; signal.pause()"), start_new_session=True
            )
            try:
                self.assertEqual(
                    diagnostics.Outcome.TIMED_OUT,
                    diagnostics.capture(
                        (sys.executable, "-c", leader),
                        seconds=2,
                        limit=1000,
                    ),
                )
                state = subprocess.run(
                    ("ps", "-p", child_pid.read_text(), "-o", "stat="),
                    capture_output=True,
                    text=True,
                    check=False,
                ).stdout.strip()
                self.assertTrue(not state or state.startswith("Z"), "owned child survived")
                self.assertIsNone(control.poll(), "unrelated process was retired")
            finally:
                if helper_pid.exists():
                    try:
                        os.killpg(int(helper_pid.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                control.kill()
                control.wait(timeout=5)

    def test_capture_preserves_output_and_nonzero_status(self):
        result = diagnostics.capture(
            (sys.executable, "-c", "import sys; print('controlled evidence'); sys.exit(7)"),
            seconds=2,
            limit=1000,
        )
        self.assertEqual(diagnostics.Captured(b"controlled evidence\n", 7), result)

    def test_hung_and_oversized_helpers_are_bounded(self):
        for program, limit, expected in (
            ("import signal; signal.pause()", 1000, diagnostics.Outcome.TIMED_OUT),
            (
                "import sys; sys.stdout.buffer.write(b'x' * 200000); sys.stdout.flush()",
                100,
                diagnostics.Outcome.TRUNCATED,
            ),
        ):
            with self.subTest(expected=expected):
                self.assertEqual(
                    expected,
                    diagnostics.capture(
                        (sys.executable, "-c", program),
                        seconds=0.3,
                        limit=limit,
                    ),
                )

    def test_missing_or_ambiguous_pid_never_probes_a_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log = root / "graph.log"
            for pids in ((), ("0",), ("1",), ("999999999999999999",), ("23", "24")):
                with self.subTest(pids=pids), patch.object(diagnostics, "capture") as capture:
                    log.write_text(
                        "\n".join(
                            f"The client will now receive all logging from the daemon (pid: {pid})."
                            for pid in pids
                        )
                    )
                    self.assertEqual(
                        diagnostics.Outcome.UNAVAILABLE,
                        diagnostics.snapshot(candidates(log), root / "dump", "abc", root),
                    )
                    capture.assert_not_called()

    def test_unavailable_jcmd_and_failed_process_observation_never_publish(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            observed = diagnostics.DaemonPids()
            observed.observe(
                b"The client will now receive all logging from the daemon (pid: 123).\n"
            )
            with patch.object(diagnostics, "capture") as capture:
                self.assertEqual(
                    diagnostics.Outcome.UNAVAILABLE,
                    diagnostics.snapshot(observed, root / "dump", "owner", root),
                )
                capture.assert_not_called()
            (root / "bin").mkdir()
            (root / "bin/jcmd").touch()
            with patch.object(diagnostics, "identity", return_value=b"start"):
                self.assertEqual(
                    diagnostics.Outcome.UNAVAILABLE,
                    diagnostics.snapshot(observed, root / "dump", "owner", root),
                )
            for result in (
                diagnostics.Captured(b"", 0),
                diagnostics.Captured(b"denied", 1),
                diagnostics.Outcome.TRUNCATED,
                diagnostics.Outcome.TIMED_OUT,
            ):
                with (
                    self.subTest(result=result),
                    patch.object(
                        diagnostics,
                        "capture",
                        return_value=result,
                    ) as capture,
                ):
                    expected = (
                        result
                        if isinstance(result, diagnostics.Outcome)
                        else diagnostics.Outcome.UNAVAILABLE
                    )
                    self.assertEqual(
                        expected, diagnostics.snapshot(observed, root / "dump", "owner", root)
                    )
                    self.assertEqual(1, capture.call_count)
                    self.assertFalse((root / "dump").exists())

    def test_actual_process_association_does_not_retain_raw_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            marker = root / "attached"
            jcmd = root / "bin/jcmd"
            jcmd.parent.mkdir()
            jcmd.write_text(
                f"#!{sys.executable}\nfrom pathlib import Path\n"
                f"Path({str(marker)!r}).write_text('attached')\n"
                "print('controlled thread dump')\n"
            )
            jcmd.chmod(0o755)
            token = "c" * 32
            process = subprocess.Popen(
                (
                    sys.executable,
                    "-c",
                    "import signal; signal.pause()",
                    "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                ),
                env={
                    **os.environ,
                    diagnostics.OWNER_KEY: token,
                    "KAST_DIAGNOSTIC_PRIVATE_SENTINEL": "must-not-be-retained",
                },
            )
            try:
                log = root / "graph.log"
                line = f"The client will now receive all logging from the daemon (pid: {process.pid}).\n"
                log.write_text(line * 2)  # Repeating one observed PID is not ambiguity.
                destination = root / "dump"
                self.assertEqual(
                    diagnostics.Outcome.UNAVAILABLE,
                    diagnostics.snapshot(candidates(log), destination, token[:-1], root),
                )
                self.assertFalse(marker.exists(), "a mismatched owner was attached")
                self.assertEqual(
                    diagnostics.Outcome.RETAINED,
                    diagnostics.snapshot(candidates(log), destination, token, root),
                )
                self.assertEqual("controlled thread dump\n", destination.read_text())
                self.assertTrue(marker.exists())
                self.assertIsNone(process.poll(), "diagnosis must not stop the target")
            finally:
                process.terminate()
                process.wait(timeout=5)

    def test_rejection_and_identity_change_cannot_publish_a_dump(self):
        token = "d" * 32
        owned = diagnostics.Captured(
            b"Wed Nov 4 12:00:00 2026 java org.gradle.launcher.daemon.bootstrap.GradleDaemon "
            + diagnostics.OWNER_KEY.encode()
            + b"="
            + token.encode()
            + b"\n",
            0,
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "bin").mkdir()
            (root / "bin/jcmd").touch()
            log = root / "graph.log"
            log.write_text("The client will now receive all logging from the daemon (pid: 123).\n")
            for result in (
                diagnostics.Outcome.TIMED_OUT,
                diagnostics.Outcome.TRUNCATED,
                diagnostics.Captured(b"attach failed", 1),
            ):
                with (
                    self.subTest(result=result),
                    patch.object(
                        diagnostics,
                        "capture",
                        side_effect=(owned, result),
                    ) as capture,
                ):
                    expected = (
                        diagnostics.Outcome.ATTACH_FAILED
                        if isinstance(result, diagnostics.Captured)
                        else result
                    )
                    self.assertEqual(
                        expected, diagnostics.snapshot(candidates(log), root / "dump", token, root)
                    )
                    self.assertEqual(2, capture.call_count)
                    self.assertFalse((root / "dump").exists())
            changed = diagnostics.Captured(owned.output.replace(b"12:00:00", b"12:00:01"), 0)
            with patch.object(
                diagnostics,
                "capture",
                side_effect=(
                    owned,
                    diagnostics.Captured(b"unconfirmed dump", 0),
                    changed,
                ),
            ):
                self.assertEqual(
                    diagnostics.Outcome.IDENTITY_UNCONFIRMED,
                    diagnostics.snapshot(candidates(log), root / "dump", token, root),
                )
                self.assertFalse((root / "dump").exists())

            write_bytes = Path.write_bytes

            def interrupted_write(path, content):
                write_bytes(path, content[:3])
                raise OSError("write refused after partial output")

            with (
                patch.object(
                    diagnostics,
                    "capture",
                    side_effect=(
                        owned,
                        diagnostics.Captured(b"complete dump", 0),
                        owned,
                    ),
                ),
                patch.object(Path, "write_bytes", new=interrupted_write),
            ):
                self.assertEqual(
                    diagnostics.Outcome.UNAVAILABLE,
                    diagnostics.snapshot(candidates(log), root / "dump", token, root),
                )
                self.assertFalse((root / "dump").exists())
                self.assertEqual([], list(root.glob(".daemon-diagnostic-*")))

    def test_wrong_key_substring_or_missing_daemon_identity_never_attaches(self):
        token = "e" * 32
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "bin").mkdir()
            (root / "bin/jcmd").touch()
            log = root / "graph.log"
            log.write_text("The client will now receive all logging from the daemon (pid: 123).\n")
            for tail in (
                f"org.gradle.launcher.daemon.bootstrap.GradleDaemon OTHER_KEY={token}",
                f"org.gradle.launcher.daemon.bootstrap.GradleDaemon {diagnostics.OWNER_KEY}={token}x",
                f"unrelated.Main {diagnostics.OWNER_KEY}={token}",
            ):
                with (
                    self.subTest(tail=tail),
                    patch.object(
                        diagnostics,
                        "capture",
                        return_value=diagnostics.Captured(
                            f"Wed Nov 4 12:00:00 2026 java {tail}\n".encode(), 0
                        ),
                    ) as capture,
                ):
                    self.assertEqual(
                        diagnostics.Outcome.UNAVAILABLE,
                        diagnostics.snapshot(candidates(log), root / "dump", token, root),
                    )
                    self.assertEqual(1, capture.call_count, "rejection must happen before attach")


if __name__ == "__main__":
    unittest.main()
