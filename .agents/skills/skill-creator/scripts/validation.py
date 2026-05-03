#!/usr/bin/env python3
"""Shared validation for skill-creator contracts."""

from __future__ import annotations

import json
import math
import re
from collections import Counter
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from pathlib import Path, PurePosixPath
from typing import Any

MAX_SKILL_NAME_LENGTH = 64
ALLOWED_FRONTMATTER_PROPERTIES = {
    "name",
    "description",
    "license",
    "allowed-tools",
    "metadata",
}
ALLOWED_STAGES = {"candidate", "holdout", "core", "retired"}
CANONICAL_SKILL_DIRS = {
    "agents",
    "scripts",
    "references",
    "assets",
    "evals",
    "history",
    "eval-viewer",
    "fixtures",
}
CANONICAL_LAYOUT_WARNINGS = {
    "reference": "Use references/ for durable documentation so skills share one layout.",
}
TRANSIENT_ROOT_FILES = {
    "session.html",
    "benchmark.json",
    "benchmark.md",
    "feedback.json",
    "comparison.json",
    "analysis.json",
    "consolidation_report.json",
    "consolidation_report.md",
    "overlap_report.json",
}
MISPLACED_ARTIFACTS = {
    "catalog.json": ("evals", "catalog.json"),
    "pain_points.jsonl": ("evals", "pain_points.jsonl"),
    "progression.json": ("history", "progression.json"),
}
NARROW_SCOPE_TOKENS = {
    "backend-service-client",
    "backend-service-clients/",
    "applications/",
    "common/",
    "contract-packages/",
    "core/",
    "features/",
    "src/main",
    "src/test",
    "testfixtures",
    "build.gradle",
    "settings.gradle",
}
TOKEN_STOP_WORDS = {
    "a",
    "an",
    "and",
    "are",
    "as",
    "at",
    "be",
    "build",
    "by",
    "create",
    "creating",
    "for",
    "from",
    "guidelines",
    "help",
    "in",
    "into",
    "maintaining",
    "msl",
    "new",
    "of",
    "on",
    "or",
    "project",
    "skill",
    "skills",
    "that",
    "the",
    "this",
    "to",
    "use",
    "when",
    "with",
    "workflow",
    "workflows",
}
TIMESTAMP_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
OVERLAP_WARNING_MIN_SCORE = 0.33
OVERLAP_HIGH_SIGNAL_MAX_FREQUENCY = 2


@dataclass
class ValidationReport:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def is_valid(self) -> bool:
        return not self.errors

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    def extend(self, other: "ValidationReport") -> None:
        self.errors.extend(other.errors)
        self.warnings.extend(other.warnings)


@dataclass(frozen=True)
class SkillDescriptor:
    path: Path
    name: str
    description: str
    name_tokens: frozenset[str]
    description_tokens: frozenset[str]
    tokens: frozenset[str]


@dataclass(frozen=True)
class OverlapFinding:
    skill_a: str
    skill_b: str
    score: float
    weighted_overlap: float
    description_overlap: float
    name_overlap: float
    name_similarity: float
    shared_terms: tuple[str, ...]
    shared_high_signal_terms: tuple[str, ...]


def format_report(report: ValidationReport, success_message: str) -> str:
    lines = []
    if report.errors:
        lines.append("Validation failed:")
        lines.extend(f"- {message}" for message in report.errors)
    else:
        lines.append(success_message)
    if report.warnings:
        lines.append("")
        lines.append("Warnings:")
        lines.extend(f"- {message}" for message in report.warnings)
    return "\n".join(lines)


def parse_frontmatter(frontmatter_text: str) -> tuple[dict[str, str], set[str]]:
    result: dict[str, str] = {}
    keys: set[str] = set()
    lines = frontmatter_text.splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        if not line.strip():
            index += 1
            continue
        if line.startswith((" ", "\t")):
            index += 1
            continue
        if ":" not in line:
            raise ValueError(f"Invalid frontmatter line: {line}")
        key, value = line.split(":", 1)
        key = key.strip()
        value = value.strip()
        keys.add(key)
        if value in {"|", ">", "|-", ">-"}:
            block_lines: list[str] = []
            index += 1
            while index < len(lines) and (
                lines[index].startswith("  ") or lines[index].startswith("\t")
            ):
                block_lines.append(lines[index].strip())
                index += 1
            result[key] = " ".join(block_lines).strip()
            continue
        result[key] = value.strip('"').strip("'")
        index += 1
    return result, keys


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: {exc}") from exc
    except OSError as exc:
        raise ValueError(f"Could not read {path}: {exc}") from exc


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _require_object(value: Any, context: str, report: ValidationReport) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        report.error(f"{context} must be a JSON object.")
        return None
    return value


def _require_list(value: Any, context: str, report: ValidationReport) -> list[Any] | None:
    if not isinstance(value, list):
        report.error(f"{context} must be an array.")
        return None
    return value


def _require_string(
    value: Any,
    context: str,
    report: ValidationReport,
    *,
    allow_empty: bool = False,
) -> str | None:
    if not isinstance(value, str):
        report.error(f"{context} must be a string.")
        return None
    text = value.strip()
    if not allow_empty and not text:
        report.error(f"{context} must be a non-empty string.")
        return None
    return text


def _require_bool(value: Any, context: str, report: ValidationReport) -> bool | None:
    if not isinstance(value, bool):
        report.error(f"{context} must be a boolean.")
        return None
    return value


def _require_int(
    value: Any,
    context: str,
    report: ValidationReport,
    *,
    minimum: int | None = None,
) -> int | None:
    if not isinstance(value, int) or isinstance(value, bool):
        report.error(f"{context} must be an integer.")
        return None
    if minimum is not None and value < minimum:
        report.error(f"{context} must be >= {minimum}.")
        return None
    return value


def _require_number(
    value: Any,
    context: str,
    report: ValidationReport,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
) -> float | None:
    if not _is_number(value):
        report.error(f"{context} must be a number.")
        return None
    number = float(value)
    if minimum is not None and number < minimum:
        report.error(f"{context} must be >= {minimum}.")
    if maximum is not None and number > maximum:
        report.error(f"{context} must be <= {maximum}.")
    return number


def _require_string_list(
    value: Any,
    context: str,
    report: ValidationReport,
    *,
    allow_empty_items: bool = False,
    unique: bool = False,
) -> list[str] | None:
    items = _require_list(value, context, report)
    if items is None:
        return None
    strings: list[str] = []
    for index, item in enumerate(items):
        text = _require_string(
            item,
            f"{context}[{index}]",
            report,
            allow_empty=allow_empty_items,
        )
        if text is not None:
            strings.append(text)
    if unique and len(strings) != len(set(strings)):
        report.error(f"{context} must not contain duplicates.")
    return strings


def _require_timestamp(value: Any, context: str, report: ValidationReport) -> str | None:
    text = _require_string(value, context, report)
    if text is None:
        return None
    if not TIMESTAMP_PATTERN.match(text):
        report.error(f"{context} must be an ISO-8601 UTC timestamp like 2026-04-28T17:42:00Z.")
    return text


def _validate_case_id(case_id: str, context: str, report: ValidationReport) -> None:
    if not re.match(r"^[a-z0-9]+(?:-[a-z0-9]+)*$", case_id):
        report.error(
            f"{context} must be hyphen-case with lowercase letters and digits only."
        )


def _canonical_eval_file_parts(raw_path: str) -> PurePosixPath | None:
    normalized = raw_path.replace("\\", "/")
    path = PurePosixPath(normalized)
    if path.is_absolute() or ".." in path.parts:
        return None
    if len(path.parts) < 3:
        return None
    if path.parts[0] != "evals" or path.parts[1] != "files":
        return None
    return path


def _meaningful_tokens(text: str) -> set[str]:
    tokens = {
        token
        for token in re.findall(r"[a-z0-9]+", text.lower())
        if len(token) >= 3 and token not in TOKEN_STOP_WORDS
    }
    return tokens


def _load_skill_descriptor(skill_file: Path) -> SkillDescriptor | None:
    try:
        content = skill_file.read_text()
    except OSError:
        return None
    match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
    if not match:
        return None
    try:
        frontmatter, _ = parse_frontmatter(match.group(1))
    except ValueError:
        return None
    name = frontmatter.get("name", "").strip()
    description = frontmatter.get("description", "").strip()
    if not name or not description:
        return None
    name_tokens = frozenset(_meaningful_tokens(name))
    description_tokens = frozenset(_meaningful_tokens(description))
    return SkillDescriptor(
        path=skill_file.parent.resolve(),
        name=name,
        description=description,
        name_tokens=name_tokens,
        description_tokens=description_tokens,
        tokens=frozenset(name_tokens | description_tokens),
    )


def _load_skill_descriptors(skills_root: Path) -> list[SkillDescriptor]:
    descriptors: list[SkillDescriptor] = []
    for sibling_file in sorted(skills_root.glob("*/SKILL.md")):
        descriptor = _load_skill_descriptor(sibling_file)
        if descriptor is not None:
            descriptors.append(descriptor)
    return descriptors


def _token_weights(descriptors: list[SkillDescriptor]) -> dict[str, float]:
    frequencies = Counter(token for descriptor in descriptors for token in descriptor.tokens)
    total = max(len(descriptors), 1)
    return {
        token: 1.0 + math.log((total + 1) / (frequency + 1))
        for token, frequency in frequencies.items()
    }


def _weighted_overlap_ratio(
    left: set[str] | frozenset[str],
    right: set[str] | frozenset[str],
    weights: dict[str, float],
) -> float:
    if not left or not right:
        return 0.0
    shared = left & right
    if not shared:
        return 0.0
    left_weight = sum(weights.get(token, 1.0) for token in left)
    right_weight = sum(weights.get(token, 1.0) for token in right)
    denominator = min(left_weight, right_weight) or 1.0
    shared_weight = sum(weights.get(token, 1.0) for token in shared)
    return shared_weight / denominator


def _ordered_overlap_terms(shared: set[str] | frozenset[str], weights: dict[str, float]) -> list[str]:
    return sorted(shared, key=lambda token: (-weights.get(token, 1.0), token))


def _should_warn_overlap(
    *,
    weighted_overlap: float,
    description_overlap: float,
    name_overlap: float,
    name_similarity: float,
    high_signal_count: int,
) -> bool:
    return (
        (
            high_signal_count >= 3
            and description_overlap >= 0.24
            and weighted_overlap >= 0.22
        )
        or (
            name_similarity >= 0.78
            and description_overlap >= 0.30
            and high_signal_count >= 2
        )
        or (
            name_overlap >= 0.55
            and description_overlap >= 0.26
            and high_signal_count >= 2
        )
    )


def _build_overlap_finding(
    left: SkillDescriptor,
    right: SkillDescriptor,
    *,
    weights: dict[str, float],
    frequencies: Counter[str],
) -> OverlapFinding | None:
    shared = set(left.tokens & right.tokens)
    if len(shared) < 2:
        return None

    high_signal_terms = {
        token
        for token in shared
        if frequencies.get(token, 0) <= OVERLAP_HIGH_SIGNAL_MAX_FREQUENCY
    }
    weighted_overlap = _weighted_overlap_ratio(left.tokens, right.tokens, weights)
    description_overlap = _weighted_overlap_ratio(
        left.description_tokens,
        right.description_tokens,
        weights,
    )
    name_overlap = _weighted_overlap_ratio(left.name_tokens, right.name_tokens, weights)
    name_similarity = SequenceMatcher(None, left.name, right.name).ratio()
    score = max(
        weighted_overlap,
        0.65 * weighted_overlap + 0.25 * description_overlap + 0.10 * name_similarity,
        0.50 * description_overlap + 0.35 * name_overlap + 0.15 * name_similarity,
    )

    if not _should_warn_overlap(
        weighted_overlap=weighted_overlap,
        description_overlap=description_overlap,
        name_overlap=name_overlap,
        name_similarity=name_similarity,
        high_signal_count=len(high_signal_terms),
    ):
        return None

    shared_terms = _ordered_overlap_terms(shared, weights)
    shared_high_signal_terms = _ordered_overlap_terms(high_signal_terms, weights)
    if score < OVERLAP_WARNING_MIN_SCORE:
        return None

    ordered_names = sorted((left.name, right.name))
    return OverlapFinding(
        skill_a=ordered_names[0],
        skill_b=ordered_names[1],
        score=round(score, 4),
        weighted_overlap=round(weighted_overlap, 4),
        description_overlap=round(description_overlap, 4),
        name_overlap=round(name_overlap, 4),
        name_similarity=round(name_similarity, 4),
        shared_terms=tuple(shared_terms[:6]),
        shared_high_signal_terms=tuple(shared_high_signal_terms[:6]),
    )


def find_skill_overlaps(
    skills_root: Path,
    *,
    focus_skill: Path | None = None,
) -> list[OverlapFinding]:
    root = skills_root.resolve()
    descriptors = _load_skill_descriptors(root)
    if len(descriptors) < 2:
        return []

    frequencies = Counter(token for descriptor in descriptors for token in descriptor.tokens)
    weights = _token_weights(descriptors)
    focus = focus_skill.resolve() if focus_skill is not None else None
    findings: list[OverlapFinding] = []
    for index, left in enumerate(descriptors):
        for right in descriptors[index + 1 :]:
            if focus is not None and focus not in {left.path, right.path}:
                continue
            finding = _build_overlap_finding(
                left,
                right,
                weights=weights,
                frequencies=frequencies,
            )
            if finding is not None:
                findings.append(finding)
    return sorted(findings, key=lambda item: (-item.score, item.skill_a, item.skill_b))


def build_overlap_report(skills_root: Path) -> dict[str, Any]:
    findings = find_skill_overlaps(skills_root)
    return {
        "skills_root": str(skills_root.resolve()),
        "skill_count": len(_load_skill_descriptors(skills_root.resolve())),
        "findings": [
            {
                "skill_a": finding.skill_a,
                "skill_b": finding.skill_b,
                "score": finding.score,
                "weighted_overlap": finding.weighted_overlap,
                "description_overlap": finding.description_overlap,
                "name_overlap": finding.name_overlap,
                "name_similarity": finding.name_similarity,
                "shared_terms": list(finding.shared_terms),
                "shared_high_signal_terms": list(finding.shared_high_signal_terms),
            }
            for finding in findings
        ],
    }


def _audit_scope(name: str, description: str, report: ValidationReport) -> None:
    corpus = f"{name} {description}".lower()
    if any(token in corpus for token in NARROW_SCOPE_TOKENS):
        report.warn(
            "Skill scope looks tied to a specific repo subtree or file layout. "
            "Prefer AGENTS.md guidance or consolidation unless the workflow is broadly reusable."
        )


def _audit_overlap(
    skill_path: Path,
    name: str,
    description: str,
    report: ValidationReport,
    *,
    skills_root: Path | None = None,
) -> None:
    del name, description
    root = skills_root or skill_path.parent
    for finding in find_skill_overlaps(root, focus_skill=skill_path):
        sibling_name = finding.skill_b if finding.skill_a == skill_path.name else finding.skill_a
        preview_terms = finding.shared_high_signal_terms or finding.shared_terms
        preview = ", ".join(preview_terms[:5])
        report.warn(
            f"Skill scope overlaps with sibling '{sibling_name}' "
            f"(score={finding.score:.2f}, weighted_overlap={finding.weighted_overlap:.2f}, "
            f"shared terms: {preview}). Consider consolidating, clarifying boundaries, "
            "or benchmarking a consolidated candidate against legacy sibling configurations."
        )


def validate_skill_md(skill_path: Path) -> tuple[ValidationReport, dict[str, str]]:
    report = ValidationReport()
    skill_md = skill_path / "SKILL.md"
    if not skill_md.exists():
        report.error("SKILL.md not found.")
        return report, {}

    try:
        content = skill_md.read_text()
    except OSError as exc:
        report.error(f"Could not read {skill_md}: {exc}")
        return report, {}

    if not content.startswith("---"):
        report.error("SKILL.md must start with YAML frontmatter.")
        return report, {}

    match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
    if not match:
        report.error("SKILL.md frontmatter is malformed.")
        return report, {}

    try:
        frontmatter, keys = parse_frontmatter(match.group(1))
    except ValueError as exc:
        report.error(str(exc))
        return report, {}

    unexpected_keys = keys - ALLOWED_FRONTMATTER_PROPERTIES
    if unexpected_keys:
        allowed = ", ".join(sorted(ALLOWED_FRONTMATTER_PROPERTIES))
        unexpected = ", ".join(sorted(unexpected_keys))
        report.error(
            f"Unexpected key(s) in SKILL.md frontmatter: {unexpected}. Allowed properties are: {allowed}."
        )

    name = frontmatter.get("name", "")
    if not isinstance(name, str):
        report.error("Frontmatter 'name' must be a string.")
    else:
        name = name.strip()
        if not name:
            report.error("Frontmatter 'name' is required.")
        elif not re.match(r"^[a-z0-9-]+$", name):
            report.error(
                "Frontmatter 'name' must be hyphen-case with lowercase letters, digits, and hyphens only."
            )
        elif name.startswith("-") or name.endswith("-") or "--" in name:
            report.error(
                "Frontmatter 'name' cannot start/end with a hyphen or contain consecutive hyphens."
            )
        elif len(name) > MAX_SKILL_NAME_LENGTH:
            report.error(
                f"Frontmatter 'name' is too long ({len(name)} characters). "
                f"Maximum is {MAX_SKILL_NAME_LENGTH}."
            )

    description = frontmatter.get("description", "")
    if not isinstance(description, str):
        report.error("Frontmatter 'description' must be a string.")
    else:
        description = description.strip()
        if not description:
            report.error("Frontmatter 'description' is required.")
        elif "<" in description or ">" in description:
            report.error("Frontmatter 'description' cannot contain angle brackets.")
        elif len(description) > 1024:
            report.error(
                f"Frontmatter 'description' is too long ({len(description)} characters). Maximum is 1024."
            )

    return report, frontmatter


def validate_catalog_data(
    catalog: Any,
    *,
    path: Path,
    skill_dir: Path,
    expected_skill_name: str | None = None,
) -> ValidationReport:
    report = ValidationReport()
    data = _require_object(catalog, f"{path}", report)
    if data is None:
        return report

    skill_name = _require_string(data.get("skill_name"), f"{path}: skill_name", report)
    if expected_skill_name and skill_name and skill_name != expected_skill_name:
        report.error(
            f"{path}: skill_name must match SKILL.md frontmatter name '{expected_skill_name}'."
        )

    _require_int(data.get("version"), f"{path}: version", report, minimum=1)
    cases = _require_list(data.get("cases"), f"{path}: cases", report)
    if cases is None:
        return report

    seen_ids: set[str] = set()
    for index, case in enumerate(cases):
        context = f"{path}: cases[{index}]"
        case_obj = _require_object(case, context, report)
        if case_obj is None:
            continue

        case_id = _require_string(case_obj.get("id"), f"{context}.id", report)
        if case_id:
            _validate_case_id(case_id, f"{context}.id", report)
            if case_id in seen_ids:
                report.error(f"{context}.id '{case_id}' is duplicated.")
            seen_ids.add(case_id)

        _require_string(case_obj.get("title"), f"{context}.title", report)
        _require_string(case_obj.get("prompt"), f"{context}.prompt", report)
        _require_string(case_obj.get("expected_output"), f"{context}.expected_output", report)
        _require_string_list(
            case_obj.get("expectations"),
            f"{context}.expectations",
            report,
        )
        _require_string_list(
            case_obj.get("labels"),
            f"{context}.labels",
            report,
            unique=True,
        )

        stage = _require_string(case_obj.get("stage"), f"{context}.stage", report)
        if stage and stage not in ALLOWED_STAGES:
            allowed = ", ".join(sorted(ALLOWED_STAGES))
            report.error(f"{context}.stage must be one of: {allowed}.")

        files = _require_string_list(case_obj.get("files"), f"{context}.files", report)
        if files is not None:
            for file_path in files:
                parts = _canonical_eval_file_parts(file_path)
                if parts is None:
                    report.error(
                        f"{context}.files entry '{file_path}' must stay under evals/files/ and must not use absolute paths or '..'."
                    )
                    continue
                if not (skill_dir / Path(*parts.parts)).exists():
                    report.error(f"{context}.files entry '{file_path}' does not exist.")

        source = _require_object(case_obj.get("source"), f"{context}.source", report)
        if source is not None:
            _require_string(source.get("kind"), f"{context}.source.kind", report)

        promotion = _require_object(case_obj.get("promotion"), f"{context}.promotion", report)
        if promotion is not None:
            _require_number(
                promotion.get("required_pass_rate"),
                f"{context}.promotion.required_pass_rate",
                report,
                minimum=0.0,
                maximum=1.0,
            )
            _require_int(
                promotion.get("required_benchmarks"),
                f"{context}.promotion.required_benchmarks",
                report,
                minimum=1,
            )

    return report


def validate_pain_points_records(records: Any, *, path: Path) -> ValidationReport:
    report = ValidationReport()
    entries = _require_list(records, f"{path}", report)
    if entries is None:
        return report

    seen_ids: set[str] = set()
    for index, record in enumerate(entries):
        context = f"{path}: records[{index}]"
        item = _require_object(record, context, report)
        if item is None:
            continue

        record_id = _require_string(item.get("id"), f"{context}.id", report)
        if record_id:
            if record_id in seen_ids:
                report.error(f"{context}.id '{record_id}' is duplicated.")
            seen_ids.add(record_id)

        _require_string(item.get("title"), f"{context}.title", report)
        _require_string(item.get("summary"), f"{context}.summary", report)
        _require_string_list(item.get("labels"), f"{context}.labels", report, unique=True)

        source = _require_object(item.get("source"), f"{context}.source", report)
        if source is not None:
            _require_string(source.get("kind"), f"{context}.source.kind", report)

        suggested = _require_object(item.get("suggested_eval"), f"{context}.suggested_eval", report)
        if suggested is None:
            continue
        _require_string(suggested.get("prompt"), f"{context}.suggested_eval.prompt", report)
        _require_string(
            suggested.get("expected_output"),
            f"{context}.suggested_eval.expected_output",
            report,
        )
        files = _require_string_list(
            suggested.get("files"),
            f"{context}.suggested_eval.files",
            report,
        )
        if files is not None:
            for file_path in files:
                if _canonical_eval_file_parts(file_path) is None:
                    report.error(
                        f"{context}.suggested_eval.files entry '{file_path}' must stay under evals/files/."
                    )
        _require_string_list(
            suggested.get("expectations"),
            f"{context}.suggested_eval.expectations",
            report,
        )
        _require_string_list(
            suggested.get("labels"),
            f"{context}.suggested_eval.labels",
            report,
            unique=True,
        )
    return report


def validate_normalized_sessions_data(data: Any, *, path: Path) -> ValidationReport:
    report = ValidationReport()
    root = _require_object(data, f"{path}", report)
    if root is None:
        return report
    sessions = _require_list(root.get("sessions"), f"{path}: sessions", report)
    if sessions is None:
        return report
    for index, session in enumerate(sessions):
        context = f"{path}: sessions[{index}]"
        item = _require_object(session, context, report)
        if item is None:
            continue
        _require_string(item.get("session_id"), f"{context}.session_id", report)
        pain_points = item.get("pain_points", [])
        nested = validate_pain_points_records(pain_points, path=Path(f"{path}: sessions[{index}].pain_points"))
        report.extend(nested)
    return report


def validate_progression_data(
    progression: Any,
    *,
    path: Path,
    expected_skill_name: str | None = None,
) -> ValidationReport:
    report = ValidationReport()
    data = _require_object(progression, f"{path}", report)
    if data is None:
        return report

    skill_name = _require_string(data.get("skill_name"), f"{path}: skill_name", report)
    if expected_skill_name and skill_name and skill_name != expected_skill_name:
        report.error(
            f"{path}: skill_name must match SKILL.md frontmatter name '{expected_skill_name}'."
        )
    _require_timestamp(data.get("updated_at"), f"{path}: updated_at", report)

    benchmarks = _require_list(data.get("benchmarks"), f"{path}: benchmarks", report)
    if benchmarks is not None:
        for index, benchmark in enumerate(benchmarks):
            context = f"{path}: benchmarks[{index}]"
            item = _require_object(benchmark, context, report)
            if item is None:
                continue
            _require_string(item.get("benchmark_path"), f"{context}.benchmark_path", report)
            _require_timestamp(item.get("timestamp"), f"{context}.timestamp", report)
            _require_string(
                item.get("primary_configuration"),
                f"{context}.primary_configuration",
                report,
            )
            _require_bool(item.get("accepted"), f"{context}.accepted", report)
            _require_string_list(item.get("reasons"), f"{context}.reasons", report)
            stage_summary = _require_object(item.get("stage_summary"), f"{context}.stage_summary", report)
            if stage_summary is not None:
                for stage in ("candidate", "holdout", "core"):
                    summary = _require_object(
                        stage_summary.get(stage),
                        f"{context}.stage_summary.{stage}",
                        report,
                    )
                    if summary is None:
                        continue
                    _require_int(summary.get("count"), f"{context}.stage_summary.{stage}.count", report, minimum=0)
                    _require_number(
                        summary.get("mean_pass_rate"),
                        f"{context}.stage_summary.{stage}.mean_pass_rate",
                        report,
                        minimum=0.0,
                        maximum=1.0,
                    )
                    _require_number(
                        summary.get("min_pass_rate"),
                        f"{context}.stage_summary.{stage}.min_pass_rate",
                        report,
                        minimum=0.0,
                        maximum=1.0,
                    )
            promotions = _require_list(item.get("promotions"), f"{context}.promotions", report)
            if promotions is not None:
                for promo_index, promotion in enumerate(promotions):
                    promo_context = f"{context}.promotions[{promo_index}]"
                    promo = _require_object(promotion, promo_context, report)
                    if promo is None:
                        continue
                    _require_string(promo.get("case_id"), f"{promo_context}.case_id", report)
                    from_stage = _require_string(promo.get("from"), f"{promo_context}.from", report)
                    to_stage = _require_string(promo.get("to"), f"{promo_context}.to", report)
                    if from_stage and from_stage not in ALLOWED_STAGES:
                        report.error(f"{promo_context}.from must be one of: {', '.join(sorted(ALLOWED_STAGES))}.")
                    if to_stage and to_stage not in ALLOWED_STAGES:
                        report.error(f"{promo_context}.to must be one of: {', '.join(sorted(ALLOWED_STAGES))}.")
                    _require_number(
                        promo.get("pass_rate"),
                        f"{promo_context}.pass_rate",
                        report,
                        minimum=0.0,
                        maximum=1.0,
                    )

    case_history = _require_object(data.get("case_history"), f"{path}: case_history", report)
    if case_history is not None:
        for case_id, value in case_history.items():
            context = f"{path}: case_history.{case_id}"
            item = _require_object(value, context, report)
            if item is None:
                continue
            stage = _require_string(item.get("stage"), f"{context}.stage", report)
            if stage and stage not in ALLOWED_STAGES:
                report.error(f"{context}.stage must be one of: {', '.join(sorted(ALLOWED_STAGES))}.")
            _require_int(item.get("qualifying_streak"), f"{context}.qualifying_streak", report, minimum=0)
            _require_number(
                item.get("last_pass_rate"),
                f"{context}.last_pass_rate",
                report,
                minimum=0.0,
                maximum=1.0,
            )
            accepted_pass_rate = item.get("accepted_pass_rate")
            if accepted_pass_rate is not None:
                _require_number(
                    accepted_pass_rate,
                    f"{context}.accepted_pass_rate",
                    report,
                    minimum=0.0,
                    maximum=1.0,
                )
            last_accepted_benchmark = item.get("last_accepted_benchmark")
            if last_accepted_benchmark is not None:
                _require_string(
                    last_accepted_benchmark,
                    f"{context}.last_accepted_benchmark",
                    report,
                )
    return report


def validate_eval_metadata_data(data: Any, *, path: Path) -> ValidationReport:
    report = ValidationReport()
    item = _require_object(data, f"{path}", report)
    if item is None:
        return report

    eval_id = item.get("eval_id")
    if isinstance(eval_id, bool) or not isinstance(eval_id, (str, int, float)):
        report.error(f"{path}: eval_id must be a string or number.")
    elif isinstance(eval_id, str) and not eval_id.strip():
        report.error(f"{path}: eval_id must not be empty.")
    _require_string(item.get("eval_name"), f"{path}: eval_name", report)
    _require_string(item.get("prompt"), f"{path}: prompt", report)
    _require_string_list(item.get("assertions"), f"{path}: assertions", report)
    return report


def _validate_expectation_objects(value: Any, context: str, report: ValidationReport) -> None:
    items = _require_list(value, context, report)
    if items is None:
        return
    for index, item in enumerate(items):
        entry = _require_object(item, f"{context}[{index}]", report)
        if entry is None:
            continue
        _require_string(entry.get("text"), f"{context}[{index}].text", report)
        _require_bool(entry.get("passed"), f"{context}[{index}].passed", report)
        _require_string(entry.get("evidence"), f"{context}[{index}].evidence", report)


def validate_grading_data(data: Any, *, path: Path) -> ValidationReport:
    report = ValidationReport()
    item = _require_object(data, f"{path}", report)
    if item is None:
        return report

    _validate_expectation_objects(item.get("expectations"), f"{path}: expectations", report)

    summary = _require_object(item.get("summary"), f"{path}: summary", report)
    if summary is not None:
        passed = _require_int(summary.get("passed"), f"{path}: summary.passed", report, minimum=0)
        failed = _require_int(summary.get("failed"), f"{path}: summary.failed", report, minimum=0)
        total = _require_int(summary.get("total"), f"{path}: summary.total", report, minimum=0)
        pass_rate = _require_number(
            summary.get("pass_rate"),
            f"{path}: summary.pass_rate",
            report,
            minimum=0.0,
            maximum=1.0,
        )
        if passed is not None and failed is not None and total is not None and passed + failed != total:
            report.error(f"{path}: summary.total must equal summary.passed + summary.failed.")
        if passed is not None and total not in (None, 0) and pass_rate is not None:
            expected_rate = passed / total
            if abs(expected_rate - pass_rate) > 1e-9:
                report.error(f"{path}: summary.pass_rate must match summary.passed / summary.total.")

    execution_metrics = _require_object(item.get("execution_metrics"), f"{path}: execution_metrics", report)
    if execution_metrics is not None:
        tool_calls = execution_metrics.get("tool_calls")
        if tool_calls is not None and not isinstance(tool_calls, dict):
            report.error(f"{path}: execution_metrics.tool_calls must be an object when present.")
        _require_int(
            execution_metrics.get("total_tool_calls"),
            f"{path}: execution_metrics.total_tool_calls",
            report,
            minimum=0,
        )
        _require_int(
            execution_metrics.get("total_steps"),
            f"{path}: execution_metrics.total_steps",
            report,
            minimum=0,
        )
        _require_int(
            execution_metrics.get("errors_encountered"),
            f"{path}: execution_metrics.errors_encountered",
            report,
            minimum=0,
        )
        _require_int(
            execution_metrics.get("output_chars"),
            f"{path}: execution_metrics.output_chars",
            report,
            minimum=0,
        )
        _require_int(
            execution_metrics.get("transcript_chars"),
            f"{path}: execution_metrics.transcript_chars",
            report,
            minimum=0,
        )

    timing = _require_object(item.get("timing"), f"{path}: timing", report)
    if timing is not None:
        _require_number(
            timing.get("executor_duration_seconds"),
            f"{path}: timing.executor_duration_seconds",
            report,
            minimum=0.0,
        )
        _require_number(
            timing.get("grader_duration_seconds"),
            f"{path}: timing.grader_duration_seconds",
            report,
            minimum=0.0,
        )
        _require_number(
            timing.get("total_duration_seconds"),
            f"{path}: timing.total_duration_seconds",
            report,
            minimum=0.0,
        )
    return report


def _validate_stat_block(value: Any, context: str, report: ValidationReport) -> None:
    block = _require_object(value, context, report)
    if block is None:
        return
    for key in ("mean", "stddev", "min", "max"):
        _require_number(block.get(key), f"{context}.{key}", report)


def validate_benchmark_data(
    benchmark: Any,
    *,
    path: Path,
    expected_skill_name: str | None = None,
) -> ValidationReport:
    report = ValidationReport()
    data = _require_object(benchmark, f"{path}", report)
    if data is None:
        return report

    metadata = _require_object(data.get("metadata"), f"{path}: metadata", report)
    if metadata is not None:
        skill_name = _require_string(metadata.get("skill_name"), f"{path}: metadata.skill_name", report)
        if expected_skill_name and skill_name and skill_name != expected_skill_name:
            report.error(
                f"{path}: metadata.skill_name must match '{expected_skill_name}'."
            )
        _require_string(metadata.get("skill_path"), f"{path}: metadata.skill_path", report)
        _require_string(metadata.get("executor_model"), f"{path}: metadata.executor_model", report)
        _require_string(metadata.get("analyzer_model"), f"{path}: metadata.analyzer_model", report)
        _require_timestamp(metadata.get("timestamp"), f"{path}: metadata.timestamp", report)
        _require_list(metadata.get("evals_run"), f"{path}: metadata.evals_run", report)
        _require_int(
            metadata.get("runs_per_configuration"),
            f"{path}: metadata.runs_per_configuration",
            report,
            minimum=1,
        )

    runs = _require_list(data.get("runs"), f"{path}: runs", report)
    if runs is not None:
        for index, run in enumerate(runs):
            context = f"{path}: runs[{index}]"
            item = _require_object(run, context, report)
            if item is None:
                continue
            eval_id = item.get("eval_id")
            if isinstance(eval_id, bool) or not isinstance(eval_id, (str, int, float)):
                report.error(f"{context}.eval_id must be a string or number.")
            elif isinstance(eval_id, str) and not eval_id.strip():
                report.error(f"{context}.eval_id must not be empty.")
            _require_string(item.get("configuration"), f"{context}.configuration", report)
            _require_int(item.get("run_number"), f"{context}.run_number", report, minimum=1)

            result = _require_object(item.get("result"), f"{context}.result", report)
            if result is not None:
                _require_number(
                    result.get("pass_rate"),
                    f"{context}.result.pass_rate",
                    report,
                    minimum=0.0,
                    maximum=1.0,
                )
                _require_int(result.get("passed"), f"{context}.result.passed", report, minimum=0)
                _require_int(result.get("failed"), f"{context}.result.failed", report, minimum=0)
                _require_int(result.get("total"), f"{context}.result.total", report, minimum=0)
                _require_number(
                    result.get("time_seconds"),
                    f"{context}.result.time_seconds",
                    report,
                    minimum=0.0,
                )
                _require_int(result.get("tokens"), f"{context}.result.tokens", report, minimum=0)
                _require_int(result.get("tool_calls"), f"{context}.result.tool_calls", report, minimum=0)
                _require_int(result.get("errors"), f"{context}.result.errors", report, minimum=0)

            _validate_expectation_objects(item.get("expectations"), f"{context}.expectations", report)
            _require_string_list(item.get("notes"), f"{context}.notes", report, allow_empty_items=False)

    run_summary = _require_object(data.get("run_summary"), f"{path}: run_summary", report)
    if run_summary is not None:
        config_keys = [key for key in run_summary if key != "delta"]
        if not config_keys:
            report.error(f"{path}: run_summary must contain at least one configuration block.")
        for key in config_keys:
            summary = _require_object(run_summary.get(key), f"{path}: run_summary.{key}", report)
            if summary is None:
                continue
            _validate_stat_block(summary.get("pass_rate"), f"{path}: run_summary.{key}.pass_rate", report)
            _validate_stat_block(summary.get("time_seconds"), f"{path}: run_summary.{key}.time_seconds", report)
            _validate_stat_block(summary.get("tokens"), f"{path}: run_summary.{key}.tokens", report)
        delta = run_summary.get("delta")
        if delta is not None:
            delta_block = _require_object(delta, f"{path}: run_summary.delta", report)
            if delta_block is not None:
                _require_string(delta_block.get("pass_rate"), f"{path}: run_summary.delta.pass_rate", report)
                _require_string(delta_block.get("time_seconds"), f"{path}: run_summary.delta.time_seconds", report)
                _require_string(delta_block.get("tokens"), f"{path}: run_summary.delta.tokens", report)

    _require_string_list(data.get("notes"), f"{path}: notes", report)
    return report


def validate_skill_directory(
    skill_path: Path,
    *,
    audit_collection: bool = True,
    skills_root: Path | None = None,
) -> ValidationReport:
    report = ValidationReport()
    if not skill_path.exists():
        report.error(f"Skill directory not found: {skill_path}")
        return report
    if not skill_path.is_dir():
        report.error(f"Path is not a directory: {skill_path}")
        return report

    frontmatter_report, frontmatter = validate_skill_md(skill_path)
    report.extend(frontmatter_report)
    skill_name = frontmatter.get("name", "").strip()
    description = frontmatter.get("description", "").strip()
    if skill_name and skill_path.name != skill_name:
        report.error(
            f"Skill directory name '{skill_path.name}' must match SKILL.md frontmatter name '{skill_name}'."
        )

    for item in sorted(skill_path.iterdir()):
        if item.name in CANONICAL_LAYOUT_WARNINGS:
            report.warn(CANONICAL_LAYOUT_WARNINGS[item.name])
        if item.name in TRANSIENT_ROOT_FILES:
            report.warn(
                f"Transient file '{item.name}' should live in a benchmark workspace, not the skill root."
            )

    for path in sorted(skill_path.rglob("*")):
        if path.name in {".DS_Store", "__pycache__", "node_modules"}:
            continue
        rel_path = path.relative_to(skill_path)
        if path.is_dir():
            continue
        expected_location = MISPLACED_ARTIFACTS.get(path.name)
        if expected_location and rel_path.parts != expected_location:
            report.error(
                f"{rel_path} is misplaced. Use {'/'.join(expected_location)} instead."
            )

    evals_dir = skill_path / "evals"
    history_dir = skill_path / "history"
    catalog_path = evals_dir / "catalog.json"
    pain_points_path = evals_dir / "pain_points.jsonl"
    files_dir = evals_dir / "files"
    progression_path = history_dir / "progression.json"

    has_eval_contract = any(
        candidate.exists()
        for candidate in (evals_dir, history_dir, catalog_path, pain_points_path, progression_path)
    )
    if has_eval_contract:
        if not evals_dir.exists():
            report.error("history/ exists without evals/. Durable eval assets must stay together.")
        if not history_dir.exists():
            report.error("evals/ exists without history/. Durable eval assets must stay together.")
        if not catalog_path.exists():
            report.error("evals/catalog.json is required when a skill has evals/.")
        if not pain_points_path.exists():
            report.error("evals/pain_points.jsonl is required when a skill has evals/.")
        if not files_dir.exists() or not files_dir.is_dir():
            report.error("evals/files/ is required when a skill has evals/.")
        if not progression_path.exists():
            report.error("history/progression.json is required when a skill has evals/.")

    if catalog_path.exists():
        try:
            catalog_data = _load_json(catalog_path)
        except ValueError as exc:
            report.error(str(exc))
        else:
            report.extend(
                validate_catalog_data(
                    catalog_data,
                    path=catalog_path,
                    skill_dir=skill_path,
                    expected_skill_name=skill_name or None,
                )
            )
    if pain_points_path.exists():
        try:
            records = [
                json.loads(line)
                for line in pain_points_path.read_text().splitlines()
                if line.strip()
            ]
        except json.JSONDecodeError as exc:
            report.error(f"Invalid JSON in {pain_points_path}: {exc}")
        except OSError as exc:
            report.error(f"Could not read {pain_points_path}: {exc}")
        else:
            report.extend(validate_pain_points_records(records, path=pain_points_path))
    if progression_path.exists():
        try:
            progression_data = _load_json(progression_path)
        except ValueError as exc:
            report.error(str(exc))
        else:
            report.extend(
                validate_progression_data(
                    progression_data,
                    path=progression_path,
                    expected_skill_name=skill_name or None,
                )
            )

    if skill_name and description:
        _audit_scope(skill_name, description, report)
        if audit_collection:
            _audit_overlap(skill_path, skill_name, description, report, skills_root=skills_root)
    return report


def load_catalog(path: Path, *, skill_dir: Path, expected_skill_name: str | None = None) -> dict[str, Any]:
    data = _load_json(path)
    report = validate_catalog_data(
        data,
        path=path,
        skill_dir=skill_dir,
        expected_skill_name=expected_skill_name,
    )
    if report.errors:
        raise ValueError(format_report(report, f"{path} is valid."))
    return data


def load_pain_point_source(path: Path) -> list[dict[str, Any]]:
    if path.suffix == ".jsonl":
        records: list[dict[str, Any]] = []
        try:
            with path.open() as handle:
                for line_number, line in enumerate(handle, start=1):
                    text = line.strip()
                    if not text:
                        continue
                    try:
                        record = json.loads(text)
                    except json.JSONDecodeError as exc:
                        raise ValueError(f"Invalid JSON in {path} line {line_number}: {exc}") from exc
                    records.append(record)
        except OSError as exc:
            raise ValueError(f"Could not read {path}: {exc}") from exc
        report = validate_pain_points_records(records, path=path)
        if report.errors:
            raise ValueError(format_report(report, f"{path} is valid."))
        return records

    data = _load_json(path)
    if isinstance(data, dict) and isinstance(data.get("sessions"), list):
        report = validate_normalized_sessions_data(data, path=path)
        if report.errors:
            raise ValueError(format_report(report, f"{path} is valid."))
        pain_points: list[dict[str, Any]] = []
        for session in data["sessions"]:
            pain_points.extend(session.get("pain_points", []))
        return pain_points
    if isinstance(data, list):
        report = validate_pain_points_records(data, path=path)
        if report.errors:
            raise ValueError(format_report(report, f"{path} is valid."))
        return data
    raise ValueError(
        f"Unsupported pain-point source format: {path}. Use pain-point JSONL or normalized sessions JSON."
    )


def load_progression(path: Path, *, expected_skill_name: str | None = None) -> dict[str, Any]:
    data = _load_json(path)
    report = validate_progression_data(
        data,
        path=path,
        expected_skill_name=expected_skill_name,
    )
    if report.errors:
        raise ValueError(format_report(report, f"{path} is valid."))
    return data


def load_eval_metadata(path: Path) -> dict[str, Any]:
    data = _load_json(path)
    report = validate_eval_metadata_data(data, path=path)
    if report.errors:
        raise ValueError(format_report(report, f"{path} is valid."))
    return data


def load_grading(path: Path) -> dict[str, Any]:
    data = _load_json(path)
    report = validate_grading_data(data, path=path)
    if report.errors:
        raise ValueError(format_report(report, f"{path} is valid."))
    return data


def load_benchmark(path: Path, *, expected_skill_name: str | None = None) -> dict[str, Any]:
    data = _load_json(path)
    report = validate_benchmark_data(
        data,
        path=path,
        expected_skill_name=expected_skill_name,
    )
    if report.errors:
        raise ValueError(format_report(report, f"{path} is valid."))
    return data
