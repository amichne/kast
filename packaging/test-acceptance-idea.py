"""Offline admission checks; fake app trees, no downloads or IDE process."""
import json
from pathlib import Path
import tempfile
import unittest
from acceptance_idea import BUILD, admit_home


class IdeaAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name).resolve() / 'IDEA.app/Contents'
        (self.home / 'Resources').mkdir(parents=True)
        self.info = self.home / 'Resources/product-info.json'
        self.document = {'buildNumber': BUILD, 'launch': [{'os': 'macOS', 'arch': 'aarch64'}]}
        self.info.write_text(json.dumps(self.document))
        self.java = self.home / 'jbr/Contents/Home/bin/java'
        self.java.parent.mkdir(parents=True)
        self.java.write_text('#!/bin/sh\nexit 0\n')
        self.java.chmod(0o700)

    def test_exact_full_distribution_is_admitted_without_launching_it(self):
        self.assertEqual(admit_home(self.home), self.home)

    def test_newer_build_is_not_substituted_for_exact_runtime_authority(self):
        self.document['buildNumber'] = '262.10315.125'
        self.info.write_text(json.dumps(self.document))
        with self.assertRaisesRegex(ValueError, 'IDEA_BUILD_REJECTED'):
            admit_home(self.home)

    def test_compile_distribution_without_bundled_runtime_is_rejected(self):
        self.java.unlink()
        with self.assertRaisesRegex(ValueError, 'IDEA_BUNDLED_RUNTIME_REJECTED'):
            admit_home(self.home)

    def test_intel_only_distribution_is_rejected(self):
        self.document['launch'][0]['arch'] = 'x86_64'
        self.info.write_text(json.dumps(self.document))
        with self.assertRaisesRegex(ValueError, 'IDEA_PLATFORM_REJECTED'):
            admit_home(self.home)

    def test_metadata_redirect_does_not_establish_identity(self):
        outside = self.home.parent / 'foreign.json'
        self.info.rename(outside)
        self.info.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'IDEA_METADATA_REJECTED'):
            admit_home(self.home)


if __name__ == '__main__':
    unittest.main()
