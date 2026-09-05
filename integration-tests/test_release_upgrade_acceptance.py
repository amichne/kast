#!/usr/bin/env python3
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import release_upgrade_acceptance as acceptance


class UpgradeAcceptanceTest(unittest.TestCase):
    def assert_cause(self, expected, action):
        with self.assertRaises(acceptance.UpgradeFailure) as observed:
            action()
        self.assertEqual(expected, observed.exception.cause)

    def test_selects_latest_immutable_published_zero_release(self):
        def release(tag, **extra):
            return {"tag_name": tag, "draft": False, "prerelease": False, "immutable": True, **extra}
        releases = [release("v0.9.0"), release("v0.32.2"), release("v1.0.0"), release("v0.33.0", prerelease=True), release("v0.34.0", draft=True)]
        try:
            result = acceptance.select_prior_release(releases, "1.0.0")
        except acceptance.UpgradeFailure:
            self.fail("latest immutable 0.x baseline was not admitted")
        self.assertEqual("v0.32.2", result["tag_name"])

    def test_publishing_candidate_or_newer_release_does_not_change_prior(self):
        prior = {"tag_name": "v0.32.2", "draft": False, "prerelease": False, "immutable": True}
        releases = [prior, {**prior, "tag_name": "v0.33.0"}, {**prior, "tag_name": "v0.34.0"}]
        self.assertEqual(prior, acceptance.select_prior_release(releases, "0.33.0"))
        self.assert_cause(acceptance.Cause.PRIOR_RELEASE_UNAVAILABLE,
                          lambda: acceptance.select_prior_release(releases[1:], "0.33.0"))

    def test_missing_ambiguous_or_mutable_latest_release_cannot_fall_back(self):
        release = {"tag_name": "v0.32.2", "draft": False, "prerelease": False, "immutable": True}
        self.assert_cause(acceptance.Cause.PRIOR_RELEASE_UNAVAILABLE, lambda: acceptance.select_prior_release([], "1.0.0"))
        self.assert_cause(acceptance.Cause.PRIOR_RELEASE_UNAVAILABLE, lambda: acceptance.select_prior_release([release, release], "1.0.0"))
        newer = {**release, "tag_name": "v0.33.0", "immutable": False}
        self.assert_cause(acceptance.Cause.PRIOR_RELEASE_MUTABLE, lambda: acceptance.select_prior_release([release, newer], "1.0.0"))

    def test_prior_download_checks_every_github_digest_and_size(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            payloads = {name: name.encode() for name in acceptance.asset_names("0.32.2")}
            source = root / "source"
            source.mkdir()
            for name, content in payloads.items():
                (source / name).write_bytes(content)
            release = {"tag_name": "v0.32.2", "draft": False, "prerelease": False, "immutable": True,
                       "assets": [{"name": name, "digest": acceptance.file_digest(source / name), "size": len(data)} for name, data in payloads.items()]}

            def github(arguments, **options):
                if arguments[1] == "api":
                    self.assertIn("--paginate", arguments)
                    self.assertIn("--slurp", arguments)
                    return json.dumps([[release]])
                name = arguments[arguments.index("--pattern") + 1]
                destination = Path(arguments[arguments.index("--dir") + 1])
                (destination / name).write_bytes(payloads[name])
                return ""

            with mock.patch.object(acceptance, "required", side_effect=github):
                prior = acceptance.download_prior_assets(root / "accepted", "1.0.0")
                self.assertEqual(4, len(prior["assets"]))
                self.assertEqual(acceptance.identity([release]), prior["releaseCatalogDigest"])
                release["assets"][1]["digest"] = "sha256:" + "0" * 64
                self.assert_cause(acceptance.Cause.PRIOR_ASSET_IDENTITY_MISMATCH,
                                  lambda: acceptance.download_prior_assets(root / "tampered", "1.0.0"))
                release["assets"] = []
                self.assert_cause(acceptance.Cause.PRIOR_ASSET_UNAVAILABLE,
                                  lambda: acceptance.download_prior_assets(root / "missing", "1.0.0"))

    def test_corruption_derives_from_exact_candidate_without_modifying_original(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "assets"
            make_assets(source, "1.0.0")
            prior = {name: acceptance.file_digest(source / name) for name in acceptance.asset_names("1.0.0")}
            checksum = root / "checksum"
            acceptance.corrupt_assets(source, checksum, "1.0.0", acceptance.Corruption.CHECKSUM_MISMATCH)
            self.assertEqual(prior[acceptance.asset_names("1.0.0")[0]], acceptance.file_digest(checksum / acceptance.asset_names("1.0.0")[0]))
            unsafe = root / "unsafe"
            acceptance.corrupt_assets(source, unsafe, "1.0.0", acceptance.Corruption.UNSAFE_ARCHIVE_PATH)
            control = unsafe / acceptance.asset_names("1.0.0")[0]
            with tarfile.open(control) as archive:
                self.assertEqual(["bin/kast", "../escape"], archive.getnames())
                self.assertEqual(b"candidate-control", archive.extractfile("bin/kast").read())
            self.assertEqual(acceptance.file_digest(control).removeprefix("sha256:"), control.with_name(control.name + ".sha256").read_text().split()[0])
            self.assertEqual(prior, {name: acceptance.file_digest(source / name) for name in acceptance.asset_names("1.0.0")})

    def test_unrelated_or_generic_failure_cannot_prove_corruption_rejection(self):
        accepted = subprocess.CompletedProcess([], 1, "", "  x kast-install: control archive contains an unsafe path: ../escape\n")
        acceptance.assert_corruption_rejected(accepted, acceptance.Corruption.UNSAFE_ARCHIVE_PATH, "1.0.0")
        for result in [subprocess.CompletedProcess([], 0, "", accepted.stderr),
                       subprocess.CompletedProcess([], 1, "", "Java is unavailable\n"),
                       subprocess.CompletedProcess([], 1, "not final JSON", accepted.stderr),
                       subprocess.CompletedProcess([], 2, "", accepted.stderr)]:
            self.assert_cause(acceptance.Cause.CORRUPTION_NOT_REJECTED,
                              lambda: acceptance.assert_corruption_rejected(result, acceptance.Corruption.UNSAFE_ARCHIVE_PATH, "1.0.0"))

    def test_actual_fixture_commands_upgrade_without_start_and_prove_preservation(self):
        with UpgradeFixture() as fixture:
            environment, proof = fixture.run()
            self.assertEqual("passed", proof["status"])
            self.assertEqual(["0.32.2", "1.0.0"], fixture.installed_versions)
            self.assertEqual(["checksum-mismatch", "unsafe-archive-path"], [case["case"] for case in proof["corruptionCases"]])
            self.assertEqual({"--version", "status"}, set(fixture.commands.read_text().splitlines()))
            self.assertNotIn("KAST_RUNTIME_ARCHIVE", environment)
            self.assertEqual(fixture.environment, {"PATH": os.environ["PATH"], "KAST_RUNTIME_ARCHIVE": "caller-archive"})
            self.assertNotIn("candidate-control", json.dumps(proof))

    def test_correct_diagnostic_cannot_hide_changed_candidate_bytes_or_workspace(self):
        for damage, expected in [("installation", acceptance.Cause.ACTIVE_INSTALLATION_CHANGED),
                                 ("workspace", acceptance.Cause.WORKSPACE_CHANGED),
                                 ("assets", acceptance.Cause.CANDIDATE_ASSETS_CHANGED)]:
            with self.subTest(damage=damage), UpgradeFixture(damage) as fixture:
                self.assert_cause(expected, fixture.run)

    def test_passive_status_rejects_runtime_start_or_wrong_root(self):
        with UpgradeFixture() as fixture:
            fixture.install(fixture.host, fixture.assets, "0.32.2", fixture.root / "idea", fixture.environment)
            for document in [{"command": "status", "status": "complete", "runtime": "ready", "root": str(fixture.host.workspace)},
                             {"command": "status", "status": "complete", "runtime": "stopped", "root": "/wrong"}]:
                completed = subprocess.CompletedProcess([], 0, json.dumps(document), "")
                with mock.patch.object(acceptance, "command", return_value=completed):
                    self.assert_cause(acceptance.Cause.PASSIVE_STATUS_UNPROVEN,
                                      lambda: acceptance.passive_status(fixture.host, fixture.environment))


def make_assets(destination, version):
    destination.mkdir()
    control, control_checksum, runtime, runtime_checksum = acceptance.asset_names(version)
    with tarfile.open(destination / control, "w:gz") as archive:
        entry = tarfile.TarInfo("bin/kast")
        entry.size = len(b"candidate-control")
        archive.addfile(entry, io.BytesIO(b"candidate-control"))
    (destination / runtime).write_bytes(b"candidate-runtime")
    for payload, checksum in [(control, control_checksum), (runtime, runtime_checksum)]:
        (destination / checksum).write_text(acceptance.file_digest(destination / payload).removeprefix("sha256:") + "  " + payload + "\n")


class UpgradeFixture:
    def __init__(self, damage=None):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.host = SimpleNamespace(root=self.root, home=self.root / "home", workspace=self.root / "workspace",
                                    runtime=self.root / "runtime", readiness_file=self.root / "broker-ready", broker_socket=self.root / "broker-socket")
        for path in [self.host.home, self.host.workspace, self.host.runtime]:
            path.mkdir()
        (self.host.workspace / "Source.kt").write_text("class Example\n")
        for args in [("init", "--quiet"), ("add", "."), ("-c", "user.name=Fixture", "-c", "user.email=fixture@kast.invalid", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "fixture")]:
            subprocess.run(["git", *args], cwd=self.host.workspace, check=True, capture_output=True)
        self.assets = self.root / "assets"
        make_assets(self.assets, "1.0.0")
        self.commands = self.root / "commands"
        self.environment = {"PATH": os.environ["PATH"], "KAST_RUNTIME_ARCHIVE": "caller-archive"}
        self.installed_versions = []
        self.damage = damage

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.temporary.cleanup()

    def install(self, host, assets, version, idea, environment):
        self.installed_versions.append(version)
        root = host.root / "installation"
        target = root / "versions" / version
        (target / "bin").mkdir(parents=True)
        launcher = target / "bin/kast-complete"
        launcher.write_text(f'''#!{sys.executable}
import json,os,sys
with open({str(self.commands)!r},'a') as output: output.write(sys.argv[1]+'\\n')
if sys.argv[1]=='--version': print('kast {version} (IntelliJ sidecar)')
elif sys.argv[1]=='status': print(json.dumps({{"command":"status","status":"complete","runtime":"stopped","root":os.getcwd()}}))
else: sys.exit(64)
''')
        launcher.chmod(0o755)
        current = root / "current"
        current.unlink(missing_ok=True)
        current.symlink_to("versions/" + version)
        (host.root / "bin").mkdir(exist_ok=True)
        public = host.root / "bin/kast"
        public.unlink(missing_ok=True)
        public.symlink_to(root / "current/bin/kast-complete")
        config = host.home / ".config/kast/environment"
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text("KAST_ENABLE_LAUNCHD=0\n")
        return {**environment, "XDG_CONFIG_HOME": str(host.home / ".config")}

    def run(self):
        def downloaded(destination, candidate_version):
            make_assets(destination, "0.32.2")
            return {"tag": "v0.32.2", "version": "0.32.2", "immutable": True,
                    "assets": {name: acceptance.file_digest(destination / name) for name in acceptance.asset_names("0.32.2")},
                    "releaseCatalogDigest": "sha256:" + "a" * 64}
        real_command = acceptance.command

        def commands(arguments, **options):
            if arguments[:2] == ["/bin/bash", str(acceptance.ROOT / "install.sh")]:
                directory = Path(arguments[-1])
                if self.damage == "installation":
                    with (self.root / "installation/current/bin/kast-complete").open("a") as target:
                        target.write("# changed\n")
                elif self.damage == "workspace":
                    (self.host.workspace / "Source.kt").write_text("class Changed\n")
                elif self.damage == "assets":
                    (self.assets / acceptance.asset_names("1.0.0")[1]).write_text("changed checksum\n")
                if directory.name == "checksum-mismatch":
                    reason = f"SHA-256 mismatch for {acceptance.asset_names('1.0.0')[0]}"
                else:
                    reason = "control archive contains an unsafe path: ../escape"
                return subprocess.CompletedProcess(arguments, 1, "", "  x kast-install: " + reason + "\n")
            return real_command(arguments, **options)

        with mock.patch.object(acceptance, "download_prior_assets", side_effect=downloaded), mock.patch.object(acceptance, "command", side_effect=commands):
            return acceptance.install_candidate_with_upgrade_proof(self.host, self.assets, "1.0.0", self.root / "idea", self.environment, self.install)


if __name__ == "__main__":
    unittest.main()
