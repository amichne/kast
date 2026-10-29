import json
import sys
from pathlib import Path

import anyio
import pytest
from access import prepare
from pydantic import BaseModel
from transport import Catalog, CatalogReply, Envelope, Reason, Rejected, Reply, Tool, admit, execute


class Document(BaseModel):
    status: str
    reason: str


def catalog(effect: str = "READ") -> bytes:
    tool = Tool.model_validate(
        {
            "name": "query_symbols",
            "description": "Fixture transport only",
            "effect": effect,
            "inputSchema": {
                "type": "object",
                "required": ["request"],
                "properties": {"request": {"type": "object"}},
                "additionalProperties": False,
            },
        }
    )
    return (
        CatalogReply(
            type="catalog",
            catalog=Catalog(
                schemaVersion=17,
                tools=(tool,),
                callTimeoutMillis=1_800_000,
                maxResponseBytes=4_194_304,
            ),
        )
        .model_dump_json()
        .encode()
    )


def test_catalog_rejects_write_without_explicit_selection(tmp_path: Path) -> None:
    raw = tmp_path / "request.json"
    raw.write_text(prepare(["find", "Thing"]).arguments.model_dump_json(exclude_none=True))
    prepared = prepare(["raw", "--file", str(raw)])
    outcome = admit(prepared, catalog("WRITE"))
    assert isinstance(outcome, Rejected)
    assert outcome.reason == Reason.WRITE_NOT_SELECTED


def test_unknown_contract_and_bad_request_are_local_failures(tmp_path: Path) -> None:
    prepared = prepare(["find", "Thing"])
    wrong = CatalogReply.model_validate_json(catalog()).model_copy(
        update={
            "catalog": Catalog(
                schemaVersion=999, tools=(), callTimeoutMillis=1_800_000, maxResponseBytes=4_194_304
            )
        }
    )
    outcome = admit(prepared, wrong.model_dump_json().encode())
    assert isinstance(outcome, Rejected)
    assert outcome.reason == Reason.CONTRACT_MISMATCH
    raw = tmp_path / "bad.json"
    raw.write_text("{}")
    outcome = admit(prepare(["raw", "--file", str(raw)]), catalog())
    assert isinstance(outcome, Rejected)
    assert outcome.reason == Reason.SCHEMA_REJECTED


def fixture_process(tmp_path: Path, response: bytes) -> Path:
    (tmp_path / "catalog.json").write_bytes(catalog())
    (tmp_path / "response.json").write_bytes(response)
    rpc = tmp_path / "RPC with spaces; literal"
    rpc.write_text(
        f"#!{sys.executable}\n"
        + """import json, sys
from pathlib import Path
root=Path(__file__).parent
with (root/'calls.jsonl').open('a') as stream:
    stream.write(json.dumps(sys.argv[1:])+'\\n')
if sys.argv[1:]==['--catalog=COMPLETE','catalog']:
    sys.stdout.buffer.write((root/'catalog.json').read_bytes())
elif sys.argv[1:]==['--catalog=COMPLETE','call','query_symbols']:
    (root/'input.json').write_bytes(sys.stdin.buffer.read())
    sys.stdout.buffer.write((root/'response.json').read_bytes())
else:
    sys.exit(61)
"""
    )
    rpc.chmod(0o700)
    return rpc


def test_real_child_binding_preserves_rejection_bytes_and_does_not_retry(tmp_path: Path) -> None:
    document = Document(status="rejected", reason="UNLINKED_BUILD")
    original = (
        Envelope(type="rejected_document", document=document.model_dump(mode="json"))
        .model_dump_json(indent=2)
        .encode()
        + b"\n"
    )
    rpc = fixture_process(tmp_path, original)
    prepared = prepare(["find", "Thing", "--rpc", str(rpc), "--workspace", str(tmp_path)])
    result = anyio.run(execute, prepared)
    assert isinstance(result, Reply)
    assert result.stdout == original
    assert (tmp_path / "input.json").read_bytes() == prepared.arguments.model_dump_json(
        exclude_none=True
    ).encode()
    assert [json.loads(line) for line in (tmp_path / "calls.jsonl").read_text().splitlines()] == [
        ["--catalog=COMPLETE", "catalog"],
        ["--catalog=COMPLETE", "call", "query_symbols"],
    ]


def test_emit_request_never_calls_semantic_child(tmp_path: Path) -> None:
    rpc = fixture_process(tmp_path, b"SHOULD NOT BE READ")
    result = anyio.run(execute, prepare(["find", "Thing", "--rpc", str(rpc), "--emit-request"]))
    assert isinstance(result, Reply)
    assert not (tmp_path / "input.json").exists()
    assert len((tmp_path / "calls.jsonl").read_text().splitlines()) == 1


@pytest.mark.parametrize("arguments,expected", [([], 1800), (["--timeout", "42"], 42)])
def test_installed_deadline_reaches_the_invocation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, arguments: list[str], expected: float
) -> None:
    import transport

    rpc = fixture_process(tmp_path, b'{"type":"complete","document":{}}')
    allowances: list[float | None] = []
    original = anyio.fail_after

    def record(delay: float | None, shield: bool = False):
        allowances.append(delay)
        return original(delay, shield=shield)

    monkeypatch.setattr(transport.anyio, "fail_after", record)
    result = anyio.run(execute, prepare(["find", "Thing", "--rpc", str(rpc), *arguments]))
    assert isinstance(result, Reply)
    assert allowances == [120, expected]


@pytest.mark.parametrize("value", [None, 0, -1, True, "1800000", 3_600_001])
def test_invalid_installed_deadline_rejects_before_call(tmp_path: Path, value: object) -> None:
    rpc = fixture_process(tmp_path, b"must not execute")
    raw = json.loads((tmp_path / "catalog.json").read_bytes())
    raw["catalog"]["callTimeoutMillis"] = value
    (tmp_path / "catalog.json").write_text(json.dumps(raw))
    result = anyio.run(execute, prepare(["find", "Thing", "--rpc", str(rpc)]))
    assert isinstance(result, Rejected)
    assert result.reason == Reason.INVALID_REPLY
    assert not (tmp_path / "input.json").exists()


def test_offline_catalog_cannot_authorize_execution(tmp_path: Path) -> None:
    import pytest
    from access import InputError

    with pytest.raises(InputError):
        prepare(["find", "Thing", "--catalog-file", str(tmp_path / "catalog.json")])


def test_all_task_requests_match_repository_contract() -> None:
    from jsonschema import Draft202012Validator, validate

    root = Path(__file__).resolve().parents[2]
    schema = json.loads((root / "agent-tools/references/query-schema.json").read_text())
    exact = "exact:v5:AAAAAAAAAAAAAAAAAAAAAA"
    commands = [
        ["find", "Thing", "--kind", "interface", "--source-set", "main"],
        ["callers", "--ref", exact],
        ["references", "--ref", exact, "--directory", "cli", "--source-set", "main"],
        ["trace", "--ref", exact],
        ["walk", "callers", "--ref", exact, "--depth", "3"],
        ["source", "--ref", exact, "--region", "CALLABLE_BODY", "--context", "10"],
        [
            "result",
            "--ref",
            "result:v1:11111111-1111-1111-1111-111111111111",
            "--output",
            "occurrences",
        ],
        ["resume", "--token", "query-output:v1:11111111-1111-1111-1111-111111111111"],
    ]
    for arguments in commands:
        request = prepare(arguments).arguments.model_dump(mode="json", exclude_none=True)
        validate(request, schema, cls=Draft202012Validator)


@pytest.mark.parametrize("field", ["callTimeoutMillis", "maxResponseBytes"])
def test_missing_installed_policy_rejects(tmp_path: Path, field: str) -> None:
    rpc = fixture_process(tmp_path, b"must not execute")
    raw = json.loads((tmp_path / "catalog.json").read_bytes())
    del raw["catalog"][field]
    (tmp_path / "catalog.json").write_text(json.dumps(raw))
    result = anyio.run(execute, prepare(["find", "Thing", "--rpc", str(rpc)]))
    assert isinstance(result, Rejected)
    assert result.reason == Reason.INVALID_REPLY
    assert not (tmp_path / "input.json").exists()


@pytest.mark.parametrize("value", [None, 0, -1, True, "4194304", 2_147_483_648])
def test_invalid_response_allowance_rejects(tmp_path: Path, value: object) -> None:
    rpc = fixture_process(tmp_path, b"must not execute")
    raw = json.loads((tmp_path / "catalog.json").read_bytes())
    raw["catalog"]["maxResponseBytes"] = value
    (tmp_path / "catalog.json").write_text(json.dumps(raw))
    result = anyio.run(execute, prepare(["find", "Thing", "--rpc", str(rpc)]))
    assert isinstance(result, Rejected)
    assert result.reason == Reason.INVALID_REPLY
    assert not (tmp_path / "input.json").exists()
