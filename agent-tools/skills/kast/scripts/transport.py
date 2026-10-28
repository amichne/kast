"""Installed RPC adapter. No query retry, implicit selection, or result rewriting."""

from __future__ import annotations

import sys
from enum import StrEnum
from typing import Literal

import anyio
from access import InputError, Model, Prepared, prepare
from jsonschema import Draft202012Validator, validate
from jsonschema.exceptions import SchemaError
from jsonschema.exceptions import ValidationError as SchemaValidationError
from pydantic import BaseModel, ConfigDict, JsonValue, RootModel, ValidationError


class Stage(StrEnum):
    INPUT = "INPUT"
    CATALOG = "CATALOG"
    INVOCATION = "INVOCATION"
    SAVE = "SAVE"


class Reason(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    UNAVAILABLE = "UNAVAILABLE"
    CONTRACT_MISMATCH = "CONTRACT_MISMATCH"
    UNKNOWN_TOOL = "UNKNOWN_TOOL"
    WRITE_NOT_SELECTED = "WRITE_NOT_SELECTED"
    SCHEMA_REJECTED = "SCHEMA_REJECTED"
    PROCESS_FAILED = "PROCESS_FAILED"
    TIMEOUT = "TIMEOUT"
    CANCELLED = "CANCELLED"
    INVALID_REPLY = "INVALID_REPLY"


class Rejected(Model):
    type: Literal["ACCESS_REJECTED"] = "ACCESS_REJECTED"
    stage: Stage
    reason: Reason
    detail: str


class Tool(Model):
    name: str
    description: str
    effect: Literal["READ", "WRITE"]
    inputSchema: dict[str, JsonValue]


class Catalog(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)
    schemaVersion: int
    tools: tuple[Tool, ...]


class CatalogReply(Model):
    type: Literal["catalog"]
    catalog: Catalog


class Envelope(BaseModel):
    """Inspect only the transport tag. The original document bytes stay authoritative."""

    model_config = ConfigDict(extra="allow", frozen=True)
    type: Literal["complete", "qualified", "rejected", "rejected_document"]
    document: dict[str, JsonValue] | None = None


class Reply(Model):
    stdout: bytes
    stderr: bytes
    returncode: int


async def invoke(
    prepared: Prepared, command: tuple[str, ...], body: bytes, stage: Stage
) -> Reply | Rejected:
    options = prepared.options
    try:
        with anyio.fail_after(options.timeout):
            child = await anyio.run_process(
                [str(options.rpc), "--catalog=COMPLETE", *command],
                cwd=options.workspace,
                input=body,
                check=False,
            )
        return Reply(stdout=child.stdout, stderr=child.stderr, returncode=child.returncode)
    except TimeoutError:
        return Rejected(
            stage=stage,
            reason=Reason.TIMEOUT,
            detail="The child stopped. Submission or effects can be uncertain. Do not retry automatically.",
        )
    except OSError as error:
        return Rejected(stage=stage, reason=Reason.UNAVAILABLE, detail=str(error))


def admit(prepared: Prepared, raw_catalog: bytes) -> Tool | Rejected:
    try:
        catalog = CatalogReply.model_validate_json(raw_catalog).catalog
    except ValidationError:
        return Rejected(
            stage=Stage.CATALOG,
            reason=Reason.INVALID_REPLY,
            detail="The installed command did not return a tool catalog.",
        )
    if catalog.schemaVersion not in (17, 18):
        return Rejected(
            stage=Stage.CATALOG,
            reason=Reason.CONTRACT_MISMATCH,
            detail=f"Task access needs contract 17 or 18. Installed contract is {catalog.schemaVersion}.",
        )
    matches = [tool for tool in catalog.tools if tool.name == prepared.tool]
    if len(matches) != 1:
        return Rejected(
            stage=Stage.CATALOG,
            reason=Reason.UNKNOWN_TOOL,
            detail="The catalog must contain exactly one requested tool.",
        )
    tool = matches[0]
    if prepared.options.command == "schema":
        return tool
    if tool.effect == "WRITE" and not prepared.options.write:
        return Rejected(
            stage=Stage.INPUT,
            reason=Reason.WRITE_NOT_SELECTED,
            detail="Use raw --write only for an explicitly requested source change.",
        )
    try:
        validate(
            prepared.arguments.model_dump(mode="json", exclude_none=True),
            tool.inputSchema,
            cls=Draft202012Validator,
        )
    except SchemaValidationError as error:
        location = "/".join(str(part) for part in error.absolute_path)
        return Rejected(
            stage=Stage.INPUT,
            reason=Reason.SCHEMA_REJECTED,
            detail=f"{location}: {error.message}"[:1600],
        )
    except SchemaError:
        return Rejected(
            stage=Stage.CATALOG,
            reason=Reason.INVALID_REPLY,
            detail="The installed tool schema is invalid.",
        )
    return tool


async def execute(prepared: Prepared) -> Reply | Rejected:
    if prepared.options.catalog_file:
        try:
            raw_catalog = prepared.options.catalog_file.read_bytes()
        except OSError as error:
            return Rejected(stage=Stage.CATALOG, reason=Reason.UNAVAILABLE, detail=str(error))
    else:
        result = await invoke(prepared, ("catalog",), b"", Stage.CATALOG)
        if isinstance(result, Rejected):
            return result
        if result.returncode:
            return Rejected(
                stage=Stage.CATALOG,
                reason=Reason.PROCESS_FAILED,
                detail=f"Catalog command exited {result.returncode}.",
            )
        raw_catalog = result.stdout
    admitted = admit(prepared, raw_catalog)
    if isinstance(admitted, Rejected):
        return admitted
    if prepared.options.command == "schema":
        selected: JsonValue = admitted.inputSchema
        if prepared.options.definition:
            definitions = admitted.inputSchema.get("$defs")
            if not isinstance(definitions, dict) or prepared.options.definition not in definitions:
                return Rejected(
                    stage=Stage.INPUT,
                    reason=Reason.INVALID_INPUT,
                    detail="No such definition in the installed tool schema.",
                )
            selected = definitions[prepared.options.definition]
        return Reply(
            stdout=RootModel[JsonValue](selected).model_dump_json().encode() + b"\n",
            stderr=b"",
            returncode=0,
        )
    body = prepared.arguments.model_dump_json(exclude_none=True).encode()
    if prepared.options.emit_request:
        return Reply(stdout=body + b"\n", stderr=b"", returncode=0)
    return await invoke(prepared, ("call", admitted.name), body, Stage.INVOCATION)


def run(arguments: list[str]) -> int:
    try:
        prepared = prepare(arguments)
    except (InputError, OSError) as error:
        print(
            Rejected(
                stage=Stage.INPUT, reason=Reason.INVALID_INPUT, detail=str(error)
            ).model_dump_json()
        )
        return 2
    # Refuse an overwrite before a semantic or write operation can start.
    output = None
    try:
        if prepared.options.save:
            output = prepared.options.save.open("xb")
    except OSError as error:
        print(
            Rejected(
                stage=Stage.SAVE, reason=Reason.UNAVAILABLE, detail=str(error)
            ).model_dump_json()
        )
        return 2
    try:
        result = anyio.run(execute, prepared)
        if isinstance(result, Rejected):
            raw = result.model_dump_json().encode() + b"\n"
            status = 2
        else:
            raw = result.stdout
            sys.stderr.buffer.write(result.stderr)
            if prepared.options.emit_request or prepared.options.command == "schema":
                status = 0
            else:
                try:
                    envelope = Envelope.model_validate_json(raw)
                    status = 0 if result.returncode == 0 and envelope.type == "complete" else 1
                except ValidationError:
                    # Preserve malformed evidence without reporting success.
                    sys.stderr.write(
                        Rejected(
                            stage=Stage.INVOCATION,
                            reason=Reason.INVALID_REPLY,
                            detail="The child reply is not an admitted RPC envelope.",
                        ).model_dump_json()
                        + "\n"
                    )
                    status = 2
        if output:
            output.write(raw)
        sys.stdout.buffer.write(raw)
        return status
    finally:
        if output:
            output.close()
