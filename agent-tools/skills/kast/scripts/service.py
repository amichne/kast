"""MCP owns session cancellation. The task adapter owns one cancellable RPC child."""

import argparse
import signal
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import anyio
from access import InputError, Options, Raw
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server
from mcp.types import CallToolResult, ListToolsResult, TextContent, Tool, ToolAnnotations
from pydantic import ValidationError
from tasks import TASKS, Context, prepare_task
from transport import Envelope, Reason, Rejected, Stage, execute


def catalog() -> ListToolsResult:
    return ListToolsResult(
        tools=[
            Tool(
                name=task.name,
                description=task.description,
                inputSchema=task.model.model_json_schema(),
                annotations=ToolAnnotations(
                    readOnlyHint=task.read_only,
                    destructiveHint=not task.read_only,
                    idempotentHint=task.read_only,
                    openWorldHint=False,
                ),
            )
            for task in TASKS
        ]
    )


def rejected(reason: Reason, detail: str, stage: Stage = Stage.INPUT) -> CallToolResult:
    value = Rejected(stage=stage, reason=reason, detail=detail)
    return CallToolResult(
        content=[TextContent(type="text", text=value.model_dump_json())],
        structuredContent=value.model_dump(mode="json"),
        isError=True,
    )


async def call(name: str, arguments: object, context: Context) -> CallToolResult:
    task = next((task for task in TASKS if task.name == name), None)
    if task is None:
        return rejected(
            Reason.UNKNOWN_TOOL, "Use the task catalog. Raw tool names are not exposed."
        )
    try:
        prepared = prepare_task(task, arguments, context)
    except (InputError, ValidationError) as error:
        return rejected(Reason.INVALID_INPUT, str(error)[:1600])
    response = await execute(prepared)
    if isinstance(response, Rejected):
        return CallToolResult(
            content=[TextContent(type="text", text=response.model_dump_json())],
            structuredContent=response.model_dump(mode="json"),
            isError=True,
        )
    # Keep the exact canonical JSON text and its structured value. Do not summarize evidence.
    try:
        text = response.stdout.decode("utf-8")
        structured = Raw.model_validate_json(response.stdout).root
        failed = response.returncode != 0
        if name != "schema":
            failed = failed or Envelope.model_validate_json(response.stdout).type != "complete"
    except (UnicodeError, ValidationError):
        return rejected(
            Reason.INVALID_REPLY,
            "The child did not return an admitted RPC reply. Effects can be uncertain.",
            Stage.INVOCATION,
        )
    return CallToolResult(
        content=[TextContent(type="text", text=text)], structuredContent=structured, isError=failed
    )


@asynccontextmanager
async def lifespan(_server: Server[None, None]) -> AsyncIterator[None]:
    yield None


async def stdio(context: Context) -> None:
    server: Server[None, None] = Server(
        "kast-tasks",
        lifespan=lifespan,
        version="1.0.0",
        instructions="Use find before relationships. Keep issued refs and result qualifications. Read the bundled Kast skill.",
    )

    async def list_tools() -> list[Tool]:
        return catalog().tools

    async def call_tool(name: str, arguments: dict[str, object]) -> CallToolResult:
        return await call(name, arguments, context)

    server.list_tools()(list_tools)
    server.call_tool()(call_tool)

    async with stdio_server() as (reader, writer):
        await server.run(reader, writer, server.create_initialization_options())


def main() -> int:
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rpc", type=Path, default=Options.model_fields["rpc"].default)
    parser.add_argument("--workspace", type=Path, default=Path.cwd())
    parser.add_argument("command", choices=("stdio", "catalog", "call"), default="stdio", nargs="?")
    parser.add_argument("name", nargs="?")
    args = parser.parse_args()
    context = Context(workspace=Path(args.workspace).resolve(), rpc=Path(args.rpc))
    if args.command == "catalog":
        print(catalog().model_dump_json(exclude_none=True))
        return 0
    if args.command == "call":
        raw = sys.stdin.buffer.read(1_048_577)
        if len(raw) > 1_048_576:
            result = rejected(Reason.INVALID_INPUT, "Task arguments exceed one MiB.")
        else:
            try:
                arguments = Raw.model_validate_json(raw).root
            except ValidationError:
                result = rejected(Reason.INVALID_INPUT, "Task arguments must be a JSON object.")
            else:
                result = anyio.run(call, str(args.name or ""), arguments, context)
        print(result.model_dump_json(exclude_none=True))
        return 0
    anyio.run(stdio, context)
    return 0
