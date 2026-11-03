"""Real owned children prove streaming, deadlines, cancellation and isolation."""

import io
import json
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import gradle_graph as graph


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class Console(io.StringIO):
    def __init__(self, observe):
        super().__init__()
        self.observe = observe

    def write(self, value):
        written = super().write(value)
        self.observe(value)
        return written


class GraphProcessTest(unittest.TestCase):
    def test_live_unterminated_output_must_arrive_before_child_can_finish(self):
        for code in (0, 7):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                ack = root / "ack with spaces"
                os.mkfifo(ack)
                child = """import os, select, sys
descriptor = os.open(sys.argv[1], os.O_RDONLY | os.O_NONBLOCK)
assert sys.argv[2] == 'literal ; $(not-a-command)'
print('graph ready é', end='', flush=True)
assert select.select([descriptor], [], [], 5)[0], 'console did not acknowledge progress'
assert os.read(descriptor, 1) == b'!'
print(' completed', file=sys.stderr, flush=True)
raise SystemExit(int(sys.argv[3]))
"""

                def observed(value, ack=ack):
                    if "graph ready é" in value:
                        with ack.open("wb", buffering=0) as writer:
                            writer.write(b"!")

                with redirect_stdout(Console(observed)) as console:
                    result = graph.run(
                        (
                            sys.executable,
                            "-c",
                            child,
                            str(ack),
                            "literal ; $(not-a-command)",
                            str(code),
                        ),
                        root,
                        root,
                    )
                self.assertEqual(code, result.exitCode)
                self.assertEqual(
                    graph.Outcome.SUCCEEDED if code == 0 else graph.Outcome.PROCESS_FAILED,
                    result.outcome,
                )
                self.assertEqual("graph ready é completed\n", (root / "task-graph.log").read_text())
                self.assertIn("graph ready é completed\n", console.getvalue())
                report = json.loads((root / "task-graph-execution.json").read_text())
                self.assertEqual(code, report["exitCode"])
                self.assertEqual("NOT_REQUESTED", report["diagnostic"])

    def test_deadline_reports_quiet_progress_and_reaps_only_owned_process(self):
        for ignore_term in (False, True):
            with self.subTest(ignore_term=ignore_term), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                child = """import os, signal, sys
from pathlib import Path
if sys.argv[2] == 'True':
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
Path(sys.argv[1]).write_text(str(os.getpid()))
print('ready', flush=True)
signal.pause()
"""
                clock = Clock()

                def observed(value, clock=clock):
                    if value == "ready\n":
                        clock.now = 15.0
                    if '"state": "RUNNING"' in value:
                        clock.now = 16.0

                control = subprocess.Popen(
                    (sys.executable, "-c", "import signal; signal.pause()"), start_new_session=True
                )
                try:
                    with redirect_stdout(Console(observed)) as console:
                        result = graph.run(
                            (sys.executable, "-c", child, str(root / "pid"), str(ignore_term)),
                            root,
                            root,
                            graph.Budget(16),
                            clock=clock,
                        )
                    self.assertIsNone(control.poll(), "unrelated process was retired")
                finally:
                    control.kill()
                    control.wait(timeout=5)
                self.assertEqual(graph.Outcome.TIMED_OUT, result.outcome)
                self.assertEqual(124, result.exitCode)
                self.assertEqual(graph.Cleanup.CLIENT_REAPED, result.cleanup)
                self.assertEqual(graph.Diagnostic.RETAINED, result.diagnostic)
                with self.assertRaises(ChildProcessError):
                    os.waitpid(int((root / "pid").read_text()), os.WNOHANG)
                progress = [
                    json.loads(line.removeprefix("source-validation: task-graph "))
                    for line in console.getvalue().splitlines()
                    if line.startswith("source-validation: task-graph ")
                ]
                self.assertEqual(
                    {
                        "state": "RUNNING",
                        "processId": int((root / "pid").read_text()),
                        "elapsedMilliseconds": 15000,
                        "quietMilliseconds": 15000,
                        "deadlineSeconds": 16,
                    },
                    progress[1],
                )
                self.assertEqual("TIMED_OUT", progress[-1]["outcome"])
                self.assertEqual("ready\n", (root / "task-graph.log").read_text())
                snapshot = (root / "task-graph-process.txt").read_text()
                self.assertLess(len(snapshot), 4200)
                self.assertNotIn(child, snapshot)

    def test_cancellation_keeps_signal_identity_and_restores_callers_handlers(self):
        for number in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(number=number), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                handlers = {
                    item: signal.getsignal(item) for item in (signal.SIGINT, signal.SIGTERM)
                }
                child = """import os, signal, sys
from pathlib import Path
Path(sys.argv[1]).write_text(str(os.getpid()))
print('partial graph', flush=True)
os.kill(os.getppid(), int(sys.argv[2]))
signal.pause()
"""
                with redirect_stdout(io.StringIO()):
                    result = graph.run(
                        (sys.executable, "-c", child, str(root / "pid"), str(number)), root, root
                    )
                self.assertEqual(graph.Outcome.CANCELLED, result.outcome)
                self.assertEqual(128 + number, result.exitCode)
                self.assertEqual(graph.Cleanup.CLIENT_REAPED, result.cleanup)
                self.assertEqual("partial graph\n", (root / "task-graph.log").read_text())
                with self.assertRaises(ChildProcessError):
                    os.waitpid(int((root / "pid").read_text()), os.WNOHANG)
                for item, handler in handlers.items():
                    self.assertEqual(handler, signal.getsignal(item))

    def test_deadline_retires_a_child_in_the_owned_group(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            child = """import signal, subprocess, sys
from pathlib import Path
worker = subprocess.Popen([sys.executable, '-c', "import signal; print('worker ready', flush=True); signal.pause()"])
def retire(number, frame):
    Path(sys.argv[1]).write_text(str(worker.wait(timeout=2)))
    raise SystemExit(0)
signal.signal(signal.SIGTERM, retire)
print('parent ready', flush=True)
signal.pause()
"""
            clock = Clock()
            ready = set()

            def observed(value):
                ready.update(value.splitlines())
                if {"worker ready", "parent ready"} <= ready:
                    clock.now = 600.0

            with redirect_stdout(Console(observed)):
                result = graph.run(
                    (sys.executable, "-c", child, str(root / "retired")), root, root, clock=clock
                )
            self.assertEqual(graph.Outcome.TIMED_OUT, result.outcome)
            self.assertEqual(graph.Cleanup.CLIENT_REAPED, result.cleanup)
            self.assertEqual(str(-signal.SIGTERM), (root / "retired").read_text())

    def test_start_refusal_removes_stale_evidence_without_manufacturing_success(self):
        with tempfile.TemporaryDirectory() as temporary, redirect_stdout(io.StringIO()):
            root = Path(temporary)
            for name in graph.REPORTS:
                (root / name).write_text("stale")
            result = graph.run((str(root / "missing executable"),), root, root)
            self.assertEqual(graph.Outcome.START_FAILED, result.outcome)
            self.assertEqual(1, result.exitCode)
            self.assertEqual(b"", (root / "task-graph.log").read_bytes())
            self.assertFalse((root / "task-graph-process.txt").exists())
            self.assertEqual(
                {
                    "outcome": "START_FAILED",
                    "exitCode": 1,
                    "elapsedMilliseconds": result.elapsedMilliseconds,
                    "diagnostic": "NOT_REQUESTED",
                    "cleanup": "NOT_REQUIRED",
                },
                json.loads((root / "task-graph-execution.json").read_text()),
            )

    def test_unavailable_diagnostic_never_replaces_timeout_or_prevents_cleanup(self):
        for failure, expected in (
            (OSError(), graph.Diagnostic.UNAVAILABLE),
            (subprocess.TimeoutExpired("ps", 2), graph.Diagnostic.TIMED_OUT),
        ):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                clock = Clock()

                def observed(value, clock=clock):
                    if value == "ready\n":
                        clock.now = 600.0

                child = "import signal; print('ready', flush=True); signal.pause()"
                with (
                    patch.object(graph.subprocess, "run", side_effect=failure),
                    redirect_stdout(Console(observed)),
                ):
                    result = graph.run((sys.executable, "-c", child), root, root, clock=clock)
                self.assertEqual(graph.Outcome.TIMED_OUT, result.outcome)
                self.assertEqual(expected, result.diagnostic)
                self.assertEqual(graph.Cleanup.CLIENT_REAPED, result.cleanup)
                self.assertFalse((root / "task-graph-process.txt").exists())

    def test_snapshot_write_refusal_preserves_the_timeout_and_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            clock = Clock()

            def observed(value):
                if value == "ready\n":
                    clock.now = 600.0

            with (
                patch.object(Path, "write_bytes", side_effect=OSError("snapshot refused")),
                redirect_stdout(Console(observed)),
            ):
                result = graph.run(
                    (
                        sys.executable,
                        "-c",
                        "import signal; print('ready', flush=True); signal.pause()",
                    ),
                    root,
                    root,
                    clock=clock,
                )
            self.assertEqual(graph.Outcome.TIMED_OUT, result.outcome)
            self.assertEqual(graph.Diagnostic.UNAVAILABLE, result.diagnostic)
            self.assertEqual(graph.Cleanup.CLIENT_REAPED, result.cleanup)


if __name__ == "__main__":
    unittest.main()
