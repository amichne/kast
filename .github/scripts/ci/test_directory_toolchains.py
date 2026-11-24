from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import directory_toolchains as contract
from directory_toolchains import Failure, Toolchain

DECLARED = {
    "engine": Toolchain.GRADLE,
    "manager": Toolchain.GO,
    "release": Toolchain.PYTHON,
    "docs": Toolchain.SUPPORT,
}
ADMITTED = (
    "README.md",
    "build.gradle.kts",
    "engine/relation/build.gradle.kts",
    "engine/relation/src/main/kotlin/Relation.kt",
    "manager/go.mod",
    "manager/cmd/kast/main.go",
    "manager/check",
    "release/pyproject.toml",
    "release/assemble.py",
    "docs/tooling/pyproject.toml",
    "docs/samples/Sample.kt",
)


def found(*paths: str, without: str | None = None) -> set[tuple[Failure, str]]:
    tracked = tuple(path for path in ADMITTED if path != without) + paths
    return {(item.failure, item.location) for item in contract.violations(tracked, DECLARED)}


class DirectoryToolchainsTest(unittest.TestCase):
    def test_each_directory_with_its_own_toolchain_is_admitted(self) -> None:
        self.assertEqual(set(), found())

    def test_a_new_top_level_directory_needs_a_declaration(self) -> None:
        self.assertEqual({(Failure.UNDECLARED_DIRECTORY, "frontend")}, found("frontend/notes.md", "frontend/check"))

    def test_a_declared_directory_without_tracked_files_is_rejected(self) -> None:
        self.assertEqual(
            {(Failure.ABSENT_DIRECTORY, "manager")},
            {
                (item.failure, item.location)
                for item in contract.violations(
                    tuple(path for path in ADMITTED if not path.startswith("manager/")), DECLARED
                )
            },
        )

    def test_a_declared_toolchain_needs_its_build_file(self) -> None:
        for path, directory in (
            ("engine/relation/build.gradle.kts", "engine"),
            ("manager/go.mod", "manager"),
            ("release/pyproject.toml", "release"),
        ):
            with self.subTest(path):
                self.assertEqual({(Failure.ABSENT_BUILD, directory)}, found(without=path))

    def test_a_second_toolchain_is_rejected_by_its_build_file_or_source(self) -> None:
        for path in (
            "engine/tool/go.mod",
            "engine/tool/main.go",
            "engine/tool/pyproject.toml",
            "manager/build.gradle.kts",
            "manager/settings.gradle",
            "manager/src/Main.kt",
            "manager/src/Main.java",
            "release/go.work",
            "release/src/Main.kt",
        ):
            with self.subTest(path):
                self.assertEqual({(Failure.FOREIGN_TOOLCHAIN, path)}, found(path))

    def test_go_has_no_support_exception(self) -> None:
        for path in ("docs/tooling/go.mod", "docs/samples/main.go"):
            with self.subTest(path):
                self.assertEqual({(Failure.FOREIGN_TOOLCHAIN, path)}, found(path))

    def test_go_at_the_repository_root_is_rejected(self) -> None:
        for path in ("go.mod", "go.work", "main.go"):
            with self.subTest(path):
                self.assertEqual({(Failure.FOREIGN_TOOLCHAIN, path)}, found(path))

    def test_python_scripts_are_tools_of_any_directory(self) -> None:
        self.assertEqual(set(), found("manager/acceptance.py", "engine/release/record.py"))

    def test_the_repository_is_admitted(self) -> None:
        self.assertEqual([], contract.violations(contract.tracked(), contract.DIRECTORIES))


if __name__ == "__main__":
    unittest.main()
