"""Restricted-PATH contracts; scripted interpreters are not native qualification."""

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

import defusedxml

import generate_bootstrap as generator
import private_interpreter as private


def script(path: Path, source: str) -> None:
    _ = path.write_text(source)
    path.chmod(0o755)


class BootstrapTest(unittest.TestCase):
    def test_checked_in_bootstrap_is_the_exact_generated_inventory(self) -> None:
        self.assertEqual(generator.render(), (generator.HERE.parent / "install.sh").read_bytes())
        # Linux limits each exec argument to 32 pages, including the terminating NUL.
        self.assertLess(len(generator.render()), 131_072)
        inventory = (generator.HERE.parent / "build.gradle.kts").read_text()
        for name in generator.SOURCES:
            self.assertIn('"packaging/' + name + '"', inventory)

    def test_unsupported_platform_rejects_before_any_storage_or_transfer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tools = root / "tools"
            tools.mkdir()
            script(tools / "uname", '#!/bin/sh\nprintf "unsupported\\n"\n')
            result = subprocess.run(
                ["/bin/bash", str(generator.HERE.parent / "install.sh")],
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
                result = subprocess.run(
                    ["/bin/bash", str(bootstrap), "--recover-only", "--dry-run", "--json"],
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
                ["/bin/bash", str(generator.HERE.parent / "install.sh")],
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
