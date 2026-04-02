---
title: Kast docs
description: Install Kast, start a workspace runtime, and run the supported
  Kotlin analysis flow through the repo-local CLI.
icon: lucide/network
---

Kast has one supported operator path: run the repo-local `kast` command
against the workspace you want to analyze. These pages focus on the flow that
works today: install the CLI, ensure a workspace runtime, run analysis
commands, and stop the runtime when you are done.

!!! note
    The current supported flow does not advertise `callHierarchy` yet.

## Start with the page that matches your goal

This short doc set is organized around the jobs people usually need to do
first.

<div class="grid cards" markdown>

-   __Get started__

    ---

    Install the published CLI, start a workspace runtime, and verify that the
    workspace is ready for analysis.

    [Open the guide](get-started.md)

-   __Run analysis commands__

    ---

    Use the common read and mutation commands with examples that stay close to
    the current CLI surface.

    [Open the guide](run-analysis-commands.md)

-   __Use Kast from an LLM agent__

    ---

    Run semantic symbol and reference lookups through the packaged `kast`
    skill and its CLI discovery workflow.

    [Open the guide](use-kast-from-an-llm-agent.md)

-   __Command reference__

    ---

    Keep the public command list, key options, and support boundaries nearby
    while you work.

    [Open the guide](command-reference.md)

</div>

## What every Kast command has in common

Kast stays consistent across commands so you can move between tasks without
relearning the interface.

- Use `--key=value` syntax for command options.
- Pass absolute paths for `--workspace-root`, `--file-path`, and
  `--request-file`.
- Expect successful results as machine-readable JSON on stdout.
- Expect daemon lifecycle notes, when present, on stderr.
- Keep Java 21 or newer available on your path or under `JAVA_HOME`.

## A typical Kast session

Most sessions follow the same short loop, even when the specific analysis task
changes.

1. Install the published CLI with `./install.sh`.
2. Start or reuse a workspace runtime with `kast workspace ensure`.
3. Inspect `kast capabilities`, then run the analysis command you need.
4. Stop the workspace daemon with `kast daemon stop` when you are done.

## Next steps

If you are new to Kast, start with the guided setup page. If you already have a
running workspace daemon, jump straight to the task and reference pages.

- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md)
- [Command reference](command-reference.md)
