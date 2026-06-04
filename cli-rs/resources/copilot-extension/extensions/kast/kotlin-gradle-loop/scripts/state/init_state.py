#!/usr/bin/env python3
"""Initialize or backfill the agent workflow state file.
Usage: python3 init_state.py <project_root>
Output JSON: {"ok": true, "state_file": "...", "already_existed": bool,
"migrated": bool} or {"ok": false, "error": "..."}
"""
import json
import os
import sys
from datetime import datetime, timezone

def default_state(project_root, timestamp):
    return {
        "schema_version": 2,
        "project_root": project_root,
        "created_at": timestamp,
        "updated_at": timestamp,
        "project": {
            "status": "pending",
            "boot_module": None,
            "main_class": None,
            "app_modules": [],
            "leaf_modules": [],
            "modules_with_ksp": [],
            "modules_with_kapt": [],
            "dependency_graph": {},
            "jdk_version": None,
            "kotlin_version": None,
            "gradle_version": None,
            "gradleHook": None,
            "has_buildsrc": False,
            "has_included_builds": False,
            "source_sets": {},
            "errors": [],
        },
        "goal": {
            "description": "",
            "constraints": [],
            "acceptance_criteria": [],
        },
        "gradle": {
            "last_build": None,
            "configuration_cache_compatible": None,
            "build_cache_enabled": None,
        },
        "tests": {
            "status": "pending",
            "total": 0,
            "passed": 0,
            "failed": 0,
            "skipped": 0,
            "duration_seconds": 0,
            "failures": [],
            "timestamp": None,
        },
        "coverage": {
            "status": "pending",
            "line_percent": None,
            "branch_percent": None,
            "class_percent": None,
            "method_percent": None,
            "covered_lines": 0,
            "missed_lines": 0,
            "total_lines": 0,
            "lowest_coverage_classes": [],
            "timestamp": None,
        },
        "compilation": {
            "status": "pending",
            "incremental_modules": [],
            "non_incremental_modules": [],
            "non_incremental_reasons": {},
            "timestamp": None,
        },
        "history": [],
    }


def merge_defaults(current, defaults):
    changed = False
    merged = dict(current)

    for key, value in defaults.items():
        if key not in merged:
            merged[key] = value
            changed = True
            continue

        if isinstance(value, dict):
            if isinstance(merged[key], dict):
                merged_child, child_changed = merge_defaults(merged[key], value)
                if child_changed:
                    merged[key] = merged_child
                    changed = True
            else:
                # Existing value is not a dict (e.g. null from corruption);
                # replace it with the full default subtree.
                merged[key] = value
                changed = True

    return merged, changed


def write_state(state_file, state):
    with open(state_file, "w") as handle:
        json.dump(state, handle, indent=2)


def main():
    if len(sys.argv) < 2:
        json.dump(
            {"ok": False, "error": "Usage: init_state.py <project_root>"},
            sys.stdout,
        )
        sys.exit(1)

    project_root = os.path.abspath(sys.argv[1])
    if not os.path.isdir(project_root):
        json.dump(
            {"ok": False, "error": f"Not a directory: {project_root}"},
            sys.stdout,
        )
        sys.exit(1)

    has_gradle = any(
        os.path.exists(os.path.join(project_root, filename))
        for filename in [
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle",
        ]
    )
    if not has_gradle:
        json.dump(
            {
                "ok": False,
                "error": f"No Gradle build files found in {project_root}",
            },
            sys.stdout,
        )
        sys.exit(1)

    workflow_dir = os.path.join(project_root, ".agent-workflow")
    state_file = os.path.join(workflow_dir, "state.json")
    os.makedirs(os.path.join(workflow_dir, "logs"), exist_ok=True)

    now = datetime.now(timezone.utc).isoformat()
    defaults = default_state(project_root, now)

    if os.path.exists(state_file):
        try:
            with open(state_file) as handle:
                state = json.load(handle)
        except json.JSONDecodeError as exc:
            json.dump(
                {
                    "ok": False,
                    "error": f"Invalid JSON in {state_file}: {exc}",
                },
                sys.stdout,
            )
            sys.exit(1)

        merged, changed = merge_defaults(state, defaults)
        if merged.get("schema_version", 0) < 2:
            merged["schema_version"] = 2
            changed = True
        if changed:
            merged["updated_at"] = now
            write_state(state_file, merged)
        json.dump(
            {
                "ok": True,
                "state_file": state_file,
                "already_existed": True,
                "migrated": changed,
            },
            sys.stdout,
        )
        return

    write_state(state_file, defaults)
    json.dump(
        {
            "ok": True,
            "state_file": state_file,
            "already_existed": False,
            "migrated": False,
        },
        sys.stdout,
    )

if __name__ == "__main__":
    main()
