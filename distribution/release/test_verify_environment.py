#!/usr/bin/env python3
"""Release build bindings use admitted fixture source and scripted external tools."""

from dataclasses import asdict, dataclass
import json
from pathlib import Path
import shutil
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
VERIFY = ROOT / ".github/scripts/ci/verify.sh"
SOURCE_REVISION = "a" * 40


@dataclass(frozen=True)
class Resources:
    total_secs: float = 0.01


@dataclass(frozen=True)
class NativeMetrics:
    resource_usage: Resources = Resources()


class VerifyEnvironmentTest(unittest.TestCase):
    def test_asset_build_keeps_exact_source_and_product_gate_with_serial_configuration(self) -> None:
        for gradle_exit in (0, 7):
            with self.subTest(gradle_exit=gradle_exit), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                fixture = root / "source"
                scripts = fixture / ".github/scripts/release"
                scripts.mkdir(parents=True)
                for name in ("build-assets.sh", "admit-source.sh"):
                    shutil.copy2(ROOT / ".github/scripts/release" / name, scripts / name)
                (fixture / ".gitignore").write_text("build/\n")
                self.write_executable(
                    fixture / "gradlew",
                    "#!/bin/sh\nmkdir -p build\nprintf '%s\\n' \"$@\" > build/gradle-arguments\n"
                    f"exit {gradle_exit}\n",
                )
                commands = root / "bin"
                commands.mkdir()
                self.write_executable(
                    commands / "uv",
                    "#!/bin/sh\nprintf '%s\\n' \"$@\" > build/finalize-arguments\n",
                )
                for command in (
                    ["git", "init", "-q"],
                    ["git", "add", "."],
                    ["git", "-c", "user.name=Kast fixture", "-c", "user.email=fixture@example.invalid",
                     "commit", "-qm", "owned release fixture"],
                ):
                    subprocess.run(command, cwd=fixture, check=True, capture_output=True)
                revision = subprocess.check_output(
                    ["git", "rev-parse", "HEAD"], cwd=fixture, text=True,
                ).strip()
                result = subprocess.run(
                    ["/bin/bash", str(scripts / "build-assets.sh"), "--version", "1.2.3",
                     "--source-revision", revision],
                    cwd=root, env={"HOME": str(root), "PATH": f"{commands}:/usr/bin:/bin"},
                    text=True, capture_output=True, timeout=10,
                )
                self.assertEqual(gradle_exit, result.returncode, result.stdout + result.stderr)
                arguments = (fixture / "build/gradle-arguments").read_text().splitlines()
                self.assertIn("--no-daemon", arguments)
                self.assertIn("--no-parallel", arguments)
                self.assertIn("--max-workers=2", arguments)
                self.assertIn("-Pversion=1.2.3", arguments)
                self.assertIn(f"-PkastSourceRevision={revision}", arguments)
                self.assertEqual(
                    ["productBuildGate", "assembleRelease", "generateKastModuleKnowledge"],
                    [value for value in arguments if not value.startswith("-")],
                )
                finalized = fixture / "build/finalize-arguments"
                if gradle_exit:
                    self.assertFalse(finalized.exists())
                else:
                    self.assertEqual(
                        ["run", "--locked", "--script", ".github/scripts/release/finalize_assets.py",
                         "--version", "1.2.3", "--source-revision", revision],
                        finalized.read_text().splitlines(),
                    )

    def test_release_jdk_input_is_consumed_before_asset_build(self) -> None:
        with tempfile.TemporaryDirectory(prefix="kast-release-environment-") as directory:
            fixture = Path(directory).resolve()
            production = fixture / ".github/scripts/ci"
            production.mkdir(parents=True)
            shutil.copy2(VERIFY, production / "verify.sh")
            shutil.copy2(VERIFY.with_name("build_execution.py"), production / "build_execution.py")
            commands = fixture / "bin"
            commands.mkdir()
            self.write_executable(
                commands / "git",
                f"""#!/bin/sh
case "$*" in
  "rev-parse HEAD") printf '%s\\n' '{SOURCE_REVISION}' ;;
  "symbolic-ref -q HEAD") exit 1 ;;
  *) printf '%s\\n' "unexpected git command: $*" >&2; exit 70 ;;
esac
""",
            )
            self.write_executable(
                commands / "bash",
                f"""#!/bin/sh
case "$1" in
  .github/scripts/release/admit-source.sh) exit 0 ;;
  .github/scripts/release/build-assets.sh)
    [ "${{JAVA_HOME-}}" = "/opt/kast-release-jdk-25" ] || exit 71
    [ -z "${{KAST_RELEASE_JDK_25+x}}" ] || {{
      printf '%s\\n' 'release-only KAST_RELEASE_JDK_25 leaked into asset build' >&2
      exit 72
    }}
    [ "${{KAST_ENABLE_LAUNCHD-}}" = "0" ] || exit 73
    [ "$*" = ".github/scripts/release/build-assets.sh --version 1.2.3 --source-revision aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" ] || exit 74
    printf '%s\\n' '{json.dumps(asdict(NativeMetrics()))}' > build/reports/native/build-output.json
    ;;
  *) printf '%s\\n' "unexpected bash command: $*" >&2; exit 75 ;;
esac
""",
            )
            environment = {
                "HOME": directory,
                "PATH": f"{commands}:/usr/bin:/bin",
                "KAST_RELEASE_JDK_25": "/opt/kast-release-jdk-25",
                "KAST_ENABLE_LAUNCHD": "0",
            }
            result = subprocess.run(
                ["/bin/bash", str(production / "verify.sh"), "--version", "1.2.3"],
                cwd=fixture,
                env=environment,
                text=True,
                capture_output=True,
                timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            report = json.loads((fixture / "build/reports/ci/build-execution.json").read_text())
            self.assertEqual("SUCCESS", report["outcome"])
            self.assertEqual("RETAINED", report["nativeMetrics"])

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(
            f"release-candidate: built v1.2.3 from {SOURCE_REVISION}",
            result.stdout,
        )

    @staticmethod
    def write_executable(path: Path, content: str) -> None:
        path.write_text(content)
        path.chmod(0o755)


if __name__ == "__main__":
    unittest.main()
