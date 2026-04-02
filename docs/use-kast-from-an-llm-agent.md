---
title: Use Kast from an LLM agent
description: Resolve Kotlin symbols and references through the packaged
  `kast` skill and its CLI discovery workflow.
icon: lucide/book-open
---

This guide explains how to use the packaged `kast` skill when an LLM agent
needs semantic Kotlin lookup. The workflow is intentionally narrow: discover
the CLI, ensure the workspace daemon, resolve the symbol, and then find
references from the same workspace.

!!! note
    The current packaged skill does not support `callHierarchy`. Use
    `symbol resolve` and `references` for semantic navigation today.

## What the packaged skill owns

The packaged skill removes a few operator decisions from the prompt. That
matters because LLM workflows become unreliable when they hardcode binary
paths, skip daemon startup, or guess at result semantics.

| Skill behavior | Why it matters |
| --- | --- |
| Resolves the CLI with `bash .agents/skills/kast/scripts/resolve-kast.sh` | Avoids hardcoded binary paths and lets the skill find `kast` on `PATH`, in local build output, or through a one-time wrapper build |
| Runs `workspace ensure` before analysis | Makes sure symbol and reference queries hit a ready daemon instead of a cold workspace |
| Uses `--key=value` syntax and absolute paths | Keeps requests compatible with the public CLI contract |
| Treats stdout JSON as the result and stderr as daemon notes | Prevents the agent from mixing control-plane notes into the semantic output |
| Handles common failures such as `NOT_FOUND` and missing capabilities | Keeps the agent from reporting empty or misleading semantic results |

## What you need to give the agent

The skill still needs a small amount of concrete input from you. If you keep
these values explicit, the agent can run deterministically and report the
result without guessing.

| Input | Required | Example | Notes |
| --- | --- | --- | --- |
| Workspace root | Yes | `/absolute/path/to/workspace` | Must be the Kotlin workspace root that Kast should index |
| File path | Yes | `/absolute/path/to/src/main/kotlin/sample/Use.kt` | Must be an absolute path inside the workspace |
| Offset | Yes | `41` | Zero-based UTF-16 character offset from the start of the file |
| Include declaration | References only | `true` | Returns the declaration in the same payload as the usage list |
| Output expectation | Recommended | `return fqName, kind, declaration path, and callers` | Helps the agent summarize the JSON instead of pasting it back at you |

!!! note
    If you only know a line and column, convert them to a zero-based UTF-16
    offset before you ask the agent to run Kast. Line and column coordinates
    are not accepted by the CLI.

## Ask the agent to resolve a symbol

Use `symbol resolve` first when you want to confirm what a token refers to
before you ask broader questions. This is the safest way to anchor the rest of
the workflow on the correct declaration.

Example prompt for the agent:

```text
Use the packaged `kast` skill to resolve the symbol at
/absolute/path/to/src/main/kotlin/sample/Use.kt offset 41 in workspace
/absolute/path/to/workspace. Return the fully qualified name, kind,
declaration file, line, column, and type if present.
```

The packaged skill aligns that request to this command sequence:

```bash
KAST=$(bash .agents/skills/kast/scripts/resolve-kast.sh)
"$KAST" workspace ensure --workspace-root=/absolute/path/to/workspace
"$KAST" symbol resolve \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/sample/Use.kt \
  --offset=41
```

The key result fields are `symbol.fqName`, `symbol.kind`, `symbol.location`,
`symbol.type`, and `symbol.containingDeclaration`.

## Ask the agent to find references

Use `references` after symbol resolution when you want usage sites, caller
discovery, or a rough change-impact check. Ask for the declaration in the same
result when the agent needs one response that contains both the definition and
the usage list.

Example prompt for the agent:

```text
Use the packaged `kast` skill to find references for the symbol at
/absolute/path/to/src/main/kotlin/sample/Use.kt offset 41 in workspace
/absolute/path/to/workspace. Include the declaration and summarize each usage
by file, line, column, and preview.
```

The packaged skill aligns that request to this command sequence:

```bash
KAST=$(bash .agents/skills/kast/scripts/resolve-kast.sh)
"$KAST" workspace ensure --workspace-root=/absolute/path/to/workspace
"$KAST" references \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/sample/Use.kt \
  --offset=41 \
  --include-declaration=true
```

The key result fields are `declaration`, `references`, and `page.truncated`.

## Interpret the returned JSON

The result payload already contains enough structure for an LLM to make useful
semantic decisions. The important part is to read the right fields and report
their limits honestly.

| Field | How the agent should use it |
| --- | --- |
| `symbol.fqName` | Treat this as the stable symbol identity when comparing declarations and usages |
| `symbol.kind` | Confirm that the resolved target is the kind you expected, such as `FUNCTION` or `PROPERTY` |
| `symbol.location.filePath`, `startLine`, `startColumn` | Use these as the declaration anchor in summaries or follow-up file reads |
| `references[].preview` | Use this as a short usage snippet, not as a replacement for reading the full file |
| `declaration` | Use this optional field to keep declaration and usage data together in one response |
| `page.truncated` | Report that the result set was capped; do not imply you saw every usage when this is `true` |

## Use a reliable symbol-reference loop

The strongest LLM workflow is short and repetitive. Resolve first, then expand
to references only after the declaration identity is clear.

1. Ask the agent to run the packaged `kast` skill.
2. Resolve the symbol at the target file path and offset.
3. Verify the returned `fqName`, `kind`, and declaration location.
4. Run `references` against the same file path and offset.
5. Ask for `--include-declaration=true` when you want one combined summary.
6. Report `page.truncated` explicitly if Kast capped the result set.
7. Move to rename planning only after the declaration and reference spread make
   sense.

## Avoid common mistakes

Most bad symbol-reference results come from a small set of avoidable input or
interpretation mistakes.

- Do not pass relative paths for the workspace or file.
- Do not pass a line and column as though they were the `offset`.
- Do not aim the offset at whitespace, comments, or string contents.
- Do not assume `preview` is the full surrounding function body.
- Do not assume `page.truncated=true` means pagination is available in the
  current skill workflow.
- Do not ask this skill for `callHierarchy`; that remains a known gap.

## Next steps

Use the broader task and reference pages when you want the underlying CLI
commands outside the LLM-specific workflow.

- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
- [Command reference](command-reference.md)
