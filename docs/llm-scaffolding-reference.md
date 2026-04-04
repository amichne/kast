---
title: LLM scaffolding reference
description: Use this advanced page when you need explicit workspace, file,
  and offset control for the packaged `kast` skill.
---

This page is the advanced companion to
[Use Kast from an LLM agent](use-kast-from-an-llm-agent.md). Use it when you
are authoring repeatable prompt templates, building automation, or debugging a
lookup that needs explicit CLI control. The public golden path stays
human-first. This page explains the lower-level scaffolding the skill uses
under the hood.

Run `kast-skilled` once from the workspace root before you follow the examples
here. It creates a repository-local `kast` skill symlink that points back to
the single packaged skill root from `KAST_SKILL_PATH`, so the examples can use
that linked skill path without copying the skill tree.

!!! note
    The packaged skill can run `call hierarchy` after `symbol resolve`
    confirms the target symbol. Provide an incoming or outgoing direction when
    you ask for it.

## What the packaged skill scaffolds

The packaged skill narrows several implementation decisions so prompts and
automation do not guess at the CLI surface. Use this table to see what the
skill is doing before it runs the public commands.

| Layer | What the skill handles | When you need to care |
| --- | --- | --- |
| CLI discovery | Runs `bash "$SKILL_ROOT/scripts/resolve-kast.sh"` to find `kast` on `PATH`, in local build output, or in `dist/` | When the binary cannot be found or you need to reproduce the exact invocation |
| Workspace lifecycle | Runs `workspace ensure` before analysis | When a query hits a cold, indexing, or degraded workspace |
| Conversational lookup bridge | Searches for candidate declarations from a class, function, or property reference, then uses `"$SKILL_ROOT/scripts/find-symbol-offset.py"` to turn the chosen candidate into declaration-first UTF-16 offsets | When a human reference is ambiguous or you need to debug why one symbol won |
| Semantic verification | Resolves the chosen position with `symbol resolve` before it expands to `references`, `call hierarchy`, or `rename` | When the first match is not the symbol you meant |
| Failure handling | Treats stderr as daemon notes and must surface missing capabilities, `NOT_FOUND`, and truncation honestly | When automation must distinguish "no result" from "bad input" |

## Inputs the CLI still requires

The skill can hide these details from the normal reader flow, but the public
CLI still needs them. Keep them explicit when you are debugging or writing
repeatable automation.

- An absolute workspace root
- An absolute file path inside that workspace
- A zero-based UTF-16 `offset` from the start of the file
- Optional flags such as `--include-declaration=true`, `--direction=incoming`,
  or `--depth=2`
- A clear statement of what the caller must summarize from the JSON result

## Use the minimal command sequence

When you need to reproduce the packaged flow by hand, keep the command
sequence short. Resolve the binary, ensure the workspace, resolve the symbol,
and only then expand into references or call hierarchy.

```bash
SKILL_ROOT=/absolute/path/to/your/installed/kast-skill
KAST=$(bash "$SKILL_ROOT/scripts/resolve-kast.sh")
"$KAST" workspace ensure --workspace-root=/absolute/path/to/workspace
"$KAST" symbol resolve \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123
"$KAST" references \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123 \
  --include-declaration=true
"$KAST" call hierarchy \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123 \
  --direction=incoming \
  --depth=2
```

## Bridge a human reference into a CLI position

The helper script makes named symbol lookups more reliable by returning
candidate offsets with declaration-like matches first. Use it when you need to
reproduce or debug the skill's bridge from a human reference into the raw CLI
coordinates.

1. Locate the likely declaration file for the class or property.
2. Run `find-symbol-offset.py` with the symbol name.
3. Take the first result line as the best declaration candidate.
4. Verify that candidate with `symbol resolve`.
5. Reuse the same file and offset for `references`, `call hierarchy`, or
   `rename`.

Example class lookup:

```bash
rg -n --glob '*.kt' 'class HealthCheckService\\b' \
  /absolute/path/to/workspace

python "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/src/main/kotlin/com/example/HealthCheckService.kt \
  --symbol HealthCheckService
```

Example property lookup:

```bash
rg -n --glob '*.kt' '\\bval retryDelay\\b|\\bvar retryDelay\\b' \
  /absolute/path/to/workspace

python "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/src/main/kotlin/com/example/RetryConfig.kt \
  --symbol retryDelay
```

The script prints one line per candidate in this shape:

```text
<offset>\t<line>\t<col>\t<context-snippet>
```

Take the first line first. If the follow-up `symbol resolve` result does not
match the class or property you intended, move to the next candidate or add a
better containing-type hint in the human prompt.

## Read the result safely

The JSON result is structured enough for reliable summaries, but only if you
read the right fields and report their limits honestly.

- Treat `symbol.fqName` as the stable identity.
- Treat `symbol.kind` as the first guardrail against a wrong match.
- Treat declaration coordinates as navigation anchors, not as user-friendly
  prose by themselves.
- Treat `references[].preview` as a snippet, not as the full surrounding body.
- Treat `page.truncated=true` as a hard cap on the visible result set.
- Treat `stats.*Reached` and node `truncation` fields in a call hierarchy
  result as hard proof that Kast bounded the tree.

## Watch for common failure patterns

Most bad symbol or reference results come from a small set of avoidable input
mistakes and interpretation errors.

- Relative paths instead of absolute paths
- A line and column passed as though they were the raw `offset`
- An offset that lands on whitespace, comments, or string contents
- A missing capability that the caller never checked
- A `call hierarchy` request that omits direction or ignores truncation
  metadata

## Next steps

Use the human-first page when you want the default reading path again, and use
the broader CLI pages when you need command-specific details.

- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md)
- [Run analysis commands](run-analysis-commands.md)
- [Command reference](command-reference.md)
