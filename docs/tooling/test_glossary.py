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
    Glossary,
    Rejected,
    load_glossary,
    render_markdown,
    render_public_mdx,
    vale_rules,
)
from glossary_json import (
    occurrences as admitted_occurrences,
)
from markdown_parser import ParserFailure, SourceDocument, prepare_sources

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).with_name("check_glossary.py")


def occurrences(glossary: Glossary, text: str, path: Path):
    prepared = prepare_sources((SourceDocument(path, text),))
    assert not isinstance(prepared, ParserFailure), prepared
    return admitted_occurrences(glossary, prepared[0])


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
            original = "    The bundled plugin supplies the Kast skill and CLI guidance"
            replacement = "    A saved result supplies MCP tools"
        case "fence":
            original = "    kast install codex"
            replacement = "    saved result"
    assert text.count(original) == 1
    expected_line = text[: text.index(original)].count("\n") + 1
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
                "line": expected_line,
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


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("1. Item\n\n    A saved result.\n", ((3, 7, 18),)),
        ("- Item\n\n    A saved result.\n", ((3, 7, 18),)),
        ("10. Item\n\n    A saved result.\n", ((3, 7, 18),)),
        ("- Outer\n  - Inner\n\n      A saved result.\n", ((4, 9, 20),)),
        ("1. Outer\n   1. Inner\n\n      A saved result.\n", ((4, 9, 20),)),
        ("1. Item\n\n       saved result\n\n    A saved result.\n", ((5, 7, 18),)),
        ("- Item\n\n      saved result\n\n    A saved result.\n", ((5, 7, 18),)),
        (
            "- Outer\n  - Inner\n\n        saved result\n\n      A saved result.\n",
            ((6, 9, 20),),
        ),
        ("1. ```text\n   saved result\n   ```\n\n    A saved result.\n", ((5, 7, 18),)),
        (
            "- Item\n\n  ```text\n  saved result\n  ```\n\n    A saved result.\n",
            ((7, 7, 18),),
        ),
        ("- ```text\n  saved result\nA saved result.\n", ((3, 3, 14),)),
        (
            "- Item\n\nOutside paragraph.\n\n    saved result\n\nA saved result.\n",
            ((7, 3, 14),),
        ),
        ("- Item\n# Heading\n\n    saved result\n\nA saved result.\n", ((6, 3, 14),)),
        (
            "- Outer\n  - Inner\n\n  Parent paragraph.\n\n      saved result\n\n    A saved result.\n",
            ((8, 7, 18),),
        ),
        ("1. Item\nlazy paragraph.\n\n    A saved result.\n", ((4, 7, 18),)),
        ("A paragraph.\n    A saved result.\n", ((2, 7, 18),)),
        ("    ```text\n    saved result\n\nA saved result.\n", ((4, 3, 14),)),
        ("-     saved result\n\nA saved result.\n", ((3, 3, 14),)),
        ("1. - saved result\n", ((1, 6, 17),)),
        ("-\n      saved result\n", ()),
        ("- <!-- comment -->\n      saved result\n", ()),
        ("A paragraph.\n2. Item\n\n    saved result\n", ()),
        ("- Item\n- - -\n\n    saved result\n", ()),
        ("- `code`\n      A saved result.\n", ((2, 9, 20),)),
    ],
    ids=[
        "ordered-list-paragraph",
        "unordered-list-paragraph",
        "wide-ordered-marker",
        "nested-unordered-paragraph",
        "nested-ordered-paragraph",
        "ordered-list-relative-code",
        "unordered-list-relative-code",
        "nested-list-relative-code",
        "fence-after-list-marker",
        "fence-in-list-paragraph",
        "list-fence-ends-at-outdent",
        "list-context-ends-after-blank",
        "heading-ends-list-context",
        "outdent-to-parent-list",
        "lazy-list-paragraph",
        "indent-cannot-interrupt-paragraph",
        "indented-fence-is-code",
        "code-after-list-marker",
        "same-line-nested-list",
        "empty-list-item-before-code",
        "comment-only-list-item-before-code",
        "noninitial-order-cannot-interrupt-paragraph",
        "thematic-break-ends-list-context",
        "inline-code-keeps-list-paragraph-open",
    ],
)
def test_markdown_list_indentation_keeps_prose_and_real_code_distinct(
    text: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide.md"))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


@pytest.mark.parametrize("suffix", [".md", ".mdx"])
@pytest.mark.parametrize(
    ("text", "expected"),
    [
        (r"Use \`saved result\` here.", ((1, 7, 18),)),
        (r"Use \\`saved result\\` here.", ()),
        (r"Use \\\`saved result\\\` here.", ((1, 9, 20),)),
        (r"Use \\\\`saved result\\\\` here.", ()),
        (r"Use \`saved result` here.", ((1, 7, 18),)),
        (r"Use `saved result\` A saved result.", ((1, 23, 34),)),
        (r"Use `saved result\\` A saved result.", ((1, 24, 35),)),
        (r"Use \``saved result`` here.", ((1, 8, 19),)),
        (r"Use \``saved result` here.", ()),
        (r"Use \\``saved result`` here.", ()),
        (r"Use \<!-- A saved result.", ((1, 13, 24),)),
        (r"Use \\<!-- saved result --> A saved result.", ((1, 31, 42),)),
    ],
    ids=[
        "odd-escaped-opener",
        "even-backslashes-before-opener",
        "three-backslashes-before-opener",
        "four-backslashes-before-opener",
        "escaped-opener-with-literal-closer",
        "odd-backslashes-inside-code-do-not-escape-closer",
        "even-backslashes-inside-code-do-not-escape-closer",
        "escaped-first-tick-of-double-run",
        "remaining-tick-can-open-code",
        "even-backslashes-before-double-run",
        "escaped-comment-opener-remains-prose",
        "even-backslashes-before-comment-opener",
    ],
)
def test_markdown_escapes_preserve_visible_backticks_and_real_code_spans(
    suffix: str, text: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide" + suffix))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("```code```\nA saved result.\n", ((2, 3, 14),)),
        ("```saved result```\nA saved result.\n", ((2, 3, 14),)),
        ("```saved result``\nA saved result.\n", ((1, 4, 15), (2, 3, 14))),
        ("```lang\\`code\nsaved result\n", ((2, 1, 12),)),
        ("```text\nsaved result\n", ()),
        ("~~~lang`code\nsaved result\n~~~\nA saved result.\n", ((4, 3, 14),)),
        ("````text\nsaved result\n`````\nA saved result.\n", ((4, 3, 14),)),
        ("````text\n```\nsaved result\n````\nA saved result.\n", ((5, 3, 14),)),
        ("```text\n~~~\nsaved result\n```\nA saved result.\n", ((5, 3, 14),)),
        ("```text\n``` info\nsaved result\n```\nA saved result.\n", ((5, 3, 14),)),
        ("```text\n\\```\nsaved result\n```\nA saved result.\n", ((5, 3, 14),)),
        ("Use ```saved result``` here.\nA saved result.\n", ((2, 3, 14),)),
        ("Use `code\nsaved result\n` A saved result.\n", ((3, 5, 16),)),
        ("Use ``code\n`saved result\n`` A saved result.\n", ((3, 6, 17),)),
        ("Use ``code\nsaved result\n` here.\n", ((2, 1, 12),)),
        ("Use `code\nsaved result\n", ((2, 1, 12),)),
        ("Use `code\n\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("Use `code\n# A saved result.\n` here.\n", ((2, 5, 16),)),
        ("Use `code\n----\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("- Use `code\n  saved result\n  ` A saved result.\n", ((3, 7, 18),)),
        ("- Use `code\n- saved result\n  ` here.\n", ((2, 3, 14),)),
        ("Use `code\n    saved result\n` here.\n", ()),
        ("Use `code\n```text\nsaved result\n```\n` A saved result.\n", ((5, 5, 16),)),
        ("Use `code <!-- hidden\nsaved result\n` A saved result.\n", ((3, 5, 16),)),
        ("Use `code\n<!-- hidden -->\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("Use `code\ninside <!-- saved result -->\n` A saved result.\n", ((3, 5, 16),)),
        ("Use `code\r\nsaved result\r\n` A saved result.\r\n", ((3, 5, 16),)),
        ("```code```\r\nA saved result.\r\n", ((2, 3, 14),)),
    ],
    ids=[
        "same-line-triple-code-span",
        "same-line-triple-span-with-term",
        "invalid-info-unmatched",
        "escaped-backtick-still-invalid-in-info",
        "valid-unclosed-fence",
        "tilde-info-allows-backtick",
        "longer-closing-fence",
        "shorter-fence-does-not-close",
        "different-marker-does-not-close",
        "closing-fence-info-is-data",
        "escaped-fence-closer-is-data",
        "prefixed-triple-span",
        "multiline-single-span",
        "multiline-double-span-with-shorter-run",
        "unmatched-double-opener",
        "unmatched-opener-at-eof",
        "blank-line-ends-inline-paragraph",
        "heading-ends-inline-paragraph",
        "setext-ends-inline-paragraph",
        "multiline-span-in-list",
        "sibling-list-item-ends-paragraph",
        "indented-paragraph-continuation-in-span",
        "valid-fence-ends-inline-paragraph",
        "inline-comment-marker-is-code",
        "block-comment-ends-inline-paragraph",
        "inline-comment-within-multiline-code",
        "crlf-multiline-span",
        "crlf-invalid-fence-info",
    ],
)
def test_delimiter_family_preserves_prose_and_code_positions(
    text: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide.md"))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


def test_new_nested_list_item_starts_its_own_code_context() -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    text = "1. Outer\n   -     ```text\n         saved result\n         ```\n     A saved result.\n"
    hits = occurrences(glossary, text, Path("guide.md"))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == ((5, 8, 19),)


def test_mdx_component_heading_ends_a_code_span_paragraph() -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    text = (
        '<Tabs>\n  <Tab title="Example">\n    Use `code\n    # saved result\n'
        "    ` here.\n  </Tab>\n</Tabs>\n"
    )
    hits = occurrences(glossary, text, Path("guide.mdx"))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == ((4, 7, 18),)


@pytest.mark.parametrize(
    ("text", "suffix", "expected"),
    [
        ("# Use `code\nsaved result\n` here.\n", ".md", ((2, 1, 12),)),
        ("- # Use `code\n  saved result\n  ` here.\n", ".md", ((2, 3, 14),)),
        (
            '<Tabs>\n  <Tab title="Example">\n    # Use `code\n    saved result\n'
            "    ` here.\n  </Tab>\n</Tabs>\n",
            ".mdx",
            ((4, 5, 16),),
        ),
    ],
)
def test_code_span_opener_in_a_heading_cannot_reach_another_block(
    text: str, suffix: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide" + suffix))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Use `code\n+\nsaved result\n` here.\n", ()),
        ("Use `code\n*   \nsaved result\n` here.\n", ()),
        ("Use `code\n1.\nsaved result\n` here.\n", ()),
        ("Use `code\n1)  \nsaved result\n` here.\n", ()),
        ("Use `code\n-\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("- Use `code\n  +\n  saved result\n  ` here.\n", ()),
        ("- Use `code\n+\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("- Outer\n  - Use `code\n    *\n    saved result\n    ` here.\n", ()),
        ("Use `code\n+ <!-- hidden -->\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("Use `code\n+ `code`\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("Use `code\n2. `code`\nsaved result\n` here.\n", ()),
        ("Use `code\n01. Item\nsaved result\n` here.\n", ()),
        ("Use `code\n001) Item\nsaved result\n` here.\n", ()),
        ("Use `code\n\n+\nsaved result\n` here.\n", ((4, 1, 12),)),
        ("- Use `code\n_*_\nsaved result\n` here.\n", ()),
        ("- Use `code\n*-*\nsaved result\n` here.\n", ()),
        ("Use `code\n***\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("Use `code\n* - *\nsaved result\n` here.\n", ((3, 1, 12),)),
        ("- Use `code\n--\n  saved result\n  ` here.\n", ()),
        ("- Use `code\n=\n  saved result\n  ` here.\n", ()),
        ("- Outer\n  - A paragraph.\n--\n    saved result\n    End.\n", ((4, 5, 16),)),
        ("- Outer\n  - A paragraph.\n=\n    saved result\n    End.\n", ((4, 5, 16),)),
        ("Use `code\n+\t\nsaved result\n` here.\n", ()),
        ("A paragraph.\n\n+\t\n    saved result\n", ((4, 5, 16),)),
        ("- Outer\n  -    Use `code\n      +\n       saved result\n       ` here.\n", ()),
        ("- Outer\n  -    Use `code\n      + Item\n       saved result\n       ` here.\n", ()),
        ("- Outer\n  -    Use `code\n     +\n       saved result\n       ` here.\n", ((4, 8, 19),)),
        (
            "- Outer\n  -    Use `code\n     + Item\n       saved result\n       ` here.\n",
            ((4, 8, 19),),
        ),
    ],
    ids=[
        "empty-plus-keeps-paragraph",
        "whitespace-star-keeps-paragraph",
        "empty-ordered-dot",
        "empty-ordered-parenthesis",
        "single-minus-is-setext",
        "empty-child-keeps-paragraph",
        "empty-sibling-ends-paragraph",
        "empty-nested-child-keeps-paragraph",
        "comment-payload-can-interrupt",
        "code-payload-can-interrupt",
        "noninitial-code-payload",
        "leading-zero-first-dot",
        "leading-zero-first-parenthesis",
        "empty-item-after-blank",
        "mixed-underscore-run-is-lazy-prose",
        "mixed-bullet-run-is-lazy-prose",
        "homogeneous-thematic-break",
        "mixed-spaced-markers-can-start-a-list",
        "outdented-setext-code-dash",
        "outdented-setext-code-equals",
        "outdented-setext-prose-dash",
        "outdented-setext-prose-equals",
        "tab-padding-keeps-open-paragraph",
        "tab-padding-empty-item-after-blank",
        "wide-parent-indented-empty-marker-is-lazy",
        "wide-parent-indented-content-is-lazy",
        "wide-parent-admitted-empty-sibling",
        "wide-parent-admitted-content-sibling",
    ],
)
def test_list_interruption_preserves_empty_payload_and_block_precedence(
    text: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide.md"))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


@pytest.mark.parametrize("suffix", [".md", ".mdx"])
@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("<!-- hidden saved result\n--> A saved result.\n", ((2, 7, 18),)),
        ("A saved result <!-- hidden\nsaved result\n--> End.\n", ((1, 3, 14),)),
        (
            "saved result<!-- saved result -->saved result<!-- saved result -->saved result",
            ((1, 1, 12), (1, 34, 45), (1, 67, 78)),
        ),
        (
            "<!-- saved result --> A saved result <!-- hidden\nsaved result\n--> A saved result.\n",
            ((1, 25, 36), (3, 7, 18)),
        ),
        (
            "<!-- hidden\n--> A saved result <!-- saved result --> A saved result.\n",
            ((2, 7, 18), (2, 44, 55)),
        ),
        ("`<!--` A saved result.\nA saved result.\n", ((1, 10, 21), (2, 3, 14))),
        ("`<!-- saved result -->` A saved result.\n", ((1, 27, 38),)),
        ("`` `<!--` `` A saved result.\nA saved result.\n", ((1, 16, 27), (2, 3, 14))),
        ("<!-- hidden\n`-->` A saved result.\n", ((2, 9, 20),)),
        (
            "<!-- hidden\n```text\nsaved result\n--> A saved result.\nA saved result.\n",
            ((4, 7, 18), (5, 3, 14)),
        ),
        (
            "<!-- hidden\n~~~text\nsaved result\n--> A saved result.\nA saved result.\n",
            ((4, 7, 18), (5, 3, 14)),
        ),
        ("```text\n<!--\nsaved result\n```\nA saved result.\n", ((5, 3, 14),)),
        ("A saved result <!-- hidden\nsaved result\n", ((1, 3, 14),)),
    ],
    ids=[
        "suffix-after-multiline-comment",
        "prefix-before-multiline-comment",
        "multiple-inline-comments",
        "inline-comment-then-multiline-comment",
        "multiline-close-then-inline-comment",
        "comment-opener-in-inline-code",
        "complete-comment-in-inline-code",
        "comment-opener-in-double-backtick-code",
        "backticks-inside-comment-do-not-protect-terminator",
        "backtick-fence-inside-comment",
        "tilde-fence-inside-comment",
        "comment-opener-inside-code-fence",
        "unterminated-comment-keeps-visible-prefix",
    ],
)
def test_comment_masks_preserve_visible_prose_and_positions(
    suffix: str, text: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide" + suffix))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


def test_cli_rejects_prose_after_a_multiline_comment(tmp_path: Path) -> None:
    source = tmp_path / "glossary.json"
    data = json.loads((ROOT / "docs/glossary.json").read_text())
    data["scope"] = {"root": ".", "include": ["*.mdx"], "exclude": []}
    source.write_text(json.dumps(data))
    document = tmp_path / "guide.mdx"
    document.write_text("<!-- hidden saved result\n--> A saved result.\n")
    result = run(str(source), str(document))
    assert result.returncode == 1, result.stderr
    assert json.loads(result.stdout)["documents"][0]["findings"] == [
        {
            "type": "REPLACE",
            "conceptId": "retained-result",
            "canonical": "retained result",
            "form": "saved result",
            "line": 2,
            "span": [7, 18],
        }
    ]


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


@pytest.mark.parametrize(
    ("text", "suffix", "expected"),
    [
        ("---\nA saved result", ".md", ((2, 3, 14),)),
        ("---\nA saved result", ".mdx", ((2, 3, 14),)),
        ("---\nA saved result\n---\nA saved result.", ".md", ((4, 3, 14),)),
        ("---\nA saved result\n---\nA saved result.", ".mdx", ((4, 3, 14),)),
        ("---\nA saved result\n...\nA saved result.", ".md", ((4, 3, 14),)),
        ("---\nA saved result\n...\nA saved result.", ".mdx", ((4, 3, 14),)),
        ("--- \t\nA saved result\n--- \t\nA saved result.", ".md", ((4, 3, 14),)),
        ("--- \t\nA saved result\n--- \t\nA saved result.", ".mdx", ((4, 3, 14),)),
        ("---\n---\nA saved result.", ".md", ((3, 3, 14),)),
        ("---\n---\nA saved result.", ".mdx", ((3, 3, 14),)),
        (" ---\nA saved result\n---\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        (" ---\nA saved result\n---\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n ---\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n ---\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("----\nA saved result\n---\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        ("----\nA saved result\n---\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n----\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n----\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("\\---\nA saved result\n---\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        ("\\---\nA saved result\n---\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n\\---\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n\\---\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("\n---\nA saved result\n---\nA saved result.", ".md", ((3, 3, 14), (5, 3, 14))),
        ("\n---\nA saved result\n---\nA saved result.", ".mdx", ((3, 3, 14), (5, 3, 14))),
        ("---x\nA saved result\n---\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        ("---x\nA saved result\n---\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n---x\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n---x\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n...x\nA saved result.", ".md", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n...x\nA saved result.", ".mdx", ((2, 3, 14), (4, 3, 14))),
        ("---\nA saved result\n... \t", ".md", ()),
        ("---\nA saved result\n... \t", ".mdx", ()),
    ],
    ids=[
        "unclosed-markdown",
        "unclosed-mdx",
        "closed-markdown",
        "closed-mdx",
        "dot-close-markdown",
        "dot-close-mdx",
        "horizontal-padding-markdown",
        "horizontal-padding-mdx",
        "empty-markdown",
        "empty-mdx",
        "indented-open-markdown",
        "indented-open-mdx",
        "indented-close-markdown",
        "indented-close-mdx",
        "wide-open-markdown",
        "wide-open-mdx",
        "wide-close-markdown",
        "wide-close-mdx",
        "escaped-open-markdown",
        "escaped-open-mdx",
        "escaped-close-markdown",
        "escaped-close-mdx",
        "not-first-line-markdown",
        "not-first-line-mdx",
        "open-info-markdown",
        "open-info-mdx",
        "close-info-markdown",
        "close-info-mdx",
        "dot-close-info-markdown",
        "dot-close-info-mdx",
        "dot-close-at-eof-markdown",
        "dot-close-at-eof-mdx",
    ],
)
def test_frontmatter_admission_keeps_malformed_input_visible(
    text: str, suffix: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide" + suffix))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


@pytest.mark.parametrize(
    ("text", "suffix", "expected"),
    [
        ("A ](saved result) here.", ".md", ((1, 5, 16),)),
        ("A ](saved result) here.", ".mdx", ((1, 5, 16),)),
        ("A [label (saved result) here.", ".md", ((1, 11, 22),)),
        ("A [label (saved result) here.", ".mdx", ((1, 11, 22),)),
        ("A [label](saved result) here.", ".md", ((1, 11, 22),)),
        ("A [label](saved result) here.", ".mdx", ((1, 11, 22),)),
        ("A \\[label](saved result) here.", ".md", ((1, 12, 23),)),
        ("A \\[label](saved result) here.", ".mdx", ((1, 12, 23),)),
        ("A [label\\](saved result) here.", ".md", ((1, 12, 23),)),
        ("A [label\\](saved result) here.", ".mdx", ((1, 12, 23),)),
        ("A [label] (saved result) here.", ".md", ((1, 12, 23),)),
        ("A [label] (saved result) here.", ".mdx", ((1, 12, 23),)),
        ("A [label]\\(saved result) here.", ".md", ((1, 12, 23),)),
        ("A [label]\\(saved result) here.", ".mdx", ((1, 12, 23),)),
        ("A [saved result](target) here.", ".md", ((1, 4, 15),)),
        ("A [saved result](target) here.", ".mdx", ((1, 4, 15),)),
        ("A [outer [saved result]](target) here.", ".md", ((1, 11, 22),)),
        ("A [outer [saved result]](target) here.", ".mdx", ((1, 11, 22),)),
        ('A [label](target "saved result") here.', ".md", ()),
        ('A [label](target "saved result") here.', ".mdx", ()),
        ("A [label](target 'saved result') here.", ".md", ()),
        ("A [label](target 'saved result') here.", ".mdx", ()),
        ("A [label](target (saved result)) here.", ".md", ()),
        ("A [label](target (saved result)) here.", ".mdx", ()),
        ('A [label](target "saved result) here.', ".md", ((1, 19, 30),)),
        ('A [label](target "saved result) here.', ".mdx", ((1, 19, 30),)),
        ('A [label](target"saved result") here.', ".md", ((1, 18, 29),)),
        ('A [label](target"saved result") here.', ".mdx", ((1, 18, 29),)),
        ("A [label](target (saved result) here.", ".md", ((1, 19, 30),)),
        ("A [label](target (saved result) here.", ".mdx", ((1, 19, 30),)),
        ('A [label](target(a(b)) "saved result") here.', ".md", ()),
        ('A [label](target(a(b)) "saved result") here.', ".mdx", ()),
        ('A [label](target(a(b) "saved result") here.', ".md", ((1, 24, 35),)),
        ('A [label](target(a(b) "saved result") here.', ".mdx", ((1, 24, 35),)),
        ('A [label](target\\) "saved result") here.', ".md", ()),
        ('A [label](target\\) "saved result") here.', ".mdx", ()),
        ('A [label](target\\( "saved result") here.', ".md", ()),
        ('A [label](target\\( "saved result") here.', ".mdx", ()),
        ("A [label](<saved result>) here.", ".md", ()),
        ("A [label](<saved result>) here.", ".mdx", ()),
        ("A [](<saved result>) here.", ".md", ()),
        ("A [](<saved result>) here.", ".mdx", ()),
        ("A [ ](<saved result>) here.", ".md", ()),
        ("A [ ](<saved result>) here.", ".mdx", ()),
        ("A [outer [label]](<saved result>) here.", ".md", ()),
        ("A [outer [label]](<saved result>) here.", ".mdx", ()),
        ("A [outer [label](<saved result>) here.", ".md", ()),
        ("A [outer [label](<saved result>) here.", ".mdx", ()),
        ('A [outer [label](target)](target "saved result") here.', ".md", ((1, 35, 46),)),
        ('A [outer [label](target)](target "saved result") here.', ".mdx", ((1, 35, 46),)),
        ('A label](target "saved result") here.', ".md", ((1, 18, 29),)),
        ('A label](target "saved result") here.', ".mdx", ((1, 18, 29),)),
        ("A [label](<saved result) here.", ".md", ((1, 12, 23),)),
        ('A [label](<saved result> "title") here.', ".md", ()),
        ('A [label](<saved result> "title") here.', ".mdx", ()),
        ("A [label](<saved result<>) here.", ".md", ((1, 12, 23),)),
        ("A [label](<saved result\\<>) here.", ".md", ()),
        ("A [label](<saved result\\<>) here.", ".mdx", ()),
        ("A [label](<saved result\\>>) here.", ".md", ()),
        ("A [label](<saved result\\>>) here.", ".mdx", ()),
        ("A [label](<saved result\\>) here.", ".md", ((1, 12, 23),)),
        ("A \\\\[label](<saved result>) here.", ".md", ()),
        ("A \\\\[label](<saved result>) here.", ".mdx", ()),
        ("A [label\\\\](<saved result>) here.", ".md", ()),
        ("A [label\\\\](<saved result>) here.", ".mdx", ()),
        ("A [`[` label](<saved result>) here.", ".md", ()),
        ("A [`[` label](<saved result>) here.", ".mdx", ()),
        ("A `[label]`(saved result) here.", ".md", ((1, 13, 24),)),
        ("A `[label]`(saved result) here.", ".mdx", ((1, 13, 24),)),
        ('A [label]( "saved result") here.', ".md", ((1, 13, 24),)),
        ('A [label]( "saved result") here.', ".mdx", ((1, 13, 24),)),
        ("A [label](target) ](saved result) here.", ".md", ((1, 21, 32),)),
        ("A [label](target) ](saved result) here.", ".mdx", ((1, 21, 32),)),
    ],
    ids=[
        "bare-markdown",
        "bare-mdx",
        "unclosed-label-markdown",
        "unclosed-label-mdx",
        "raw-space-markdown",
        "raw-space-mdx",
        "escaped-open-markdown",
        "escaped-open-mdx",
        "escaped-close-markdown",
        "escaped-close-mdx",
        "separated-resource-markdown",
        "separated-resource-mdx",
        "escaped-resource-markdown",
        "escaped-resource-mdx",
        "label-visible-markdown",
        "label-visible-mdx",
        "nested-label-visible-markdown",
        "nested-label-visible-mdx",
        "double-title-markdown",
        "double-title-mdx",
        "single-title-markdown",
        "single-title-mdx",
        "parenthesized-title-markdown",
        "parenthesized-title-mdx",
        "missing-title-quote-markdown",
        "missing-title-quote-mdx",
        "missing-title-gap-markdown",
        "missing-title-gap-mdx",
        "missing-resource-close-markdown",
        "missing-resource-close-mdx",
        "balanced-url-markdown",
        "balanced-url-mdx",
        "unbalanced-url-markdown",
        "unbalanced-url-mdx",
        "escaped-url-close-markdown",
        "escaped-url-close-mdx",
        "escaped-url-open-markdown",
        "escaped-url-open-mdx",
        "angle-markdown",
        "angle-mdx",
        "empty-label-markdown",
        "empty-label-mdx",
        "blank-label-markdown",
        "blank-label-mdx",
        "nested-brackets-markdown",
        "nested-brackets-mdx",
        "inner-link-markdown",
        "inner-link-mdx",
        "nested-link-rejects-outer-markdown",
        "nested-link-rejects-outer-mdx",
        "missing-label-opener-markdown",
        "missing-label-opener-mdx",
        "missing-angle-close-markdown",
        "angle-title-markdown",
        "angle-title-mdx",
        "unescaped-angle-markdown",
        "escaped-angle-markdown",
        "escaped-angle-mdx",
        "escaped-angle-close-markdown",
        "escaped-angle-close-mdx",
        "escaped-only-angle-close-markdown",
        "even-label-escape-markdown",
        "even-label-escape-mdx",
        "even-close-escape-markdown",
        "even-close-escape-mdx",
        "code-label-markdown",
        "code-label-mdx",
        "code-fake-label-markdown",
        "code-fake-label-mdx",
        "title-only-markdown",
        "title-only-mdx",
        "back-to-back-markdown",
        "back-to-back-mdx",
    ],
)
def test_link_admission_keeps_malformed_input_visible(
    text: str, suffix: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide" + suffix))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("> ```\n> saved result\n> ```", ()),
        ("> A saved result.\n", ((1, 5, 16),)),
        ("> ```\n> saved result\n", ()),
        ("> ```\n> saved result\nA saved result.", ((3, 3, 14),)),
        ("> ```\n> saved result\n\nA saved result.", ((4, 3, 14),)),
        ("> > ```\n> > saved result\n> > ```\nA saved result.", ((4, 3, 14),)),
        ("> > ```\n> saved result\n> > ```\n> saved result", ((2, 3, 14), (4, 3, 14))),
        ("> ```\n>> saved result\n> ```\nA saved result.", ((4, 3, 14),)),
        (">     saved result\nA saved result.", ((2, 3, 14),)),
        ("> A paragraph.\n>     saved result\n", ((2, 7, 18),)),
        ("> A paragraph.\n>\n>     saved result\nA saved result.", ((4, 3, 14),)),
        ("> A paragraph.\n\n>     saved result\nA saved result.", ((4, 3, 14),)),
        (">     saved result\n    A saved result.", ()),
        ("> Use `code\n> saved result\n> ` here.", ()),
        ("> Use `code\nsaved result\n> ` here.", ()),
        ("> Use `code\n> saved result\n` here.", ()),
        ("> Use `code\n>\nsaved result\n` here.", ((3, 1, 12),)),
        ("> Use `code\n\nsaved result\n` here.", ((3, 1, 12),)),
        ("> Use `code\n>> saved result\n> ` here.", ((2, 4, 15),)),
        ("> > A paragraph.\n> A saved result.", ((2, 5, 16),)),
        ("> A paragraph.\n    A saved result.", ((2, 7, 18),)),
        ("> Use `code\n# saved result\n` here.", ((2, 3, 14),)),
        ("> Use `code\n- saved result\n` here.", ((2, 3, 14),)),
        ("> Use `code\n2. saved result\n` here.", ((2, 4, 15),)),
        ("> - ```\n>   saved result\n>   ```\nA saved result.", ((4, 3, 14),)),
        ("> - Item\n>\n>   A saved result.", ((3, 7, 18),)),
        ("> - Item\n>\n>       saved result\nA saved result.", ((4, 3, 14),)),
        ("\\> A saved result.", ((1, 6, 17),)),
        ("`> saved result`\nA saved result.", ((2, 3, 14),)),
        ("> ```code```\n> A saved result.", ((2, 5, 16),)),
        (
            "> A saved result.\n> ```\n> saved result\n> ```\n> A saved result.",
            ((1, 5, 16), (5, 5, 16)),
        ),
    ],
    ids=[
        "reported-fence",
        "quoted-prose",
        "unclosed-fence",
        "fence-ends-at-root",
        "fence-ends-at-blank",
        "nested-fence",
        "fence-ends-at-parent",
        "extra-quote-marker-is-code",
        "indented-code",
        "indent-does-not-interrupt",
        "code-after-quoted-blank",
        "code-after-root-blank",
        "code-does-not-gain-laziness",
        "explicit-inline-code",
        "lazy-inline-body",
        "lazy-inline-close",
        "quoted-blank-ends-inline",
        "root-blank-ends-inline",
        "nested-quote-ends-inline",
        "nested-lazy-prose",
        "lazy-indented-prose",
        "heading-ends-quote",
        "list-ends-quote",
        "noninitial-list-is-lazy",
        "quoted-list-fence",
        "quoted-list-prose",
        "quoted-list-code",
        "escaped-marker",
        "prefix-in-code",
        "invalid-fence-info",
        "prefix-prose-suffix",
    ],
)
def test_commonmark_quote_container_owns_code_and_visible_prose(
    text: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    hits = occurrences(glossary, text, Path("guide.md"))
    assert tuple((hit.line, hit.start, hit.end) for hit in hits) == expected


def test_public_glossary_keeps_canonical_definitions_and_sources() -> None:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    public = render_public_mdx(glossary)
    assert public == (ROOT / "docs/public/glossary.mdx").read_text()
    for entry in glossary.entries:
        assert f"## {entry.term}\n\n{entry.definition}\n" in public
        assert entry.usage[0].url in public


def test_public_projection_rejects_drift_without_changing_the_page(tmp_path: Path) -> None:
    view = tmp_path / "public glossary ; literal.mdx"
    source = str(ROOT / "docs/glossary.json")
    assert run(source, "--public-render", str(view)).returncode == 0
    assert run(source, "--check-public-render", str(view)).returncode == 0
    stale = view.read_text().replace("An opaque handle", "A guessed name", 1)
    assert stale != view.read_text()
    view.write_text(stale)
    result = run(source, "--check-public-render", str(view))
    assert result.returncode == 2
    assert json.loads(result.stdout)["code"] == "STALE_VIEW"
    assert view.read_text() == stale


def test_public_projection_cannot_overwrite_its_source() -> None:
    source = ROOT / "docs/glossary.json"
    original = source.read_bytes()
    result = run(str(source), "--public-render", str(source))
    assert result.returncode == 2
    assert json.loads(result.stdout)["code"] == "INPUT"
    assert source.read_bytes() == original
