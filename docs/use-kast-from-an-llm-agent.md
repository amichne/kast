---
title: Use Kast from an LLM agent
description: Start with a human description of a Kotlin symbol and let the
  packaged `kast` skill bridge that request into semantic lookup.
icon: lucide/book-open
---

This guide shows the human-first path for the packaged `kast` skill. Start
with the symbol the way you would describe it to another engineer, such as
"find references to `HealthCheckService`" or "resolve the `retryDelay`
property on `RetryConfig`." The skill can bridge that request into the
precise lookup inputs that Kast needs.

!!! note
    The packaged skill can run `call hierarchy` after it has a confirmed symbol
    position. Ask it to resolve the symbol first, then request incoming or
    outgoing callers.

## Install the packaged skill into the workspace

Before an agent can use the packaged `kast` skill from a repository, link it
into that repository once. The `kast-skilled` command creates a symlink only,
so every workspace points back to the single packaged skill root from
`KAST_SKILL_PATH`.

1. Install Kast with `install.sh` if you have not already done that.

2. From the workspace root, run:

   ```bash
   kast-skilled
   ```

3. Confirm the prompt before the symlink is created. By default, the command
   picks `.agents/skills/kast`, `.github/skills/kast`, or `.claude/skills/kast`
   from the directories already present in the current directory.

## Start with a conversational reference

The best prompt names the target in human terms and says what you want back.
Lead with the class, function, or property you care about, then add just
enough context to disambiguate it.

- Name the symbol the way you naturally know it, such as a class name, a
  function name, or a property on a containing type.
- Say what the agent must do, such as resolve the symbol, find references, or
  assess rename impact.
- Say what the answer must include, such as the fully qualified name,
  declaration location, callers, or short usage previews.

Example prompts:

```text
Use the packaged `kast` skill to find references to `HealthCheckService` in
this workspace. Confirm the declaration first, then summarize the callers.
```

```text
Use the packaged `kast` skill to resolve the `retryDelay` property on
`RetryConfig`. Tell me where it is declared and what type Kast reports.
```

```text
Use the packaged `kast` skill to show the incoming call hierarchy for
`HealthCheckService.runChecks`. Resolve the symbol first, then summarize the
top callers and any truncation.
```

## Follow the golden path

The most reliable flow keeps the agent narrow at each stage. Resolve the
symbol first, confirm identity, and only then expand into broader usage or
edit planning.

1. Name the target in conversational terms.
2. Ask the agent to resolve the symbol before it gathers references.
3. Confirm the reported symbol kind, fully qualified name, and declaration
    match what you meant.
4. Ask for references, call hierarchy, rename impact, or diagnostics only
   after the identity is explicit.

## Let the skill bridge the mechanics

The packaged skill exists so you do not have to lead with raw CLI internals.
When the request is clear enough, the skill can bridge the low-level work for
you.

- Discover the correct `kast` executable through the skill's resolver script.
- Ensure the workspace daemon is ready before running analysis.
- Search for likely declaration sites from the human reference you gave it.
- Translate the selected declaration into the file and offset that Kast needs.
- Verify the target with `symbol resolve` before it expands into
  `references`, `call hierarchy`, `rename`, or other follow-up commands.

## Add context only when the name is ambiguous

Some workspaces have repeated names. When that happens, add more human context
before you fall back to raw file positions.

- Name the containing type, such as "`retryDelay` on `RetryConfig`."
- Name the module, package, or feature area where the symbol lives.
- Mention a nearby caller, implementation, or usage pattern you already know.
- Say which kind of symbol you expect, such as a class, function, or property.

Example refinements:

```text
Find references to the `timeoutMillis` property on `HttpClientConfig`, not
the local variable with the same name.
```

```text
Resolve `loadUser` in the API module, the function used by `UserController`.
```

## Ask for a result you can act on

A good answer is not just "found it." Ask the agent to summarize the parts
that help you decide what to do next.

- `fqName` or containing declaration for stable identity
- Symbol kind and, when present, type information
- Declaration file, line, and column
- References grouped or summarized by caller and file
- Whether Kast truncated the visible reference set or call hierarchy tree

## Move to scaffolding only when you need it

Most people do not need to think about absolute paths, UTF-16 offsets, or raw
request payloads. When you are authoring automation, debugging an ambiguous
lookup, or forcing a specific CLI invocation, use the advanced reference page
instead of putting those details on the main path.

- [LLM scaffolding reference](llm-scaffolding-reference.md)

## Next steps

Once the symbol identity is clear, move to the command pages that match the
next action you want to take.

- [Run analysis commands](run-analysis-commands.md)
- [LLM scaffolding reference](llm-scaffolding-reference.md)
- [Get started](get-started.md)
- [Command reference](command-reference.md)
