#!/usr/bin/env python3
"""Normalize Copilot event logs into reusable session and pain-point artifacts."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PAIN_POINT_RE = re.compile(
    r"\b("
    r"again|bug|broken|didn'?t|doesn'?t|error|fail(?:ed|ure)?|fix|"
    r"incorrect|issue|missing|not working|regress(?:ion)?|retry|wrong"
    r")\b",
    re.IGNORECASE,
)


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Ingest Copilot session event logs and emit normalized sessions plus pain points.",
    )
    parser.add_argument(
        "paths",
        nargs="*",
        help="events.jsonl file, a session directory, or a root directory containing session-state/session_state",
    )
    parser.add_argument(
        "--root",
        default="~/.copilot",
        help="Fallback root to scan when no explicit paths are provided",
    )
    parser.add_argument(
        "--out",
        default="-",
        help="Path for normalized session JSON (default: stdout)",
    )
    parser.add_argument(
        "--pain-points-out",
        default=None,
        help="Optional path for newline-delimited pain-point JSON",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Optional maximum number of event logs to ingest",
    )
    return parser.parse_args()


def resolve_input_path(path: Path) -> list[Path]:
    if path.is_file():
        return [path] if path.name == "events.jsonl" else []
    if not path.exists():
        return []
    direct = path / "events.jsonl"
    if direct.is_file():
        return [direct]

    results: list[Path] = []
    for child_name in ("session-state", "session_state"):
        child_root = path / child_name
        if child_root.is_dir():
            results.extend(sorted(child_root.glob("*/events.jsonl")))
    if results:
        return results
    return sorted(path.glob("*/events.jsonl"))


def discover_event_files(raw_paths: list[str], root: str, limit: int | None) -> list[Path]:
    candidates: list[Path] = []
    if raw_paths:
        for raw_path in raw_paths:
            candidates.extend(resolve_input_path(Path(raw_path).expanduser()))
    else:
        candidates.extend(resolve_input_path(Path(root).expanduser()))

    deduped: list[Path] = []
    seen: set[Path] = set()
    for path in candidates:
        resolved = path.resolve()
        if resolved not in seen:
            deduped.append(resolved)
            seen.add(resolved)
    if limit is not None:
        deduped = deduped[:limit]
    return deduped


def safe_load_jsonl(path: Path) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    with path.open() as handle:
        for line_number, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                events.append(json.loads(text))
            except json.JSONDecodeError as exc:
                print(
                    f"Warning: skipping invalid JSON at {path}:{line_number}: {exc}",
                    file=sys.stderr,
                )
    return events


def attachment_paths(attachments: list[dict[str, Any]]) -> list[str]:
    paths: list[str] = []
    for attachment in attachments:
        path = attachment.get("path")
        if isinstance(path, str) and path:
            paths.append(path)
    return paths


def normalize_tool_event(
    start_event: dict[str, Any] | None,
    complete_event: dict[str, Any],
) -> dict[str, Any]:
    start_data = start_event.get("data", {}) if start_event else {}
    complete_data = complete_event.get("data", {})
    result = complete_data.get("result", {})
    content = result.get("content", "")
    if not isinstance(content, str):
        content = ""
    return {
        "tool_call_id": complete_data.get("toolCallId"),
        "name": complete_data.get("toolTelemetry", {}).get("displayName")
        or start_data.get("toolName")
        or complete_data.get("toolTelemetry", {}).get("name"),
        "arguments": start_data.get("arguments", {}),
        "success": bool(complete_data.get("success")),
        "timestamp": complete_event.get("timestamp"),
        "model": complete_data.get("model"),
        "summary": content[:400].strip(),
    }


def contains_pain_signal(messages: list[str]) -> bool:
    return any(PAIN_POINT_RE.search(message or "") for message in messages)


def slugify(value: str, fallback: str) -> str:
    text = value.strip().lower()
    text = re.sub(r"[^a-z0-9]+", "-", text)
    text = text.strip("-")
    text = re.sub(r"-{2,}", "-", text)
    return text[:64] or fallback


def build_session(event_path: Path) -> dict[str, Any]:
    events = safe_load_jsonl(event_path)
    session_id = event_path.parent.name
    cwd = ""
    models: list[str] = []
    skill_invocations: list[dict[str, Any]] = []
    tool_starts: dict[str, dict[str, Any]] = {}
    interactions: dict[str, dict[str, Any]] = defaultdict(
        lambda: {
            "interaction_id": None,
            "user_messages": [],
            "assistant_messages": [],
            "tool_requests": [],
            "tools": [],
        }
    )

    for event in events:
        event_type = event.get("type")
        data = event.get("data", {})
        if event_type == "session.start":
            session_id = data.get("sessionId", session_id)
            cwd = data.get("context", {}).get("cwd", cwd)
        elif event_type == "session.model_change":
            model = data.get("newModel")
            if isinstance(model, str) and model and model not in models:
                models.append(model)
        elif event_type == "skill.invoked":
            skill_invocations.append(
                {
                    "name": data.get("name"),
                    "path": data.get("path"),
                    "timestamp": event.get("timestamp"),
                }
            )
        elif event_type == "user.message":
            interaction_id = data.get("interactionId") or event.get("id")
            bucket = interactions[interaction_id]
            bucket["interaction_id"] = interaction_id
            bucket["user_messages"].append(
                {
                    "timestamp": event.get("timestamp"),
                    "content": data.get("content", ""),
                    "transformed_content": data.get("transformedContent", ""),
                    "attachments": data.get("attachments", []),
                }
            )
        elif event_type == "assistant.message":
            interaction_id = data.get("interactionId") or event.get("id")
            bucket = interactions[interaction_id]
            bucket["interaction_id"] = interaction_id
            tool_requests = data.get("toolRequests", [])
            bucket["assistant_messages"].append(
                {
                    "timestamp": event.get("timestamp"),
                    "content": data.get("content", ""),
                    "output_tokens": data.get("outputTokens"),
                }
            )
            bucket["tool_requests"].extend(
                [
                    {
                        "name": request.get("name"),
                        "arguments": request.get("arguments", {}),
                    }
                    for request in tool_requests
                ]
            )
        elif event_type == "tool.execution_start":
            tool_call_id = data.get("toolCallId")
            if isinstance(tool_call_id, str):
                tool_starts[tool_call_id] = event
        elif event_type == "tool.execution_complete":
            tool_call_id = data.get("toolCallId")
            interaction_id = data.get("interactionId") or tool_call_id or event.get("id")
            bucket = interactions[interaction_id]
            bucket["interaction_id"] = interaction_id
            bucket["tools"].append(normalize_tool_event(tool_starts.get(tool_call_id), event))

    turns: list[dict[str, Any]] = []
    pain_points: list[dict[str, Any]] = []

    for interaction_id, bucket in sorted(
        interactions.items(),
        key=lambda item: (
            item[1]["user_messages"][0]["timestamp"]
            if item[1]["user_messages"]
            else item[1]["assistant_messages"][0]["timestamp"]
            if item[1]["assistant_messages"]
            else ""
        ),
    ):
        user_messages = bucket["user_messages"]
        assistant_messages = bucket["assistant_messages"]
        tools = bucket["tools"]
        prompt = user_messages[0]["content"] if user_messages else ""
        transformed_prompt = user_messages[0]["transformed_content"] if user_messages else ""
        followups = [message["content"] for message in user_messages[1:]]
        attachments = user_messages[0]["attachments"] if user_messages else []
        assistant_texts = [message["content"] for message in assistant_messages if message.get("content")]
        tool_failures = [tool for tool in tools if not tool.get("success")]
        followup_signal = contains_pain_signal(followups)

        turns.append(
            {
                "interaction_id": interaction_id,
                "prompt": prompt,
                "transformed_prompt": transformed_prompt,
                "followups": followups,
                "attachments": attachments,
                "tool_requests": bucket["tool_requests"],
                "tools": tools,
                "assistant_messages": assistant_messages,
                "assistant_text": "\n\n".join(assistant_texts),
                "signals": {
                    "tool_failures": len(tool_failures),
                    "followup_pain_signal": followup_signal,
                },
            }
        )

        file_inputs = attachment_paths(attachments)
        if tool_failures:
            first_failure = tool_failures[0]
            pain_points.append(
                {
                    "id": f"{session_id}:{interaction_id}:tool-failure",
                    "title": f"{first_failure.get('name') or 'tool'} failure",
                    "summary": first_failure.get("summary") or "Tool execution failed during a user task.",
                    "labels": ["copilot-event-log", "tool-failure"],
                    "source": {
                        "kind": "copilot_event_log",
                        "session_id": session_id,
                        "interaction_id": interaction_id,
                        "event_path": str(event_path),
                        "user_prompt": prompt,
                    },
                    "suggested_eval": {
                        "prompt": prompt,
                        "files": file_inputs,
                        "expected_output": "",
                        "expectations": [],
                        "labels": ["tool-failure"],
                    },
                }
            )

        if followup_signal:
            summary = next((message for message in followups if PAIN_POINT_RE.search(message or "")), followups[0])
            pain_points.append(
                {
                    "id": f"{session_id}:{interaction_id}:followup",
                    "title": slugify(summary or prompt, "followup-issue").replace("-", " ").title(),
                    "summary": summary,
                    "labels": ["copilot-event-log", "user-followup"],
                    "source": {
                        "kind": "copilot_event_log",
                        "session_id": session_id,
                        "interaction_id": interaction_id,
                        "event_path": str(event_path),
                        "user_prompt": prompt,
                    },
                    "suggested_eval": {
                        "prompt": prompt,
                        "files": file_inputs,
                        "expected_output": summary,
                        "expectations": [],
                        "labels": ["user-followup"],
                    },
                }
            )

    return {
        "session_id": session_id,
        "event_path": str(event_path),
        "cwd": cwd,
        "models": models,
        "skill_invocations": skill_invocations,
        "turns": turns,
        "pain_points": pain_points,
    }


def write_output(path: str, payload: dict[str, Any]) -> None:
    if path == "-":
        json.dump(payload, sys.stdout, indent=2)
        sys.stdout.write("\n")
        return
    output_path = Path(path).expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2) + "\n")


def write_pain_points(path: str, sessions: list[dict[str, Any]]) -> None:
    output_path = Path(path).expanduser().resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w") as handle:
        for session in sessions:
            for pain_point in session.get("pain_points", []):
                handle.write(json.dumps(pain_point) + "\n")


def main() -> None:
    args = parse_args()
    event_files = discover_event_files(args.paths, args.root, args.limit)
    if not event_files:
        print("No Copilot events.jsonl files found.", file=sys.stderr)
        sys.exit(1)

    sessions = [build_session(path) for path in event_files]
    payload = {
        "generated_at": utc_now(),
        "source_count": len(event_files),
        "sessions": sessions,
    }

    write_output(args.out, payload)
    if args.pain_points_out:
        write_pain_points(args.pain_points_out, sessions)
        print(
            f"Wrote {sum(len(session.get('pain_points', [])) for session in sessions)} pain points to "
            f"{Path(args.pain_points_out).expanduser()}",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()
