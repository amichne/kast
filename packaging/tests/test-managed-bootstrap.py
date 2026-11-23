"""Restricted-PATH contracts; scripted interpreters are not native qualification."""

import ast
import hashlib
import io
import json
import os
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

try:
    import defusedxml

    import bootstrap_release as published
    import generate_bootstrap as generator
    import private_interpreter as private
except ModuleNotFoundError as missing:
    raise SystemExit(
        f"{Path(__file__).name}: module {missing.name} is unavailable; run "
        "mise exec -- uv run --locked --project packaging python "
        "packaging/internal/verification/run-portable-tests.py "
        f"packaging/tests/{Path(__file__).name}"
    ) from missing


def script(path: Path, source: str) -> None:
    _ = path.write_text(source)
    path.chmod(0o755)


class BootstrapTest(unittest.TestCase):
    def test_private_source_paths_do_not_change_delivered_member_names(self) -> None:
        files = dict(generator.support_files())
        repository = generator.REPOSITORY
        expected = {
            "bootstrap_entry.py": "packaging/internal/bootstrap/bootstrap_entry.py",
            "private_interpreter.py": "packaging/internal/bootstrap/private_interpreter.py",
            "managed_installation.py": "packaging/internal/installation/managed_installation.py",
            "managed_release.py": "packaging/internal/installation/managed_release.py",
            "managed_product.py": "packaging/internal/product/managed_product.py",
            "managed_product_installation.py": (
                "packaging/internal/product/managed_product_installation.py"
            ),
            "managed_product_removal.py": "packaging/internal/product/managed_product_removal.py",
            "immutable_store.py": "packaging/internal/installation/immutable_store.py",
            "product_removal_recovery.py": "packaging/internal/product/product_removal_recovery.py",
            "product_state_removal.py": "packaging/internal/product/product_state_removal.py",
            "managed_update_contract.py": (
                "packaging/internal/installation/managed_update_contract.py"
            ),
            "managed_update_journal.py": (
                "packaging/internal/installation/managed_update_journal.py"
            ),
            "installation-recovery.py": "packaging/internal/installation/installation-recovery.py",
            "installation-lifecycle.py": (
                "packaging/internal/installation/installation-lifecycle.py"
            ),
            "composed_bundle.py": "packaging/composed_bundle.py",
            "composition_platform.py": "packaging/internal/composition/composition_platform.py",
            "managed_headless.py": "packaging/managed_headless.py",
            "runtime_archive.py": "packaging/internal/runtime/runtime_archive.py",
            "runtime_base.py": "packaging/internal/runtime/runtime_base.py",
            "install-candidate.sh": "packaging/internal/bootstrap/install-candidate.sh",
            "managed_installation.py.lock": (
                "packaging/internal/installation/managed_installation.py.lock"
            ),
            "managed-install-v1": "packaging/managed-install-v1",
        }
        self.assertEqual(
            {name: repository / relative for name, relative in expected.items()}, files
        )
        for unknown in ("unknown.py", "install.sh"):
            with self.subTest(unknown=unknown), self.assertRaises(ValueError):
                _ = generator.source_file(unknown)

    def test_retained_support_has_one_exact_inventory(self) -> None:
        expected = {
            "bootstrap_entry.py",
            "private_interpreter.py",
            "managed_installation.py",
            "managed_release.py",
            "managed_product.py",
            "managed_product_installation.py",
            "managed_product_removal.py",
            "immutable_store.py",
            "product_removal_recovery.py",
            "product_state_removal.py",
            "managed_update_contract.py",
            "managed_update_journal.py",
            "installation-recovery.py",
            "installation-lifecycle.py",
            "composed_bundle.py",
            "composition_platform.py",
            "managed_headless.py",
            "runtime_archive.py",
            "runtime_base.py",
            "install-candidate.sh",
            "managed_installation.py.lock",
            "managed-install-v1",
        }
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "support"
            generator.stage_support(output)
            self.assertEqual(expected, {path.name for path in output.iterdir()})
            self.assertTrue(generator.check_support(output))
            (output / "managed_release.py").unlink()
            self.assertFalse(generator.check_support(output))
            generator.stage_support(output)
            _ = (output / "foreign").write_bytes(b"protected")
            with self.assertRaises(ValueError):
                generator.stage_support(output)
            self.assertEqual(b"protected", (output / "foreign").read_bytes())

    def test_support_inventory_rejects_duplicate_and_unsafe_paths(self) -> None:
        for names in (("bootstrap_entry.py", "bootstrap_entry.py"), ("../outside",), ("/root",)):
            with self.subTest(names=names), self.assertRaises(ValueError):
                _ = generator.validate_members(names)

    def test_support_rejects_a_foreign_directory_member(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary).resolve() / "support"
            generator.stage_support(output)
            artifact = output / "dependencies"
            artifact.mkdir()
            self.assertFalse(generator.check_support(output))
            with self.assertRaises(ValueError):
                generator.stage_support(output)
            self.assertTrue(artifact.is_dir())

    def test_engine_members_keep_their_source_syntax_tree_and_positions(self) -> None:
        for name in generator.SOURCES:
            source = generator.source_file(name).read_bytes()
            engine = generator.engine_member(name)
            with self.subTest(name=name):
                self.assertEqual(
                    ast.dump(ast.parse(source), include_attributes=True),
                    ast.dump(ast.parse(engine), include_attributes=True),
                )
                self.assertEqual(source.count(b"\n"), engine.count(b"\n"))
                self.assertLessEqual(len(engine), len(source))

    def test_checked_in_bootstrap_is_the_exact_generated_inventory(self) -> None:
        self.assertEqual(generator.render(), (generator.REPOSITORY / "install.sh").read_bytes())
        # Linux limits each exec argument to 32 pages, including the terminating NUL.
        self.assertLess(len(generator.render()), 131_072)
        inventory = (generator.REPOSITORY / "build.gradle.kts").read_text()
        self.assertIn("dependsOn(generateInstallerSupport)", inventory)
        self.assertIn("from(installerSupportDirectory)", inventory)

    def test_unsupported_platform_rejects_before_any_storage_or_transfer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tools = root / "tools"
            tools.mkdir()
            script(tools / "uname", '#!/bin/sh\nprintf "unsupported\\n"\n')
            result = subprocess.run(
                ["/bin/bash", str(generator.REPOSITORY / "install.sh")],
                env={
                    "HOME": str(root / "home"),
                    "PATH": str(tools),
                    "TMPDIR": str(root / "absent"),
                },
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("supported complete products", result.stderr)
            self.assertEqual([tools], list(root.iterdir()))

    def test_local_artifact_conflicts_reject_before_downloads(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bootstrap = root / "install.sh"
            _ = bootstrap.write_bytes(generator.render())
            for arguments in (
                ["--local-artifact", str(root), "--channel", "preview"],
                ["--version", "1.2.3", "--local-artifact", str(root)],
                ["--local-artifact"],
            ):
                with self.subTest(arguments=arguments):
                    result = subprocess.run(
                        ["/bin/bash", str(bootstrap), *arguments],
                        env={"HOME": str(root), "PATH": ""},
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    self.assertEqual(1, result.returncode)
                    self.assertIn("select exactly one build", result.stderr)
                    self.assertEqual([bootstrap], list(root.iterdir()))

    def test_both_target_bootstraps_run_the_engine_without_python_or_uv_on_path(self) -> None:
        for system, machine in (("Darwin", "arm64"), ("Linux", "x86_64")):
            with self.subTest(system=system), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                tools, home = root / "tools", root / "home"
                tools.mkdir()
                home.mkdir()
                for name in ("tar", "gzip", "mktemp", "mkdir", "rm"):
                    tool = shutil.which(name, path=os.defpath)
                    assert tool is not None
                    (tools / name).symlink_to(tool)
                hasher = shutil.which("shasum", path=os.defpath)
                assert hasher is not None
                (tools / "shasum").symlink_to(hasher)
                script(tools / "sha256sum", f'#!/bin/sh\nexec {shlex.quote(hasher)} -a 256 "$@"\n')
                script(
                    tools / "uname",
                    f'#!/bin/sh\ncase "$1" in -s) echo {system};; '
                    f"-m) echo {machine};; *) exit 91;; esac\n",
                )
                archive = root / "interpreter.tar.gz"
                # External interpreter observation only; the real engine still runs.
                raw = (
                    f"#!{sys.executable}\nimport os,sys\n"
                    "os.execv(sys.executable,[sys.executable,*sys.argv[1:]])\n"
                ).encode()
                with tarfile.open(archive, "w:gz") as incoming:
                    entry = tarfile.TarInfo("python/bin/python3.12")
                    entry.size, entry.mode = len(raw), 0o755
                    incoming.addfile(entry, io.BytesIO(raw))
                wheel = root / "dependency.whl"
                dependency = Path(defusedxml.__file__).parent
                with zipfile.ZipFile(wheel, "w") as output:
                    for source in dependency.glob("*.py"):
                        output.write(source, "defusedxml/" + source.name)
                pins = tuple(replace(pin, sha256=private.digest(archive)) for pin in private.PINS)
                with (
                    patch.object(generator, "PINS", pins),
                    patch.object(generator, "DEPENDENCY_SHA256", private.digest(wheel)),
                ):
                    bootstrap = root / "install.sh"
                    _ = bootstrap.write_bytes(generator.render())
                log = root / "transfers"
                urls = "|".join(shlex.quote(pin.url) for pin in pins)
                script(
                    tools / "curl",
                    "\n".join(
                        (
                            "#!/bin/sh",
                            "destination=''",
                            'while [ "$#" -gt 0 ]; do',
                            'if [ "$1" = --output ]; then shift; destination=$1; fi',
                            "url=$1; shift",
                            "done",
                            f'printf "%s\\n" "$url" >> {shlex.quote(str(log))}',
                            'case "$url" in',
                            f'{urls}) /bin/cp {shlex.quote(str(archive))} "$destination" ;;',
                            f"{shlex.quote(private.DEPENDENCY_URL)}) "
                            f'/bin/cp {shlex.quote(str(wheel))} "$destination" ;;',
                            "*) exit 92 ;;",
                            "esac",
                            "",
                        )
                    ),
                )
                local = root / "local product ; literal"
                local.mkdir()
                result = subprocess.run(
                    [
                        "/bin/bash",
                        str(bootstrap),
                        "--local-artifact",
                        str(local),
                        "--recover-only",
                        "--dry-run",
                        "--json",
                    ],
                    env={"HOME": str(home), "PATH": str(tools), "TMPDIR": str(root)},
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertEqual("RECOVERY_REQUIRED", json.loads(result.stdout)["type"])
                self.assertEqual(
                    [pins[0 if system == "Darwin" else 1].url, private.DEPENDENCY_URL],
                    log.read_text().splitlines(),
                )
                self.assertEqual([], list(home.iterdir()))
                self.assertFalse(list(root.glob("kast-bootstrap.*")))
                # Valid encoding is not integrity evidence. Reject changed engine bytes
                # before extracting or running the installer, even after admitted downloads.
                prefix, separator, body = bootstrap.read_text().partition("<<'KAST_ENGINE'\n")
                self.assertTrue(separator)
                bad_engine = root / "bad-engine.sh"
                changed = ("0" if body[0] != "0" else "1") + body[1:]
                _ = bad_engine.write_text(prefix + separator + changed)
                log.unlink()
                corrupt_engine = subprocess.run(
                    ["/bin/bash", str(bad_engine), "--recover-only", "--json"],
                    env={"HOME": str(home), "PATH": str(tools), "TMPDIR": str(root)},
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                self.assertEqual(1, corrupt_engine.returncode)
                self.assertIn("bootstrap engine digest mismatch", corrupt_engine.stderr)
                self.assertEqual("", corrupt_engine.stdout)
                self.assertEqual([], list(home.iterdir()))
                self.assertFalse(list(root.glob("kast-bootstrap.*")))
                # A transfer can return bytes successfully without returning admitted bytes.
                # Corruption must stop before interpreter execution or the dependency fetch.
                _ = archive.write_bytes(b"not the pinned interpreter")
                log.unlink()
                corrupt = subprocess.run(
                    ["/bin/bash", str(bootstrap), "--recover-only", "--json"],
                    env={"HOME": str(home), "PATH": str(tools), "TMPDIR": str(root)},
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                self.assertEqual(1, corrupt.returncode)
                self.assertIn("bootstrap artifact digest mismatch", corrupt.stderr)
                self.assertEqual(
                    [pins[0 if system == "Darwin" else 1].url], log.read_text().splitlines()
                )
                self.assertEqual([], list(home.iterdir()))
                self.assertFalse(list(root.glob("kast-bootstrap.*")))

    def test_only_a_preview_dry_run_uses_the_pinned_bootstrap(self) -> None:
        for system, machine in (("Darwin", "arm64"), ("Linux", "x86_64")):
            with self.subTest(system=system), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                tools, home, local = root / "tools", root / "home", root / "local"
                for directory in (tools, home, local):
                    directory.mkdir()
                for name in ("tar", "mktemp", "mkdir", "rm", "chmod"):
                    tool = shutil.which(name, path=os.defpath)
                    assert tool is not None
                    (tools / name).symlink_to(tool)
                hasher = shutil.which("shasum", path=os.defpath)
                assert hasher is not None
                (tools / "shasum").symlink_to(hasher)
                script(tools / "sha256sum", f'#!/bin/sh\nexec {shlex.quote(hasher)} -a 256 "$@"\n')
                script(
                    tools / "uname",
                    f'#!/bin/sh\ncase "$1" in -s) echo {system};; '
                    f"-m) echo {machine};; *) exit 91;; esac\n",
                )
                # Scripted process-boundary evidence, not the published executable.
                executable = root / "bootstrap"
                script(executable, '#!/bin/sh\nprintf "%s\\n" "$@"\nexit 4\n')
                pins = tuple(
                    replace(pin, sha256=private.digest(executable))
                    for pin in published.BOOTSTRAP_PINS
                )
                with patch.object(generator, "BOOTSTRAP_PINS", pins):
                    installer = root / "install.sh"
                    _ = installer.write_bytes(generator.render())
                pin, python = (
                    (pins[0], private.PINS[0]) if system == "Darwin" else (pins[1], private.PINS[1])
                )
                log = root / "transfers"
                script(
                    tools / "curl",
                    '#!/bin/sh\nwhile [ "$#" -gt 1 ]; do\n'
                    'if [ "$1" = --output ]; then destination=$2; fi\nshift\ndone\n'
                    f'printf "%s\\n" "$1" >> {shlex.quote(str(log))}\n'
                    f'[ "$1" = {shlex.quote(pin.url)} ] || exit 92\n'
                    # A transfer carries bytes only, so the installer must set the mode itself.
                    f'/bin/cat {shlex.quote(str(executable))} > "$destination"\n',
                )

                def run(
                    *arguments: str,
                    tools: Path = tools,
                    home: Path = home,
                    root: Path = root,
                    log: Path = log,
                    installer: Path = installer,
                ) -> tuple[subprocess.CompletedProcess[str], list[str]]:
                    log.unlink(missing_ok=True)
                    result = subprocess.run(
                        ["/bin/bash", str(installer), *arguments],
                        env={"HOME": str(home), "PATH": str(tools), "TMPDIR": str(root)},
                        capture_output=True,
                        text=True,
                        timeout=30,
                    )
                    self.assertEqual([], list(home.iterdir()))
                    self.assertFalse(list(root.glob("kast-bootstrap.*")))
                    return result, log.read_text().splitlines()

                for arguments in (
                    ("--dry-run",),
                    ("--dry-run", "--json"),
                    ("--channel", "preview", "--json", "--dry-run"),
                ):
                    result, transfers = run(*arguments)
                    # The executable owns the status and the output.
                    self.assertEqual(4, result.returncode, result.stderr)
                    self.assertEqual(["install", *arguments], result.stdout.splitlines())
                    self.assertEqual([pin.url], transfers)
                for arguments in (
                    (),
                    ("--json",),
                    ("--channel", "preview"),
                    ("--dry-run", "--channel", "stable"),
                    ("--dry-run", "--version", "1.2.3"),
                    ("--dry-run", "--commit", "abcdef0"),
                    ("--dry-run", "--recover-only"),
                    ("--local-artifact", str(local), "--dry-run"),
                ):
                    result, transfers = run(*arguments)
                    self.assertEqual((92, ""), (result.returncode, result.stdout))
                    self.assertEqual([python.url], transfers)
                # Bytes other than the pinned ones never run.
                script(executable, '#!/bin/sh\nprintf "must not run\\n"\n')
                result, transfers = run("--dry-run")
                self.assertEqual((1, ""), (result.returncode, result.stdout))
                self.assertIn("bootstrap artifact digest mismatch", result.stderr)
                self.assertEqual([pin.url], transfers)

    def test_bootstrap_pins_name_one_published_release_per_target(self) -> None:
        self.assertRegex(published.TAG, r"\Abootstrap-[0-9a-f]{12}\Z")
        self.assertEqual(
            [pin.target for pin in private.PINS], [pin.target for pin in published.BOOTSTRAP_PINS]
        )
        for pin in published.BOOTSTRAP_PINS:
            self.assertRegex(pin.sha256, r"\A[0-9a-f]{64}\Z")
            self.assertEqual(
                f"https://github.com/amichne/kast/releases/download/{published.TAG}"
                f"/kast-bootstrap-{pin.target}",
                pin.url,
            )

    def test_installed_helper_without_private_support_never_falls_back_to_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            for mode in ("--json", "--remove-product"):
                with self.subTest(mode=mode):
                    result = subprocess.run(
                        ["/bin/bash", str(generator.HERE / "install-candidate.sh"), mode],
                        env={"HOME": str(root), "PATH": ""},
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    self.assertEqual(1, result.returncode)
                    self.assertIn("private interpreter unavailable", result.stderr)
                    self.assertEqual([], list(root.iterdir()))

    def test_removal_helper_binds_retained_interpreter_and_literal_arguments(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            helper = root / "install-candidate.sh"
            _ = shutil.copyfile(generator.HERE / helper.name, helper)
            interpreter = root / "private-python/bin/python3.12"
            interpreter.parent.mkdir(parents=True)
            # Scripted process-boundary evidence, not native interpreter qualification.
            script(interpreter, '#!/bin/bash\nprintf "%s\\n" "$@"\n')
            arguments = [
                "--remove-product",
                "--install-root",
                "/owned root ; literal",
                "--uninstall-journal",
                "/journal",
            ]
            result = subprocess.run(
                ["/bin/bash", str(helper), *arguments],
                env={"HOME": str(root), "PATH": ""},
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                ["-I", "-B", str(root / "bootstrap_entry.py"), *arguments],
                result.stdout.splitlines(),
            )
            normal = subprocess.run(
                ["/bin/bash", str(helper), "--channel", "preview", "--json"],
                env={"HOME": str(root), "PATH": ""},
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(0, normal.returncode, normal.stderr)
            self.assertEqual(
                [
                    "-I",
                    "-B",
                    str(root / "bootstrap_entry.py"),
                    "--private-python-archive",
                    str(root / "private-python.tar.gz"),
                    "--private-dependency",
                    str(root / "defusedxml.whl"),
                    "--channel",
                    "preview",
                    "--json",
                ],
                normal.stdout.splitlines(),
            )

    def test_legacy_ambient_selection_rejects_before_platform_or_transfer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            result = subprocess.run(
                ["/bin/bash", str(generator.REPOSITORY / "install.sh")],
                env={"HOME": str(root), "PATH": "", "KAST_VERSION": "1.2.3"},
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("KAST_VERSION is not a public product input", result.stderr)
            self.assertEqual([], list(root.iterdir()))

    def test_private_interpreter_pins_reject_wrong_bytes_before_staging(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive, wheel = root / "archive", root / "dependency"
            _ = archive.write_bytes(b"not the pinned interpreter")
            _ = wheel.write_bytes(b"not the pinned dependency")
            for target in ("macos-aarch64", "linux-x86_64", "linux-aarch64"):
                with self.subTest(target=target), self.assertRaises(ValueError):
                    _ = private.PrivateInterpreter.admit(archive, wheel, target)

    def test_private_interpreter_materializes_only_admitted_internal_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            archive, wheel = root / "archive", root / "dependency"
            _ = wheel.write_bytes(b"dependency fixture")
            with tarfile.open(archive, "w:gz") as output:
                member = tarfile.TarInfo("python/bin/python3.12")
                member.size, member.mode = 7, 0o755
                output.addfile(member, io.BytesIO(b"fixture"))
                link = tarfile.TarInfo("python/bin/python3")
                link.type, link.linkname = tarfile.SYMTYPE, "python3.12"
                output.addfile(link)
            pin = replace(private.PINS[0], sha256=private.digest(archive))
            metadata = root / "metadata"
            metadata.mkdir()
            with (
                patch.object(private, "PINS", (pin,)),
                patch.object(
                    private, "DEPENDENCY_SHA256", hashlib.sha256(wheel.read_bytes()).hexdigest()
                ),
            ):
                admitted = private.PrivateInterpreter.admit(archive, wheel, pin.target)
                admitted.stage(metadata)
            self.assertEqual(b"fixture", (metadata / "private-python/bin/python3").read_bytes())
            self.assertFalse((metadata / "private-python/bin/python3").is_symlink())
            self.assertEqual(
                archive.read_bytes(), (metadata / "private-python.tar.gz").read_bytes()
            )


if __name__ == "__main__":
    _ = unittest.main()
