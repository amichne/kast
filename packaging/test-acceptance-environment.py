"""Offline acceptance harness checks; no installed service or IDE is started."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from acceptance_environment import AcceptanceEnvironment, EnvironmentFailure, EnvironmentRejected, GradleDaemonIdentity, GradleRetirement


def load_script(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ProbeComplete(Exception):
    pass


class StartupEnvironmentTest(unittest.TestCase):
    def test_missing_harness_entry_is_rejected_before_creating_worker_environment(self):
        script = load_script("test-model-input-startup")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            classpath = root / "classpath"
            classpath.write_text(str(root / "absent-java-classes"))
            argv = ["startup", "--product", str(root), "--runtime", str(root / "runtime.zip"),
                    "--idea-home", str(root), "--harness-classpath-file", str(classpath),
                    "--report", str(root / "report.json")]
            with patch.object(sys, "argv", argv), patch.object(script, "AcceptanceEnvironment") as environment:
                with self.assertRaisesRegex(AssertionError, "missing or relative entries"):
                    script.main()
                environment.assert_not_called()

    def test_model_startup_does_not_inherit_home_or_credentials(self):
        script = load_script("test-model-input-startup")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime = root / "runtime.zip"
            runtime.write_bytes(b"fixture runtime")
            product = root / "product"
            (product / "bin").mkdir(parents=True)
            launcher = product / "bin/kast"
            launcher.write_text("#!/bin/sh\nexit 0\n")
            launcher.chmod(0o700)
            metadata = product / "share/kast"
            metadata.mkdir(parents=True)
            (metadata / "configuration-schema.json").write_text(json.dumps({"parameters": [
                {"key": "GRADLE_USER_HOME", "mutability": "USER_SETTING", "sources": ["SAVED_INSTALLATION"]}]}))
            idea = root / "idea"
            idea.mkdir()
            (root / "classpath").write_text(str(Path(sys.executable).resolve()))
            captured = {}
            saved = []

            def probe(command, **kwargs):
                if "start" in command:
                    captured.update(kwargs["env"])
                    saved.append(Path(captured["KAST_CONFIGURATION_FILE"]).read_text())
                    raise ProbeComplete()
                return subprocess.CompletedProcess(command, 0, "", "")

            argv = ["startup", "--product", str(product), "--runtime", str(runtime),
                    "--idea-home", str(idea), "--harness-classpath-file", str(root / "classpath"), "--report", str(root / "report.json")]
            try:
                with patch.object(sys, "argv", argv), patch.dict(os.environ, {
                    "HOME": str(root / "live-home"), "CODEX_HOME": str(root / "live-codex"),
                    "GRADLE_USER_HOME": str(root / "live-gradle"), "OPENAI_API_KEY": "fixture-secret",
                    "_JAVA_OPTIONS": "-Duser.home=/live-jvm-home", "KAST_INJECTED": "untrusted",
                }), patch.object(script, "source_provenance", return_value={"sourceHead": "a" * 40, "sourceTreeClean": False}), \
                        patch.object(script, "stage_versioned_product", side_effect=lambda fixture, source, runtime: fixture.stage_product(source)), \
                        patch.object(script.subprocess, "run", side_effect=probe):
                    with self.assertRaises(ProbeComplete):
                        script.main()
                self.assertNotEqual(captured["HOME"], str(root / "live-home"))
                self.assertNotIn("OPENAI_API_KEY", captured)
                self.assertNotIn("KAST_INJECTED", captured)
                self.assertIn("GRADLE_USER_HOME=" + captured["GRADLE_USER_HOME"], saved[0],
                              "lifecycle children must reload the same Gradle home and launch identity")
                self.assertEqual(Path(captured["KAST_RUNTIME_DIRECTORY"]), Path(captured["HOME"]).parent / "product/state/run")
                fixture = Path(captured["HOME"]).parent
                for name in ("HOME", "CODEX_HOME", "GRADLE_USER_HOME", "XDG_CONFIG_HOME", "TMPDIR"):
                    self.assertTrue(Path(captured[name]).is_relative_to(fixture), name)
                self.assertIn("-Duser.home=" + captured["HOME"], shlex.split(captured["_JAVA_OPTIONS"]))
            finally:
                if captured:
                    shutil.rmtree(Path(captured["HOME"]).parent)

    def test_installed_product_artifact_inputs_never_reach_runtime_environment(self):
        script = load_script("run-installed-product")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            product = root / "product"
            product.mkdir()
            (product / "payload").write_text("fixture")
            control = root / "control.zip"
            runtime = root / "runtime.zip"
            control.write_bytes(b"control")
            runtime.write_bytes(b"runtime")
            inputs = {"KAST_INSTALLED_PRODUCT": str(product), "KAST_CONTROL_ARCHIVE": str(control),
                      "KAST_SEMANTIC_RUNTIME_ARCHIVE": str(runtime), "KAST_INSTALLED_REPORT_DIRECTORY": str(root / "report")}
            captured = {}
            invocation = []
            def probe(command, **kwargs):
                invocation.extend(command)
                captured.update(kwargs["env"])
                raise ProbeComplete()
            try:
                with patch.dict(os.environ, inputs | {"KAST_ACCEPTANCE_PROFILE": "parent-only", "KAST_ACCEPTANCE_IDEA_HOME": "/parent/idea", "KAST_CODEX_ACCEPTANCE_VERSION": "parent-only"}), \
                        patch.object(script.subprocess, "run", side_effect=probe):
                    with self.assertRaises(ProbeComplete):
                        script.main()
                for key in inputs.keys() | {"KAST_ACCEPTANCE_PROFILE", "KAST_ACCEPTANCE_IDEA_HOME", "KAST_CODEX_ACCEPTANCE_VERSION"}:
                    self.assertNotIn(key, captured, key + " leaked into runtime children")
                self.assertIn("--product", invocation)
                self.assertIn(str(runtime.resolve()), invocation)
                self.assertIn("KAST_RUNTIME_DIRECTORY", captured)
                self.assertTrue(Path(captured["KAST_RUNTIME_DIRECTORY"]).is_relative_to(Path(captured["HOME"]).parent))
            finally:
                if captured:
                    shutil.rmtree(Path(captured["HOME"]).parent)

    def test_host_version_probe_is_already_isolated(self):
        script = load_script("test-installed-codex-host")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "bin").mkdir()
            for name in ("kast", "kast-codex", "codex"):
                path = root / "bin" / name
                path.write_text("#!/bin/sh\nexit 0\n")
                path.chmod(0o700)
            captured = {}

            def probe(command, **kwargs):
                captured.update(kwargs.get("env", os.environ))
                raise ProbeComplete()

            try:
                with patch.object(sys, "argv", ["host", str(root), str(root), str(root / "report")]), \
                        patch.dict(os.environ, {"KAST_ACCEPTANCE_CODEX_EXECUTABLE": str(root / "bin/codex"),
                                                "OPENAI_API_KEY": "fixture-secret"}), \
                        patch.object(script.subprocess, "run", side_effect=probe):
                    with self.assertRaises(ProbeComplete):
                        script.main()
                self.assertNotIn("OPENAI_API_KEY", captured)
                self.assertNotEqual(captured["HOME"], os.environ["HOME"])
                self.assertEqual(captured["KAST_ENABLE_LAUNCHD"], "0")
            finally:
                if "KAST_RUNTIME_DIRECTORY" in captured:
                    shutil.rmtree(Path(captured["HOME"]).parent)


class PrivateEnvironmentTest(unittest.TestCase):
    def test_terminal_report_never_passes_before_successful_tree_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory).resolve() / 'report.json'
            fixture = self.fixture()
            evidence = {'checksPassed': True}
            with fixture:
                fixture.report_after_cleanup(report, evidence)
                fixture.mark_passed()
                self.assertFalse(json.loads(report.read_text())['passed'])
            self.assertFalse(fixture.root.exists())
            self.assertTrue(json.loads(report.read_text())['passed'])
            self.assertEqual(evidence['fixtureCleanup']['outcome'], 'removed')

    def test_partial_tree_removal_failure_overrides_successful_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory).resolve() / 'report.json'
            fixture = self.fixture()
            try:
                with patch('acceptance_environment.shutil.rmtree', side_effect=OSError(66, 'Directory not empty')):
                    with self.assertRaises(EnvironmentRejected):
                        with fixture:
                            fixture.report_after_cleanup(report, {'checksPassed': True})
                            fixture.mark_passed()
                evidence = json.loads(report.read_text())
                self.assertTrue(evidence['checksPassed'])
                self.assertFalse(evidence['passed'])
                self.assertEqual(evidence['fixtureCleanup'], {'outcome': 'retained-cleanup-failure', 'retained': True, 'stage': 'fixture-tree'})
            finally:
                shutil.rmtree(fixture.root)

    def test_gradle_stop_acknowledgement_waits_for_exact_process_exit(self):
        with self.fixture() as fixture:
            identity = GradleDaemonIdentity(101, 'Tue Sep 8 21:00:00 2026', 'private fixture daemon')
            with patch.object(fixture, '_gradle_daemons', side_effect=[(identity,), (identity,), ()]) as observed, \
                    patch('acceptance_environment.time.sleep'):
                self.assertEqual(fixture.await_gradle_retirement((identity,)), GradleRetirement.RETIRED)
                self.assertEqual(observed.call_count, 3)
            fixture.mark_passed()

    def test_unseen_private_gradle_process_is_retained_without_signal(self):
        with self.fixture() as fixture:
            original = GradleDaemonIdentity(101, 'original', 'original private daemon')
            replacement = GradleDaemonIdentity(102, 'new', 'new private daemon')
            with patch.object(fixture, '_gradle_daemons', return_value=(replacement,)), patch('os.kill') as kill:
                self.assertEqual(fixture.await_gradle_retirement((original,)), GradleRetirement.REJECTED)
                kill.assert_not_called()
            fixture.mark_passed()

    def test_gradle_observation_requires_private_classpath_and_carries_start_and_command(self):
        with AcceptanceEnvironment({'ps': Path('/bin/ps')}) as fixture:
            private = f'/jdk/bin/java -cp {fixture.root}/gradle/wrapper/dists/gradle-9.4.1/x/gradle-9.4.1/lib/gradle-daemon-main-9.4.1.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.4.1'
            foreign = private.replace(str(fixture.root), '/foreign')
            result = subprocess.CompletedProcess([], 0,
                f'101 Tue Sep 8 21:00:00 2026 {private}\n102 Tue Sep 8 21:00:00 2026 {foreign}\n', '')
            with patch('acceptance_environment.subprocess.run', return_value=result):
                self.assertEqual(fixture.capture_gradle_daemons(),
                    (GradleDaemonIdentity(101, 'Tue Sep 8 21:00:00 2026', private),))
            fixture.mark_passed()

    def test_gradle_wait_rejects_pid_reuse_and_times_out_without_signalling(self):
        with self.fixture() as fixture:
            original = GradleDaemonIdentity(101, 'old start', 'same command')
            replacement = GradleDaemonIdentity(101, 'new start', 'same command')
            with patch.object(fixture, '_gradle_daemons', return_value=(replacement,)), patch('os.kill') as kill:
                self.assertEqual(fixture.await_gradle_retirement((original,)), GradleRetirement.REJECTED)
                kill.assert_not_called()
            with patch.object(fixture, '_gradle_daemons', return_value=(original,)), patch('os.kill') as kill:
                self.assertEqual(fixture.await_gradle_retirement((original,), timeout=0), GradleRetirement.TIMED_OUT)
                kill.assert_not_called()
            fixture.mark_passed()

    def test_tree_cleanup_failure_is_finite_and_retains_failure_evidence(self):
        fixture = self.fixture()
        try:
            with patch('acceptance_environment.shutil.rmtree', side_effect=OSError(66, 'Directory not empty')):
                with self.assertRaises(EnvironmentRejected) as rejected:
                    with fixture:
                        fixture.mark_passed()
            self.assertEqual(rejected.exception.condition, EnvironmentFailure.CLEANUP_FAILED)
            document = json.loads((fixture.root / 'acceptance-outcome.json').read_text())
            self.assertEqual(document['outcome'], 'cleanup-failed')
            self.assertFalse(fixture._passed)
        finally:
            shutil.rmtree(fixture.root)

    def fixture(self, **kwargs):
        return AcceptanceEnvironment({"python3": Path(sys.executable)}, **kwargs)

    def test_child_sees_only_admitted_environment_and_tools(self):
        with patch.dict(os.environ, {"AWS_SECRET_ACCESS_KEY": "fixture-secret", "JAVA_TOOL_OPTIONS": "untrusted"}):
            with self.fixture() as fixture:
                child = fixture.spawn([str(fixture.tools["python3"]), "-c",
                    "import json,os; print(json.dumps(dict(os.environ)))"], stdout=subprocess.PIPE, text=True)
                import json
                observed = json.loads(child.communicate(timeout=10)[0])
                self.assertEqual(child.returncode, 0)
                self.assertNotIn("AWS_SECRET_ACCESS_KEY", observed)
                self.assertNotEqual(observed["JAVA_TOOL_OPTIONS"], "untrusted")
                self.assertEqual(observed["HTTPS_PROXY"], "http://127.0.0.1:9")
                self.assertEqual(observed["NO_PROXY"], "127.0.0.1,localhost,::1")
                self.assertEqual(set(Path(observed["PATH"]).iterdir()), {fixture.root / "tools/python3"})
                self.assertEqual(fixture.root.stat().st_mode & 0o777, 0o700)
                fixture.mark_passed()
            self.assertFalse(fixture.root.exists())

    def test_failure_retains_bounded_evidence(self):
        fixture = self.fixture()
        try:
            with self.assertRaises(ProbeComplete):
                with fixture:
                    raise ProbeComplete("unbounded payload must not be recorded")
            evidence = (fixture.root / "acceptance-outcome.json").read_text()
            self.assertIn('"retained": true', evidence)
            self.assertNotIn("payload", evidence)
        finally:
            shutil.rmtree(fixture.root)

    def test_cleanup_terminates_only_fixture_owned_process(self):
        sibling = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        try:
            with self.fixture() as fixture:
                owned = fixture.spawn([sys.executable, "-c", "import time; time.sleep(60)"])
                fixture.mark_passed()
            self.assertIsNotNone(owned.poll())
            self.assertIsNone(sibling.poll())
        finally:
            sibling.terminate()
            sibling.wait(timeout=5)

    def test_invalid_tool_and_root_fail_closed(self):
        for tools in ({}, {"python3": Path("relative")}, {"../escape": Path(sys.executable)},
                      {"python3": Path("/definitely-missing-kast-tool")}):
            with self.assertRaises(EnvironmentRejected) as failure:
                AcceptanceEnvironment(tools)
            self.assertEqual(failure.exception.condition, EnvironmentFailure.INVALID_TOOL)
        with self.assertRaises(EnvironmentRejected) as failure:
            self.fixture(parent=Path("relative"))
        self.assertEqual(failure.exception.condition, EnvironmentFailure.INVALID_ROOT)

    def test_two_staged_installations_cannot_change_each_other_or_the_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            (source / "version").write_text("original")
            with self.fixture() as first, self.fixture() as second:
                left = first.stage_product(source)
                right = second.stage_product(source)
                (left / "version").write_text("upgraded")
                self.assertEqual((right / "version").read_text(), "original")
                self.assertEqual((source / "version").read_text(), "original")
                self.assertFalse(left.is_symlink())
                first.mark_passed()
                second.mark_passed()

    def test_versioned_product_manifest_admits_exact_copied_bytes_and_owned_paths(self):
        from installed_acceptance_product import stage_versioned_product
        lifecycle = load_script("installation-lifecycle")
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "source"
            for directory in ("bin", "lib", "share/kast"):
                (source / directory).mkdir(parents=True)
            launcher = source / "bin/kast"
            launcher.write_text("#!/bin/sh\nexit 0\n")
            launcher.chmod(0o755)
            runtime = Path(temporary) / "runtime.zip"
            runtime.write_bytes(b"owned runtime fixture")
            (source / "share/kast/semantic-runtime.json").write_text(json.dumps({
                "productVersion": "0.36.1-dev", "archive": {"sha256": "sha256:" + hashlib.sha256(runtime.read_bytes()).hexdigest()}}))
            with self.fixture() as fixture:
                product = stage_versioned_product(fixture, source, runtime)
                admitted = lifecycle.Installation.admit(str(product))
                self.assertEqual(admitted.root, product)
                self.assertEqual(fixture.environment["KAST_RUNTIME_DIRECTORY"], str(product / "state/run"))
                self.assertEqual(fixture.environment["KAST_CACHE_ROOT"], str(product / "state/cache"))
                self.assertEqual(fixture.environment["KAST_RUNTIME_STORE"], str(product / "runtime-payloads"))
                (product / "bin/kast").write_text("changed payload")
                with self.assertRaises(lifecycle.Rejected):
                    lifecycle.Installation.admit(str(product))
                self.assertEqual(launcher.read_text(), "#!/bin/sh\nexit 0\n")
                fixture.mark_passed()

    def test_product_shell_boundary_copies_inputs_and_drops_ambient_controls(self):
        runner = load_script("run-installed-product")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            product = root / "source"
            product.mkdir()
            (product / "version").write_text("original")
            archive = root / "archive"
            archive.write_bytes(b"archive")
            inputs = {"KAST_INSTALLED_PRODUCT": str(product), "KAST_CONTROL_ARCHIVE": str(archive),
                      "KAST_SEMANTIC_RUNTIME_ARCHIVE": str(archive),
                      "KAST_INSTALLED_REPORT_DIRECTORY": str(root / "reports"),
                      "KAST_ENABLE_APP_SERVER": "1", "OPENAI_API_KEY": "fixture-secret"}

            def probe(command, **kwargs):
                env = kwargs["env"]
                self.assertNotIn("OPENAI_API_KEY", env)
                self.assertEqual(env["KAST_ENABLE_APP_SERVER"], "0")
                copied = Path(command[command.index("--product") + 1])
                self.assertNotEqual(copied, product)
                self.assertEqual((copied / "version").read_text(), "original")
                self.assertEqual(command[2], "--isolated-fixture")
                self.assertNotIn("KAST_INSTALLED_PRODUCT", env)
                return subprocess.CompletedProcess(command, 0)

            with patch.dict(os.environ, inputs), patch.object(runner.subprocess, "run", side_effect=probe) as child:
                runner.main()
            child.assert_called_once()

    def test_replaced_root_is_never_removed(self):
        fixture = self.fixture()
        with tempfile.TemporaryDirectory() as unrelated:
            marker = Path(unrelated) / "untouched"
            marker.write_text("user state")
            shutil.rmtree(fixture.root)
            fixture.root.symlink_to(unrelated, target_is_directory=True)
            try:
                with self.assertRaises(EnvironmentRejected):
                    with fixture:
                        fixture.mark_passed()
                self.assertEqual(marker.read_text(), "user state")
            finally:
                fixture.root.unlink()

    def create_endpoint_alias(self, fixture):
        target = fixture.root / "product/state/run"
        target.mkdir(parents=True, mode=0o700)
        alias = Path("/tmp") / ("kast-uds-" + hashlib.sha256(str(target).encode()).hexdigest()[:32])
        alias.symlink_to(target, target_is_directory=True)

        def identity(path):
            observed = path.lstat()
            return {"device": observed.st_dev, "inode": observed.st_ino, "owner": observed.st_uid}

        receipt = target / "endpoint-alias.json"
        receipt.write_text(json.dumps({"schemaVersion": 1, "alias": str(alias), "target": str(target),
                                       "aliasIdentity": identity(alias), "targetIdentity": identity(target)}))
        receipt.chmod(0o600)
        return alias, target

    def test_success_retires_only_the_declared_endpoint_alias(self):
        fixture = self.fixture()
        alias, target = self.create_endpoint_alias(fixture)
        try:
            with fixture:
                fixture.mark_passed()
            self.assertFalse(alias.is_symlink(), "owned external endpoint alias must be retired before its target")
        finally:
            if alias.is_symlink() and os.readlink(alias) == str(target):
                alias.unlink()
            if fixture.root.exists():
                shutil.rmtree(fixture.root)

    def test_alias_replacement_retains_fixture_and_never_removes_foreign_target(self):
        fixture = self.fixture()
        alias, target = self.create_endpoint_alias(fixture)
        held = alias.with_name(alias.name + ".held")
        alias.rename(held)
        with tempfile.TemporaryDirectory() as temporary:
            foreign = Path(temporary)
            marker = foreign / "untouched"
            marker.write_text("user state")
            alias.symlink_to(foreign, target_is_directory=True)
            try:
                with self.assertRaises(EnvironmentRejected) as rejected:
                    with fixture:
                        fixture.mark_passed()
                self.assertEqual(rejected.exception.condition, EnvironmentFailure.CLEANUP_FAILED)
                self.assertTrue(fixture.root.exists())
                self.assertTrue(alias.is_symlink())
                self.assertEqual(marker.read_text(), "user state")
            finally:
                alias.unlink()
                held.unlink()
                shutil.rmtree(fixture.root)


if __name__ == "__main__":
    unittest.main()
