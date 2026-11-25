"""Run explicitly with uv run --locked pytest -s pi_skill_cli_test.py.

Pi needs a signed-in provider and an installed Kast. Pytest prepares the live
fixture. Set PI_TRIAL_WORKSPACE to reuse a prepared static-callback-fixture.
Set PI_TRIAL_SKILL to compare a different directory with the same prompts.
Use --basetemp with a new directory to retain transcripts and timing receipts.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from pydantic import TypeAdapter

from agent_efficiency_timing import Usage

DOCUMENT = TypeAdapter(dict[str, Any])
HERE = Path(__file__).resolve().parent
SKILL = HERE.parents[1] / "agent-tools/skills/kast"
MODEL = "openai-codex/gpt-6.1-sol"
CASES = {
    "callback": (
        "Does Kast prove the complete set of direct callees for forwardOnce under forwarding "
        "in main? Return COMPLETE if it does, or the specific Kast cause code if it cannot."
    ),
    "select": (
        "Find interface Widget exactly under app/src in the main source set. Return only a "
        "JSON argv array including kast. Use the declaration family accepted by the installed "
        "CLI and request name, signature, and location."
    ),
    "kind": (
        "Find declarations named Widget under app/src in main. Restrict the result to both "
        "property and type alias declarations. Consult the installed --kind flag contract. "
        "Return only a JSON argv array including kast."
    ),
    "walk": (
        "Return only the JSON request document for two hops of incoming callers with depth "
        "records and a 20-result presentation. Use the placeholder EXACT_REF for an exact "
        "symbol ref. Consult the installed contract. This is a draft, do not send it."
    ),
}
LIVE_FILE = "invocation/src/main/kotlin/fixture/staticcallbacks/invocation/Invocation.kt"
LIVE_NAMES = {"present": "invokeCallback", "absent": "kastBenchmarkNoSuchFunction"}
for case_id, name in LIVE_NAMES.items():
    CASES[case_id] = (
        f"Which file declares the exact function {name} under invocation in main? "
        "Return its workspace-relative path, ABSENT for proved absence, or UNKNOWN."
    )


def plan(args: list[str], workspace: Path, stdin: str | None = None) -> dict[str, Any]:
    result = subprocess.run(
        ["kast", "query", "plan", *args],
        cwd=workspace,
        text=True,
        input=stdin,
        capture_output=True,
        timeout=10,
        env={**os.environ, "KAST_OUTPUT_TOON": "0"},
    )
    assert result.returncode == 0, result.stderr or result.stdout
    planned = DOCUMENT.validate_json(result.stdout)
    assert planned["type"] == "planned"
    return planned["request"]


def query_documents(calls: list[dict[str, Any]]) -> Iterator[dict[str, Any]]:
    for call in calls:
        argv = call.get("argv", [])
        if argv[:1] == ["query"] and "--help" not in argv and "-h" not in argv:
            if not any(arg.startswith("--jq") for arg in argv) and call.get(
                "stdout", ""
            ).startswith("{"):
                yield DOCUMENT.validate_json(call["stdout"])


def grade(case: str, answer: str, calls: list[dict[str, Any]], workspace: Path) -> None:
    if case == "callback":
        seeds: set[str] = set()
        for result in query_documents(calls):
            doc = result.get("document", {})
            if result["type"] == "complete":
                source = doc["question"]["from"]
                if (
                    source.get("match", {}).get("text") == "forwardOnce"
                    and source["scope"]["directory"]["path"] == "forwarding"
                    and source["scope"]["sourceSets"] == ["main"]
                    and doc["coverage"]["exhaustive"] is True
                ):
                    seeds.update(row["ref"] for row in doc["items"] if row["name"] == "forwardOnce")
            elif (
                result["type"] == "rejected_document"
                and doc.get("rejection", {}).get("type") == "COMPLETION_UNPROVEN"
            ):
                detail = doc["rejection"]["detail"]
                question = detail["evidence"]["question"]
                (seed,) = question["from"]["values"]
                assert seed["token"] in seeds
                assert question["steps"] == [{"type": "related", "relation": "callees"}]
                cause = detail["cause"]
                assert cause["type"] == "CALLBACK_GRAPH_UNPROVEN"
                obligations = cause["graphFailure"]["cause"]["obligations"]
                # A cause can include a more specific obligation from the same rejection.
                code_list = r"[A-Z_]+(?:[\s:,—-]+[A-Z_]+)*"
                assert re.fullmatch(rf"{code_list}(?:\s+\({code_list}\))?", answer)
                codes = re.findall(r"[A-Z_]+", answer)
                assert codes[0] == cause["type"] and set(codes[1:]) <= set(obligations)
                return
        pytest.fail("No issued function reference and rejected callee query support this answer")
    if case in LIVE_NAMES:
        # An empty result is evidence only after complete scoped evaluation.
        for result in query_documents(calls):
            if result.get("type") != "complete":
                continue
            doc = result["document"]
            source = doc["question"]["from"]
            if source.get("match", {}).get("text") != LIVE_NAMES[case]:
                continue
            assert source["match"]["matching"] == "exact-name"
            assert source["scope"] == {
                "directory": {"path": "invocation", "containment": "descendants"},
                "sourceSets": ["main"],
                "packageName": None,
            }
            assert doc["question"]["steps"] == []
            assert doc["question"]["output"]["type"] == "symbols"
            assert source["declarationKinds"] == ["function"]
            assert doc["status"] == "complete" and doc["coverage"]["exhaustive"] is True
            summary = doc["original_evidence_summary"]
            assert summary["failure_count"] == summary["omission_count"] == 0
            assert Path(doc["live"]["root"]).resolve() == workspace
            expected = [workspace / LIVE_FILE] if case == "present" else []
            assert doc["evidence_window"]["type"] == "FINAL"
            assert doc["invocation"]["accumulated_row_count"] == len(expected)
            assert all(row["name"] == LIVE_NAMES[case] for row in doc["items"])
            files = [workspace / row["location"]["file"] for row in doc["items"]]
            assert files == expected
            assert answer == (LIVE_FILE if expected else "ABSENT")
            return
        pytest.fail("No complete scoped live result supports the answer")
    if case in {"walk", "kind"}:
        assert any(c.get("argv", [""])[0] == "describe" and c["status"] == 0 for c in calls)
    if case == "walk":
        request = plan(["--request", "-"], workspace, answer)
        assert request["from"] == {"type": "references", "values": [{"token": "EXACT_REF"}]}
        (step,) = request["steps"]
        assert (step["type"], step["relation"]) == ("walk", "callers")
        assert step["extent"] == {"type": "THROUGH_DEPTH", "maximum_depth": 2}
        assert request["output"]["type"] == "traversal_records"
        assert request["execution_budget"]["max_results"] == 20
        return
    draft = TypeAdapter(list[str]).validate_json(answer)
    assert draft[:2] == ["kast", "query"]
    allowed = {
        "--name",
        "--name-match",
        "--in",
        "--kind",
        "--source-set",
        "--fields",
        "--output",
        "--json",
    }
    assert all(arg.split("=", 1)[0] in allowed for arg in draft[2:] if arg.startswith("-"))
    request = plan(draft[2:], workspace)
    source = request["from"]
    assert source["match"] == {"type": "name", "text": "Widget", "matching": "exact-name"}
    assert source["scope"] == {
        "sourceSets": ["main"],
        "directory": {"path": "app/src", "containment": "descendants"},
        "packageName": None,
    }
    kinds = {"class"} if case == "select" else {"property", "type-alias"}
    assert set(source["declarationKinds"]) == kinds
    assert request["steps"] == [] and request["output"]["type"] == "symbols"
    if case == "select":
        assert set(request["output"]["fields"]) == {"name", "signature", "location"}


@pytest.fixture(scope="session")
def live_workspace(tmp_path_factory: pytest.TempPathFactory) -> Iterator[Path]:
    configured = os.environ.get("PI_TRIAL_WORKSPACE")
    if configured:
        yield Path(configured).resolve(strict=True)
        return
    workspace = tmp_path_factory.mktemp("kast-fixture").resolve()
    shutil.copytree(HERE.parents[1] / "fixtures/static-callback-fixture", workspace, dirs_exist_ok=True)
    root = HERE.parents[1]
    shutil.copytree(root / "gradle/wrapper", workspace / "gradle/wrapper")
    shutil.copy2(root / "gradlew", workspace / "gradlew")
    subprocess.run(["git", "init", "-q"], cwd=workspace, check=True)
    try:
        # Exclude cold import time from each agent timing.
        warm = subprocess.run(
            [
                "kast",
                "query",
                "--name",
                "invokeCallback",
                "--in",
                "invocation",
                "--kind",
                "function",
                "--source-set",
                "main",
                "--json",
            ],
            cwd=workspace,
            check=True,
            capture_output=True,
            timeout=900,
        )
        assert DOCUMENT.validate_json(warm.stdout)["type"] == "complete"
        yield workspace
    finally:
        subprocess.run(["kast", "stop", "--workspace", str(workspace)], check=True, timeout=60)


@pytest.mark.parametrize("case", CASES)
def test_pi_skill_cli(case: str, tmp_path: Path, request: pytest.FixtureRequest) -> None:
    workspace = tmp_path
    live = case in LIVE_NAMES or case == "callback"
    if live:
        workspace = request.getfixturevalue("live_workspace")
        source = HERE.parents[1] / "fixtures/static-callback-fixture"
        reference = {f.relative_to(source): f.read_bytes() for f in source.rglob("*.kt")}
        supplied = {f.relative_to(workspace): f.read_bytes() for f in workspace.rglob("*.kt")}
        assert supplied == reference and Path(LIVE_FILE) in reference
    skill = Path(os.environ.get("PI_TRIAL_SKILL", SKILL)).resolve()
    prompt = (skill / "SKILL.md").read_text()
    guide = skill / "public-cli.md"
    executable = shutil.which("kast")
    assert executable, "kast must be on PATH"
    env = {
        **os.environ,
        "PI_TRIAL_KAST": executable,
        "KAST_OUTPUT_TOON": "0",
        "PI_TRIAL_GUIDE": str(guide),
        "PI_TRIAL_WORKSPACE": str(workspace),
        "PI_TRIAL_LIVE": str(int(live)),
    }
    cmd = [
        "pi",
        "--offline",
        "--no-session",
        "--no-context-files",
        "--no-extensions",
        "--no-skills",
        "--no-mcp",
        "--no-prompt-templates",
        "--no-themes",
        "--no-approve",
        "--no-builtin-tools",
        "-e",
        str(HERE / "pi_skill_cli.ts"),
        "--model",
        MODEL,
        "--thinking",
        "low",
        "--system-prompt",
        prompt,
        "--mode",
        "json",
        "-p",
        CASES[case],
    ]
    receipt: dict[str, object] = {
        "case": case,
        "model": MODEL,
        "effort": "low",
        "correct": False,
        "exact_format_pass": False,
        "outcome": "incomplete",
        "tokens": None,
        "skill_sha256": hashlib.sha256(prompt.encode()).hexdigest(),
        "guide_sha256": hashlib.sha256(guide.read_bytes()).hexdigest(),
        "pi": subprocess.check_output(["pi", "--version"], text=True).strip(),
        "kast": subprocess.check_output(["kast", "--version"], text=True).strip(),
    }
    with Path(executable).open("rb") as binary:
        receipt["kast_sha256"] = hashlib.file_digest(binary, "sha256").hexdigest()
    transcript = tmp_path / "events.jsonl"
    start = time.monotonic()
    try:
        try:
            with transcript.open("w") as out, (tmp_path / "stderr").open("w") as err:
                result = subprocess.run(
                    cmd, cwd=tmp_path, env=env, stdout=out, stderr=err, timeout=150
                )
        finally:
            receipt["seconds"] = time.monotonic() - start
        events = [DOCUMENT.validate_json(line) for line in transcript.read_text().splitlines()]
        responses = [
            e["message"]
            for e in events
            if e["type"] == "message_end" and e["message"]["role"] == "assistant"
        ]
        calls = [
            e["result"].get("details", {}) for e in events if e["type"] == "tool_execution_end"
        ]
        receipt.update(responses=len(responses), tools=len(calls))
        usage = [Usage.model_validate(m["usage"]) for m in responses]
        for row in usage:
            assert row.totalTokens == row.input + row.output + row.cacheRead + row.cacheWrite
        assert result.returncode == 0, (tmp_path / "stderr").read_text()
        assert responses and responses[-1]["stopReason"] == "stop", "Pi did not finish normally"
        assert all(f"{m['provider']}/{m['model']}" == MODEL for m in responses)
        tokens = sum(row.totalTokens for row in usage)
        assert tokens > 0
        receipt.update(
            tokens=tokens,
            usage={
                key: sum(row.model_dump()[key] for row in usage)
                for key in ("input", "output", "cacheRead", "cacheWrite")
            },
        )
        answer = "".join(p["text"] for p in responses[-1]["content"] if p["type"] == "text")
        receipt["outcome"] = "incorrect"
        answer = answer.strip()
        grade(case, answer, calls, workspace)
        receipt.update(
            correct=True,
            outcome="correct",
            exact_format_pass=case != "callback" or answer == "CALLBACK_GRAPH_UNPROVEN",
        )
    finally:
        (tmp_path / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt))
