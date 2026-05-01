#!/usr/bin/env python3
"""Merge pain-point records into evals/catalog.json as candidate cases."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

try:
    from .validation import (
        format_report,
        load_catalog,
        load_pain_point_source,
        validate_catalog_data,
    )
except ImportError:
    from validation import (
        format_report,
        load_catalog,
        load_pain_point_source,
        validate_catalog_data,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge pain-point records into a skill's eval catalog.",
    )
    parser.add_argument("--catalog", required=True, help="Path to evals/catalog.json")
    parser.add_argument(
        "sources",
        nargs="+",
        help="Pain-point JSONL files or normalized session JSON produced by ingest_copilot_events.py",
    )
    return parser.parse_args()


def slugify(value: str, fallback: str) -> str:
    text = value.strip().lower()
    text = re.sub(r"[^a-z0-9]+", "-", text)
    text = text.strip("-")
    text = re.sub(r"-{2,}", "-", text)
    return text[:64] or fallback


def make_case_id(existing_ids: set[str], pain_point: dict[str, Any], prompt: str) -> str:
    preferred = pain_point.get("id") or pain_point.get("title") or prompt
    base = slugify(str(preferred), "candidate")
    if base not in existing_ids:
        return base
    suffix = 2
    while f"{base}-{suffix}" in existing_ids:
        suffix += 1
    return f"{base}-{suffix}"


def title_from_prompt(prompt: str) -> str:
    prompt = " ".join(prompt.split())
    if not prompt:
        return "Candidate pain point"
    return prompt[:80]


def to_case(existing_ids: set[str], pain_point: dict[str, Any]) -> dict[str, Any] | None:
    suggested = pain_point.get("suggested_eval", {})
    prompt = suggested.get("prompt") or pain_point.get("summary") or ""
    if not isinstance(prompt, str) or not prompt.strip():
        return None

    labels = set()
    for source in (pain_point.get("labels", []), suggested.get("labels", [])):
        if isinstance(source, list):
            labels.update(str(label).strip() for label in source if str(label).strip())
    labels.add("candidate")

    return {
        "id": make_case_id(existing_ids, pain_point, prompt),
        "title": pain_point.get("title") or title_from_prompt(prompt),
        "prompt": prompt,
        "files": suggested.get("files", []),
        "expected_output": suggested.get("expected_output", ""),
        "expectations": suggested.get("expectations", []),
        "labels": sorted(labels),
        "stage": "candidate",
        "source": pain_point.get("source", {"kind": "pain_point"}),
        "promotion": {
            "required_pass_rate": pain_point.get("promotion", {}).get("required_pass_rate", 1.0),
            "required_benchmarks": pain_point.get("promotion", {}).get("required_benchmarks", 2),
        },
    }


def case_signature(case: dict[str, Any]) -> tuple[str, tuple[str, ...]]:
    prompt = str(case.get("prompt", "")).strip()
    files = tuple(str(path) for path in case.get("files", []))
    return prompt, files


def main() -> None:
    args = parse_args()
    catalog_path = Path(args.catalog).expanduser().resolve()
    skill_dir = catalog_path.parent.parent

    try:
        catalog = load_catalog(catalog_path, skill_dir=skill_dir)
    except ValueError as exc:
        print(exc)
        sys.exit(1)

    cases = catalog["cases"]
    existing_ids = {str(case.get("id")) for case in cases}
    existing_signatures = {case_signature(case) for case in cases}

    added = 0
    skipped = 0
    try:
        for raw_source in args.sources:
            source_path = Path(raw_source).expanduser().resolve()
            pain_points = load_pain_point_source(source_path)
            for pain_point in pain_points:
                case = to_case(existing_ids, pain_point)
                if case is None:
                    skipped += 1
                    continue
                signature = case_signature(case)
                if signature in existing_signatures:
                    skipped += 1
                    continue
                cases.append(case)
                existing_ids.add(case["id"])
                existing_signatures.add(signature)
                added += 1
    except ValueError as exc:
        print(exc)
        sys.exit(1)

    catalog["cases"] = sorted(cases, key=lambda case: (case.get("stage", ""), case.get("id", "")))
    catalog["version"] = int(catalog.get("version", 0) or 0) + 1

    validation = validate_catalog_data(
        catalog,
        path=catalog_path,
        skill_dir=skill_dir,
        expected_skill_name=str(catalog.get("skill_name", "")).strip() or None,
    )
    if validation.errors:
        print(format_report(validation, f"{catalog_path} is valid."))
        sys.exit(1)

    catalog_path.write_text(json.dumps(catalog, indent=2) + "\n")

    print(f"Added {added} candidate case(s). Skipped {skipped}.")
    print(f"Updated catalog: {catalog_path}")


if __name__ == "__main__":
    main()
