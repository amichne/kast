#!/usr/bin/env python3
"""The Go executable's third-party notices: which license files belong to a linked package, and their text.

The gate compares the checked-in notices with the linked packages of the pinned modules. These cases need no Go.
"""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from importlib.machinery import SourceFileLoader
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
_LOADER = SourceFileLoader("frontend_check", str(ROOT / "frontend/check"))
_SPEC = importlib.util.spec_from_loader("frontend_check", _LOADER)
assert _SPEC is not None
check = importlib.util.module_from_spec(_SPEC)
sys.dont_write_bytecode = True
sys.modules["frontend_check"] = check
_LOADER.exec_module(check)


class FrontendNoticesTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()

    def write(self, name: str, text: str = "terms\n") -> Path:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        _ = path.write_text(text)
        return path

    def package(self, directory: str, component: str = "example.com/m v1.0.0", root: str = "m"):
        return check.Package(component, self.root / directory, self.root / root)

    def test_a_package_has_the_license_files_from_its_directory_to_its_root(self) -> None:
        names = ("LICENSE", "PATENTS", "NOTICE.md", "a/LICENCE.txt", "a/b/COPYING", "a/b/License-MIT")
        for name in names:
            _ = self.write(f"m/{name}")
        # Another package's terms, sources, and the files above the root are not this package's terms.
        for name in ("m/c/LICENSE", "m/a/license.go", "m/a/noticeboard.txt", "m/a/unlicensed", "LICENSE"):
            _ = self.write(name)
        found = check.license_files(self.package("m/a/b"))
        self.assertEqual(sorted(names), sorted(str(path.relative_to(self.root / "m")) for path in found))

    def test_a_package_outside_its_root_is_rejected(self) -> None:
        _ = self.write("m/LICENSE")
        _ = self.write("other/LICENSE")
        with self.assertRaises(SystemExit):
            _ = check.license_files(self.package("other"))

    def test_a_component_without_terms_is_rejected(self) -> None:
        _ = self.write("m/a/source.go")
        _ = self.write("n/LICENSE")
        packages = [self.package("m/a"), self.package("n", "example.com/n v2.0.0", "n")]
        with self.assertRaisesRegex(SystemExit, "example.com/m v1.0.0 has no license file"):
            _ = check.render(packages)

    def test_a_linked_license_file_is_rejected(self) -> None:
        target = self.write("elsewhere")
        (self.root / "m").mkdir()
        (self.root / "m/LICENSE").symlink_to(target)
        with self.assertRaises(SystemExit):
            _ = check.render([self.package("m")])

    def test_the_notices_hold_each_components_terms_once_in_order(self) -> None:
        _ = self.write("m/LICENSE", "First terms  \r\n\r\n  indented\r\n\r\n")
        _ = self.write("n/LICENSE", "Second terms")
        _ = self.write("n/PATENTS", "Grant\n")
        packages = [
            self.package("n", "example.com/n v2.0.0", "n"),
            self.package("m"),
            self.package("m"),
        ]
        rule = "=" * 80
        line = "-" * 80
        self.assertEqual(
            f"""{check.HEADER}
{rule}
example.com/m v1.0.0
LICENSE
{line}

First terms

  indented

{rule}
example.com/n v2.0.0
LICENSE
{line}

Second terms

{rule}
example.com/n v2.0.0
PATENTS
{line}

Grant
""",
            check.render(packages),
        )
        self.assertEqual(check.render(packages), check.render(list(reversed(packages))))

    def test_linked_packages_keep_their_module_or_the_toolchain(self) -> None:
        listed = "".join(
            json.dumps(value)
            for value in (
                {"ImportPath": "kast/cmd", "Dir": "/k/cmd", "Module": {"Path": "kast", "Main": True, "Dir": "/k"}},
                {"ImportPath": "fmt", "Dir": "/go/src/fmt", "Standard": True},
                {"ImportPath": "m/a", "Dir": "/c/m/a", "Module": {"Path": "m", "Version": "v1.0.0", "Dir": "/c/m"}},
            )
        )
        self.assertEqual(
            [
                check.Package("Go standard library go1.2.3", Path("/go/src/fmt"), Path("/go")),
                check.Package("m v1.0.0", Path("/c/m/a"), Path("/c/m")),
            ],
            check.linked(listed, "go1.2.3", Path("/go")),
        )

    def test_a_package_without_an_exact_module_is_rejected(self) -> None:
        module = {"Path": "m", "Version": "v1.0.0", "Dir": "/c/m"}
        for value in (
            {"ImportPath": "m", "Dir": "/c/m", "Module": {**module, "Replace": {"Path": "../m"}}},
            {"ImportPath": "m", "Dir": "/c/m", "Module": {"Path": "m", "Dir": "/c/m"}},
            {"ImportPath": "m", "Dir": "/c/m"},
        ):
            with self.subTest(value), self.assertRaises(SystemExit):
                _ = check.linked(json.dumps(value), "go1.2.3", Path("/go"))

    def test_the_checked_in_notices_name_the_required_modules(self) -> None:
        notices = (ROOT / "frontend" / check.NOTICES).read_text()
        self.assertTrue(notices.startswith(check.HEADER))
        toolchain = check.toolchain(ROOT / "frontend")
        for component in ("charm.land/bubbletea/v2 ", "github.com/coder/websocket ", f"Go standard library {toolchain}\n"):
            self.assertIn(f"\n{component}", notices)
        # The analyzers are development tools. The executable does not contain them.
        for tool in ("honnef.co/go/tools", "golang.org/x/vuln"):
            self.assertNotIn(tool, notices)


if __name__ == "__main__":
    unittest.main()
