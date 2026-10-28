#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["pydantic==2.12.3", "jsonschema==4.25.1"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Task commands over the unchanged installed Kast tool RPC."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Literal, NoReturn

from pydantic import BaseModel, ConfigDict, Field, JsonValue, RootModel, ValidationError


class InputError(ValueError):
    """CLI ingress rejection, before a semantic process starts."""


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class Directory(Model):
    type: Literal["DIRECTORY"] = "DIRECTORY"
    relativeDirectoryPath: str
    includeSubdirectories: bool = True
    sourceSetNames: tuple[str, ...]


class Package(Model):
    type: Literal["PACKAGE"] = "PACKAGE"
    packageName: str
    includeSubpackages: bool = True
    sourceSetNames: tuple[str, ...]


class Search(Model):
    type: Literal["SEARCH_DECLARATIONS"] = "SEARCH_DECLARATIONS"
    declarationName: str
    nameMatch: Literal["EXACT", "FUZZY"] = "EXACT"
    declarationKinds: tuple[str, ...] | None = None
    scope: Directory | Package


class Exact(Model):
    type: Literal["SYMBOL_REFS"] = "SYMBOL_REFS"
    symbolRefs: tuple[str, ...]


class Workspace(Model):
    type: Literal["WORKSPACE"] = "WORKSPACE"


class SourceDomain(Model):
    type: Literal["SOURCE_DOMAIN"] = "SOURCE_DOMAIN"
    directory: str | None = None
    sourceSets: tuple[str, ...]
    includeSubdirectories: bool = True
    sourcePolicy: Literal["PRODUCTION_ONLY", "TEST_ONLY", "PRODUCTION_AND_TEST"]
    generatedSources: Literal["INCLUDE", "EXCLUDE"]


class Symbols(Model):
    type: Literal["SYMBOLS"] = "SYMBOLS"
    fields: tuple[str, ...] = ("NAME", "LOCATION", "SIGNATURE")


class OtherOutput(Model):
    type: Literal["OCCURRENCES", "TRAVERSAL_RECORDS", "BINDING_ROWS", "VALUE_PATHS"]


type Output = Symbols | OtherOutput
type Relation = Literal[
    "CALLERS", "CALLEES", "REFERENCES", "IMPLEMENTATIONS", "INHERITORS", "OVERRIDES", "TYPE_USES"
]


class Expand(Model):
    type: Literal["EXPAND_RELATION"] = "EXPAND_RELATION"
    relation: Relation
    expansionScope: Workspace | SourceDomain


class Walk(Model):
    type: Literal["WALK"] = "WALK"
    relation: Relation
    maximumDepth: int = Field(ge=1, le=1000)
    expansionScope: Workspace | SourceDomain


class Emit(Model):
    type: Literal["EMIT"] = "EMIT"
    output: Output


class Trace(Model):
    type: Literal["TRACE"] = "TRACE"
    output: Symbols = Symbols()
    expansionScope: Workspace | SourceDomain


class Budget(Model):
    maxResults: int = Field(default=20, ge=1)
    maxReturnedBytes: int | None = Field(default=None, ge=4096)


class Run(Model):
    type: Literal["RUN"] = "RUN"
    source: Search | Exact
    steps: tuple[Expand | Walk, ...] | None = None
    terminalStep: Emit | Trace
    retention: Literal["RETAIN"] = "RETAIN"
    executionBudget: Budget


class Window(Model):
    type: Literal["WINDOW"] = "WINDOW"
    beforeLines: int = Field(ge=0, le=1000)
    afterLines: int = Field(ge=0, le=1000)


class SourceRead(Model):
    type: Literal["READ_SOURCE"] = "READ_SOURCE"
    candidateRef: str
    region: str = "ANCHOR"
    text: Window | None = None
    executionBudget: Budget


class ResultRead(Model):
    type: Literal["READ_RESULT"] = "READ_RESULT"
    result: str
    cursor: int = Field(default=0, ge=0)
    evidence_cursor: int = Field(default=0, ge=0)
    output: Output
    executionBudget: Budget


class Resume(Model):
    type: Literal["RESUME"] = "RESUME"
    continuation: str
    executionBudget: Budget


class Query(Model):
    request: Run | SourceRead | ResultRead | Resume
    verbose: Literal[True] | None = None


class Diagnostics(Model):
    relativePath: str


class Raw(RootModel[dict[str, JsonValue]]):
    """Opaque public tool arguments. The installed catalog admits this field."""


class Options(Model):
    command: str
    workspace: Path = Path.cwd()
    rpc: Path = Path.home() / ".local/share/kast/installation/bin/kast-tool-rpc-complete"
    emit_request: bool = False
    catalog_file: Path | None = None
    save: Path | None = None
    timeout: float = Field(default=120, gt=0)
    write: bool = False
    verbose: bool = False
    page_size: int = Field(default=20, ge=1)
    bytes: int | None = Field(default=None, ge=4096)
    name: str | None = None
    kind: str | None = None
    directory: str | None = None
    package: str | None = None
    source_set: tuple[str, ...] = ()
    shallow: bool = False
    fuzzy: bool = False
    ref: str | None = None
    token: str | None = None
    production_only: bool = False
    exclude_generated: bool = False
    relation: Relation | None = None
    depth: int | None = None
    region: str = "ANCHOR"
    context: int | None = Field(default=None, ge=0, le=1000)
    cursor: int = 0
    evidence_cursor: int = 0
    output: str = "SYMBOLS"
    path: str | None = None
    tool: str = "query_symbols"
    file: Path | None = None
    definition: str | None = None


class Prepared(Model):
    tool: str
    arguments: Query | Diagnostics | Raw
    options: Options


class Parser(argparse.ArgumentParser):
    def error(self, message: str) -> NoReturn:
        raise InputError(message)


def parser() -> Parser:
    common = Parser(add_help=False)
    common.add_argument("--workspace", type=Path, default=Path.cwd())
    common.add_argument("--rpc", type=Path, default=Options.model_fields["rpc"].default)
    common.add_argument(
        "--emit-request",
        action="store_true",
        help="Print admitted arguments without a semantic call.",
    )
    common.add_argument(
        "--catalog-file",
        type=Path,
        help="Use a saved catalog for offline request construction only.",
    )
    common.add_argument("--save", type=Path, help="Save the unchanged reply to a new file.")
    common.add_argument("--timeout", type=float, default=120)
    common.add_argument(
        "--page-size",
        type=int,
        default=20,
        help="Presentation rows per page, not a complete-result limit.",
    )
    common.add_argument("--bytes", type=int, help="Presentation budget, at least 4096 bytes.")
    common.add_argument("--verbose", action="store_true")
    root = Parser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    find = commands.add_parser(
        "find", parents=[common], help="Discover all matches. Never selects an overload."
    )
    find.add_argument("name")
    find.add_argument(
        "--kind", choices=["class", "interface", "function", "property", "type_alias"]
    )
    scope = find.add_mutually_exclusive_group()
    scope.add_argument("--directory")
    scope.add_argument("--package")
    find.add_argument("--source-set", action="append", default=[])
    find.add_argument("--shallow", action="store_true", help="Exclude descendants explicitly.")
    find.add_argument("--fuzzy", action="store_true")
    for name in (
        "callers",
        "callees",
        "references",
        "implementations",
        "inheritors",
        "overrides",
        "type_uses",
        "trace",
        "walk",
    ):
        sub = commands.add_parser(
            name, parents=[common], help="Use an issued exact reference; never a name or cursor."
        )
        sub.add_argument("--ref", required=True)
        sub.add_argument("--directory", help="Destination directory, separate from discovery.")
        sub.add_argument("--source-set", action="append", default=[])
        sub.add_argument("--production-only", action="store_true")
        sub.add_argument("--exclude-generated", action="store_true")
        if name == "walk":
            sub.add_argument(
                "relation",
                type=str.upper,
                choices=[
                    "CALLERS",
                    "CALLEES",
                    "REFERENCES",
                    "IMPLEMENTATIONS",
                    "INHERITORS",
                    "OVERRIDES",
                    "TYPE_USES",
                ],
            )
            sub.add_argument(
                "--depth",
                type=int,
                required=True,
                help="Explicit semantic hop limit; preserves each hop.",
            )
    source = commands.add_parser(
        "source",
        parents=[common],
        help="Read the declaration with ANCHOR first. Body regions can be absent.",
        description="Start with ANCHOR to establish declaration kind and structure.",
    )
    source.add_argument("--ref", required=True, help="Issued exact, candidate, or source selector.")
    source.add_argument(
        "--region",
        type=str.upper,
        default="ANCHOR",
        help="ANCHOR reads this declaration. ENCLOSING_* selects a strict outer region.",
        choices=[
            "ANCHOR",
            "CALLABLE_BODY",
            "CLASS_BODY",
            "FILE",
            "ENCLOSING_DECLARATION",
            "ENCLOSING_CALLABLE_BODY",
            "ENCLOSING_CLASS_BODY",
        ],
    )
    source.add_argument(
        "--context",
        type=int,
        help="Lines before and after the anchor. A window is not complete source.",
    )
    result = commands.add_parser("result", parents=[common])
    result.add_argument("--ref", required=True)
    result.add_argument("--cursor", type=int, default=0)
    result.add_argument("--evidence-cursor", type=int, default=0)
    result.add_argument(
        "--output",
        type=str.upper,
        required=True,
        choices=["SYMBOLS", "OCCURRENCES", "TRAVERSAL_RECORDS", "BINDING_ROWS", "VALUE_PATHS"],
    )
    resume = commands.add_parser("resume", parents=[common])
    resume.add_argument("--token", required=True)
    diag = commands.add_parser("diagnostics", parents=[common])
    diag.add_argument(
        "path", help="File or directory relative to the workspace. Use . for the root."
    )
    raw = commands.add_parser(
        "raw",
        parents=[common],
        help="Advanced unchanged tool arguments; installed catalog governs.",
    )
    raw.add_argument("--tool", default="query_symbols")
    raw.add_argument("--file", required=True, type=Path)
    raw.add_argument(
        "--write", action="store_true", help="Explicitly permit a catalog-declared WRITE tool."
    )
    schema = commands.add_parser("schema", parents=[common], help="Read one installed tool schema.")
    schema.add_argument("--tool", default="query_symbols")
    schema.add_argument(
        "--definition", help="Read one named $defs entry instead of the whole schema."
    )
    return root


def relative_directory(value: str) -> str:
    if value != "." and (
        value.startswith("/")
        or "\\" in value
        or any(part in ("", ".", "..") for part in value.split("/"))
        or any(char in value for char in "*?[]")
        or value.endswith((".kt", ".kts"))
    ):
        raise InputError(
            "Use a canonical workspace-relative directory, not a file, glob, or parent path."
        )
    return value


def source_sets(options: Options) -> tuple[str, ...]:
    names = options.source_set or ("main", "test")
    if len(set(names)) != len(names) or any(
        not name or any(c in name for c in "/*?[]\\") for name in names
    ):
        raise InputError("Use distinct exact imported source-set names.")
    return names


def reference(value: str | None, family: str) -> str:
    pattern = {
        "exact": r"exact:v5:[A-Za-z0-9_-]{22}",
        "result": r"result:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}",
        "output": r"query-output:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}",
    }[family]
    if value is None or re.fullmatch(pattern, value) is None:
        raise InputError(
            f"Copy an issued {family} reference unchanged. "
            "Names and other token families are not accepted."
        )
    return value


def destination(options: Options) -> Workspace | SourceDomain:
    if not (
        options.directory
        or options.source_set
        or options.production_only
        or options.exclude_generated
    ):
        return Workspace()
    return SourceDomain(
        directory=relative_directory(options.directory) if options.directory else None,
        sourceSets=source_sets(options),
        sourcePolicy="PRODUCTION_ONLY" if options.production_only else "PRODUCTION_AND_TEST",
        generatedSources="EXCLUDE" if options.exclude_generated else "INCLUDE",
    )


def output(name: str) -> Output:
    return Symbols() if name == "SYMBOLS" else OtherOutput.model_validate({"type": name})


def query(options: Options) -> Query:
    budget = Budget(maxResults=options.page_size, maxReturnedBytes=options.bytes)
    action: Run | SourceRead | ResultRead | Resume
    if options.command == "find":
        if not options.name or "." in options.name or any(c in options.name for c in "/*?()"):
            raise InputError(
                "Supply a simple declaration name. Use --package for a Kotlin package."
            )
        scope = (
            Package(
                packageName=options.package,
                includeSubpackages=not options.shallow,
                sourceSetNames=source_sets(options),
            )
            if options.package
            else Directory(
                relativeDirectoryPath=relative_directory(options.directory or "."),
                includeSubdirectories=not options.shallow,
                sourceSetNames=source_sets(options),
            )
        )
        kind = (
            "CLASS"
            if options.kind == "interface"
            else options.kind.upper()
            if options.kind
            else None
        )
        action = Run(
            source=Search(
                declarationName=options.name,
                nameMatch="FUZZY" if options.fuzzy else "EXACT",
                declarationKinds=(kind,) if kind else None,
                scope=scope,
            ),
            terminalStep=Emit(output=Symbols()),
            executionBudget=budget,
        )
    elif options.command == "source":
        if not options.ref:
            raise InputError("Use an issued source selector or exact reference.")
        action = SourceRead(
            candidateRef=options.ref,
            region=options.region,
            text=Window(beforeLines=options.context, afterLines=options.context)
            if options.context is not None
            else None,
            executionBudget=budget,
        )
    elif options.command == "result":
        action = ResultRead(
            result=reference(options.ref, "result"),
            cursor=options.cursor,
            evidence_cursor=options.evidence_cursor,
            output=output(options.output),
            executionBudget=budget,
        )
    elif options.command == "resume":
        action = Resume(continuation=reference(options.token, "output"), executionBudget=budget)
    else:
        exact = Exact(symbolRefs=(reference(options.ref, "exact"),))
        expansion = destination(options)
        if options.command == "trace":
            action = Run(
                source=exact, terminalStep=Trace(expansionScope=expansion), executionBudget=budget
            )
        elif options.command == "walk":
            if options.depth is None or options.relation is None:
                raise InputError("walk needs a relation and --depth.")
            action = Run(
                source=exact,
                steps=(
                    Walk(
                        relation=options.relation,
                        maximumDepth=options.depth,
                        expansionScope=expansion,
                    ),
                ),
                terminalStep=Emit(output=OtherOutput(type="TRAVERSAL_RECORDS")),
                executionBudget=budget,
            )
        else:
            step = Expand.model_validate(
                {"relation": options.command.upper(), "expansionScope": expansion}
            )
            action = Run(
                source=exact,
                steps=(step,),
                terminalStep=Emit(
                    output=OtherOutput(type="OCCURRENCES")
                    if options.command in ("references", "type_uses")
                    else Symbols()
                ),
                executionBudget=budget,
            )
    return Query(request=action, verbose=True if options.verbose else None)


def prepare(arguments: list[str]) -> Prepared:
    try:
        options = Options.model_validate(vars(parser().parse_args(arguments)))
        if options.catalog_file and not options.emit_request:
            raise InputError("--catalog-file is offline-only and needs --emit-request.")
        if options.command == "schema":
            return Prepared(tool=options.tool, arguments=Raw({}), options=options)
        if options.command == "raw":
            if options.file is None:
                raise InputError("raw needs --file.")
            return Prepared(
                tool=options.tool,
                arguments=Raw.model_validate_json(options.file.read_bytes()),
                options=options,
            )
        if options.command == "diagnostics":
            path = options.path or "."
            if path.startswith("/") or ".." in path.split("/"):
                raise InputError("Diagnostics needs a workspace-relative file or directory.")
            return Prepared(
                tool="check_diagnostics", arguments=Diagnostics(relativePath=path), options=options
            )
        return Prepared(tool="query_symbols", arguments=query(options), options=options)
    except ValidationError as error:
        raise InputError(str(error)) from error


def main() -> int:
    from transport import run

    return run(sys.argv[1:])


if __name__ == "__main__":
    raise SystemExit(main())
