---
name: kotlin-gradle-loop
description: >
  Autonomous CLI workflow for iterating on Kotlin/Gradle/JVM projects. Covers the full
  loop: codebase discovery, Gradle task execution, JUnit/JaCoCo report parsing, Kotlin
  incremental compilation analysis, and continuous goal-oriented validation. Use this skill
  whenever the user asks to run and analyze Gradle builds, fix failing tests, improve code
  coverage, diagnose compilation performance, analyze JUnit or JaCoCo results, or iterate
  on any Kotlin/Gradle project toward a defined goal. Also trigger when the user references
  build reports, test results, coverage reports, incremental compilation, or asks to "get
  the build green" or "fix the tests." This skill manages persistent state across iterations
  and determines the next action autonomously based on script output.
---

# Kotlin/Gradle Agentic Workflow

## Core Principle

**Never interact with raw terminal output.** Every interaction with Gradle, JUnit,
JaCoCo, or the Kotlin compiler goes through a script in `scripts/`. Every script
produces structured JSON on stdout. Raw output goes to log files. The agent reads
only the JSON. If the JSON indicates a failure, the agent reads the `log_file` path
for diagnostics — it never parses Gradle console output directly.

## Directory Layout

```
kotlin-gradle-loop/
├── SKILL.md                              # This file
├── hooks.json                            # Mandatory completion-hook manifest
├── scripts/
│   ├── state/
│   │   ├── init_state.py                 # Initialize .agent-workflow/state.json
│   │   ├── get_state.py                  # Read state (full, section, summary, history)
│   │   ├── update_state.py               # Set a state field
│   │   └── record_action.py              # Append to action history
│   ├── gradle/
│   │   ├── run_task.sh                   # Run any Gradle task → structured JSON
│   │   └── run_gradle_hook.sh            # Run the required project.gradleHook
│   └── parse/
│       ├── junit_results.py              # Parse JUnit XML → structured JSON
│       ├── jacoco_report.py              # Parse JaCoCo XML → structured JSON
│       └── kotlin_build_report.py        # Parse Kotlin build reports → structured JSON
└── references/
    └── schemas/
        └── state-schema.md               # Full state file schema documentation
```

## Getting Started

On first invocation, initialize the state file, then check status:

```bash
python3 scripts/state/init_state.py /path/to/project
python3 scripts/state/get_state.py /path/to/project --summary
```

If `project_discovered` is `false`, discover the project before anything else.
Discovery is an agent task — search the codebase, then record findings via
`update_state.py`. See the Discovery section below.

Re-run `init_state.py` after skill updates when you need to backfill newly
introduced defaults such as `project.gradleHook`.

## The Iteration Loop

The agent's core execution pattern is goal-oriented:

```
1. Read current state       →  get_state.py --summary
2. Compare against goal     →  Are acceptance criteria met?
3. Decide next action       →  What is the highest-value next step?
4. Execute via script       →  run_task.sh, junit_results.py, etc.
5. Parse the JSON result    →  Read the structured output
6. Update state             →  update_state.py + record_action.py
7. Go to 1
```

This continues until the goal is met or the agent cannot make further progress.
Never retry the same action more than 3 times without changing something.

### Setting a Goal

```bash
python3 scripts/state/update_state.py /project goal \
  '{"description":"Get all tests passing and line coverage above 80%",
    "constraints":["do not delete tests","do not reduce coverage"],
    "acceptance_criteria":["tests.failed == 0","coverage.line_percent >= 80"]}'
```

## Discovery

Discovery is the first task on a new project. It requires reading build files and
source code directly, then recording findings.

### What to Discover

Read `settings.gradle.kts` (or `settings.gradle`) for all `include` statements.
For each subproject, read its `build.gradle.kts` to determine:

1. **Plugins applied:** `org.springframework.boot` (boot module), `org.jetbrains.kotlin.jvm`
   (Kotlin modules), `kotlin-kapt`/`com.google.devtools.ksp` (annotation processing),
   `java-library` (exposes `api` config), `jacoco` (coverage configured).

2. **Inter-module dependencies.** Record every `implementation(project(...))` and
   `api(project(...))` edge. Build the dependency graph.

3. **Leaf modules.** Modules nothing depends on — cheapest to change.

4. **Versions.** JDK: `java.toolchain.languageVersion`, `jvmTarget`, `.java-version`.
   Kotlin: `plugins { kotlin("jvm") version "..." }` or version catalog.
   Gradle: `gradle/wrapper/gradle-wrapper.properties`.

5. **Source file counts.** Per module, count `.kt`/`.java` files in `src/main` and `src/test`.

6. **High-signal validation task.** Record the single existing Gradle task that
   gives the strongest health signal for the repo as `project.gradleHook`.
   Prefer `check` when it meaningfully covers the project. If `check` is too
   weak or too broad, use the best existing task such as `:app:check`, `build`,
   or a custom verification task.

### Recording Discovery

```bash
python3 scripts/state/update_state.py /project project '{
  "status": "complete",
  "boot_module": ":app",
  "app_modules": [":app", ":core", ":feature-users", ":feature-payments"],
  "leaf_modules": [":feature-users", ":feature-payments"],
  "dependency_graph": {
    ":feature-users": [":core"],
    ":feature-payments": [":core"],
    ":core": [],
    ":app": [":core", ":feature-users", ":feature-payments"]
  },
  "jdk_version": 21,
  "kotlin_version": "2.0.0",
  "gradle_version": "8.10",
  "gradleHook": "check"
}'

python3 scripts/state/record_action.py /project discovered_project \
  '{"modules":4,"leaf_modules":2}' \
  "Discovered 4 modules, 2 leaf modules, JDK 21, Kotlin 2.0.0"
```

## Running Gradle Tasks

All Gradle invocations go through `scripts/gradle/run_task.sh`:

```bash
bash scripts/gradle/run_task.sh /project test
bash scripts/gradle/run_task.sh /project :feature-users:test
bash scripts/gradle/run_task.sh /project :feature-users:test --tests "com.example.UserServiceTest"
bash scripts/gradle/run_task.sh /project compileKotlin
bash scripts/gradle/run_task.sh /project test --configuration-cache
bash scripts/gradle/run_task.sh /project test -Pkotlin.build.report.output=file
bash scripts/gradle/run_gradle_hook.sh /project
```

Returns JSON with `ok`, `exit_code`, `duration_ms`, `tasks_executed`,
`tasks_up_to_date`, `tasks_from_cache`, `build_successful`, `test_task_detected`,
`failure_summary`, and `log_file`.

After running, update the state:

```bash
python3 scripts/state/update_state.py /project gradle.last_build \
  '{"task":"test","exit_code":0,"duration_ms":12000,"build_successful":true}'
```

Use `run_gradle_hook.sh` for the mandatory final build-health check. It reads
the configured `project.gradleHook` from `.agent-workflow/state.json` and then
delegates to `run_task.sh`, so it returns the same structured JSON shape.

## Mandatory completion hooks

This skill ships a machine-readable hook manifest in `hooks.json`. Satisfy both
hooks before you end work.

### `docs-writer` completion hook

If you changed any Markdown file, invoke `docs-writer` before you finish. Treat
`docs/**`, repository `*.md`, and skill Markdown as documentation scope. Do not
end the turn with unreviewed Markdown changes.

### Build-health completion hook

During discovery, set `project.gradleHook` to the single highest-signal Gradle
task for the project. Before you finish, run:

```bash
bash scripts/gradle/run_gradle_hook.sh /project
```

If `project.gradleHook` is unset or the hook fails, keep iterating or report
the blocker. Never end work with an unsatisfied build-health hook.

## Parsing Results

### JUnit Results

After any test task:

```bash
python3 scripts/parse/junit_results.py /project
python3 scripts/parse/junit_results.py /project --module :feature-users
```

Returns `total`, `passed`, `failed`, `skipped`, `duration_seconds`, and `failures`
(with `class`, `method`, `module`, `message`, `type`, `stacktrace_head`).

### JaCoCo Coverage

After `jacocoTestReport`:

```bash
python3 scripts/parse/jacoco_report.py /project
python3 scripts/parse/jacoco_report.py /project --threshold 80.0
python3 scripts/parse/jacoco_report.py /project --module :feature-users
```

Returns `aggregate` (line/branch/class/method percentages), `modules`,
`lowest_coverage_classes`, and optionally `meets_threshold`.

### Kotlin Build Reports

After any compilation:

```bash
python3 scripts/parse/kotlin_build_report.py /project
```

Returns `compilations` (per-module incremental status), `non_incremental_modules`,
and `non_incremental_reasons`.

## Standard Workflows

### Get Tests Passing

```
1. bash scripts/gradle/run_task.sh /project test
2. python3 scripts/parse/junit_results.py /project
3. For each failure: read stacktrace_head, read source, determine fix
4. Apply fix
5. bash scripts/gradle/run_task.sh /project :module:test --tests "FailingTest"
6. python3 scripts/parse/junit_results.py /project --module :module
7. If still failing → go to 3
8. When targeted tests pass → run full suite (step 1)
9. If new failures appeared (regression) → go to 3
10. Update state: tests.status → "passing"
```

### Improve Coverage

```
1. bash scripts/gradle/run_task.sh /project jacocoTestReport
2. python3 scripts/parse/jacoco_report.py /project --threshold 80.0
3. If below threshold: examine lowest_coverage_classes
4. Read each low-coverage class, identify untested paths, write tests
5. Repeat from step 1 until threshold met
```

### Diagnose Build Performance

```
1. bash scripts/gradle/run_task.sh /project compileKotlin -Pkotlin.build.report.output=file
2. python3 scripts/parse/kotlin_build_report.py /project
3. If non_incremental_modules non-empty: examine reasons
4. Common fixes: change api() to implementation(), fix annotation processor config
5. Record findings in compilation state
```

## Handling Failures

When `run_task.sh` returns `{"ok": false, ...}`:

1. Read `failure_summary` for quick diagnosis.
2. If more detail needed, read `log_file` with the `view` tool. Search for
   `FAILURE:`, `BUILD FAILED`, or error class names.
3. Categorize: compilation error, test failure, configuration error, infrastructure error.
4. Record in history:
   ```bash
   python3 scripts/state/record_action.py /project gradle_failed \
     '{"task":"test","exit_code":1}' "Compilation error in UserService.kt"
   ```
5. Fix and retry, or report if not recoverable.

## Avoiding Regressions

Before considering any change complete:

1. Run `bash scripts/gradle/run_gradle_hook.sh /project`. This is the required
   final build-health hook.
2. If the hook includes test tasks, parse JUnit and compare
   `total`/`passed`/`failed` against previous state. If `failed` increased, the
   change introduced a regression.
3. Parse coverage and compare `line_percent`. If it decreased and the goal
   has a coverage constraint, the change may violate it.
4. If any Markdown changed, invoke `docs-writer` before you finish.

The state file holds previous values; parser output holds current values.
The agent must compare before updating state.

## When to Read the Reference Document

Read `references/schemas/state-schema.md` when:
- First initializing a project (to understand what fields to populate)
- Debugging unexpected state (to verify field semantics)
- Adding custom state fields for project-specific tracking
