"""Real stdio and child-process evidence, separate from compiler semantics."""

import json
import os
import sys
from pathlib import Path

import anyio
import pytest
from access import Raw, prepare
from anyio.abc import SocketAttribute, SocketStream
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.types import TextContent
from pydantic import ValidationError
from service import call, catalog
from tasks import TASKS, Context, prepare_task
from transport import Stage, invoke

from test_transport import catalog as rpc_catalog
from test_transport import fixture_process

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "agent-tools/skills/kast/scripts/server.py"
EXPECTED_NAMES = {
    "find",
    "callers",
    "callees",
    "references",
    "implementations",
    "inheritors",
    "overrides",
    "type_uses",
    "trace",
    "walk",
    "source",
    "result",
    "resume",
    "diagnostics",
    "schema",
    "raw",
}
EXACT = "exact:v5:AAAAAAAAAAAAAAAAAAAAAA"


def test_catalog_has_only_task_tools_and_one_explicit_write_route() -> None:
    tools = catalog().tools
    assert {tool.name for tool in tools} == EXPECTED_NAMES
    assert len(tools) == len(EXPECTED_NAMES)
    assert {
        tool.name for tool in tools if tool.annotations and not tool.annotations.readOnlyHint
    } == {"raw"}
    for tool in tools:
        assert tool.inputSchema["additionalProperties"] is False
        properties = Raw.model_validate(tool.inputSchema["properties"]).root
        assert isinstance(properties, dict)
        assert not {"workspace", "rpc", "save", "timeout"}.intersection(properties)


@pytest.mark.parametrize(
    "name,arguments",
    [
        ("query_symbols", {"request": {}}),
        ("find", {"name": "Thing", "workspace": "/tmp"}),
        ("find", {"name": "Thing", "page_size": "2"}),
        ("find", {"name": "Thing", "page_size": True}),
        ("find", {"name": "Thing", "source_sets": []}),
        ("find", {"name": "Thing", "directory": "cli", "package": "io.example"}),
        ("source", {"ref": EXACT, "region": "NONEXISTENT"}),
        ("walk", {"ref": EXACT, "relation": "CALLERS"}),
        ("diagnostics", {"path": "../outside"}),
        ("raw", {"tool": "replace_body", "arguments": {}, "write": "true"}),
    ],
)
def test_bad_task_inputs_reject_before_a_process(
    tmp_path: Path, name: str, arguments: object
) -> None:
    result = anyio.run(call, name, arguments, Context(tmp_path, tmp_path / "absent"))
    assert result.isError
    assert result.structuredContent is not None
    assert result.structuredContent["stage"] == "INPUT"
    assert result.structuredContent["reason"] in {"INVALID_INPUT", "UNKNOWN_TOOL"}
    assert list(tmp_path.iterdir()) == []


def test_source_defaults_and_relation_scope_use_existing_requests(tmp_path: Path) -> None:
    context = Context(tmp_path, tmp_path / "rpc")
    source_task = next(task for task in TASKS if task.name == "source")
    source = prepare_task(source_task, {"ref": EXACT}, context)
    request = source.arguments.model_dump(mode="json", exclude_none=True)["request"]
    assert request["type"] == "READ_SOURCE"
    assert request["region"] == "ANCHOR"
    callers = prepare_task(
        next(task for task in TASKS if task.name == "callers"), {"ref": EXACT}, context
    )
    relation = callers.arguments.model_dump(mode="json", exclude_none=True)["request"]["steps"][0]
    assert relation["expansionScope"] == {"type": "WORKSPACE"}


def test_raw_write_rejects_before_semantic_call(tmp_path: Path) -> None:
    rpc = fixture_process(tmp_path, b"must not execute")
    (tmp_path / "catalog.json").write_bytes(rpc_catalog("WRITE"))
    arguments: dict[str, object] = {"tool": "query_symbols", "arguments": {"request": {}}}
    result = anyio.run(call, "raw", arguments, Context(tmp_path, rpc))
    assert result.isError
    assert result.structuredContent is not None
    assert result.structuredContent["reason"] == "WRITE_NOT_SELECTED"
    assert not (tmp_path / "input.json").exists()
    assert len((tmp_path / "calls.jsonl").read_text().splitlines()) == 1


@pytest.mark.parametrize(
    "outcome,error", [("complete", False), ("qualified", True), ("rejected_document", True)]
)
def test_real_mcp_session_preserves_canonical_evidence(
    tmp_path: Path, outcome: str, error: bool
) -> None:
    original = (
        json.dumps({"type": outcome, "document": {"reason": "fixture", "ref": EXACT}}, indent=2)
        + "\n"
    )
    rpc = fixture_process(tmp_path, original.encode())

    async def session() -> None:
        params = StdioServerParameters(
            command=sys.executable,
            args=[str(SERVER), "--rpc", str(rpc), "--workspace", str(tmp_path)],
        )
        async with stdio_client(params) as streams, ClientSession(*streams) as client:
            await client.initialize()
            inventory = await client.list_tools()
            assert {tool.name for tool in inventory.tools} == EXPECTED_NAMES
            result = await client.call_tool(
                "find", {"name": "Thing", "directory": "cli", "source_sets": ["main"]}
            )
            assert result.isError is error
            assert isinstance(result.content[0], TextContent)
            assert result.content[0].text == original
            assert result.structuredContent == json.loads(original)

    anyio.run(session)
    assert len((tmp_path / "calls.jsonl").read_text().splitlines()) == 2
    request = json.loads((tmp_path / "input.json").read_text())["request"]
    assert request["source"]["scope"]["sourceSetNames"] == ["main"]


def test_cancellation_reaps_the_real_rpc_child(tmp_path: Path) -> None:
    async def exercise() -> None:
        ready = anyio.Event()
        child_pids: list[int] = []

        async def accept(stream: SocketStream) -> None:
            async with stream:
                child_pids.append(int(await stream.receive(64)))
                ready.set()
                try:
                    await stream.receive(1)
                except anyio.EndOfStream:
                    pass

        listener = await anyio.create_tcp_listener(local_host="127.0.0.1")
        port = listener.extra(SocketAttribute.local_port)
        rpc = tmp_path / "waiting-rpc"
        rpc.write_text(
            f"#!{sys.executable}\nimport os,socket\n"
            f"s=socket.create_connection(('127.0.0.1',{port}))\n"
            "s.sendall(str(os.getpid()).encode())\ns.recv(1)\n"
        )
        rpc.chmod(0o700)
        prepared = prepare(["find", "Thing", "--rpc", str(rpc), "--workspace", str(tmp_path)])
        async with anyio.create_task_group() as listeners:
            listeners.start_soon(listener.serve, accept)
            async with anyio.create_task_group() as children:
                children.start_soon(invoke, prepared, ("catalog",), b"", Stage.CATALOG)
                with anyio.fail_after(10):
                    await ready.wait()
                children.cancel_scope.cancel()
            listeners.cancel_scope.cancel()
        await listener.aclose()
        assert len(child_pids) == 1
        with pytest.raises(ProcessLookupError):
            os.kill(child_pids[0], 0)

    anyio.run(exercise)


def test_copilot_uses_shared_catalog_and_keeps_task_results(tmp_path: Path) -> None:
    import subprocess

    path = tmp_path / "catalog.json"
    path.write_text(catalog().model_dump_json(exclude_none=True))
    result = subprocess.run(
        [
            "node",
            "--experimental-vm-modules",
            "--test",
            str(Path(__file__).with_name("test_copilot.mjs")),
        ],
        env={**os.environ, "KAST_TASK_TEST_CATALOG": str(path)},
        capture_output=True,
        text=True,
        timeout=15,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_malformed_child_reply_keeps_invocation_stage_without_retry(tmp_path: Path) -> None:
    rpc = fixture_process(tmp_path, b"not an RPC reply")
    result = anyio.run(call, "find", {"name": "Thing"}, Context(tmp_path, rpc))
    assert result.isError
    assert result.structuredContent is not None
    assert result.structuredContent["reason"] == "INVALID_REPLY"
    assert result.structuredContent["stage"] == "INVOCATION"
    assert len((tmp_path / "calls.jsonl").read_text().splitlines()) == 2


def test_native_bridge_keeps_large_canonical_text_once(tmp_path: Path) -> None:
    import subprocess

    original = json.dumps({"type": "complete", "document": {"text": '"' * 600_000, "ref": EXACT}})
    rpc = fixture_process(tmp_path, original.encode())
    command = [sys.executable, str(SERVER), "--rpc", str(rpc), "--workspace", str(tmp_path)]
    inventory = subprocess.run([*command, "catalog"], capture_output=True, check=True, timeout=10)
    assert {tool["name"] for tool in json.loads(inventory.stdout)["tools"]} == EXPECTED_NAMES
    reply = subprocess.run(
        [*command, "call", "find"],
        input=b'{"name":"Thing"}',
        capture_output=True,
        check=True,
        timeout=10,
    )
    bridge = json.loads(reply.stdout)
    assert bridge["content"] == [{"type": "text", "text": original}]
    assert bridge["isError"] is False
    assert "structuredContent" not in bridge
    assert len(reply.stdout) <= 6 * 4_194_304 + 1024


def test_static_host_deadlines_cover_every_admitted_catalog() -> None:
    from transport import HOST_TIMEOUT_SECONDS, Policy

    config = json.loads((ROOT / "agent-tools/.mcp.json").read_text())["mcpServers"]["kast"]
    assert HOST_TIMEOUT_SECONDS == 3780
    assert config["tool_timeout_sec"] == HOST_TIMEOUT_SECONDS
    assert config["timeout"] == HOST_TIMEOUT_SECONDS * 1000
    assert f"timeout: {HOST_TIMEOUT_SECONDS}," in (ROOT / "pi/mcp-extension.ts").read_text()
    Policy(callTimeoutMillis=3_600_000, maxResponseBytes=2_147_483_646)
    with pytest.raises(ValidationError):
        Policy(callTimeoutMillis=3_600_001, maxResponseBytes=2_147_483_646)
