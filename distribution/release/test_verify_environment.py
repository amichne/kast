#!/usr/bin/env python3
"""Release verification environment handoff; no build or repository mutation."""

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
