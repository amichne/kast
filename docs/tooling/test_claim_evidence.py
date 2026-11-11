from dataclasses import asdict, dataclass
from pathlib import Path

import pytest

from claim_evidence import Declaration, Reason, ScanAccepted, ScanRejected, assess


@dataclass(frozen=True)
class Evidence:
    resource: str


@dataclass(frozen=True)
class Claim:
    page: str
    id: str
    evidence: list[Evidence]


@dataclass(frozen=True)
class Native:
    status: str
    claims: list[Claim]


@dataclass(frozen=True)
class View:
    concepts: list[str]


@dataclass(frozen=True)
class ClaimRef:
    page: str
    claimId: str


@dataclass(frozen=True)
class Association:
    claims: list[ClaimRef]


@dataclass(frozen=True)
class Manifest:
    views: list[View]
    associations: list[Association]


def inputs(resource: str) -> tuple[object, object]:
    return (
        asdict(Native("completed", [Claim("/openwiki/a.md", "c", [Evidence(resource)])])),
        asdict(Manifest([View(["openwiki/a.md"])], [])),
    )


def test_selected_python_declaration(tmp_path: Path) -> None:
    _ = (tmp_path / "owner.py").write_text("def owner():\n    pass\n", encoding="utf-8")
    native, manifest = inputs("repo://owner.py#L1-L1")
    report = assess(tmp_path, native, manifest)
    assert [(w.symbol, w.kind) for w in report.witnesses] == [("owner", "source-declaration")]
    assert not report.failures


def test_test_file_selects_executable_test(tmp_path: Path) -> None:
    _ = (tmp_path / "test_owner.py").write_text(
        "def test_behavior():\n    assert True\n", encoding="utf-8"
    )
    native, manifest = inputs("repo://test_owner.py")
    assert assess(tmp_path, native, manifest).witnesses[0].kind == "test"


@pytest.mark.parametrize(
    ("resource", "reason"),
    [
        ("repo://owner.py", Reason.INVALID_RANGE),
        ("repo://owner.py#L2-L2", Reason.NO_DECLARATION),
        ("repo://owner.py#L2-L99", Reason.INVALID_RANGE),
        ("repo://owner.py#L0-L1", Reason.INVALID_RESOURCE),
        ("repo://missing.py#L1-L1", Reason.MISSING_SOURCE),
        ("repo://owner.rb#L1-L1", Reason.UNSUPPORTED_LANGUAGE),
    ],
)
def test_rejects_weak_citations(tmp_path: Path, resource: str, reason: Reason) -> None:
    _ = (tmp_path / "owner.py").write_text("def owner():\n    pass\n", encoding="utf-8")
    _ = (tmp_path / "owner.rb").write_text("class Owner\nend\n", encoding="utf-8")
    native, manifest = inputs(resource)
    assert assess(tmp_path, native, manifest).failures[0].reason == reason


def test_comments_and_strings_are_not_declarations(tmp_path: Path) -> None:
    _ = (tmp_path / "test_fake.py").write_text(
        '# def test_fake():\ntext = "def test_ghost():"\n', encoding="utf-8"
    )
    native, manifest = inputs("repo://test_fake.py")
    assert assess(tmp_path, native, manifest).failures[0].reason == Reason.NO_DECLARATION


def test_kotlin_requires_name_line_and_real_parser_observation(tmp_path: Path) -> None:
    _ = (tmp_path / "owner.kt").write_text("// class Fake\nclass Owner {\n}\n", encoding="utf-8")
    observation = {"owner.kt": ScanAccepted((Declaration("Owner", 2, 2, False),))}
    native, manifest = inputs("repo://owner.kt#L2-L2")
    assert assess(tmp_path, native, manifest, observation).witnesses[0].symbol == "Owner"
    native, manifest = inputs("repo://owner.kt#L1-L1")
    assert (
        assess(tmp_path, native, manifest, observation).failures[0].reason == Reason.NO_DECLARATION
    )
    native, manifest = inputs("repo://owner.kt#L2-L2")
    assert (
        assess(tmp_path, native, manifest, {"owner.kt": ScanRejected()}).failures[0].reason
        == Reason.INVALID_SOURCE
    )


def test_kotlin_whole_test_file_requires_executable_test(tmp_path: Path) -> None:
    folder = tmp_path / "src" / "test" / "kotlin"
    folder.mkdir(parents=True)
    _ = (folder / "Example.kt").write_text(
        "class Example\nfun behavior() = Unit\n", encoding="utf-8"
    )
    native, manifest = inputs("repo://src/test/kotlin/Example.kt")
    key = "src/test/kotlin/Example.kt"
    assert (
        assess(
            tmp_path, native, manifest, {key: ScanAccepted((Declaration("Example", 1, 1, False),))}
        )
        .failures[0]
        .reason
        == Reason.NO_DECLARATION
    )
    assert (
        assess(
            tmp_path, native, manifest, {key: ScanAccepted((Declaration("behavior", 2, 2, True),))}
        )
        .witnesses[0]
        .kind
        == "test"
    )


def test_hyphenated_unittest_file_retains_named_test_evidence(tmp_path: Path) -> None:
    _ = (tmp_path / "test-checkout.py").write_text(
        "import unittest\nclass CheckoutTest(unittest.TestCase):\n    def test_control(self):\n        pass\n",
        encoding="utf-8",
    )
    native, manifest = inputs("repo://test-checkout.py")
    result = assess(tmp_path, native, manifest)
    assert [(w.symbol, w.kind) for w in result.witnesses] == [("test_control", "test")]
    assert not result.failures


def test_typescript_whole_file_only_accepts_a_test_observation(tmp_path: Path) -> None:
    _ = (tmp_path / "owner.test.mjs").write_text(
        "const Owner = 1;\ntest('behavior', () => {});\n", encoding="utf-8"
    )
    native, manifest = inputs("repo://owner.test.mjs")
    rejected = assess(
        tmp_path,
        native,
        manifest,
        {"owner.test.mjs": ScanAccepted((Declaration("Owner", 1, 1, False),))},
    )
    assert rejected.failures[0].reason == Reason.NO_DECLARATION
    accepted = assess(
        tmp_path,
        native,
        manifest,
        {"owner.test.mjs": ScanAccepted((Declaration("behavior", 2, 2, True),))},
    )
    assert accepted.witnesses[0].kind == "test"


def test_missing_and_unrelated_claims(tmp_path: Path) -> None:
    native = asdict(Native("completed", [Claim("/openwiki/other.md", "x", [])]))
    manifest = asdict(
        Manifest([View(["openwiki/a.md"])], [Association([ClaimRef("openwiki/b.md", "missing")])])
    )
    report = assess(tmp_path, native, manifest)
    assert {(f.page, f.claim_id, f.reason) for f in report.failures} == {
        ("openwiki/a.md", "", Reason.MISSING_CLAIM),
        ("openwiki/b.md", "missing", Reason.MISSING_CLAIM),
    }
