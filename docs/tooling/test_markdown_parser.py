"""Exercise the native adapter, retained HTML policy, and finite process failures."""

import hashlib
import json
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal

import pytest

import markdown_parser as adapter
from check_glossary import GlossaryReport, RejectionReport, Request, evaluate
from glossary_json import Rejected, load_glossary, occurrences
from markdown_parser import (
    LexicalProse,
    MarkdownProse,
    ParserCode,
    ParserFailure,
    ParserOutput,
    SourceDocument,
    prepare_sources,
    run_parser,
)

ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class RangeDTO:
    start: int = 3
    end: int = 15
    kind: str = "TEXT"


@dataclass(frozen=True)
class DocumentDTO:
    sha256: str
    id: int = 0
    ranges: tuple[RangeDTO, ...] = (RangeDTO(),)


@dataclass(frozen=True)
class ResponseDTO:
    documents: tuple[DocumentDTO, ...]
    type: str = "PARSED_MARKDOWN"
    schemaVersion: int = 1


@dataclass(frozen=True)
class RejectionDTO:
    code: str
    type: Literal["PARSER_REJECTED"] = "PARSER_REJECTED"
    schemaVersion: Literal[1] = 1


def output(response: ResponseDTO | RejectionDTO, status: int = 0) -> ParserOutput:
    return ParserOutput(json.dumps(asdict(response)).encode(), status)


def hits(prose: MarkdownProse | LexicalProse) -> tuple[tuple[int, int, int], ...]:
    glossary = load_glossary(ROOT / "docs/glossary.json")
    assert not isinstance(glossary, Rejected)
    return tuple((item.line, item.start, item.end) for item in occurrences(glossary, prose))


def test_real_batch_preserves_raw_coordinates_and_isolates_mdx_and_text() -> None:
    sources = (
        SourceDocument(
            Path("first.md"),
            "😀 saved result and `saved result` and saved result.\r\n"
            "\\*saved result\\* &quot;saved result&quot; saved&#32;result.\r\n",
        ),
        SourceDocument(
            Path("second.md"), '[saved result](target "saved result") and saved result.'
        ),
        SourceDocument(Path("third.mdx"), "> ```\n> saved result\n> ```"),
        SourceDocument(Path("fourth.txt"), "`saved result`"),
    )
    requests: list[bytes] = []

    def runner(request: bytes) -> ParserOutput | ParserFailure:
        assert not requests
        requests.append(request)
        return run_parser(request)

    result = prepare_sources(sources, runner)
    assert not isinstance(result, ParserFailure), result
    assert json.loads(requests[0]) == {
        "type": "PARSE_MARKDOWN",
        "schemaVersion": 1,
        "documents": [
            {
                "id": index,
                "source": source.text,
                "sha256": hashlib.sha256(source.text.encode()).hexdigest(),
            }
            for index, source in enumerate(sources[:2])
        ],
    }
    assert [prose.source for prose in result] == list(sources)
    assert hits(result[0]) == ((1, 3, 14), (1, 39, 50), (2, 3, 14), (2, 24, 35))
    assert hits(result[1]) == ((1, 2, 13), (1, 43, 54))
    assert isinstance(result[2], LexicalProse)
    assert isinstance(result[3], LexicalProse)
    assert hits(result[3]) == ((1, 2, 13),)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("<p>saved result</p>", ((1, 4, 15),)),
        ("Use https://example/saved result. A saved result.", ((1, 37, 48),)),
        ("Use https://example/x A saved result.", ((1, 25, 36),)),
        ("`https://example/x`saved result", ((1, 20, 31),)),
        ('![saved result](target "saved result")', ((1, 3, 14),)),
        ('[saved result]: target "saved result"', ((1, 2, 13), (1, 25, 36))),
        ("![saved result][label]\n\n[label]: /target", ((1, 3, 14),)),
        ('<p title="saved result">A saved result.</p>', ((1, 11, 22), (1, 27, 38))),
        ("<p>`saved result` A saved result.</p>", ((1, 21, 32),)),
        ("<p>`<!--` A saved result.</p>", ((1, 13, 24),)),
        ("<!-- hidden saved result\n--> `saved result` A saved result.", ((2, 22, 33),)),
        ("A saved result <!-- hidden\nsaved result\n", ((1, 3, 14),)),
        (
            "<!-- saved result --> A saved result <!-- hidden\nsaved result\n--> A saved result.",
            ((1, 25, 36), (3, 7, 18)),
        ),
        ("> ```\n> <!--\n> saved result\n> ```\nA saved result.", ((5, 3, 14),)),
        ("`<!-- saved result -->` A saved result.", ((1, 27, 38),)),
    ],
)
def test_html_policy_keeps_visible_literals_and_code_comment_precedence(
    text: str, expected: tuple[tuple[int, int, int], ...]
) -> None:
    result = prepare_sources((SourceDocument(Path("guide.md"), text),))
    assert not isinstance(result, ParserFailure), result
    assert hits(result[0]) == expected


def test_lexical_inputs_never_start_the_native_parser() -> None:
    def forbidden(_request: bytes) -> ParserOutput:
        pytest.fail("Lexical inputs started the Markdown parser")

    sources = (
        SourceDocument(Path("guide.mdx"), "A saved result."),
        SourceDocument(Path("guide.txt"), "saved result"),
    )
    result = prepare_sources(sources, forbidden)
    assert not isinstance(result, ParserFailure)
    assert all(isinstance(prose, LexicalProse) for prose in result)


@pytest.mark.parametrize(
    "code",
    [ParserCode.INPUT, ParserCode.RUNTIME, ParserCode.PARSE, ParserCode.LIMIT, ParserCode.NODE],
)
def test_known_child_rejection_keeps_its_finite_code(code: ParserCode) -> None:
    result = prepare_sources(
        (SourceDocument(Path("guide.md"), "source"),),
        lambda _request: output(RejectionDTO(code.value), 2),
    )
    assert result == ParserFailure(code)


@pytest.mark.parametrize(
    "damage",
    [
        "version",
        "boolean-version",
        "unknown-field",
        "unknown-kind",
        "split-surrogate",
        "negative",
        "outside",
        "overlap",
        "wrong-id",
        "boolean-id",
        "wrong-digest",
        "missing-document",
        "unknown-type",
        "array-kind",
    ],
)
def test_untrusted_response_cannot_manufacture_admitted_ranges(damage: str) -> None:
    source = SourceDocument(Path("guide.md"), "😀 saved result")
    response = asdict(ResponseDTO((DocumentDTO(source.sha256),)))
    # Each mutation deliberately violates the external protocol.
    wire = json.loads(json.dumps(response))
    match damage:
        case "version":
            wire["schemaVersion"] = 2
        case "boolean-version":
            wire["schemaVersion"] = True
        case "unknown-field":
            wire["extra"] = "unknown"
        case "unknown-kind":
            wire["documents"][0]["ranges"][0]["kind"] = "UNKNOWN"
        case "split-surrogate":
            wire["documents"][0]["ranges"][0]["start"] = 1
        case "negative":
            wire["documents"][0]["ranges"][0]["start"] = -1
        case "outside":
            wire["documents"][0]["ranges"][0]["end"] = 16
        case "overlap":
            wire["documents"][0]["ranges"] *= 2
        case "wrong-id":
            wire["documents"][0]["id"] = 1
        case "boolean-id":
            wire["documents"][0]["id"] = False
        case "wrong-digest":
            wire["documents"][0]["sha256"] = "wrong"
        case "missing-document":
            wire["documents"] = []
        case "unknown-type":
            wire["type"] = "UNKNOWN"
        case "array-kind":
            wire["documents"][0]["ranges"][0]["kind"] = []
        case _:
            pytest.fail("Unsupported fixture mutation")
    result = prepare_sources((source,), lambda _request: ParserOutput(json.dumps(wire).encode(), 0))
    assert result == ParserFailure(ParserCode.RESPONSE)


def test_valid_scalar_endpoints_and_duplicate_response_keys() -> None:
    source = SourceDocument(Path("guide.md"), "😀 saved result")
    response = output(ResponseDTO((DocumentDTO(source.sha256),)))
    result = prepare_sources((source,), lambda _request: response)
    assert not isinstance(result, ParserFailure)
    assert hits(result[0]) == ((1, 3, 14),)
    duplicate = response.stdout.replace(
        b'"schemaVersion": 1', b'"schemaVersion": 1, "schemaVersion": 1'
    )
    assert prepare_sources((source,), lambda _request: ParserOutput(duplicate, 0)) == ParserFailure(
        ParserCode.RESPONSE
    )


@pytest.mark.parametrize("failure", [ParserCode.START, ParserCode.TIMEOUT, ParserCode.EXIT])
def test_process_failure_never_falls_back_to_lexical_prose(failure: ParserCode) -> None:
    result = prepare_sources(
        (SourceDocument(Path("guide.md"), "saved result"),), lambda _request: ParserFailure(failure)
    )
    assert result == ParserFailure(failure)


@pytest.mark.parametrize("mode", ["start", "timeout", "exit"])
def test_process_adapter_binds_command_input_and_bounded_failures(
    monkeypatch: pytest.MonkeyPatch, mode: str
) -> None:
    calls = 0

    def child(*args: object, **kwargs: object) -> subprocess.CompletedProcess[bytes]:
        nonlocal calls
        calls += 1
        assert calls == 1
        assert args == ([shutil.which("node"), str(adapter.PARSER)],)
        assert kwargs["input"] == b"request"
        assert kwargs["timeout"] == 30
        assert kwargs["cwd"] == adapter.PARSER.parent
        if mode == "start":
            raise FileNotFoundError("controlled")
        if mode == "timeout":
            raise subprocess.TimeoutExpired("controlled", 30)
        return subprocess.CompletedProcess(["node"], 7, b"")

    monkeypatch.setattr(subprocess, "run", child)
    result = run_parser(b"request")
    assert calls == 1
    assert result == (
        ParserOutput(b"", 7)
        if mode == "exit"
        else ParserFailure(ParserCode.START if mode == "start" else ParserCode.TIMEOUT)
    )


def test_limit_rejection_precedes_parser_effect() -> None:
    def forbidden(_request: bytes) -> ParserOutput:
        pytest.fail("An oversized batch started the parser")

    source = SourceDocument(Path("guide.md"), "x" * adapter.MAX_BYTES)
    assert prepare_sources((source,), forbidden) == ParserFailure(ParserCode.LIMIT)


@pytest.mark.parametrize(
    "source",
    [SourceDocument(Path("guide.java"), "text"), SourceDocument(Path("guide.md"), "\ud800")],
)
def test_invalid_source_rejects_before_the_process(source: SourceDocument) -> None:
    def forbidden(_request: bytes) -> ParserOutput:
        pytest.fail("An invalid source started the parser")

    assert prepare_sources((source,), forbidden) == ParserFailure(ParserCode.INPUT)


def test_missing_runtime_rejects_without_a_process(monkeypatch: pytest.MonkeyPatch) -> None:
    def missing(_name: str) -> None:
        return None

    monkeypatch.setattr(shutil, "which", missing)

    def forbidden(*_args: object, **_kwargs: object) -> subprocess.CompletedProcess[bytes]:
        pytest.fail("A missing runtime started a process")

    monkeypatch.setattr(subprocess, "run", forbidden)
    assert run_parser(b"request") == ParserFailure(ParserCode.START)


def test_cli_selects_scoped_markdown_before_one_real_parser_batch(tmp_path: Path) -> None:
    outside = tmp_path / "outside.md"
    outside.write_text("A saved result.")
    calls: list[tuple[SourceDocument, ...]] = []
    processes: list[bytes] = []

    def runner(request: bytes) -> ParserOutput | ParserFailure:
        assert not processes
        processes.append(request)
        return run_parser(request)

    def parser(sources: tuple[SourceDocument, ...]):
        assert not calls
        calls.append(sources)
        return prepare_sources(sources, runner)

    request = Request(
        ROOT / "docs/glossary.json",
        (ROOT / "README.md", ROOT / "AGENTS.md", outside),
        None,
        None,
        None,
        None,
    )
    result = evaluate(request, parser)
    assert isinstance(result, GlossaryReport), result
    assert [source.path for source in calls[0]] == [ROOT / "README.md", ROOT / "AGENTS.md"]
    assert len(processes) == 1
    assert result.documents[-1].type == "OUT_OF_SCOPE"
    assert not result.documents[-1].findings


def test_cli_parser_rejection_precedes_outputs(tmp_path: Path) -> None:
    rendered = tmp_path / "reader.md"
    rules = tmp_path / "rules"
    request = Request(
        ROOT / "docs/glossary.json", (ROOT / "README.md",), rendered, None, rules, None
    )
    result = evaluate(request, lambda _sources: ParserFailure(ParserCode.RUNTIME))
    assert isinstance(result, RejectionReport)
    assert result.code == ParserCode.RUNTIME
    assert not rendered.exists()
    assert not rules.exists()
