#!/usr/bin/env python3
"""Validate packaged skill guidance for compiler-owned mutation fast paths."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def normalize(text: str) -> str:
    return " ".join(text.split())


def require_contains(name: str, text: str, needles: list[str]) -> list[str]:
    return [f"{name}: missing {needle!r}" for needle in needles if needle not in text]


def require_phrase(name: str, text: str, phrases: list[str]) -> list[str]:
    normalized = normalize(text)
    return [f"{name}: missing phrase {phrase!r}" for phrase in phrases if normalize(phrase) not in normalized]


def require_before(name: str, text: str, first: str, second: str) -> list[str]:
    first_index = text.find(first)
    second_index = text.find(second)
    if first_index == -1:
        return [f"{name}: missing {first!r}"]
    if second_index == -1:
        return [f"{name}: missing {second!r}"]
    if first_index > second_index:
        return [f"{name}: expected {first!r} before {second!r}"]
    return []


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skill-root", type=Path, required=True)
    args = parser.parse_args()

    skill_root = args.skill_root.resolve()
    files = {
        "skill": skill_root / "SKILL.md",
        "quickstart": skill_root / "references" / "quickstart.md",
        "runbook": skill_root / "references" / "runbook.sh",
        "refactor_doc": skill_root.parents[2] / "docs" / "what-can-kast-do" / "refactor-safely.md",
        "recipes": skill_root.parents[2] / "docs" / "recipes.md",
        "agent_doc": skill_root.parents[2] / "docs" / "for-agents" / "index.md",
    }
    texts = {name: path.read_text(encoding="utf-8") for name, path in files.items()}

    failures: list[str] = []
    failures += require_contains(
        "skill",
        texts["skill"],
        [
            "Compiler-owned mutation fast path",
            "Use discovery/scaffold first for net-new code",
        ],
    )
    failures += require_phrase(
        "skill",
        texts["skill"],
        [
            "call the mutation operation directly",
            "Do not pre-run `symbol/resolve`, `symbol/references`, `symbol/callers`, or `symbol/scaffold` just to plan scope",
            "resolve or discover only enough to get a safe file+offset",
        ],
    )
    for name in ("quickstart", "runbook"):
        failures += require_contains(
            name,
            texts[name],
            [
                "Fast path: compiler-owned existing-code mutation by exact position.",
                "Do not pre-resolve or enumerate references",
                '"method":"raw/rename"',
                '"method":"symbol/rename"',
                '"type":"RENAME_BY_OFFSET_REQUEST"',
            ],
        )
        failures += require_before(name, texts[name], '"method":"raw/rename"', '"method":"symbol/resolve"')
        failures += require_before(name, texts[name], '"type":"RENAME_BY_OFFSET_REQUEST"', '"type":"RENAME_BY_SYMBOL_REQUEST"')

    failures += require_contains(
        "refactor_doc",
        texts["refactor_doc"],
        ["## Fast-path order"],
    )
    failures += require_phrase(
        "refactor_doc",
        texts["refactor_doc"],
        [
            "Do not run a separate resolve, references, callers, or scaffold pass",
            "Only resolve first when the request is name-only",
        ],
    )
    failures += require_phrase(
        "recipes",
        texts["recipes"],
        [
            "Fast path: start with `raw/rename`",
            "do not run symbol discovery first",
        ],
    )
    failures += require_phrase(
        "agent_doc",
        texts["agent_doc"],
        [
            "resolve first for identity and usage questions",
            "go straight to compiler-owned mutation methods",
        ],
    )

    checks = {
        "exact_position_rename_skips_pre_resolution": not any(
            "quickstart" in failure or "runbook" in failure for failure in failures
        ),
        "name_only_rename_keeps_minimal_anchor": "resolve or discover only enough to get a safe file+offset" in normalize(texts["skill"]),
        "net_new_edits_keep_scaffold_path": "Use discovery/scaffold first for net-new code" in texts["skill"],
        "public_docs_match_packaged_skill": not any(
            failure.startswith(("refactor_doc", "recipes", "agent_doc")) for failure in failures
        ),
    }
    print(json.dumps({"ok": not failures, "checks": checks, "failures": failures}, indent=2, sort_keys=True))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
