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

import gradle_graph
import routine_gate
from routine_gate import REQUIRED, inspect, inspect_native_inputs


class RoutineGateTest(unittest.TestCase):
    def test_native_input_inventory_matches_exact_compilation_owners(self):
        owners = {
            "app-server",
            "change:apply",
            "change:contract",
            "change:recovery",
            "change:verify",
            "cli",
            "diagnostic:contract",
            "distribution:cli",
            "distribution:contract",
            "distribution:managed",
            "distribution:output",
            "evidence:contract",
            "evidence:sqlite",
            "kernel",
            "protocol:contract",
            "protocol:registry",
            "protocol:wire",
            "relation:contract",
            "symbol:contract",
            "traversal:contract",
            "workspace:contract",
        }
        tasks = {f":{owner}:compileKotlin" for owner in owners}
        self.assertEqual([], inspect_native_inputs(self.output(tasks)))
        self.assertTrue(
            inspect_native_inputs(self.output(tasks | {":source:intellij:compileKotlin"}))
        )
        for task in tasks:
            self.assertTrue(inspect_native_inputs(self.output(tasks - {task})))
        self.assertTrue(inspect_native_inputs("BUILD SUCCESSFUL"))

    def output(self, tasks):
        return "\n".join(f"{task} SKIPPED" for task in sorted(tasks))

    def test_qualified_candidate_must_also_schedule_source_obligations(self):
        self.assertIn(":sourceValidationGate", REQUIRED)
        self.assertIn(":query:service:scopedCheck", REQUIRED)
        self.assertIn(":distribution:cli:check", REQUIRED)

    def test_required_proofs_remain_in_routine_gate(self):
        self.assertEqual("complete", inspect(self.output(REQUIRED))["status"])

    def test_removing_required_proof_rejects(self):
        for task in REQUIRED:
            with self.subTest(task=task):
                self.assertEqual("rejected", inspect(self.output(REQUIRED - {task}))["status"])

    def test_empty_or_incidental_output_cannot_prove_gate(self):
        self.assertEqual(
            "rejected", inspect("BUILD SUCCESSFUL\nrequested :productBuildGate SKIPPED")["status"]
        )


class RoutineExecutionTest(unittest.TestCase):
    def test_failed_attempt_replaces_prior_success_and_clears_unreached_phase_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = root / "build/reports/ci/routine-gate.json"
            report.parent.mkdir(parents=True)
            report.write_text('{"status":"complete","exitCode":0}\n')
            native = report.parent / "routine-gate/native-inputs/task-graph-execution.json"
            native.parent.mkdir(parents=True)
            native.write_text('{"outcome":"SUCCEEDED"}\n')
            protected = report.parent / "source/task-graph.log"
            protected.parent.mkdir()
            protected.write_text("previous source qualification\n")
            command = [
                sys.executable,
                "-c",
                "print('partial proof', flush=True); raise SystemExit(7)",
            ]
            with (
                patch.object(routine_gate, "ROOT", root),
                patch.object(routine_gate, "PRODUCT_GATE_COMMAND", command),
                patch.object(sys, "argv", ["routine_gate.py"]),
                redirect_stdout(io.StringIO()),
            ):
                code = routine_gate.main()
            self.assertEqual(7, code)
            recorded = json.loads(report.read_text())
            self.assertEqual("rejected", recorded["status"])
            self.assertEqual(7, recorded["exitCode"])
            self.assertFalse(native.exists(), "unreached native phase kept an old success")
            self.assertEqual("previous source qualification\n", protected.read_text())


class Clock:
    now = 0.0

    def __call__(self):
        return self.now


class Console(io.StringIO):
    def __init__(self, observe):
        super().__init__()
        self.observe = observe

    def write(self, value):
        count = super().write(value)
        self.observe(value)
        return count


class RoutinePhaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="routine phases ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.destination = self.root / "build/reports/ci/routine-gate.json"
        self.product_output = "\n".join(f"{task} SKIPPED" for task in sorted(REQUIRED)) + "\n"
        self.native_output = (
            "\n".join(
                f":{owner.replace('/', ':')}:compileKotlin SKIPPED"
                for owner in sorted(routine_gate.NATIVE_INPUT_MODULES)
            )
            + "\n"
        )
        self.product = self.root / "product.py"
        self.native = self.root / "native.py"
        self.write_child(self.product, self.product_output)
        self.write_child(self.native, self.native_output)
        for name, value in (
            ("PRODUCT_GATE_COMMAND", [sys.executable, str(self.product)]),
            ("NATIVE_COMMAND", (sys.executable, str(self.native))),
        ):
            change = patch.object(routine_gate, name, value)
            change.start()
            self.addCleanup(change.stop)

    def write_child(self, path, output, suffix=""):
        path.write_text(
            "import os, signal, sys\nfrom pathlib import Path\n"
            + "Path(__file__ + '.entered').write_text('entered')\n"
            + f"print({output!r}, end='', flush=True)\n"
            + suffix
        )

    def run_gate(self, **kwargs):
        with redirect_stdout(io.StringIO()):
            return routine_gate.run(self.root, self.destination, **kwargs)

    def test_zero_exit_without_product_proofs_never_launches_native(self):
        self.write_child(self.product, ":productBuildGate SKIPPED\n")
        result = self.run_gate()
        self.assertEqual(routine_gate.Outcome.PROOF_REJECTED, result.outcome)
        self.assertEqual(1, result.exitCode)
        self.assertFalse(Path(str(self.native) + ".entered").exists())
        self.assertTrue(result.findings)

    def test_native_inventory_rejects_missing_and_extra_owners(self):
        for output in ("", self.native_output + ":unrelated:compileKotlin SKIPPED\n"):
            with self.subTest(output=output):
                self.write_child(self.native, output)
                result = self.run_gate()
                self.assertEqual(routine_gate.Outcome.PROOF_REJECTED, result.outcome)
                self.assertEqual(routine_gate.Phase.NATIVE_INPUTS, result.phase)
                self.assertEqual(2, len(result.phases))

    def test_complete_looking_failed_process_keeps_its_exit_code(self):
        for phase in routine_gate.Phase:
            with self.subTest(phase=phase):
                self.write_child(
                    self.product,
                    self.product_output,
                    "raise SystemExit(19)\n" if phase is routine_gate.Phase.PRODUCT else "",
                )
                self.write_child(
                    self.native,
                    self.native_output,
                    "raise SystemExit(19)\n" if phase is routine_gate.Phase.NATIVE_INPUTS else "",
                )
                result = self.run_gate()
                self.assertEqual(routine_gate.Outcome.PROCESS_FAILED, result.outcome)
                self.assertEqual(19, result.exitCode)
                self.assertEqual(phase, result.phase)
                self.assertEqual(routine_gate.Status.REJECTED, result.status)

    def test_deadline_and_signal_preserve_partial_output_and_reap_owned_client(self):
        control = subprocess.Popen(
            [sys.executable, "-c", "import signal; signal.pause()"], start_new_session=True
        )
        self.addCleanup(lambda: control.poll() is None and control.kill())
        try:
            for phase in routine_gate.Phase:
                for number in (None, signal.SIGINT, signal.SIGTERM):
                    with self.subTest(phase=phase, signal=number):
                        self.write_child(self.product, self.product_output)
                        self.write_child(self.native, self.native_output)
                        path = self.product if phase is routine_gate.Phase.PRODUCT else self.native
                        output = (
                            self.product_output
                            if phase is routine_gate.Phase.PRODUCT
                            else self.native_output
                        )
                        suffix = "Path(__file__ + '.pid').write_text(str(os.getpid()))\nprint('ready', flush=True)\n"
                        if number is not None:
                            suffix += f"os.kill(os.getppid(), {number})\n"
                        suffix += "signal.pause()\n"
                        self.write_child(path, output, suffix)
                        clock = Clock()

                        def observe(value, number=number, clock=clock):
                            if number is None and "ready\n" in value:
                                clock.now = 600.0

                        def runner(command, root, destination, budget, clock=clock):
                            return gradle_graph.run(command, root, destination, budget, clock=clock)

                        with redirect_stdout(Console(observe)) as console:
                            result = routine_gate.run(self.root, self.destination, runner=runner)
                        expected = (
                            routine_gate.Outcome.TIMED_OUT
                            if number is None
                            else routine_gate.Outcome.CANCELLED
                        )
                        self.assertEqual(expected, result.outcome)
                        self.assertEqual(124 if number is None else 128 + number, result.exitCode)
                        self.assertEqual(phase, result.phase)
                        self.assertEqual(
                            gradle_graph.Cleanup.CLIENT_REAPED, result.phases[-1].result.cleanup
                        )
                        self.assertIsNone(control.poll(), "unrelated child was retired")
                        with self.assertRaises(ChildProcessError):
                            os.waitpid(int(Path(str(path) + ".pid").read_text()), os.WNOHANG)
                        retained = (
                            self.destination.parent
                            / "routine-gate"
                            / phase.value
                            / "task-graph.log"
                        )
                        self.assertIn("ready\n", retained.read_text())
                        self.assertIn('"deadlineSeconds": 600', console.getvalue())
                        self.assertEqual(
                            "rejected", json.loads(self.destination.read_text())["status"]
                        )
        finally:
            control.kill()
            control.wait(timeout=5)

    def test_between_phase_cancellation_cannot_start_the_next_process(self):
        previous = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM)}
        calls = []

        def runner(command, root, destination, budget):
            calls.append(command)
            result = gradle_graph.run(command, root, destination, budget)
            os.kill(os.getpid(), signal.SIGTERM)
            return result

        result = self.run_gate(runner=runner)
        self.assertEqual(143, result.exitCode)
        self.assertEqual(routine_gate.Outcome.CANCELLED, result.outcome)
        self.assertEqual(1, len(calls))
        self.assertFalse(Path(str(self.native) + ".entered").exists())
        for number, handler in previous.items():
            self.assertEqual(handler, signal.getsignal(number))

    def test_publication_cancellation_never_launches_native(self):
        original = routine_gate.retain
        for boundary in ("receipt", "progress"):
            with self.subTest(boundary=boundary):
                native_marker = Path(str(self.native) + ".entered")
                native_marker.unlink(missing_ok=True)

                def retain(path, record, boundary=boundary):
                    original(path, record)
                    if (
                        boundary == "receipt"
                        and record.phase is routine_gate.Phase.NATIVE_INPUTS
                        and record.outcome is routine_gate.Outcome.STARTED
                    ):
                        os.kill(os.getpid(), signal.SIGTERM)

                def observe(value, boundary=boundary):
                    if (
                        boundary == "progress"
                        and '"phase": "native-inputs", "deadlineSeconds"' in value
                    ):
                        os.kill(os.getpid(), signal.SIGTERM)

                with (
                    patch.object(routine_gate, "retain", retain),
                    redirect_stdout(Console(observe)),
                ):
                    result = routine_gate.run(self.root, self.destination)
                self.assertEqual(143, result.exitCode)
                self.assertFalse(native_marker.exists())
                self.assertEqual("CANCELLED", json.loads(self.destination.read_text())["outcome"])

    def test_final_publication_cancellation_cannot_return_success(self):
        self.initialize_git()
        original = routine_gate.retain
        for boundary in ("receipt", "summary"):
            with self.subTest(boundary=boundary):
                sent = []

                def retain(path, record, boundary=boundary, sent=sent):
                    original(path, record)
                    if (
                        boundary == "receipt"
                        and record.status is routine_gate.Status.COMPLETE
                        and not sent
                    ):
                        sent.append(True)
                        os.kill(os.getpid(), signal.SIGTERM)

                def observe(value, boundary=boundary, sent=sent):
                    if boundary == "summary" and '"outcome": "COMPLETE"' in value and not sent:
                        sent.append(True)
                        os.kill(os.getpid(), signal.SIGTERM)

                with (
                    patch.object(routine_gate, "retain", retain),
                    redirect_stdout(Console(observe)),
                ):
                    result = routine_gate.run(self.root, self.destination)
                self.assertTrue(sent)
                self.assertEqual(143, result.exitCode)
                self.assertEqual("rejected", json.loads(self.destination.read_text())["status"])

    def test_missing_executable_is_recorded_for_each_phase(self):
        for phase in routine_gate.Phase:
            with self.subTest(phase=phase):
                name = (
                    "PRODUCT_GATE_COMMAND"
                    if phase is routine_gate.Phase.PRODUCT
                    else "NATIVE_COMMAND"
                )
                with patch.object(routine_gate, name, (str(self.root / "absent"),)):
                    result = self.run_gate()
                self.assertEqual(phase, result.phase)
                self.assertEqual(routine_gate.Outcome.START_FAILED, result.outcome)
                self.assertNotEqual(0, result.exitCode)

    def initialize_git(self):
        environment = {
            key: value for key, value in os.environ.items() if not key.startswith("GIT_")
        }
        environment.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        subprocess.run(["git", "init", "-q"], cwd=self.root, env=environment, check=True)
        subprocess.run(
            [
                "git",
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "core.hooksPath=/dev/null",
                "commit",
                "--allow-empty",
                "-qm",
                "fixture",
            ],
            cwd=self.root,
            env=environment,
            check=True,
        )
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.root, env=environment, text=True
        ).strip()

    def test_success_streams_output_before_child_exit_and_keeps_reports_separate(self):
        revision = self.initialize_git()
        ack = self.root / "ack"
        os.mkfifo(ack)
        suffix = (
            "import select\n"
            f"descriptor = os.open({str(ack)!r}, os.O_RDONLY | os.O_NONBLOCK)\n"
            "print('live-output-é', end='', flush=True)\n"
            "assert select.select([descriptor], [], [], 5)[0], 'progress was buffered'\n"
            "assert os.read(descriptor, 1) == b'!'\n"
        )
        self.write_child(self.product, self.product_output, suffix)

        def observe(value):
            if "live-output-é" in value:
                with ack.open("wb", buffering=0) as writer:
                    writer.write(b"!")

        protected = self.destination.parent / "source/task-graph.log"
        protected.parent.mkdir(parents=True)
        protected.write_text("qualified source evidence")
        with (
            patch.dict(os.environ, {"GIT_DIR": "/absent/foreign.git"}),
            redirect_stdout(Console(observe)),
        ):
            result = routine_gate.run(self.root, self.destination)
        self.assertEqual(routine_gate.Outcome.COMPLETE, result.outcome)
        self.assertEqual(0, result.exitCode)
        self.assertEqual(revision, result.sourceRevision)
        self.assertEqual("qualified source evidence", protected.read_text())
        self.assertEqual(tuple(routine_gate.Phase), tuple(item.phase for item in result.phases))
        for phase in routine_gate.Phase:
            report = (
                self.destination.parent / "routine-gate" / phase.value / "task-graph-execution.json"
            )
            self.assertEqual("SUCCEEDED", json.loads(report.read_text())["outcome"])

    def test_missing_final_identity_fails_closed_after_both_graphs(self):
        result = self.run_gate()
        self.assertEqual(routine_gate.Outcome.SOURCE_UNAVAILABLE, result.outcome)
        self.assertEqual(1, result.exitCode)
        self.assertIsNone(result.sourceRevision)


class RoutineCommandTest(unittest.TestCase):
    def test_native_discovery_uses_bounded_resources_single_use_and_diagnostic_logging(self):
        command = routine_gate.NATIVE_COMMAND
        for argument in (
            "--max-workers=2",
            "-Dorg.gradle.jvmargs=-Xmx5g",
            "--no-daemon",
            "--info",
            "--dry-run",
            "--console=plain",
            "-Pversion=0.0.0",
            "-PcontrolVersion=0.0.0",
            "-PhostedPluginVersion=0.0.0",
        ):
            self.assertIn(argument, command)
        self.assertEqual(
            [":cli:nativeCompile"], [argument for argument in command if argument.startswith(":")]
        )


if __name__ == "__main__":
    unittest.main()
