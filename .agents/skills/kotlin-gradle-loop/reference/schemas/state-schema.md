# Workflow State Schema

The workflow state is a single JSON file that tracks a Kotlin/Gradle project's
structure, the agent's current goal, action history, and the latest results
from Gradle builds, JUnit tests, JaCoCo coverage, and Kotlin build reports.

## File Location

`<project_root>/.agent-workflow/state.json`

The `.agent-workflow/` directory also holds log files from script executions.

## Completion hooks

The repo-local `kotlin-gradle-loop/hooks.json` file defines two mandatory
completion hooks:

- `docs-writer-completion`: invoke `docs-writer` whenever Markdown changes.
- `build-health-completion`: run `scripts/gradle/run_gradle_hook.sh`, which
  reads `project.gradleHook` from `state.json`.

`project.gradleHook` is therefore a required discovery output for every
project that uses this skill.

## Schema

```json
{
  "schema_version": 2,
  "project_root": "/absolute/path/to/project",
  "created_at": "ISO-8601",
  "updated_at": "ISO-8601",

  "project": {
    "status": "pending | complete | failed",
    "boot_module": ":app",
    "main_class": "com.example.Application",
    "app_modules": [":feature-a", ":feature-b", ":core"],
    "leaf_modules": [":feature-a", ":feature-b"],
    "modules_with_ksp": [":feature-a"],
    "modules_with_kapt": [],
    "dependency_graph": {
      ":feature-a": [":core"],
      ":feature-b": [":core", ":feature-a"]
    },
    "jdk_version": 21,
    "kotlin_version": "2.0.0",
    "gradle_version": "8.10",
    "gradleHook": "check",
    "has_buildsrc": true,
    "has_included_builds": false,
    "source_sets": {
      ":feature-a": {"main": 45, "test": 23}
    },
    "errors": []
  },

  "goal": {
    "description": "Free-text description of what the agent is working toward",
    "constraints": ["do not delete tests", "do not reduce coverage"],
    "acceptance_criteria": ["tests.failed == 0", "coverage.line_percent >= 80"]
  },

  "gradle": {
    "last_build": {
      "task": "test",
      "exit_code": 0,
      "duration_ms": 12340,
      "tasks_executed": 42,
      "tasks_up_to_date": 30,
      "tasks_from_cache": 5,
      "build_successful": true,
      "failure_summary": null,
      "log_file": "/path/to/log",
      "timestamp": "ISO-8601"
    },
    "configuration_cache_compatible": null,
    "build_cache_enabled": null
  },

  "tests": {
    "status": "pending | passing | failing | error",
    "total": 142,
    "passed": 138,
    "failed": 3,
    "skipped": 1,
    "duration_seconds": 45.2,
    "failures": [
      {
        "class": "com.example.UserServiceTest",
        "method": "testCreateUser",
        "module": ":feature-users",
        "message": "Expected 200 but got 500",
        "type": "AssertionError",
        "stacktrace_head": "first 5 lines"
      }
    ],
    "timestamp": "ISO-8601"
  },

  "coverage": {
    "status": "pending | measured | error",
    "line_percent": 78.5,
    "branch_percent": 62.3,
    "class_percent": 91.0,
    "method_percent": 85.2,
    "covered_lines": 12340,
    "missed_lines": 3400,
    "total_lines": 15740,
    "lowest_coverage_classes": [],
    "timestamp": "ISO-8601"
  },

  "compilation": {
    "status": "pending | healthy | degraded | error",
    "incremental_modules": [],
    "non_incremental_modules": [],
    "non_incremental_reasons": {},
    "timestamp": "ISO-8601"
  },

  "history": [
    {
      "timestamp": "ISO-8601",
      "action": "ran_gradle_task",
      "detail": {"task": "test", "exit_code": 0},
      "outcome": "tests passed: 142/142",
      "state_change": "tests.status: failing -> passing"
    }
  ]
}
```

## Status Fields

- `project.status`: Whether project discovery is done.
- `project.gradleHook`: The single existing Gradle task that the build-health
  hook must run before the agent finishes.
- `tests.status`: `pending` (never run), `passing` (all pass), `failing` (some fail), `error` (couldn't run).
- `coverage.status`: `pending` (never run), `measured` (report parsed), `error` (couldn't generate).
- `compilation.status`: `pending` (never analyzed), `healthy` (all incremental), `degraded` (some non-incremental), `error`.

## History

The `history` array is append-only, bounded to 50 entries. Each entry records
what the agent did, what happened, and what state transition resulted. This
provides context about prior iterations so the agent does not repeat failed
approaches.

## Error Structure

Each entry in an `errors` array:

```json
{
  "timestamp": "ISO-8601",
  "message": "Human-readable description",
  "detail": "Stack trace or diagnostic output",
  "recoverable": true,
  "suggested_action": "Description of what to try next"
}
```
