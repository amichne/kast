#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = ROOT / "catalog.json"
DEFAULT_PROVENANCE = ROOT / "provenance.json"
DEFAULT_CANDIDATES = ROOT / "fixtures" / "copilot-history-candidates.json"

EXPECTATION_KINDS = {"outcome", "process"}
MEASUREMENT_DIMENSIONS = {
    "task_completion",
    "accuracy",
    "reliability",
    "scope_control",
    "efficiency",
}
APPLICABILITY = {"both", "with_skill_only", "without_skill_only"}
GRADED_BY = {"script", "llm"}


def load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return payload


def canonical_case_ids(catalog: dict[str, Any]) -> list[str]:
    cases = catalog.get("cases")
    if not isinstance(cases, list):
        raise ValueError("catalog.json must contain a cases array.")
    ids: list[str] = []
    for case in cases:
        if not isinstance(case, dict):
            raise ValueError("catalog.json contains a non-object case entry.")
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id:
            raise ValueError("catalog.json contains a case without a non-empty id.")
        ids.append(case_id)
    return ids


def validate_provenance(provenance: dict[str, Any], canonical_ids: set[str]) -> list[str]:
    errors: list[str] = []
    if provenance.get("$schema") != "https://github.com/amichne/kast/evaluation/provenance.schema.json":
        errors.append("provenance.json has the wrong $schema value.")
    if provenance.get("version") != 1:
        errors.append("provenance.json version must be 1.")

    source = provenance.get("source")
    if not isinstance(source, dict):
        errors.append("provenance.json source must be an object.")
    else:
        for field in ("repository", "method", "note"):
            if not isinstance(source.get(field), str) or not str(source.get(field)).strip():
                errors.append(f"provenance.json source.{field} must be a non-empty string.")

    coverage = provenance.get("case_coverage")
    if not isinstance(coverage, list):
        errors.append("provenance.json case_coverage must be an array.")
        return errors

    seen_eval_ids: set[str] = set()
    for entry in coverage:
        if not isinstance(entry, dict):
            errors.append("case_coverage entries must be objects.")
            continue
        eval_id = entry.get("eval_id")
        if not isinstance(eval_id, str) or not eval_id:
            errors.append("case_coverage entry missing eval_id.")
            continue
        if eval_id in seen_eval_ids:
            errors.append(f"Duplicate provenance entry for {eval_id}.")
        seen_eval_ids.add(eval_id)
        if eval_id not in canonical_ids:
            errors.append(f"Provenance references unknown canonical case {eval_id}.")

        status = entry.get("status")
        if status == "matched":
            entries = entry.get("entries")
            if not isinstance(entries, list) or not entries:
                errors.append(f"{eval_id} is marked matched but has no entries.")
                continue
            for history in entries:
                if not isinstance(history, dict):
                    errors.append(f"{eval_id} contains a non-object history entry.")
                    continue
                for field in ("session_id", "created_at", "summary", "excerpt", "rationale"):
                    if not isinstance(history.get(field), str) or not str(history.get(field)).strip():
                        errors.append(f"{eval_id} history entry is missing {field}.")
        elif status == "gap":
            gap_reason = entry.get("gap_reason")
            if not isinstance(gap_reason, str) or not gap_reason.strip():
                errors.append(f"{eval_id} is marked gap but has no gap_reason.")
        else:
            errors.append(f"{eval_id} has invalid provenance status {status!r}.")

    missing = sorted(canonical_ids - seen_eval_ids)
    if missing:
        errors.append(f"provenance.json is missing canonical cases: {', '.join(missing)}")

    novel = provenance.get("novel_archetypes")
    if not isinstance(novel, list):
        errors.append("provenance.json novel_archetypes must be an array.")
    else:
        seen_novel_ids: set[str] = set()
        for entry in novel:
            if not isinstance(entry, dict):
                errors.append("novel_archetypes entries must be objects.")
                continue
            archetype_id = entry.get("id")
            if not isinstance(archetype_id, str) or not archetype_id:
                errors.append("novel_archetypes entry missing id.")
                continue
            if archetype_id in seen_novel_ids:
                errors.append(f"Duplicate novel archetype id {archetype_id}.")
            seen_novel_ids.add(archetype_id)
            for field in ("title", "summary"):
                if not isinstance(entry.get(field), str) or not str(entry.get(field)).strip():
                    errors.append(f"{archetype_id} missing {field}.")
            source_entries = entry.get("source_entries")
            if not isinstance(source_entries, list) or not source_entries:
                errors.append(f"{archetype_id} must include at least one source entry.")

    return errors


def validate_candidate_catalog(candidate: dict[str, Any], canonical_ids: set[str]) -> list[str]:
    errors: list[str] = []
    if not isinstance(candidate.get("skill_name"), str) or not str(candidate.get("skill_name")).strip():
        errors.append("candidate catalog must declare skill_name.")
    version = candidate.get("version")
    if not isinstance(version, int) or version < 1:
        errors.append("candidate catalog version must be an integer >= 1.")

    cases = candidate.get("cases")
    if not isinstance(cases, list) or not cases:
        errors.append("candidate catalog must contain a non-empty cases array.")
        return errors

    seen_case_ids: set[str] = set()
    for case in cases:
        if not isinstance(case, dict):
            errors.append("candidate case entries must be objects.")
            continue
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id:
            errors.append("candidate case missing id.")
            continue
        if case_id in seen_case_ids:
            errors.append(f"duplicate candidate case id {case_id}.")
        seen_case_ids.add(case_id)
        if case_id in canonical_ids:
            errors.append(f"candidate case id {case_id} collides with canonical catalog.")

        for field in ("title", "prompt", "expected_output"):
            if not isinstance(case.get(field), str) or not str(case.get(field)).strip():
                errors.append(f"{case_id} missing {field}.")

        labels = case.get("labels")
        if not isinstance(labels, list) or not labels:
            errors.append(f"{case_id} must declare at least one label.")

        source = case.get("source")
        if not isinstance(source, dict):
            errors.append(f"{case_id} source must be an object.")
        else:
            if source.get("kind") != "copilot_history":
                errors.append(f"{case_id} source.kind must be copilot_history.")
            if not isinstance(source.get("summary"), str) or not str(source.get("summary")).strip():
                errors.append(f"{case_id} source.summary must be non-empty.")

        expectations = case.get("expectations")
        if not isinstance(expectations, list) or not expectations:
            errors.append(f"{case_id} must declare expectations.")
            continue
        seen_expectation_ids: set[str] = set()
        for expectation in expectations:
            if not isinstance(expectation, dict):
                errors.append(f"{case_id} has a non-object expectation.")
                continue
            expectation_id = expectation.get("id")
            if not isinstance(expectation_id, str) or not expectation_id:
                errors.append(f"{case_id} expectation missing id.")
                continue
            if expectation_id in seen_expectation_ids:
                errors.append(f"{case_id} has duplicate expectation id {expectation_id}.")
            seen_expectation_ids.add(expectation_id)
            if expectation.get("kind") not in EXPECTATION_KINDS:
                errors.append(f"{case_id}/{expectation_id} has invalid kind.")
            if expectation.get("dimension") not in MEASUREMENT_DIMENSIONS:
                errors.append(f"{case_id}/{expectation_id} has invalid dimension.")
            if expectation.get("applicability") not in APPLICABILITY:
                errors.append(f"{case_id}/{expectation_id} has invalid applicability.")
            if expectation.get("graded_by") not in GRADED_BY:
                errors.append(f"{case_id}/{expectation_id} has invalid graded_by.")
            if not isinstance(expectation.get("text"), str) or not str(expectation.get("text")).strip():
                errors.append(f"{case_id}/{expectation_id} must include text.")

    return errors


def validate_assets(
    *,
    catalog_path: Path = DEFAULT_CATALOG,
    provenance_path: Path = DEFAULT_PROVENANCE,
    candidates_path: Path = DEFAULT_CANDIDATES,
) -> list[str]:
    catalog = load_json(catalog_path)
    canonical_ids = set(canonical_case_ids(catalog))
    provenance = load_json(provenance_path)
    candidates = load_json(candidates_path)
    return validate_provenance(provenance, canonical_ids) + validate_candidate_catalog(candidates, canonical_ids)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate history-backed evaluation assets.")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG, help="Path to canonical catalog.json")
    parser.add_argument("--provenance", type=Path, default=DEFAULT_PROVENANCE, help="Path to provenance.json")
    parser.add_argument(
        "--candidates",
        type=Path,
        default=DEFAULT_CANDIDATES,
        help="Path to history-derived candidate catalog JSON",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    errors = validate_assets(
        catalog_path=args.catalog,
        provenance_path=args.provenance,
        candidates_path=args.candidates,
    )
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Validated history-backed evaluation assets.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
