"""Gradle never asks the next repository after a 429 or 5xx, so Maven Central must come last."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MIRROR = "https://maven-central.storage-download.googleapis.com/maven2/"
PORTAL = "gradlePluginPortal()"
BLOCK = re.compile(r"repositories \{(.*?)\}", re.S)
DECLARATION = re.compile(r'mavenCentral\(\)|gradlePluginPortal\(\)|maven\("([^"]+)"\)')
# The build's own repositories. Fixtures and experiments declare what a user's project would.
# The portal leads a block whose recorded plugin-marker checksums are the portal's own.
OWNERS = {
    "settings.gradle.kts": ([PORTAL, MIRROR], [MIRROR]),
    "build-logic/settings.gradle.kts": ([MIRROR, PORTAL], [MIRROR, PORTAL]),
    "build-logic/build.gradle.kts": ([PORTAL, MIRROR],),
    "buildSrc/build.gradle.kts": ([MIRROR],),
}


class GradleRepositoriesTest(unittest.TestCase):
    def test_maven_central_is_last_behind_its_mirror_in_every_owned_block(self):
        for name, leading in OWNERS.items():
            blocks = [
                [match[1] or match[0] for match in DECLARATION.finditer(block)]
                for block in BLOCK.findall((ROOT / name).read_text())
            ]
            with self.subTest(file=name):
                self.assertEqual(len(leading), len(blocks))
                for expected, declared in zip(leading, blocks, strict=True):
                    self.assertEqual(expected, declared[: len(expected)])
                    self.assertEqual("mavenCentral()", declared[-1])
                    self.assertEqual(len(declared), len(set(declared)))

    def test_no_other_owned_script_declares_maven_central(self):
        declaring = {
            path.relative_to(ROOT).as_posix()
            for pattern in ("*.gradle.kts", "*/*.gradle.kts", "*/*/*.gradle.kts")
            for path in ROOT.glob(pattern)
            if "mavenCentral()" in path.read_text()
            and path.relative_to(ROOT).parts[0] not in ("fixtures", "experiments", "build")
        }
        self.assertEqual(set(OWNERS), declaring)

    def test_transient_repository_errors_are_retried_beyond_the_default(self):
        properties = dict(
            line.split("=", 1)
            for line in (ROOT / "gradle.properties").read_text().splitlines()
            if line.startswith("systemProp.org.gradle.internal.repository.")
        )
        self.assertEqual(
            {
                "systemProp.org.gradle.internal.repository.max.tentatives": "6",
                "systemProp.org.gradle.internal.repository.initial.backoff": "1000",
            },
            properties,
        )


if __name__ == "__main__":
    unittest.main()
