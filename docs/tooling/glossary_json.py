"""Parse one local JSON contract into immutable concepts and scoped forms."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path, PurePosixPath
from typing import Literal, TypedDict, TypeGuard, assert_never

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError
from jsonschema.protocols import Validator

SCHEMA = Path(__file__).with_name("glossary.schema.json")


class FailureCode(StrEnum):
    IO = "IO"
    INPUT = "INPUT"
    STALE_VIEW = "STALE_VIEW"
    SYNTAX = "SYNTAX"
    GRAMMAR = "GRAMMAR"
    COLLISION = "COLLISION"
    REFERENCE = "REFERENCE"
    SCOPE = "SCOPE"


@dataclass(frozen=True, slots=True)
class Rejected:
    code: FailureCode
    detail: str


class _Usage(TypedDict):
    url: str
    locator: str
    examined: str
    meaning: str


class _Source(TypedDict):
    path: str
    locator: str


class _UsageEvidence(TypedDict):
    type: Literal["USAGE"]
    usage: _Usage


class _Justification(TypedDict):
    type: Literal["JUSTIFICATION"]
    text: str


class _RawForm(TypedDict):
    type: Literal["REPLACE", "REVIEW"]
    form: str
    reason: str
    evidence: list[_UsageEvidence | _Justification]


class _RawEntry(TypedDict):
    id: str
    term: str
    definition: str
    scope: str
    example: str
    distinction: str
    usage: list[_Usage]
    localSources: list[_Source]
    justification: str
    related: list[str]
    rejectedForms: list[_RawForm]


class _RawScope(TypedDict):
    root: str
    include: list[str]
    exclude: list[str]


class _RawReview(TypedDict):
    type: Literal["PROVISIONAL"]
    reason: str


type JsonValue = str | int | float | bool | None | list[JsonValue] | dict[str, JsonValue]


class _RawGlossary(TypedDict):
    type: Literal["GLOSSARY"]
    schemaVersion: Literal[1]
    domain: str
    review: _RawReview
    scope: _RawScope
    entries: list[_RawEntry]


@dataclass(frozen=True, slots=True)
class Usage:
    url: str
    locator: str
    examined: str
    meaning: str


@dataclass(frozen=True, slots=True)
class LocalSource:
    path: str
    locator: str


@dataclass(frozen=True, slots=True)
class UsageEvidence:
    usage: Usage


@dataclass(frozen=True, slots=True)
class Justification:
    text: str


type Evidence = UsageEvidence | Justification


@dataclass(frozen=True, slots=True)
class _Replacement:
    form: str
    reason: str
    evidence: tuple[Evidence, ...]


@dataclass(frozen=True, slots=True)
class _Review:
    form: str
    reason: str
    evidence: tuple[Evidence, ...]


type Replacement = _Replacement
type Review = _Review
type RejectedForm = Replacement | Review


def rejected_form_kind(form: RejectedForm) -> Literal["REPLACE", "REVIEW"]:
    match form:
        case _Replacement():
            return "REPLACE"
        case _Review():
            return "REVIEW"
    assert_never(form)


@dataclass(frozen=True, slots=True)
class _Entry:
    id: str
    term: str
    definition: str
    scope: str
    example: str
    distinction: str
    usage: tuple[Usage, ...]
    local_sources: tuple[LocalSource, ...]
    justification: str
    related: tuple[str, ...]
    rejected_forms: tuple[RejectedForm, ...]


type Entry = _Entry


@dataclass(frozen=True, slots=True)
class _Scope:
    root: str
    include: tuple[str, ...]
    exclude: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class _Glossary:
    domain: str
    review_reason: str
    scope: _Scope
    entries: tuple[Entry, ...]
    source_sha256: str


type Glossary = _Glossary


@dataclass(frozen=True, slots=True)
class Canonical:
    label: str


@dataclass(frozen=True, slots=True)
class Occurrence:
    entry: Entry
    form: RejectedForm | Canonical
    path: Path
    line: int
    start: int
    end: int


def _unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def _nonfinite(_value: str) -> object:
    raise ValueError("Nonfinite JSON number")


def _mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def _list(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _is_json(value: object) -> TypeGuard[JsonValue]:
    if value is None or isinstance(value, (str, int, float, bool)):
        return True
    if _list(value):
        return all(_is_json(item) for item in value)
    if _mapping(value):
        return all(isinstance(key, str) and _is_json(item) for key, item in value.items())
    return False


def _conforms(value: JsonValue, validator: Validator) -> TypeGuard[_RawGlossary]:
    # The pinned local schema establishes the complete TypedDict shape once.
    return validator.is_valid(value)


def _usage(raw: _Usage) -> Usage:
    return Usage(raw["url"], raw["locator"], raw["examined"], raw["meaning"])


def _evidence(raw: _UsageEvidence | _Justification) -> Evidence:
    match raw["type"]:
        case "USAGE":
            return UsageEvidence(_usage(raw["usage"]))
        case "JUSTIFICATION":
            return Justification(raw["text"])
    assert_never(raw["type"])


def _form(raw: _RawForm) -> RejectedForm:
    evidence = tuple(_evidence(item) for item in raw["evidence"])
    match raw["type"]:
        case "REPLACE":
            return _Replacement(raw["form"], raw["reason"], evidence)
        case "REVIEW":
            return _Review(raw["form"], raw["reason"], evidence)
    assert_never(raw["type"])


def _normalized(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.casefold()).strip()


def parse(source: bytes, validator: Validator) -> Glossary | Rejected:
    """Pure ingress: establish grammar, unique labels, references, and literal scope."""
    try:
        raw: object = json.loads(source, object_pairs_hook=_unique, parse_constant=_nonfinite)
    except (ValueError, UnicodeError):
        return Rejected(FailureCode.SYNTAX, "Invalid UTF-8 JSON or duplicate keys")
    if not _is_json(raw) or not _conforms(raw, validator):
        return Rejected(FailureCode.GRAMMAR, "The document violates glossary.schema.json")
    scope = raw["scope"]
    root = PurePosixPath(scope["root"])
    if root.is_absolute() or "\\" in scope["root"] or ":" in scope["root"]:
        return Rejected(FailureCode.SCOPE, "The scope root must be a relative POSIX path")
    for pattern in (*scope["include"], *scope["exclude"]):
        if pattern.startswith("/") or ".." in pattern.split("/") or "***" in pattern:
            return Rejected(FailureCode.SCOPE, "Invalid document glob")
    entries = tuple(
        _Entry(
            item["id"],
            item["term"],
            item["definition"],
            item["scope"],
            item["example"],
            item["distinction"],
            tuple(_usage(use) for use in item["usage"]),
            tuple(
                LocalSource(source["path"], source["locator"]) for source in item["localSources"]
            ),
            item["justification"],
            tuple(item["related"]),
            tuple(_form(form) for form in item["rejectedForms"]),
        )
        for item in raw["entries"]
    )
    ids = [entry.id for entry in entries]
    if len(ids) != len(set(ids)):
        return Rejected(FailureCode.COLLISION, "Duplicate concept identifier")
    labels: set[str] = set()
    definitions: set[str] = set()
    for entry in entries:
        definition = _normalized(entry.definition)
        if definition in definitions:
            return Rejected(FailureCode.COLLISION, "Identical definitions need concept review")
        definitions.add(definition)
        for label in (entry.term, *(form.form for form in entry.rejected_forms)):
            normalized = _normalized(label)
            if normalized in labels:
                return Rejected(
                    FailureCode.COLLISION, "A canonical or rejected form has multiple owners"
                )
            labels.add(normalized)
        for source_ref in entry.local_sources:
            path = PurePosixPath(source_ref.path)
            if (
                path.is_absolute()
                or ".." in path.parts
                or "\\" in source_ref.path
                or ":" in source_ref.path
            ):
                return Rejected(
                    FailureCode.SCOPE, "Local source citations must be repository-relative"
                )
        if entry.id in entry.related or not set(entry.related).issubset(ids):
            return Rejected(FailureCode.REFERENCE, "Unknown or self-referential related concept")
    return _Glossary(
        raw["domain"],
        raw["review"]["reason"],
        _Scope(scope["root"], tuple(scope["include"]), tuple(scope["exclude"])),
        entries,
        hashlib.sha256(source).hexdigest(),
    )


def load_glossary(path: Path) -> Glossary | Rejected:
    """Read local bytes only. Citation URLs never reach an effect adapter."""
    try:
        schema = json.loads(SCHEMA.read_bytes())
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        source = path.read_bytes()
    except (OSError, UnicodeError, ValueError, SchemaError):
        return Rejected(FailureCode.IO, "The local glossary or bundled schema cannot be read")
    return parse(source, validator)


def _glob(pattern: str, path: str) -> bool:
    parts = re.split(r"(\*\*/|\*\*|\*|\?)", pattern)
    substitutions = {"**/": "(?:.*/)?", "**": ".*", "*": "[^/]*", "?": "[^/]"}
    regex = "".join(substitutions.get(part, re.escape(part)) for part in parts)
    return re.fullmatch(regex, path) is not None


def in_scope(glossary: Glossary, path: Path, scope_root: Path) -> bool:
    try:
        relative = path.relative_to(scope_root).as_posix()
    except ValueError:
        return False
    return any(_glob(pattern, relative) for pattern in glossary.scope.include) and not any(
        _glob(pattern, relative) for pattern in glossary.scope.exclude
    )


@dataclass(frozen=True, slots=True)
class _Fence:
    marker: str


type _BlockState = Literal["PROSE", "COMMENT", "FRONTMATTER"] | _Fence
type _InlineState = Literal["PROSE", "COMMENT"]


def _mask_inline(line: str, state: _InlineState) -> tuple[str, _InlineState]:
    parts: list[str] = []
    cursor = 0
    while cursor < len(line):
        if state == "COMMENT":
            end = line.find("-->", cursor)
            if end == -1:
                parts.append(" " * (len(line) - cursor))
                return "".join(parts), state
            parts.append(" " * (end + 3 - cursor))
            cursor = end + 3
            state = "PROSE"
            continue
        token = re.search(r"<!--|`+", line[cursor:])
        if token is None:
            parts.append(line[cursor:])
            break
        start = cursor + token.start()
        end = cursor + token.end()
        parts.append(line[cursor:start])
        if token[0] == "<!--":
            parts.append(" " * len(token[0]))
            cursor = end
            state = "COMMENT"
            continue
        closing = re.search(rf"(?<!`){re.escape(token[0])}(?!`)", line[end:])
        if closing is None:
            parts.append(token[0])
            cursor = end
        else:
            cursor = end + closing.end()
            parts.append(" " * (cursor - start))
    return "".join(parts), state


def prose_lines(text: str, markup: Literal["markdown", "mdx"]) -> tuple[str, ...]:
    """Conservative lexical view. Vale remains the native markup parser."""
    lines: list[str] = []
    state: _BlockState = "PROSE"
    for index, line in enumerate(text.splitlines()):
        if index == 0 and line.strip() == "---":
            state = "FRONTMATTER"
            lines.append("")
            continue
        if state == "FRONTMATTER":
            if line.strip() in {"---", "..."}:
                state = "PROSE"
            lines.append("")
            continue
        if isinstance(state, _Fence):
            marker = re.match(r"^\s*(`{3,}|~{3,})", line)
            if marker and marker[1][0] == state.marker[0] and len(marker[1]) >= len(state.marker):
                state = "PROSE"
            lines.append("")
            continue
        if state == "PROSE":
            marker = re.match(r"^\s*(`{3,}|~{3,})", line)
            if marker:
                state = _Fence(marker[1])
                lines.append("")
                continue
            if markup == "markdown" and line.startswith(("    ", "\t")):
                lines.append("")
                continue
        line, state = _mask_inline(line, state)
        line = re.sub(r"\]\([^\n]*?\)", lambda m: "]" + " " * (len(m[0]) - 1), line)
        line = re.sub(r"https?://\S+", lambda m: " " * len(m[0]), line)
        lines.append(line)
    return tuple(lines)


def occurrences(glossary: Glossary, text: str, path: Path) -> tuple[Occurrence, ...]:
    found: list[Occurrence] = []
    lines = (
        tuple(text.splitlines())
        if path.suffix.lower() == ".txt"
        else prose_lines(text, "mdx" if path.suffix.lower() == ".mdx" else "markdown")
    )
    for line_number, line in enumerate(lines, 1):
        for entry in glossary.entries:
            for form in (Canonical(entry.term), *entry.rejected_forms):
                label = form.label if isinstance(form, Canonical) else form.form
                pattern = rf"(?<!\w){re.escape(label)}(?!\w)"
                for match in re.finditer(pattern, line, re.IGNORECASE):
                    found.append(
                        Occurrence(entry, form, path, line_number, match.start() + 1, match.end())
                    )
    # Prefer the longest complete label at each overlapping lexical location.
    ordered = sorted(found, key=lambda item: (item.line, item.start, -item.end, item.entry.id))
    selected: list[Occurrence] = []
    for item in ordered:
        if selected and selected[-1].line == item.line and item.start <= selected[-1].end:
            continue
        selected.append(item)
    return tuple(selected)


def render_markdown(glossary: Glossary) -> str:
    """Serialize a reader view without becoming a second editable glossary."""
    lines = [
        "# Slopsentral Glossary",
        "",
        "Generated from `glossary.json`. Edit the JSON source.",
        "",
        glossary.domain + ".",
        glossary.review_reason,
        "",
        "## Terms",
        "",
    ]
    for entry in glossary.entries:
        lines.extend(
            [
                f"### {entry.term}",
                "",
                entry.definition,
                "",
                f"Scope: {entry.scope}",
                f"Example: {entry.example}",
                f"Distinction: {entry.distinction}",
                f"Justification: {entry.justification}",
                "",
                "Domain usage:",
                "",
            ]
        )
        lines.extend(
            f"- [{use.locator}]({use.url}), examined {use.examined}. {use.meaning}"
            for use in entry.usage
        )
        lines.extend(["", "Local evidence:", ""])
        lines.extend(f"- `{source.path}`: {source.locator}." for source in entry.local_sources)
        lines.extend(["", "Rejected forms:", ""])
        if not entry.rejected_forms:
            lines.append("No replacement or review target passed the evidence review.")
        for form in entry.rejected_forms:
            action = "Replacement" if isinstance(form, _Replacement) else "Context review"
            lines.append(f"- `{form.form}`: {action}. {form.reason}")
        lines.append("")
    return "\n".join(lines)


def vale_rules(glossary: Glossary) -> tuple[tuple[str, str], ...]:
    """Generate literal findings. Review targets never receive a replace action."""
    swaps: dict[str, str] = {}
    reviews: list[str] = []
    for entry in glossary.entries:
        for form in entry.rejected_forms:
            match form:
                case _Replacement():
                    swaps[re.escape(form.form)] = entry.term
                case _Review():
                    reviews.append(re.escape(form.form))
    rules: list[tuple[str, str]] = []
    if swaps:
        rules.append(
            (
                "Terminology.yml",
                "extends: substitution\nlevel: error\nignorecase: true\naction:\n  name: replace\nmessage: \"Use '%s' instead of the recorded incorrect form '%s'. Examine its scope.\"\nswap: "
                + json.dumps(swaps, ensure_ascii=True)
                + "\n",
            )
        )
    if reviews:
        rules.append(
            (
                "TerminologyReview.yml",
                "extends: existence\nlevel: warning\nignorecase: true\nmessage: \"Examine '%s' against its glossary definition. No automatic replacement is established.\"\ntokens: "
                + json.dumps(reviews, ensure_ascii=True)
                + "\n",
            )
        )
    return tuple(rules)
