"""Characterize the inherited grammar and prove repository-owned projection rejection."""

import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Literal

import pytest

from glossary_json import (
    FailureCode,
    Rejected,
    load_glossary,
    occurrences,
    render_markdown,
    vale_rules,
)

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).with_name("check_glossary.py")


def run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args], capture_output=True, text=True, check=False
    )


def test_existing_projections_remain_byte_identical() -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    assert render_markdown(glossary) == (ROOT / "docs/GLOSSARY.md").read_text()
    assert dict(vale_rules(glossary)) == {
        path.name: path.read_text() for path in (ROOT / "config/vale/styles/Kast").glob("*.yml")
    }


def test_report_serializes_the_independently_expected_shape() -> None:
    source = ROOT / "docs/glossary.json"
    result = run(str(source))
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == {
        "type": "GLOSSARY_REPORT",
        "schemaVersion": 1,
        "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "entries": 23,
        "documents": [],
    }


def test_cli_exports_the_existing_projections(tmp_path: Path) -> None:
    view = tmp_path / "reader view.md"
    rules = tmp_path / "literal rules;$value"
    result = run(str(ROOT / "docs/glossary.json"), "--render", str(view), "--vale-dir", str(rules))
    assert result.returncode == 0, result.stderr
    assert view.read_bytes() == (ROOT / "docs/GLOSSARY.md").read_bytes()
    assert {path.name: path.read_bytes() for path in rules.iterdir()} == {
        path.name: path.read_bytes() for path in (ROOT / "config/vale/styles/Kast").iterdir()
    }


def test_citation_urls_never_open_a_socket(monkeypatch: pytest.MonkeyPatch) -> None:
    import socket

    def forbidden(*_args: object, **_kwargs: object) -> object:
        pytest.fail("Glossary parsing opened a socket for a citation")

    monkeypatch.setattr(socket, "socket", forbidden)
    result = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(result, Rejected)
    assert render_markdown(result)


def test_stale_or_extra_rules_fail_without_mutation(tmp_path: Path) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    rules = tmp_path / "rules with spaces;literal"
    rules.mkdir()
    for name, content in vale_rules(glossary):
        (rules / name).write_text(content)
    args = (str(ROOT / "docs/glossary.json"), "--check-vale", str(rules))
    assert run(*args).returncode == 0
    stale = rules / "TerminologyReview.yml"
    stale.write_text("stale rule\n")
    result = run(*args)
    assert result.returncode == 2
    assert json.loads(result.stdout)["code"] == "STALE_RULES"
    assert stale.read_text() == "stale rule\n"


@pytest.mark.parametrize("damage", ["variant", "collision", "reference", "scope", "date"])
def test_grammar_and_semantics_reject_invalid_glossaries(
    tmp_path: Path, damage: Literal["variant", "collision", "reference", "scope", "date"]
) -> None:
    data = json.loads((ROOT / "docs/glossary.json").read_text())
    first, second = data["entries"][:2]
    expected = FailureCode.GRAMMAR
    match damage:
        case "variant":
            data["review"]["type"] = "APPROVED"
        case "collision":
            second["term"] = first["term"].upper()
            expected = FailureCode.COLLISION
        case "reference":
            first["related"] = ["absent"]
            expected = FailureCode.REFERENCE
        case "scope":
            data["scope"]["include"] = ["../*.md"]
            expected = FailureCode.SCOPE
        case "date":
            first["usage"][0]["examined"] = "2026-99-99"
    path = tmp_path / "glossary.json"
    path.write_text(json.dumps(data))
    result = load_glossary(path)
    assert isinstance(result, Rejected)
    assert result.code == expected


def test_cli_proves_scope_markup_and_longest_label(tmp_path: Path) -> None:
    root = tmp_path / "scope with spaces;$literal"
    root.mkdir()
    source = root / "glossary.json"
    data = json.loads((ROOT / "docs/glossary.json").read_text())
    data["scope"] = {"root": ".", "include": ["**/*.mdx"], "exclude": ["history/**"]}
    source.write_text(json.dumps(data))
    document = root / "guide.mdx"
    document.write_text(
        "Use a saved-result reference.\n` saved result `\n~~~text\nsaved result\n~~~\n"
    )
    history = root / "history"
    history.mkdir()
    (history / "report.mdx").write_text("A saved result.\n")
    cache = root / ".venv"
    cache.mkdir()
    (cache / "third-party.mdx").write_text("A saved result.\n")
    result = run(str(source), str(root))
    assert result.returncode == 1, result.stderr
    report = json.loads(result.stdout)
    assert len(report["documents"]) == 2
    assert [
        (item["form"], item["canonical"], item["line"])
        for document in report["documents"]
        for item in document["findings"]
    ] == [("saved-result reference", "retained result reference", 1)]
    assert report["sourceSha256"]


@pytest.mark.parametrize("placement", ["prose", "fence"])
def test_cli_distinguishes_real_nested_mdx_prose_from_code(
    tmp_path: Path, placement: Literal["prose", "fence"]
) -> None:
    source = tmp_path / "glossary.json"
    data = json.loads((ROOT / "docs/glossary.json").read_text())
    data["scope"] = {"root": ".", "include": ["docs/public/**/*.mdx"], "exclude": []}
    source.write_text(json.dumps(data))
    text = (ROOT / "docs/public/reference/integrations.mdx").read_text()
    match placement:
        case "prose":
            original = "    The bundled plugin supplies MCP tools"
            replacement = "    A saved result supplies MCP tools"
        case "fence":
            original = "    kast plugin codex"
            replacement = "    saved result"
    assert text.count(original) == 1
    text = text.replace(original, replacement)
    document = tmp_path / "docs/public/reference/integrations.mdx"
    document.parent.mkdir(parents=True)
    document.write_text(text)
    result = run(str(source), str(document))
    assert result.returncode == (1 if placement == "prose" else 0), result.stderr
    findings = json.loads(result.stdout)["documents"][0]["findings"]
    if placement == "prose":
        assert findings == [
            {
                "type": "REPLACE",
                "conceptId": "retained-result",
                "canonical": "retained result",
                "form": "saved result",
                "line": 23,
                "span": [7, 18],
            }
        ]
    else:
        assert findings == []


def test_nested_mdx_keeps_deeper_prose_and_excludes_inline_code() -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    text = (
        '<Tabs>\n  <Tab title="Codex">\n    A saved result.\n'
        '    `saved result`\n    <Accordion title="Nested">\n'
        "      A saved query result.\n    </Accordion>\n  </Tab>\n</Tabs>\n"
    )
    hits = occurrences(glossary, text, Path("guide.mdx"))
    assert [(hit.line, hit.start, hit.end) for hit in hits] == [(3, 7, 18), (6, 9, 26)]


def test_markdown_still_excludes_indented_code() -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    text = "A paragraph.\n\n    saved result\n\tsaved query result\n\nA saved result.\n"
    hits = occurrences(glossary, text, Path("guide.md"))
    assert [(hit.line, hit.start, hit.end) for hit in hits] == [(6, 3, 14)]


def test_stale_view_and_source_overwrite_fail_without_mutation(tmp_path: Path) -> None:
    source = tmp_path / "glossary.json"
    source.write_bytes((ROOT / "docs/glossary.json").read_bytes())
    original = source.read_bytes()
    result = run(str(source), "--render", str(source))
    assert result.returncode == 2
    assert json.loads(result.stdout)["code"] == "INPUT"
    assert source.read_bytes() == original
    view = tmp_path / "GLOSSARY.md"
    view.write_text("Edited by hand.\n")
    result = run(str(source), "--check-render", str(view))
    assert result.returncode == 2
    assert json.loads(result.stdout)["code"] == "STALE_VIEW"
    assert view.read_text() == "Edited by hand.\n"


@pytest.mark.parametrize("source", ['{"type":"GLOSSARY","type":"GLOSSARY"}', '{"version":NaN}'])
def test_duplicate_keys_and_nonfinite_numbers_fail(tmp_path: Path, source: str) -> None:
    path = tmp_path / "glossary.json"
    path.write_text(source)
    result = load_glossary(path)
    assert isinstance(result, Rejected)
    assert result.code == FailureCode.SYNTAX


def test_review_forms_never_generate_replacements(tmp_path: Path) -> None:
    data = json.loads((ROOT / "docs/glossary.json").read_text())
    for entry in data["entries"]:
        for form in entry["rejectedForms"]:
            form["type"] = "REVIEW"
    source = tmp_path / "glossary.json"
    source.write_text(json.dumps(data))
    result = load_glossary(source)
    assert not isinstance(result, Rejected)
    rules = dict(vale_rules(result))
    assert set(rules) == {"TerminologyReview.yml"}
    assert "action:" not in rules["TerminologyReview.yml"]
    assert "swap:" not in rules["TerminologyReview.yml"]


def test_unknown_fields_and_missing_evidence_fail(tmp_path: Path) -> None:
    data = json.loads((ROOT / "docs/glossary.json").read_text())
    data["entries"][0]["usage"] = []
    source = tmp_path / "glossary.json"
    source.write_text(json.dumps(data))
    result = load_glossary(source)
    assert isinstance(result, Rejected)
    assert result.code == FailureCode.GRAMMAR
    data = json.loads((ROOT / "docs/glossary.json").read_text())
    data["unexpected"] = "unknown"
    source.write_text(json.dumps(data))
    result = load_glossary(source)
    assert isinstance(result, Rejected)
    assert result.code == FailureCode.GRAMMAR


def test_missing_input_and_existing_export_fail_before_mutation(tmp_path: Path) -> None:
    source = ROOT / "docs/glossary.json"
    result = run(str(source), str(tmp_path / "missing.md"), "--render", str(tmp_path / "view.md"))
    assert result.returncode == 2
    assert json.loads(result.stdout)["code"] == "INPUT"
    assert not (tmp_path / "view.md").exists()
    result = run(str(source), "--vale-dir", str(tmp_path))
    assert result.returncode == 2
    assert json.loads(result.stdout)["code"] == "INPUT"
