"""Ownership checks for the small installer test fixture."""
from pathlib import Path
import shutil
import tempfile
import unittest

from installer_fixture import InstallerFixture, FixtureFailure, FixtureRejected, admitted_tools


class InstallerFixtureTest(unittest.TestCase):
    def test_child_environment_owns_home_and_drops_ambient_controls(self):
        with InstallerFixture(admitted_tools()) as fixture:
            environment = fixture.environment
            self.assertEqual(Path(environment["HOME"]), fixture.root / "home")
            self.assertEqual(Path(environment["PATH"]), fixture.root / "tools")
            self.assertNotIn("OPENAI_API_KEY", environment)
            self.assertNotIn("KAST_INSTALL_ROOT", environment)
            fixture.mark_passed()
        self.assertFalse(fixture.root.exists())

    def test_staged_products_do_not_mutate_source_or_each_other(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            (source / "version").write_text("original")
            with InstallerFixture(admitted_tools()) as first, InstallerFixture(admitted_tools()) as second:
                left = first.stage_product(source)
                right = second.stage_product(source)
                (left / "version").write_text("changed")
                self.assertEqual((right / "version").read_text(), "original")
                self.assertEqual((source / "version").read_text(), "original")
                first.mark_passed()
                second.mark_passed()

    def test_replaced_root_is_never_removed(self):
        fixture = InstallerFixture(admitted_tools())
        with tempfile.TemporaryDirectory() as directory:
            external = Path(directory)
            marker = external / "preserve"
            marker.write_text("user state")
            shutil.rmtree(fixture.root)
            fixture.root.symlink_to(external, target_is_directory=True)
            try:
                with self.assertRaises(FixtureRejected) as rejected:
                    with fixture:
                        fixture.mark_passed()
                self.assertEqual(rejected.exception.condition, FixtureFailure.INVALID_ROOT)
                self.assertEqual(marker.read_text(), "user state")
            finally:
                fixture.root.unlink()


if __name__ == "__main__":
    unittest.main()
