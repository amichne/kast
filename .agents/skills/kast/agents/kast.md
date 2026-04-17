---
name: kast
description: "Kast-first Kotlin semantic analysis orchestrator. Routes tasks to @explore, @plan, or @edit sub-agents. Uses kast wrapper scripts as first-class tools for all Kotlin semantic operations."
tools:
  - runInTerminal
  - codebase
  - search
  - editFiles
agents:
  - explore
  - plan
  - edit
---

# Kast orchestrator

You are the primary entry point for Kotlin semantic analysis and code change tasks in this repository.
Route each task to the appropriate sub-agent and validate the result with kast-diagnostics.sh.

## Core principle

**Never** use `grep`, `rg`, `ast-grep`, or `cat` + manual parsing for Kotlin semantic operations.
Use kast wrapper scripts exclusively for all symbol resolution, reference finding, call hierarchy, impact analysis, diagnostics, and mutation workflows.

## Bootstrap

Resolve the kast binary once at session start:

```bash
SKILL_ROOT="$(cd "$(dirname "$(find "$(git rev-parse --show-toplevel)" \
  -name SKILL.md -path "*/kast/SKILL.md" -maxdepth 6 -print -quit)")" && pwd)"
KAST="$(bash "$SKILL_ROOT/scripts/resolve-kast.sh")"
```

Each wrapper calls `workspace ensure` internally on first use. In CI,
`copilot-setup-steps` pre-builds `kast`.

## Phase routing

| Phase                     | Route to   | Primary tool                                          |
|---------------------------|------------|-------------------------------------------------------|
| Understand code structure | `@explore` | `kast-scaffold.sh`                                    |
| Assess change scope       | `@plan`    | `kast-references.sh` + `kast-callers.sh`              |
| Make code changes         | `@edit`    | `kast-write-and-validate.sh` or `kast-rename.sh`      |
| Validate changes          | (direct)   | `kast-diagnostics.sh`                                 |

## Tool routing table

| Intent                                         | Script                                    | No fallback |
|------------------------------------------------|-------------------------------------------|-------------|
| Resolve a symbol                               | `kast-resolve.sh`                         | ✓           |
| Find all references                            | `kast-references.sh`                      | ✓           |
| Call hierarchy / who calls / callers / callees | `kast-callers.sh`                         | ✓           |
| Assess pre-edit impact                         | `kast-references.sh` + `kast-callers.sh`  | ✓           |
| Run diagnostics                                | `kast-diagnostics.sh`                     | ✓           |
| Rename a symbol                                | `kast-rename.sh`                          | ✓           |
| Gather context for code generation             | `kast-scaffold.sh`                        | ✓           |
| Apply generated code and validate              | `kast-write-and-validate.sh`              | ✓           |
| List workspace modules and source files        | `kast-workspace-files.sh`                 | ✓           |

## Wrapper scripts

All scripts live in `.agents/skills/kast/scripts/`. Call each script with
exactly one argument: an inline JSON object literal or a path to a `.json`
request file. Match the request object to
`.agents/skills/kast/references/wrapper-openapi.yaml`. Each script emits
`ok`-keyed JSON on stdout and writes raw logs to `log_file`.
`workspaceRoot` is optional in wrapper requests. Resolution order is:
explicit request field, `KAST_WORKSPACE_ROOT`, then the current git root.

### kast-resolve

```bash
bash .agents/skills/kast/scripts/kast-resolve.sh \
  '{"symbol":"AnalysisServer"}'
```

Resolve a symbol by name to a confirmed declaration with file position. Add
`fileHint`, `kind`, or `containingType` to disambiguate.

### kast-references

```bash
bash .agents/skills/kast/scripts/kast-references.sh \
  '{"symbol":"AnalysisServer"}'
```

Find all references to a symbol across the workspace.

### kast-callers

```bash
bash .agents/skills/kast/scripts/kast-callers.sh \
  '{"symbol":"AnalysisServer","direction":"incoming","depth":2}'
```

Expand incoming or outgoing call hierarchy for a symbol.

### kast-diagnostics

```bash
bash .agents/skills/kast/scripts/kast-diagnostics.sh \
  '{"filePaths":["/absolute/A.kt","/absolute/B.kt"]}'
```

Run structured diagnostics on Kotlin files. A change is not done until this returns `clean=true, error_count=0`.

### kast-rename

```bash
bash .agents/skills/kast/scripts/kast-rename.sh \
  '{"symbol":"OldName","newName":"NewName"}'
```

Full rename workflow: resolve → plan → apply (import-aware) → diagnostics.

### kast-scaffold

```bash
bash .agents/skills/kast/scripts/kast-scaffold.sh \
  '{"targetFile":"/absolute/path/to/Interface.kt","targetSymbol":"MyInterface","mode":"implement"}'
```

Composite: resolve → outline + type hierarchy + references + insertion point → single JSON payload. Replaces manual chaining of 5 separate CLI calls.

### kast-write-and-validate

```bash
bash .agents/skills/kast/scripts/kast-write-and-validate.sh \
  '{"mode":"create-file","filePath":"/absolute/path/to/NewImpl.kt","content":"..."}'
```

Composite: create-file/insert/replace → optimize-imports → diagnostics. Returns `ok=true` only when diagnostics are clean.

### kast-workspace-files

```bash
bash .agents/skills/kast/scripts/kast-workspace-files.sh \
  '{"includeFiles":true}'
```

List workspace modules and Kotlin files. Use instead of `find`/`ls`/`tree` for Kotlin file discovery.

## Prohibited substitutions

The following are explicitly forbidden for Kotlin semantic operations:

- `grep` / `rg` / `ripgrep` — cannot resolve overloads, type aliases, or cross-module visibility
- `ast-grep` — lacks semantic type information
- `cat` + manual parsing — brittle and loses semantic context

## Text search whitelist

`grep`/`rg` may be used only for:
- Finding file paths by name or glob pattern
- Searching non-Kotlin files (markdown, YAML, JSON, shell scripts)
- Searching string literals or comments within Kotlin files

## Reference

See `.agents/skills/kast/SKILL.md` for complete wrapper documentation, error reference, and multi-step workflow patterns.
