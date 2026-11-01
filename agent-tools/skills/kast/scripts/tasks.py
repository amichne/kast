"""Task catalog shared by MCP and native adapters. RPC keeps semantic authority."""

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from access import Diagnostics, InputError, Model, Options, Prepared, Raw, Relation, query
from pydantic import Field, JsonValue

type ToolName = Literal[
    "query_symbols", "check_diagnostics", "health_check", "add_declaration", "replace_body"
]
type Region = Literal[
    "ANCHOR",
    "CALLABLE_BODY",
    "CLASS_BODY",
    "FILE",
    "ENCLOSING_DECLARATION",
    "ENCLOSING_CALLABLE_BODY",
    "ENCLOSING_CLASS_BODY",
]
type OutputKind = Literal[
    "symbols", "occurrences", "traversal_records", "binding_rows", "value_paths"
]


@dataclass(frozen=True)
class Context:
    workspace: Path
    rpc: Path


class TaskInput(Model):
    def options(self, command: str, context: Context) -> Options:
        values = self.model_dump()
        if "source_sets" in values:
            values["source_set"] = values.pop("source_sets")
        if "output" in values:
            values["output"] = str(values["output"]).upper()
        return Options.model_validate(
            {**values, "command": command, "workspace": context.workspace, "rpc": context.rpc}
        )


class Controls(TaskInput):
    page_size: int = Field(
        default=20, ge=1, description="Presentation rows, not semantic completion."
    )
    bytes: int | None = Field(default=None, ge=4096, description="Optional output allowance.")
    verbose: bool = False


class Find(Controls):
    name: str = Field(
        min_length=1, description="Simple declaration name. Keep known kind and scope."
    )
    kind: Literal["class", "interface", "function", "property", "type_alias"] | None = None
    directory: str | None = Field(
        default=None, description="Declaration directory, not the call site."
    )
    package: str | None = None
    source_sets: tuple[str, ...] = Field(default=("main", "test"), min_length=1)
    shallow: bool = False
    fuzzy: bool = False


class Related(Controls):
    ref: str = Field(
        pattern=r"^exact:v5:[A-Za-z0-9_-]{22}$", description="Issued exact reference, unchanged."
    )
    directory: str | None = Field(
        default=None, description="Destination directory. Omission covers the workspace."
    )
    source_sets: tuple[str, ...] = ()
    production_only: bool = False
    exclude_generated: bool = False


class Walk(Related):
    relation: Relation
    depth: int = Field(
        ge=1, le=1000, description="Semantic hop limit. Keeps traversal records for each hop."
    )


class Source(Controls):
    ref: str = Field(
        min_length=1, description="Issued exact reference, candidate, or source selector."
    )
    region: Region = Field(
        default="ANCHOR",
        description="Start with ANCHOR. Body regions can be absent. ENCLOSING selects a strict outer region.",
    )
    context: int | None = Field(
        default=10,
        ge=0,
        le=1000,
        description="Lines before and after the anchor. A window does not prove complete source.",
    )


class Result(Controls):
    ref: str = Field(pattern=r"^result:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    output: OutputKind = Field(description="Original output kind. Keep both issued cursors.")
    cursor: int = Field(default=0, ge=0)
    evidence_cursor: int = Field(default=0, ge=0)


class Resume(Controls):
    token: str = Field(pattern=r"^query-output:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")


class DiagnosticInput(TaskInput):
    path: str = Field(min_length=1, description="Workspace-relative file or directory.")


class Advanced(TaskInput):
    tool: ToolName
    arguments: dict[str, JsonValue] = Field(
        description="Opaque arguments admitted by the installed tool schema."
    )
    write: bool = Field(
        default=False,
        description="Explicitly select a user-requested source change. Writes reject without true.",
    )


class Schema(TaskInput):
    tool: ToolName = "query_symbols"
    definition: str | None = None


@dataclass(frozen=True)
class Task:
    name: str
    description: str
    model: type[TaskInput]
    read_only: bool = True


TASKS = (
    Task(
        "find",
        "Find declarations before relationships. Names can match overloads. Select owner, signature, and location before using an issued ref. The interface kind searches the CLASS family, so read source to establish the declaration kind.",
        Find,
    ),
    *(
        Task(
            name,
            "Read one-hop "
            + name
            + " for an issued exact ref. Destination scope is separate from discovery. Never use a cursor on a call as the callee identity. Preserve completeness and omissions.",
            Related,
        )
        for name in (
            "callers",
            "callees",
            "references",
            "implementations",
            "inheritors",
            "overrides",
            "type_uses",
        )
    ),
    Task(
        "trace",
        "Trace the fixed affected-use topology for an issued exact ref. Use walk for an explicit depth.",
        Related,
    ),
    Task(
        "walk",
        "Traverse a relationship through an explicit depth and keep every hop. Repeated one-hop expansions keep only the final frontier.",
        Walk,
    ),
    Task(
        "source",
        "Read the declaration with ANCHOR first. Body regions can be absent. Location ranges are UTF-16 offsets, not line numbers.",
        Source,
    ),
    Task(
        "result",
        "Read retained output without repeating semantic work. Keep the original output kind and both issued cursors.",
        Result,
    ),
    Task(
        "resume",
        "Present already-produced output from an issued query-output token. This does not continue semantic execution.",
        Resume,
    ),
    Task(
        "diagnostics",
        "Read compiler diagnostics for a workspace-relative file or directory.",
        DiagnosticInput,
    ),
    Task(
        "schema",
        "Read an installed advanced tool schema or one named definition. This does not start semantic work.",
        Schema,
    ),
    Task(
        "raw",
        "Use an advanced existing operation after reading schema. Prefer task tools. Source changes need user authorization and write=true. Preserve the original receipt or failure.",
        Advanced,
        read_only=False,
    ),
)


def prepare_task(task: Task, arguments: object, context: Context) -> Prepared:
    value = task.model.model_validate_json(
        Raw.model_validate(arguments).model_dump_json(), strict=True
    )
    if isinstance(value, Advanced):
        return Prepared(
            tool=value.tool,
            arguments=Raw(value.arguments),
            options=Options(
                command="raw",
                workspace=context.workspace,
                rpc=context.rpc,
                write=value.write,
            ),
        )
    options = value.options(task.name, context)
    if isinstance(value, Schema):
        return Prepared(tool=value.tool, arguments=Raw({}), options=options)
    if isinstance(value, DiagnosticInput):
        if value.path.startswith("/") or ".." in value.path.split("/"):
            raise InputError("Diagnostics needs a workspace-relative file or directory.")
        return Prepared(
            tool="check_diagnostics",
            arguments=Diagnostics(relativePath=value.path),
            options=options,
        )
    if isinstance(value, Find) and value.directory is not None and value.package is not None:
        raise InputError("Choose a declaration directory or package, not both.")
    return Prepared(tool="query_symbols", arguments=query(options), options=options)
