#!/usr/bin/env python3
"""Release verification environment handoff; no build or repository mutation."""

from pathlib import Path
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
VERIFY = ROOT / ".github/scripts/ci/verify.sh"
SOURCE_REVISION = "a" * 40


class VerifyEnvironmentTest(unittest.TestCase):
    def test_release_jdk_input_is_consumed_before_asset_build(self) -> None:
        with tempfile.TemporaryDirectory(prefix="kast-release-environment-") as directory:
            fixture = Path(directory)
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
                """#!/bin/sh
case "$1" in
  .github/scripts/release/admit-source.sh) exit 0 ;;
  .github/scripts/release/build-assets.sh)
    [ "${JAVA_HOME-}" = "/opt/kast-release-jdk-25" ] || exit 71
    [ -z "${KAST_RELEASE_JDK_25+x}" ] || {
      printf '%s\\n' 'release-only KAST_RELEASE_JDK_25 leaked into asset build' >&2
      exit 72
    }
    [ "${KAST_ENABLE_LAUNCHD-}" = "0" ] || exit 73
    [ "$*" = ".github/scripts/release/build-assets.sh --version 1.2.3 --source-revision aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" ] || exit 74
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
                ["/bin/bash", str(VERIFY), "--version", "1.2.3"],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                timeout=10,
            )

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
