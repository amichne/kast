#!/usr/bin/env python3
"""Build a sanitized routing corpus from Copilot session exports and logs."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from html import unescape
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable

MARKDOWN_USER_HEADING = "### 👤 User"
TOOL_HEADING_RE = re.compile(r"^### ✅ `([^`]+)`$")
SESSION_ID_RE = re.compile(r"Session ID:\s*`([^`]+)`")
TITLE_RE = re.compile(r"<title>(.*?)</title>", re.IGNORECASE | re.DOTALL)
TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}")
WORKSPACE_RE = re.compile(r"Workspace initialized:\s+([0-9a-f-]{36})")
AGENT_PROMPT_RE = re.compile(r'Custom agent "([^"]+)" invoked with prompt:\s*(.*)')
AGENT_TOOLS_RE = re.compile(r'Custom agent "([^"]+)" using tools:\s*(.*)')
HOOK_RE = re.compile(r"Hook execution failed:\s*(.*)")
HTML_ENTRY_RE = re.compile(r"^#\d+$")
HTML_TOOL_RE = re.compile(r"^([a-z][a-z0-9_-]*) - (.+)$")
HTML_DURATION_RE = re.compile(r"^(?:\d+h\s+)?(?:\d+m\s+)?\d+s$")
HTML_STYLE_OR_SCRIPT_RE = re.compile(r"<(?:style|script)\b.*?</(?:style|script)>", re.IGNORECASE | re.DOTALL)
HTML_TAG_RE = re.compile(r"<[^>]+>")
KAST_COMMAND_RE = re.compile(
    r'(?:(?:"?\$KAST_CLI_PATH"?|(?:[^\s"\']+/)?kast)\s+skill\s+'
    r'(?:resolve|references|callers|diagnostics|rename|scaffold|write-and-validate|workspace-files))'
)
ABS_PATH_RE = re.compile(
    r"(?:(?:/Users|/home|/tmp|/private/tmp|/var/folders)/[^\s`\"'<>]+)"
)
UUID_RE = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")

SEMANTIC_HINTS = (
    "kotlin",
    "symbol",
    "usage",
    "usages",
    "call hierarchy",
    "calls this",
    "where is this used",
    "trace this flow",
    "understand this",
    "refactor",
    "rename",
    "workspace",
    "failing test",
)

PROMOTION_CLASSIFICATIONS = {
    "trigger-miss",
    "loaded-but-bypassed",
    "route-via-subagent",
    "semantic-abandonment",
}


@dataclass
class RoutingCase:
    source_type: str
    source_name: str
    session_id: str | None
    prompt: str
    classification: str
    loaded_skills: list[str]
    custom_agent: str | None
    tool_counts: dict[str, int]
    available_tools: list[str]
    evidence: list[str]


@dataclass
class SystemIssue:
    source_name: str
    session_id: str | None
    classification: str
    message: str


class TitleParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self._in_title = False
        self.title_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() == "title":
            self._in_title = True

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "title":
            self._in_title = False

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self.title_parts.append(data)

    @property
    def title(self) -> str:
        return "".join(self.title_parts).strip()


def extract_html_visible_lines(raw: str) -> list[str]:
    raw = HTML_STYLE_OR_SCRIPT_RE.sub("\n", raw)
    text = HTML_TAG_RE.sub("\n", raw)
    return [
        normalized
        for line in text.splitlines()
        if (normalized := re.sub(r"\s+", " ", unescape(line)).strip())
    ]


def collect_html_blocks(lines: list[str]) -> list[list[str]]:
    starts = [index for index, line in enumerate(lines) if HTML_ENTRY_RE.match(line)]
    blocks: list[list[str]] = []
    for position, start in enumerate(starts):
        end = starts[position + 1] if position + 1 < len(starts) else len(lines)
        block = lines[start:end]
        if len(block) > 1:
            blocks.append(block)
    return blocks


def block_heading(block: list[str]) -> str | None:
    return block[1].strip() if len(block) > 1 else None


def block_content_lines(block: list[str]) -> list[str]:
    index = 2
    if index < len(block) and block[index] in {"interactive", "autopilot"}:
        index += 1
    if index < len(block) and HTML_DURATION_RE.match(block[index]):
        index += 1
    return [line for line in block[index:] if line not in {"✔", "ℹ", "⚠", "💬", "💭"}]


def sanitize_text(value: str, *, limit: int = 280) -> str:
    value = ABS_PATH_RE.sub("<ABS_PATH>", value)
    value = UUID_RE.sub("<SESSION_ID>", value)
    value = re.sub(r"\s+", " ", value).strip()
    if len(value) > limit:
        return value[: limit - 1].rstrip() + "…"
    return value


def looks_semantic(prompt: str) -> bool:
    lowered = prompt.lower()
    return any(hint in lowered for hint in SEMANTIC_HINTS)


def classify_export(prompt: str, loaded_skills: list[str], tool_counts: Counter[str]) -> str:
    if "kast" in loaded_skills and tool_counts.get("bash", 0) > 0:
        return "loaded-but-bypassed"
    if looks_semantic(prompt) and "kast" not in loaded_skills:
        return "trigger-miss"
    return "needs-review"


def classify_html_export(
    prompt: str,
    loaded_skills: list[str],
    tool_counts: Counter[str],
    *,
    kast_command_blocks: int,
    grep_like_commands: int,
) -> str:
    if kast_command_blocks > 0 and grep_like_commands > 0:
        return "semantic-abandonment"
    if looks_semantic(prompt) and "kast" not in loaded_skills and kast_command_blocks == 0:
        return "trigger-miss"
    if "kast" in loaded_skills and tool_counts.get("bash", 0) > 0 and kast_command_blocks == 0:
        return "loaded-but-bypassed"
    return "needs-review"


def classify_log_prompt(agent: str, prompt: str) -> str:
    if agent == "kast":
        return "routed-through-kast"
    if agent in {"explore", "plan", "edit"} and looks_semantic(prompt):
        return "route-via-subagent"
    return "needs-review"


def collect_text_block(lines: list[str], start: int) -> tuple[str, int]:
    collected: list[str] = []
    index = start
    while index < len(lines):
        line = lines[index]
        if line == "---" or line.startswith("### "):
            break
        collected.append(line)
        index += 1
    return sanitize_text("\n".join(collected)), index


def parse_markdown_export(path: Path) -> list[RoutingCase]:
    lines = path.read_text(encoding="utf-8").splitlines()
    session_id = None
    prompts: list[str] = []
    loaded_skills: list[str] = []
    tool_counts: Counter[str] = Counter()

    for line in lines:
        if session_id is None:
            match = SESSION_ID_RE.search(line)
            if match:
                session_id = match.group(1)

    index = 0
    while index < len(lines):
        line = lines[index].strip()
        if line == MARKDOWN_USER_HEADING:
            prompt, next_index = collect_text_block(lines, index + 1)
            if prompt:
                prompts.append(prompt)
            index = next_index
            continue
        match = TOOL_HEADING_RE.match(line)
        if match:
            tool_name = match.group(1)
            tool_counts[tool_name] += 1
            if tool_name == "skill":
                skill_name = None
                lookahead = index + 1
                while lookahead < len(lines):
                    candidate = lines[lookahead].strip()
                    if candidate.startswith("**") and candidate.endswith("**"):
                        skill_name = candidate.strip("*")
                        break
                    if candidate == "---" or candidate.startswith("### "):
                        break
                    lookahead += 1
                if skill_name:
                    loaded_skills.append(skill_name)
        index += 1

    if not prompts:
        return []

    prompt = prompts[0]
    classification = classify_export(prompt, loaded_skills, tool_counts)
    evidence = [
        f"loaded_skills={','.join(loaded_skills) or 'none'}",
        f"tool_counts={json.dumps(tool_counts, sort_keys=True)}",
    ]
    return [
        RoutingCase(
            source_type="session-export",
            source_name=path.name,
            session_id=session_id,
            prompt=prompt,
            classification=classification,
            loaded_skills=sorted(set(loaded_skills)),
            custom_agent=None,
            tool_counts=dict(tool_counts),
            available_tools=[],
            evidence=evidence,
        ),
    ]


def parse_html_export(path: Path) -> list[RoutingCase]:
    raw = path.read_text(encoding="utf-8")
    parser = TitleParser()
    parser.feed(raw)
    title = parser.title or sanitize_text(TITLE_RE.search(raw).group(1)) if TITLE_RE.search(raw) else ""
    lines = extract_html_visible_lines(raw)
    blocks = collect_html_blocks(lines)
    session_id = next((line for line in lines if UUID_RE.fullmatch(line)), None)
    prompts: list[str] = []
    fallback_prompts: list[str] = []
    loaded_skills: list[str] = []
    tool_counts: Counter[str] = Counter()
    kast_command_blocks = 0
    grep_like_commands = 0
    contract_reference_reads = 0
    bootstrap_probes = 0

    for block in blocks:
        heading = block_heading(block)
        if heading is None:
            continue
        content_lines = block_content_lines(block)
        content_text = "\n".join(content_lines)

        if heading == "User":
            prompt = sanitize_text(" ".join(content_lines))
            if prompt:
                prompts.append(prompt)
            continue

        if heading == "Copilot":
            prompt = sanitize_text(" ".join(content_lines))
            if prompt:
                fallback_prompts.append(prompt)
            continue

        match = HTML_TOOL_RE.match(heading)
        if not match:
            continue
        tool_name, detail = match.groups()
        tool_counts[tool_name] += 1
        if tool_name == "skill":
            skill_name = detail.strip()
            if skill_name:
                loaded_skills.append(skill_name)
        if tool_name == "bash":
            if KAST_COMMAND_RE.search(content_text):
                kast_command_blocks += 1
            if re.search(r"\b(?:grep|rg)\b", content_text):
                grep_like_commands += 1
            if "KAST_CLI_PATH=" in content_text or "command not found" in content_text:
                bootstrap_probes += 1
        if tool_name in {"grep", "rg"}:
            grep_like_commands += 1
        if tool_name == "view" and any(
            marker in content_text
            for marker in (".kast-version", "wrapper-openapi.yaml")
        ):
            contract_reference_reads += 1

    prompt = next(iter(prompts), "")
    if not prompt:
        prompt = next(iter(fallback_prompts), "")
    if not prompt and title:
        prompt = sanitize_text(title)
    if not prompt:
        return []

    classification = classify_html_export(
        prompt,
        loaded_skills,
        tool_counts,
        kast_command_blocks=kast_command_blocks,
        grep_like_commands=grep_like_commands,
    )
    return [
        RoutingCase(
            source_type="session-export-html",
            source_name=path.name,
            session_id=session_id,
            prompt=prompt,
            classification=classification,
            loaded_skills=sorted(set(loaded_skills)),
            custom_agent=None,
            tool_counts=dict(tool_counts),
            available_tools=[],
            evidence=[
                "html_visible_text_blocks=true",
                f"loaded_skills={','.join(sorted(set(loaded_skills))) or 'none'}",
                f"tool_counts={json.dumps(tool_counts, sort_keys=True)}",
                f"kast_command_blocks={kast_command_blocks}",
                f"grep_like_commands={grep_like_commands}",
                f"contract_reference_reads={contract_reference_reads}",
                f"bootstrap_probes={bootstrap_probes}",
            ],
        ),
    ]


def parse_log_file(path: Path) -> tuple[list[RoutingCase], list[SystemIssue]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    session_id = None
    available_tools_by_agent: dict[str, list[str]] = {}
    cases: list[RoutingCase] = []
    issues: list[SystemIssue] = []

    index = 0
    while index < len(lines):
        line = lines[index]
        if session_id is None:
            workspace_match = WORKSPACE_RE.search(line)
            if workspace_match:
                session_id = workspace_match.group(1)

        if ".github/agents/kast.md: unknown field ignored: agents" in line:
            issues.append(
                SystemIssue(
                    source_name=path.name,
                    session_id=session_id,
                    classification="config-drift",
                    message=sanitize_text(line),
                ),
            )

        hook_match = HOOK_RE.search(line)
        if hook_match:
            issues.append(
                SystemIssue(
                    source_name=path.name,
                    session_id=session_id,
                    classification="config-drift",
                    message=sanitize_text(hook_match.group(1)),
                ),
            )

        tools_match = AGENT_TOOLS_RE.search(line)
        if tools_match:
            agent_name = tools_match.group(1)
            tools = [part.strip() for part in tools_match.group(2).split(",") if part.strip()]
            available_tools_by_agent[agent_name] = tools
            index += 1
            continue

        prompt_match = AGENT_PROMPT_RE.search(line)
        if prompt_match:
            agent_name = prompt_match.group(1)
            prompt_lines = [prompt_match.group(2).strip()]
            lookahead = index + 1
            while lookahead < len(lines) and not TIMESTAMP_RE.match(lines[lookahead]):
                prompt_lines.append(lines[lookahead].strip())
                lookahead += 1
            prompt = sanitize_text(" ".join(part for part in prompt_lines if part))
            tools = available_tools_by_agent.get(agent_name, [])
            cases.append(
                RoutingCase(
                    source_type="process-log",
                    source_name=path.name,
                    session_id=session_id,
                    prompt=prompt,
                    classification=classify_log_prompt(agent_name, prompt),
                    loaded_skills=[],
                    custom_agent=agent_name,
                    tool_counts={},
                    available_tools=tools,
                    evidence=[
                        f"custom_agent={agent_name}",
                        f"available_tools={','.join(tools) or 'unknown'}",
                    ],
                ),
            )
            index = lookahead
            continue

        index += 1

    return cases, issues


def gather_session_exports(session_dirs: list[Path]) -> list[Path]:
    by_stem: dict[str, Path] = {}
    for directory in session_dirs:
        for path in sorted(directory.rglob("copilot-session-*")):
            if path.suffix not in {".md", ".html"}:
                continue
            existing = by_stem.get(path.stem)
            if existing is None or existing.suffix == ".html" and path.suffix == ".md":
                by_stem[path.stem] = path
    return sorted(by_stem.values())


def gather_logs(log_dirs: list[Path]) -> list[Path]:
    collected: list[Path] = []
    for directory in log_dirs:
        collected.extend(sorted(directory.rglob("process-*.log")))
    return collected


def ensure_parent(path: Path | None) -> None:
    if path is not None:
        path.parent.mkdir(parents=True, exist_ok=True)


def write_jsonl(path: Path, rows: Iterable[RoutingCase]) -> None:
    ensure_parent(path)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(asdict(row), sort_keys=True))
            handle.write("\n")


def build_promotion_candidates(cases: list[RoutingCase]) -> dict[str, object]:
    evals = []
    seen_ids: set[str] = set()
    for case in cases:
        if case.classification not in PROMOTION_CLASSIFICATIONS or not case.prompt:
            continue
        slug = re.sub(r"[^a-z0-9]+", "-", case.prompt.lower()).strip("-")
        slug = slug[:48] or "routing-case"
        candidate_id = slug
        suffix = 1
        while candidate_id in seen_ids:
            suffix += 1
            candidate_id = f"{slug}-{suffix}"
        seen_ids.add(candidate_id)
        evals.append(
            {
                "id": candidate_id,
                "prompt": case.prompt,
                "expected_skill": "kast",
                "expected_route": "@kast",
                "allowed_ops": [
                    "kast skill workspace-files",
                    "kast skill scaffold",
                    "kast skill resolve",
                    "kast skill references",
                    "kast skill callers",
                ],
                "forbidden_ops": ["grep", "rg"],
                "derived_from": {
                    "source_type": case.source_type,
                    "source_name": case.source_name,
                    "classification": case.classification,
                },
            },
        )
    return {
        "skill_name": "kast",
        "suite": "routing-promotion-candidates",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "evals": evals,
    }


def render_summary(cases: list[RoutingCase], issues: list[SystemIssue]) -> str:
    class_counts = Counter(case.classification for case in cases)
    agent_counts = Counter(case.custom_agent for case in cases if case.custom_agent)
    issue_counts = Counter(issue.classification for issue in issues)

    lines = [
        "# Kast routing corpus summary",
        "",
        f"- Generated at: {datetime.now(timezone.utc).isoformat()}",
        f"- Routing cases: {len(cases)}",
        f"- Systemic issues: {len(issues)}",
        "",
        "## Case classifications",
        "",
    ]
    if class_counts:
        for name, count in sorted(class_counts.items()):
            lines.append(f"- `{name}`: {count}")
    else:
        lines.append("- No routing cases found")

    lines += ["", "## Agent sightings", ""]
    if agent_counts:
        for name, count in sorted(agent_counts.items()):
            lines.append(f"- `{name}`: {count}")
    else:
        lines.append("- No custom agents found in the supplied logs")

    lines += ["", "## Systemic issues", ""]
    if issue_counts:
        for name, count in sorted(issue_counts.items()):
            lines.append(f"- `{name}`: {count}")
    else:
        lines.append("- No systemic issues found")

    promotion_candidates = [
        case for case in cases if case.classification in PROMOTION_CLASSIFICATIONS
    ]
    lines += ["", "## Promotion candidates", ""]
    if promotion_candidates:
        for case in promotion_candidates[:10]:
            lines.append(
                f"- `{case.classification}` from `{case.source_name}`: {case.prompt}"
            )
        if len(promotion_candidates) > 10:
            lines.append(
                f"- … plus {len(promotion_candidates) - 10} more candidate case(s)"
            )
    else:
        lines.append("- No promotion candidates found")

    if issues:
        lines += ["", "## Issue details", ""]
        for issue in issues[:20]:
            lines.append(f"- `{issue.source_name}`: {issue.message}")
        if len(issues) > 20:
            lines.append(f"- … plus {len(issues) - 20} more issue(s)")

    return "\n".join(lines) + "\n"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session-dir", action="append", default=[], help="Directory containing /share session exports")
    parser.add_argument("--logs-dir", action="append", default=[], help="Directory containing Copilot process logs")
    parser.add_argument("--output-jsonl", type=Path, help="Write routing cases as JSONL")
    parser.add_argument("--output-markdown", type=Path, help="Write a Markdown summary")
    parser.add_argument("--output-promotions", type=Path, help="Write promotion candidates as JSON")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    session_dirs = [Path(value).expanduser().resolve() for value in args.session_dir]
    log_dirs = [Path(value).expanduser().resolve() for value in args.logs_dir]

    cases: list[RoutingCase] = []
    issues: list[SystemIssue] = []

    for export_path in gather_session_exports(session_dirs):
        if export_path.suffix == ".md":
            cases.extend(parse_markdown_export(export_path))
        else:
            cases.extend(parse_html_export(export_path))

    for log_path in gather_logs(log_dirs):
        parsed_cases, parsed_issues = parse_log_file(log_path)
        cases.extend(parsed_cases)
        issues.extend(parsed_issues)

    summary = render_summary(cases, issues)
    promotions = build_promotion_candidates(cases)

    if args.output_jsonl:
        write_jsonl(args.output_jsonl, cases)
    if args.output_markdown:
        ensure_parent(args.output_markdown)
        args.output_markdown.write_text(summary, encoding="utf-8")
    if args.output_promotions:
        ensure_parent(args.output_promotions)
        args.output_promotions.write_text(
            json.dumps(promotions, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    if not args.output_markdown:
        sys.stdout.write(summary)

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
