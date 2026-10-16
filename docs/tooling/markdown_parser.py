"""Admit immutable prose ranges from one bounded, repository-owned parser process."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from collections.abc import Callable
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal, TypeGuard

MAX_BYTES = 8 * 1024 * 1024
MAX_DOCUMENTS = 4096
MAX_RESPONSE_BYTES = 32 * 1024 * 1024
PARSER = Path(__file__).with_name("markdown") / "parse.ts"


class ParserCode(StrEnum):
    INPUT = "MARKDOWN_INPUT"
    RUNTIME = "MARKDOWN_RUNTIME"
    PARSE = "MARKDOWN_PARSE"
    LIMIT = "MARKDOWN_LIMIT"
    NODE = "MARKDOWN_NODE"
    START = "MARKDOWN_START"
    EXIT = "MARKDOWN_EXIT"
    TIMEOUT = "MARKDOWN_TIMEOUT"
    RESPONSE = "MARKDOWN_RESPONSE"


@dataclass(frozen=True, slots=True)
class ParserFailure:
    code: ParserCode

    @property
    def detail(self) -> str:
        return f"The Markdown adapter rejected the batch: {self.code.value}"


@dataclass(frozen=True, slots=True)
class SourceDocument:
    path: Path
    text: str

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.text.encode("utf-8")).hexdigest()


@dataclass(frozen=True, slots=True)
class _SourceRange:
    kind: Literal["TEXT", "LEGACY_OPAQUE", "CODE"]
    start: int
    end: int


@dataclass(frozen=True, slots=True)
class MarkdownProse:
    source: SourceDocument
    ranges: tuple[_SourceRange, ...]


@dataclass(frozen=True, slots=True)
class LexicalProse:
    source: SourceDocument


type ProseDocument = MarkdownProse | LexicalProse


@dataclass(frozen=True, slots=True)
class _RequestDocument:
    id: int
    source: str
    sha256: str


@dataclass(frozen=True, slots=True)
class _Request:
    documents: tuple[_RequestDocument, ...]
    type: Literal["PARSE_MARKDOWN"] = "PARSE_MARKDOWN"
    schemaVersion: Literal[1] = 1


@dataclass(frozen=True, slots=True)
class ParserOutput:
    stdout: bytes
    returncode: int


type ParserRunner = Callable[[bytes], ParserOutput | ParserFailure]


def run_parser(request: bytes) -> ParserOutput | ParserFailure:
    node = shutil.which("node")
    if node is None:
        return ParserFailure(ParserCode.START)
    try:
        result = subprocess.run(
            [node, str(PARSER)],
            input=request,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=30,
            check=False,
            cwd=PARSER.parent,
        )
    except subprocess.TimeoutExpired:
        return ParserFailure(ParserCode.TIMEOUT)
    except OSError:
        return ParserFailure(ParserCode.START)
    return ParserOutput(result.stdout, result.returncode)


def _record(value: object) -> TypeGuard[dict[str, object]]:
    return _mapping(value) and all(isinstance(key, str) for key in value)


def _mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def _objects(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate response key")
        result[key] = value
    return result


def _ranges(source: str, values: object) -> tuple[_SourceRange, ...] | ParserFailure:
    if not _objects(values) or len(values) > 262144:
        return ParserFailure(ParserCode.RESPONSE)
    utf16: list[_SourceRange] = []
    previous = 0
    for value in values:
        if not _record(value) or set(value) != {"kind", "start", "end"}:
            return ParserFailure(ParserCode.RESPONSE)
        kind, start, end = value["kind"], value["start"], value["end"]
        if (
            not isinstance(kind, str)
            or kind not in {"TEXT", "LEGACY_OPAQUE", "CODE"}
            or type(start) is not int
            or type(end) is not int
            or start < previous
            or end <= start
        ):
            return ParserFailure(ParserCode.RESPONSE)
        if kind != "TEXT" and kind != "LEGACY_OPAQUE" and kind != "CODE":
            return ParserFailure(ParserCode.RESPONSE)
        utf16.append(_SourceRange(kind, start, end))
        previous = end
    # Only the admitted endpoints need a UTF-16 conversion. Surrogate splits fail.
    endpoints = sorted({point for span in utf16 for point in (span.start, span.end)})
    positions: dict[int, int] = {}
    cursor = 0
    units = 0
    for index, char in enumerate(source):
        if cursor < len(endpoints) and endpoints[cursor] == units:
            positions[units] = index
            cursor += 1
        units += 2 if ord(char) > 0xFFFF else 1
        if cursor < len(endpoints) and endpoints[cursor] < units:
            return ParserFailure(ParserCode.RESPONSE)
    if cursor < len(endpoints) and endpoints[cursor] == units:
        positions[units] = len(source)
        cursor += 1
    if cursor != len(endpoints):
        return ParserFailure(ParserCode.RESPONSE)
    return tuple(
        _SourceRange(span.kind, positions[span.start], positions[span.end]) for span in utf16
    )


def prepare_sources(
    sources: tuple[SourceDocument, ...], runner: ParserRunner = run_parser
) -> tuple[ProseDocument, ...] | ParserFailure:
    if any(source.path.suffix.lower() not in {".md", ".mdx", ".txt"} for source in sources):
        return ParserFailure(ParserCode.INPUT)
    try:
        markdown = tuple(
            _RequestDocument(index, source.text, source.sha256)
            for index, source in enumerate(sources)
            if source.path.suffix.lower() == ".md"
        )
        request = json.dumps(asdict(_Request(markdown)), ensure_ascii=False).encode("utf-8")
    except UnicodeError:
        return ParserFailure(ParserCode.INPUT)
    if not markdown:
        return tuple(LexicalProse(source) for source in sources)
    if len(markdown) > MAX_DOCUMENTS or len(request) > MAX_BYTES:
        return ParserFailure(ParserCode.LIMIT)
    output = runner(request)
    if isinstance(output, ParserFailure):
        return output
    if not output.stdout and output.returncode != 0:
        return ParserFailure(ParserCode.EXIT)
    if len(output.stdout) > MAX_RESPONSE_BYTES:
        return ParserFailure(ParserCode.LIMIT)
    try:
        response: object = json.loads(output.stdout, object_pairs_hook=_unique)
    except (ValueError, UnicodeError):
        return ParserFailure(ParserCode.RESPONSE)
    if (
        not _record(response)
        or type(response.get("schemaVersion")) is not int
        or response.get("schemaVersion") != 1
    ):
        return ParserFailure(ParserCode.RESPONSE)
    if response.get("type") == "PARSER_REJECTED":
        code = response.get("code")
        if set(response) != {"type", "schemaVersion", "code"} or not isinstance(code, str):
            return ParserFailure(ParserCode.RESPONSE)
        try:
            admitted_code = ParserCode(code)
        except ValueError:
            return ParserFailure(ParserCode.RESPONSE)
        if (
            admitted_code
            not in {
                ParserCode.INPUT,
                ParserCode.RUNTIME,
                ParserCode.PARSE,
                ParserCode.LIMIT,
                ParserCode.NODE,
            }
            or output.returncode != 2
        ):
            return ParserFailure(ParserCode.RESPONSE)
        return ParserFailure(admitted_code)
    documents = response.get("documents")
    if (
        set(response) != {"type", "schemaVersion", "documents"}
        or response["type"] != "PARSED_MARKDOWN"
        or output.returncode != 0
        or not _objects(documents)
        or len(documents) != len(markdown)
    ):
        return ParserFailure(ParserCode.RESPONSE)
    admitted: dict[int, MarkdownProse] = {}
    for expected, document in zip(markdown, documents, strict=True):
        if (
            not _record(document)
            or set(document) != {"id", "sha256", "ranges"}
            or type(document["id"]) is not int
            or document["id"] != expected.id
            or document["sha256"] != expected.sha256
        ):
            return ParserFailure(ParserCode.RESPONSE)
        ranges = _ranges(expected.source, document["ranges"])
        if isinstance(ranges, ParserFailure):
            return ranges
        admitted[expected.id] = MarkdownProse(sources[expected.id], ranges)
    return tuple(
        admitted[index] if source.path.suffix.lower() == ".md" else LexicalProse(source)
        for index, source in enumerate(sources)
    )
