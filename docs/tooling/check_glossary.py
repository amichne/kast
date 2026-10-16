#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["jsonschema==4.26.0"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Keep Kast terminology and its projections under one local source owner."""

import argparse
import hashlib
import json
from collections.abc import Callable
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal, assert_never

from glossary_json import (
    Canonical,
    FailureCode,
    Occurrence,
    Rejected,
    in_scope,
    load_glossary,
    occurrences,
    rejected_form_kind,
    render_markdown,
    render_public_mdx,
    vale_rules,
)
from markdown_parser import (
    ParserCode,
    ParserFailure,
    ProseDocument,
    SourceDocument,
    prepare_sources,
)

GENERATED_DIRECTORIES = frozenset(
    {".git", ".gradle", ".venv", "__pycache__", "build", "node_modules"}
)
PROSE_SUFFIXES = frozenset({".md", ".mdx", ".txt"})


class Failure(StrEnum):
    INPUT = "INPUT"
    IO = "IO"
    STALE_VIEW = "STALE_VIEW"
    STALE_RULES = "STALE_RULES"


@dataclass(frozen=True)
class RejectionReport:
    code: Failure | FailureCode | ParserCode
    detail: str
    type: Literal["REJECTED"] = "REJECTED"


@dataclass(frozen=True)
class Finding:
    type: Literal["REPLACE", "REVIEW"]
    conceptId: str
    canonical: str
    form: str
    line: int
    span: tuple[int, int]


@dataclass(frozen=True)
class DocumentReport:
    path: str
    type: Literal["IN_SCOPE", "OUT_OF_SCOPE"]
    sha256: str
    findings: tuple[Finding, ...]


@dataclass(frozen=True)
class GlossaryReport:
    sourceSha256: str
    entries: int
    documents: tuple[DocumentReport, ...]
    type: Literal["GLOSSARY_REPORT"] = "GLOSSARY_REPORT"
    schemaVersion: Literal[1] = 1


@dataclass(frozen=True)
class Request:
    source: Path
    documents: tuple[Path, ...]
    render: Path | None
    check_render: Path | None
    vale_dir: Path | None
    check_vale: Path | None
    public_render: Path | None = None
    check_public_render: Path | None = None


@dataclass(frozen=True)
class _ScopedInput:
    source: SourceDocument
    sha256: str


@dataclass(frozen=True)
class _ExcludedInput:
    path: Path
    sha256: str


type BatchParser = Callable[[tuple[SourceDocument, ...]], tuple[ProseDocument, ...] | ParserFailure]


def finding(item: Occurrence) -> Finding | Canonical:
    if isinstance(item.form, Canonical):
        return item.form
    return Finding(
        rejected_form_kind(item.form),
        item.entry.id,
        item.entry.term,
        item.form.form,
        item.line,
        (item.start, item.end),
    )


def evaluate(
    request: Request, parser: BatchParser = prepare_sources
) -> GlossaryReport | RejectionReport:
    glossary = load_glossary(request.source)
    if isinstance(glossary, Rejected):
        return RejectionReport(glossary.code, glossary.detail)
    try:
        if request.render is not None and request.render.resolve() == request.source.resolve():
            return RejectionReport(Failure.INPUT, "The reader view cannot replace the JSON source")
        if (
            request.public_render is not None
            and request.public_render.resolve() == request.source.resolve()
        ):
            return RejectionReport(Failure.INPUT, "The public view cannot replace the JSON source")
        if request.vale_dir is not None and request.vale_dir.exists():
            return RejectionReport(Failure.INPUT, "Export Vale rules into a new directory")
        scope_root = (request.source.parent / glossary.scope.root).resolve()
        documents: list[DocumentReport] = []
        paths: dict[Path, None] = {}
        for document in request.documents:
            if document.is_dir():
                for directory, children, files in document.walk():
                    children[:] = sorted(set(children) - GENERATED_DIRECTORIES)
                    for name in sorted(files):
                        path = directory / name
                        if path.suffix in PROSE_SUFFIXES:
                            paths[path] = None
            else:
                paths[document] = None
        inputs: list[_ScopedInput | _ExcludedInput] = []
        for path in paths:
            if not path.is_file() or path.suffix not in PROSE_SUFFIXES:
                return RejectionReport(
                    Failure.INPUT, "Supply existing Markdown, MDX, or text documents"
                )
            scoped = in_scope(glossary, path.resolve(), scope_root)
            source = path.read_bytes()
            digest = hashlib.sha256(source).hexdigest()
            inputs.append(
                _ScopedInput(SourceDocument(path, source.decode("utf-8")), digest)
                if scoped
                else _ExcludedInput(path, digest)
            )
        parsed = parser(tuple(item.source for item in inputs if isinstance(item, _ScopedInput)))
        if isinstance(parsed, ParserFailure):
            return RejectionReport(parsed.code, parsed.detail)
        admitted = iter(parsed)
        for item in inputs:
            match item:
                case _ScopedInput():
                    path = item.source.path
                    hits = occurrences(glossary, next(admitted))
                    scope: Literal["IN_SCOPE", "OUT_OF_SCOPE"] = "IN_SCOPE"
                case _ExcludedInput():
                    path, hits, scope = item.path, (), "OUT_OF_SCOPE"
            findings = tuple(
                result for item in hits if not isinstance(result := finding(item), Canonical)
            )
            documents.append(
                DocumentReport(
                    str(path),
                    scope,
                    item.sha256,
                    findings,
                )
            )
        markdown = render_markdown(glossary)
        public_mdx = render_public_mdx(glossary)
        if (
            request.check_public_render is not None
            and request.check_public_render.read_text(encoding="utf-8") != public_mdx
        ):
            return RejectionReport(
                Failure.STALE_VIEW, "Regenerate the public glossary from docs/glossary.json"
            )
        if (
            request.check_render is not None
            and request.check_render.read_text(encoding="utf-8") != markdown
        ):
            return RejectionReport(
                Failure.STALE_VIEW, "Regenerate the reader view from docs/glossary.json"
            )
        if request.check_vale is not None:
            expected = dict(vale_rules(glossary))
            actual = {
                path.name: path.read_text(encoding="utf-8")
                for path in request.check_vale.glob("*.yml")
            }
            if actual != expected:
                return RejectionReport(
                    Failure.STALE_RULES, "Regenerate the complete Kast Vale rule set"
                )
        if request.render is not None:
            _ = request.render.write_text(markdown, encoding="utf-8")
        if request.public_render is not None:
            _ = request.public_render.write_text(public_mdx, encoding="utf-8")
        if request.vale_dir is not None:
            request.vale_dir.mkdir(parents=True)
            for name, content in vale_rules(glossary):
                _ = (request.vale_dir / name).write_text(content, encoding="utf-8")
    except (OSError, UnicodeError):
        return RejectionReport(Failure.IO, "A local input or output cannot be read or written")
    return GlossaryReport(glossary.source_sha256, len(glossary.entries), tuple(documents))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("documents", type=Path, nargs="*")
    parser.add_argument("--render", type=Path)
    parser.add_argument("--check-render", type=Path)
    parser.add_argument("--public-render", type=Path)
    parser.add_argument("--check-public-render", type=Path)
    parser.add_argument("--vale-dir", type=Path)
    parser.add_argument("--check-vale", type=Path)
    args = parser.parse_args()
    result = evaluate(
        Request(
            args.source,
            tuple(args.documents),
            args.render,
            args.check_render,
            args.vale_dir,
            args.check_vale,
            args.public_render,
            args.check_public_render,
        )
    )
    print(json.dumps(asdict(result), indent=2, sort_keys=True))
    match result:
        case RejectionReport():
            return 2
        case GlossaryReport():
            return int(any(document.findings for document in result.documents))
    assert_never(result)


if __name__ == "__main__":
    raise SystemExit(main())
