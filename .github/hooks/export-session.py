#!/usr/bin/env python3
from __future__ import annotations

import html
import json
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


@dataclass
class TranscriptEntry:
    kind: str
    heading: str
    timestamp: datetime
    content: str
    label: str | None = None
    tool_name: str | None = None
    success: bool | None = None
    arguments: dict[str, object] | None = None
    result_text: str | None = None


def load_hook_input() -> dict[str, object]:
    raw = os.environ.get("HOOK_INPUT", "").strip()
    if not raw:
        raw = sys.stdin.read().strip()
    if not raw:
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"session export: invalid hook input: {exc}") from exc
    if not isinstance(payload, dict):
        raise SystemExit("session export: hook input must be a JSON object")
    return payload


def parse_bool(value: str | None) -> bool:
    return (value or "").strip().lower() == "true"


def parse_timestamp(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def format_local_timestamp(value: datetime) -> str:
    local = value.astimezone()
    hour = local.hour % 12 or 12
    period = "AM" if local.hour < 12 else "PM"
    return f"{local.month}/{local.day}/{local.year}, {hour}:{local.minute:02d}:{local.second:02d} {period}"


def format_elapsed(start: datetime, end: datetime) -> str:
    seconds = max(0, int((end - start).total_seconds()))
    hours, remainder = divmod(seconds, 3600)
    minutes, remaining_seconds = divmod(remainder, 60)
    parts: list[str] = []
    if hours:
        parts.append(f"{hours}h")
    if minutes or hours:
        parts.append(f"{minutes}m")
    parts.append(f"{remaining_seconds}s")
    return " ".join(parts)


def sanitize_text(value: object) -> str:
    return str(value).replace("\r\n", "\n").replace("\r", "\n").strip()


def first_line(value: str | None) -> str | None:
    if not value:
        return None
    line = value.splitlines()[0].strip()
    return line or None


def load_events(events_path: Path) -> list[dict[str, object]]:
    events: list[dict[str, object]] = []
    for line in events_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"session export: invalid event log entry in {events_path}: {exc}") from exc
        if not isinstance(event, dict):
            raise SystemExit(f"session export: invalid event log entry in {events_path}")
        events.append(event)
    return events


def describe_tool(tool_name: str, arguments: dict[str, object] | None, request: dict[str, object] | None) -> str | None:
    if tool_name == "report_intent" and arguments:
        intent = arguments.get("intent")
        if isinstance(intent, str) and intent.strip():
            return intent.strip()

    if tool_name == "skill" and arguments:
        skill_name = arguments.get("skill")
        if isinstance(skill_name, str) and skill_name.strip():
            return skill_name.strip()

    if request:
        intention = request.get("intentionSummary")
        if isinstance(intention, str) and intention.strip():
            return intention.strip()

    if tool_name == "bash" and arguments:
        command = arguments.get("command")
        if isinstance(command, str):
            return first_line(command)

    if arguments:
        try:
            serialized = json.dumps(arguments, sort_keys=True)
        except TypeError:
            serialized = None
        if serialized:
            return first_line(serialized)

    return None


def build_entries(events: list[dict[str, object]]) -> tuple[dict[str, object], datetime, list[TranscriptEntry]]:
    session_start: dict[str, object] | None = None
    start_timestamp: datetime | None = None
    last_timestamp: datetime | None = None
    entries: list[TranscriptEntry] = []
    pending_tool_requests: dict[str, dict[str, object]] = {}
    pending_tool_starts: dict[str, dict[str, object]] = {}

    for event in events:
        event_type = event.get("type")
        data = event.get("data")
        if not isinstance(data, dict):
            data = {}

        timestamp = parse_timestamp(event.get("timestamp"))
        if timestamp is not None:
            last_timestamp = timestamp

        if event_type == "session.start":
            session_start = data
            start_timestamp = parse_timestamp(data.get("startTime")) or timestamp or datetime.now(timezone.utc)
            continue

        if event_type == "assistant.message":
            content = sanitize_text(data.get("content", ""))
            if content:
                entries.append(
                    TranscriptEntry(
                        kind="copilot",
                        heading="Copilot",
                        timestamp=timestamp or start_timestamp or datetime.now(timezone.utc),
                        content=content,
                    ),
                )
            tool_requests = data.get("toolRequests")
            if isinstance(tool_requests, list):
                for request in tool_requests:
                    if not isinstance(request, dict):
                        continue
                    tool_call_id = request.get("toolCallId")
                    if isinstance(tool_call_id, str) and tool_call_id:
                        pending_tool_requests[tool_call_id] = request
            continue

        if event_type == "user.message":
            entries.append(
                TranscriptEntry(
                    kind="user",
                    heading="User",
                    timestamp=timestamp or start_timestamp or datetime.now(timezone.utc),
                    content=sanitize_text(data.get("content", "")),
                ),
            )
            continue

        if event_type == "tool.execution_start":
            tool_call_id = data.get("toolCallId")
            if isinstance(tool_call_id, str) and tool_call_id:
                pending_tool_starts[tool_call_id] = data
            continue

        if event_type == "tool.execution_complete":
            tool_call_id = data.get("toolCallId")
            if not isinstance(tool_call_id, str) or not tool_call_id:
                continue

            start = pending_tool_starts.pop(tool_call_id, {})
            request = pending_tool_requests.pop(tool_call_id, {})
            tool_name = start.get("toolName") or request.get("name") or "tool"
            if not isinstance(tool_name, str) or not tool_name.strip():
                tool_name = "tool"
            arguments = start.get("arguments")
            if not isinstance(arguments, dict):
                request_arguments = request.get("arguments")
                arguments = request_arguments if isinstance(request_arguments, dict) else None
            success = bool(data.get("success"))
            result = data.get("result")
            result_text = None
            if isinstance(result, dict):
                result_content = result.get("detailedContent") or result.get("content")
                if isinstance(result_content, str) and result_content.strip():
                    result_text = sanitize_text(result_content)
            detail = describe_tool(tool_name, arguments, request if isinstance(request, dict) else None)

            entries.append(
                TranscriptEntry(
                    kind="tool",
                    heading=detail or tool_name,
                    timestamp=timestamp or start_timestamp or datetime.now(timezone.utc),
                    content="",
                    label=detail,
                    tool_name=tool_name,
                    success=success,
                    arguments=arguments,
                    result_text=result_text,
                ),
            )
            continue

        if event_type == "session.info":
            message = sanitize_text(data.get("message", ""))
            if message:
                entries.append(
                    TranscriptEntry(
                        kind="info",
                        heading="Notification",
                        timestamp=timestamp or start_timestamp or datetime.now(timezone.utc),
                        content=message,
                    ),
                )
            continue

        if event_type == "session.model_change":
            message = sanitize_text(data.get("newModel", ""))
            if message:
                entries.append(
                    TranscriptEntry(
                        kind="info",
                        heading="Notification",
                        timestamp=timestamp or start_timestamp or datetime.now(timezone.utc),
                        content=f"Model changed to: {message}",
                    ),
                )
            continue

        if event_type == "session.mode_changed":
            previous_mode = sanitize_text(data.get("previousMode", ""))
            new_mode = sanitize_text(data.get("newMode", ""))
            if previous_mode or new_mode:
                entries.append(
                    TranscriptEntry(
                        kind="info",
                        heading="Notification",
                        timestamp=timestamp or start_timestamp or datetime.now(timezone.utc),
                        content=f"Mode changed from {previous_mode or 'unknown'} to {new_mode or 'unknown'}",
                    ),
                )
            continue

        if event_type == "session.task_complete":
            summary = sanitize_text(data.get("summary", ""))
            if summary:
                entries.append(
                    TranscriptEntry(
                        kind="info",
                        heading="Task complete",
                        timestamp=timestamp or start_timestamp or datetime.now(timezone.utc),
                        content=summary,
                    ),
                )

    if session_start is None or start_timestamp is None:
        raise SystemExit("session export: session.start event not found")

    if last_timestamp is None:
        last_timestamp = start_timestamp

    return session_start, start_timestamp, entries


def render_markdown(
    session_id: str,
    session_start: dict[str, object],
    start_timestamp: datetime,
    end_timestamp: datetime,
    entries: list[TranscriptEntry],
) -> str:
    exported_at = datetime.now().astimezone()
    started = format_local_timestamp(start_timestamp)
    duration = format_elapsed(start_timestamp, end_timestamp)
    lines: list[str] = [
        "# 🤖 Copilot CLI Session",
        "",
        "> [!NOTE]",
        f"> - **Session ID:** `{session_id}`  ",
        f"> - **Started:** {started}  ",
        f"> - **Duration:** {duration}  ",
        f"> - **Exported:** {format_local_timestamp(exported_at)}",
        "",
    ]

    for entry in entries:
        if entry.kind == "tool":
            icon = "✅" if entry.success else "❌"
            heading_line = f"### {icon} `{entry.tool_name or 'tool'}`"
        elif entry.kind == "user":
            heading_line = f"### 👤 {entry.heading}"
        elif entry.kind == "copilot":
            heading_line = f"### 💬 {entry.heading}"
        else:
            heading_line = f"### ℹ️ {entry.heading}"
        lines.extend(
            [
                "---",
                "",
                f"<sub>⏱️ {format_elapsed(start_timestamp, entry.timestamp)}</sub>",
                "",
                heading_line,
                "",
            ],
        )
        if entry.kind == "tool" and entry.label:
            lines.extend([f"**{entry.label}**", ""])
        elif entry.kind != "tool":
            lines.extend([entry.content, ""])

        if entry.kind == "tool":
            if entry.arguments:
                lines.extend(
                    [
                        "<details>",
                        "<summary>Arguments</summary>",
                        "",
                        "```json",
                        json.dumps(entry.arguments, indent=2, sort_keys=True),
                        "```",
                        "</details>",
                        "",
                    ],
                )
            if entry.result_text:
                lines.extend(["```", entry.result_text, "```", ""])
        else:
            continue

    return "\n".join(lines).rstrip() + "\n"


def render_html(
    session_id: str,
    session_start: dict[str, object],
    start_timestamp: datetime,
    end_timestamp: datetime,
    entries: list[TranscriptEntry],
) -> str:
    started = format_local_timestamp(start_timestamp)
    exported_at = format_local_timestamp(datetime.now().astimezone())
    duration = format_elapsed(start_timestamp, end_timestamp)
    title = f"Copilot CLI Session — {session_id}"
    body: list[str] = []

    for index, entry in enumerate(entries, start=1):
        mode = "interactive" if entry.kind in {"user", "copilot"} else "autopilot"
        body.append('<section class="entry">')
        body.append(f"<div>#{index}</div>")
        if entry.kind == "tool":
            heading_text = f"{entry.tool_name} - {entry.heading}" if entry.heading else (entry.tool_name or "tool")
        else:
            heading_text = entry.heading
        body.append(f"<div>{html.escape(heading_text)}</div>")
        body.append(f"<div>{mode}</div>")
        body.append(f"<div>{html.escape(format_elapsed(start_timestamp, entry.timestamp))}</div>")
        if entry.kind == "tool" and entry.label:
            body.append(f"<div>**{html.escape(entry.label)}**</div>")
        elif entry.content:
            for line in entry.content.splitlines():
                body.append(f"<div>{html.escape(line)}</div>")
        if entry.kind == "tool" and entry.arguments:
            body.append("<div>Arguments</div>")
            body.append("<pre>")
            body.append(html.escape(json.dumps(entry.arguments, indent=2, sort_keys=True)))
            body.append("</pre>")
        if entry.kind == "tool" and entry.result_text:
            for line in entry.result_text.splitlines():
                body.append(f"<div>{html.escape(line)}</div>")
        body.append("</section>")
        body.append("")

    return "\n".join(
        [
            "<!DOCTYPE html>",
            '<html lang="en">',
            "<head>",
            '<meta charset="utf-8" />',
            '<meta name="viewport" content="width=device-width, initial-scale=1" />',
            f"<title>{html.escape(title)}</title>",
            "<style>",
            "body{font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;line-height:1.5;margin:2rem auto;max-width:1000px;padding:0 1rem;color:#111827;background:#f8fafc}",
            ".meta,.entry{background:#fff;border:1px solid #cbd5e1;border-radius:10px;padding:1rem;margin:.75rem 0}",
            ".entry div{margin:.2rem 0;white-space:pre-wrap}",
            "pre{background:#0f172a;color:#e2e8f0;padding:1rem;border-radius:8px;overflow:auto;white-space:pre-wrap}",
            "code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}",
            "</style>",
            "</head>",
            "<body>",
            '<header class="meta">',
            "<h1>Copilot CLI Session</h1>",
            f"<p><strong>Session ID:</strong> {html.escape(session_id)}</p>",
            f"<p><strong>Started:</strong> {html.escape(started)}</p>",
            f"<p><strong>Duration:</strong> {html.escape(duration)}</p>",
            f"<p><strong>Exported:</strong> {html.escape(exported_at)}</p>",
            "</header>",
            *body,
            "</body>",
            "</html>",
            "",
        ],
    )


def main() -> int:
    if not parse_bool(os.environ.get("KAST_SESSION_EXPORT")):
        return 0

    payload = load_hook_input()
    session_id = payload.get("sessionId")
    session_payload = payload.get("session")
    if not session_id and isinstance(session_payload, dict):
        session_id = session_payload.get("id")
    if not isinstance(session_id, str) or not session_id.strip():
        raise SystemExit("session export: sessionId missing from hook input")
    session_id = session_id.strip()

    session_dir = Path.home() / ".copilot" / "session-state" / session_id
    events_path = session_dir / "events.jsonl"
    if not events_path.is_file():
        raise SystemExit(f"session export: session event log not found: {events_path}")

    events = load_events(events_path)
    session_start, start_timestamp, entries = build_entries(events)
    end_timestamp = start_timestamp
    for entry in entries:
        if entry.timestamp > end_timestamp:
            end_timestamp = entry.timestamp

    shutdown_timestamp = None
    for event in reversed(events):
        if event.get("type") == "session.shutdown":
            shutdown_timestamp = parse_timestamp(event.get("timestamp"))
            if shutdown_timestamp is not None:
                end_timestamp = shutdown_timestamp
            break

    export_root = Path(os.environ.get("KAST_SESSION_EXPORT_PATH") or (Path.home() / ".kast" / "sessions")).expanduser()
    if export_root.exists() and not export_root.is_dir():
        raise SystemExit(f"session export: export path is not a directory: {export_root}")
    export_root.mkdir(parents=True, exist_ok=True)

    markdown_path = export_root / f"copilot-session-{session_id}.md"
    html_path = export_root / f"copilot-session-{session_id}.html"

    markdown_path.write_text(
        render_markdown(session_id, session_start, start_timestamp, end_timestamp, entries),
        encoding="utf-8",
    )
    html_path.write_text(
        render_html(session_id, session_start, start_timestamp, end_timestamp, entries),
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
