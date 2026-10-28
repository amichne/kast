# pyright: strict
"""Local archive admission and real atomic exchange, separate from IDE reload proof."""

from __future__ import annotations

import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from reload_hosted import (
    Bundle,
    Failure,
    Jar,
    Rejected,
    Replacement,
    Staged,
    apply_replacement,
    bundle,
    plan,
    read_archive,
    read_installed,
)


def fixture(version: str = "1", descriptor_suffix: str = "", schema: str = "same") -> Bundle:
    content = io.BytesIO()
    with zipfile.ZipFile(content, "w") as archive:
        archive.writestr(
            "META-INF/plugin.xml",
            f"<idea-plugin><id>io.github.amichne.kast.ide-hosted</id><version>{version}</version>{descriptor_suffix}</idea-plugin>",
        )
        archive.writestr(
            "kast-hosted-query.properties", f"version={version}\nschemaDigest={schema}\n"
        )
    result = bundle(
        (
            Jar(f"kast-ide-hosted-{version}.jar", content.getvalue()),
            Jar("dependency.jar", b"unchanged"),
        )
    )
    if not isinstance(result, Bundle):
        raise AssertionError("Unexpected outcome type")
    return result


class LocalReloadTest(unittest.TestCase):
    def test_replacement_keeps_installed_paths_and_new_bytes(self) -> None:
        old, new = fixture(), fixture("2")
        result = plan(old, new)
        self.assertIsInstance(result, Replacement)
        if not isinstance(result, Replacement):
            raise AssertionError("Unexpected outcome type")
        self.assertEqual(result.jars[0].name, old.host_jar)
        self.assertEqual(result.jars[0].content, new.jars[0].content)
        self.assertEqual(result.version, "2")

    def test_descriptor_contract_and_layout_changes_reject(self) -> None:
        old = fixture()
        self.assertEqual(
            plan(old, fixture("2", "<depends>new.plugin</depends>")),
            Rejected(Failure.DESCRIPTOR_CHANGED),
        )
        self.assertEqual(
            plan(old, fixture("2", schema="changed")), Rejected(Failure.CONTRACT_CHANGED)
        )
        reduced = bundle(fixture("2").jars[:1])
        if not isinstance(reduced, Bundle):
            raise AssertionError("Unexpected outcome type")
        self.assertEqual(plan(old, reduced), Rejected(Failure.LAYOUT_CHANGED))

    def test_invalid_archive_never_extracts_parent_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "literal $(name); with spaces.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("kast-ide-hosted/lib/../../outside.jar", b"no")
            self.assertEqual(read_archive(path), Rejected(Failure.ARCHIVE_REJECTED))
            self.assertEqual(list(Path(directory).iterdir()), [path])

    def test_duplicate_jar_names_and_foreign_plugin_reject(self) -> None:
        old = fixture()
        self.assertEqual(bundle(old.jars + old.jars), Rejected(Failure.ARCHIVE_REJECTED))
        self.assertEqual(
            bundle((Jar("foreign.jar", b"unrelated"),)), Rejected(Failure.ARCHIVE_REJECTED)
        )

    def test_concurrent_content_change_leaves_current_bundle_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lib = Path(directory) / "plugins/kast-ide-hosted/lib"
            lib.mkdir(parents=True)
            old, new = fixture(), fixture("2")
            for jar in old.jars:
                _ = (lib / jar.name).write_bytes(jar.content)
            result = plan(old, new)
            if not isinstance(result, Replacement):
                raise AssertionError("Unexpected outcome type")
            _ = (lib / "dependency.jar").write_bytes(b"another task")
            self.assertEqual(
                apply_replacement(lib, result, Path(directory) / "recovery"),
                Rejected(Failure.CONCURRENT_CHANGE),
            )
            self.assertEqual((lib / "dependency.jar").read_bytes(), b"another task")
            self.assertEqual((lib / old.host_jar).read_bytes(), old.jars[0].content)

    def test_symlink_jar_rejects_before_exchange(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lib = Path(directory) / "plugins/kast-ide-hosted/lib"
            lib.mkdir(parents=True)
            outside = Path(directory) / "outside.jar"
            _ = outside.write_bytes(b"protected")
            (lib / "dependency.jar").symlink_to(outside)
            self.assertEqual(read_installed(lib), Rejected(Failure.OWNERSHIP_UNPROVEN))
            self.assertEqual(outside.read_bytes(), b"protected")

    @unittest.skipUnless(sys.platform == "darwin", "macOS directory exchange")
    def test_native_exchange_preserves_complete_previous_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lib = Path(directory) / "plugins/kast-ide-hosted/lib"
            lib.mkdir(parents=True)
            old, new = fixture(), fixture("2")
            for jar in old.jars:
                _ = (lib / jar.name).write_bytes(jar.content)
            result = plan(old, new)
            if not isinstance(result, Replacement):
                raise AssertionError("Unexpected outcome type")
            applied = apply_replacement(lib, result, Path(directory) / "recovery")
            if not isinstance(applied, Staged):
                raise AssertionError("Unexpected outcome type")
            self.assertEqual((lib / old.host_jar).read_bytes(), new.jars[0].content)
            self.assertEqual(
                (Path(applied.backup) / old.host_jar).read_bytes(), old.jars[0].content
            )
            self.assertEqual({p.name for p in lib.iterdir()}, {jar.name for jar in old.jars})


if __name__ == "__main__":
    _ = unittest.main()
