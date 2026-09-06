#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest


MODULE_PATH = Path(__file__).with_name("resolve_version.py")
SPEC = importlib.util.spec_from_file_location("resolve_version", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
resolver = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = resolver
SPEC.loader.exec_module(resolver)


class ResolveVersionTest(unittest.TestCase):
    def releases(self, *tags: str) -> list[dict]:
        return [
            {"tag_name": tag, "draft": False, "prerelease": False}
            for tag in tags
        ]

    def test_semantic_maximum_is_numeric(self) -> None:
        releases = self.releases("v0.9.9", "v0.10.0", "v0.2.100")
        self.assertEqual(
            str(resolver.next_version(releases, resolver.Bump.PATCH)),
            "0.10.1",
        )

    def test_each_bump_resets_lower_components(self) -> None:
        releases = self.releases("v2.7.9")
        self.assertEqual(str(resolver.next_version(releases, resolver.Bump.PATCH)), "2.7.10")
        self.assertEqual(str(resolver.next_version(releases, resolver.Bump.MINOR)), "2.8.0")
        self.assertEqual(str(resolver.next_version(releases, resolver.Bump.MAJOR)), "3.0.0")

    def test_only_published_stable_semver_releases_are_authority(self) -> None:
        releases = self.releases("v1.4.0") + [
            {"tag_name": "v9.0.0", "draft": True, "prerelease": False},
            {"tag_name": "v8.0.0", "draft": False, "prerelease": True},
            {"tag_name": "nightly", "draft": False, "prerelease": False},
        ]
        self.assertEqual(str(resolver.next_version(releases, resolver.Bump.MINOR)), "1.5.0")

    def test_empty_catalog_has_explicit_origin(self) -> None:
        self.assertEqual(str(resolver.next_version([], resolver.Bump.PATCH)), "0.0.1")
        self.assertEqual(str(resolver.next_version([], resolver.Bump.MINOR)), "0.1.0")
        self.assertEqual(str(resolver.next_version([], resolver.Bump.MAJOR)), "1.0.0")


if __name__ == "__main__":
    unittest.main()
