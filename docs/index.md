---
title: Kast docs
description: Install Kast, start a workspace runtime, and run the supported
  Kotlin analysis flow through the repo-local CLI.
icon: lucide/network
---

Kast is a Kotlin analysis tool that exposes semantic code intelligence through
a command-line interface. It uses the same K2 Analysis API that powers
IntelliJ IDEA, so it understands your code the way the IDE does — including
type inference, inheritance, and cross-module dependencies — but without the
graphical interface.

The current supported operator path is the repo-local `kast` command. These
pages focus on the flow that works today: install the CLI, ensure a workspace
runtime, run analysis commands, and stop the runtime when you are done. The
installer can also register `kast-skilled` for the packaged skill bridge and
offer to wire Bash or Zsh completions into your shell init file while it sets
up the launchers.

!!! note
    The current supported flow includes bounded `call hierarchy` traversal
    through the repo-local `kast` CLI.

## Start with the page that matches your goal

This short doc set is organized around the jobs people usually need to do
first.

<div class="grid cards" markdown>

-   __How Kast works__

    ---

    Understand the architecture, what Kast does, and when to use it instead
    of text search or an IDE.

    [Open the guide](how-it-works.md)

-   __Get started__

    ---

    Install the published CLI, start a workspace runtime, and verify that the
    workspace is ready for analysis.

    [Open the guide](get-started.md)

-   __Command reference__

    ---

    Keep the public command list, key options, and support boundaries nearby
    while you work.

    [Open the guide](command-reference.md)

-   __Run analysis commands__

    ---

    Use the common read and mutation commands with examples that stay close to
    the current CLI surface.

    [Open the guide](run-analysis-commands.md)

-   __Use Kast from an LLM agent__

    ---

    Start with conversational requests like "find references to
    `HealthCheckService`" and let the packaged `kast` skill bridge the lookup.

    [Open the guide](use-kast-from-an-llm-agent.md)

</div>

## Advanced prompting

Most readers do not need to think about offsets, request payloads, or raw
command reproduction. If you are authoring automation or debugging how the
skill maps a human reference into the CLI, use the advanced scaffolding page
after the human-first guide.

- [LLM scaffolding reference](llm-scaffolding-reference.md)

## What every Kast command has in common

Kast stays consistent across commands so you can move between tasks without
relearning the interface.

- Run `kast --help` for the grouped command overview. In interactive terminals,
  the help page uses ANSI color.
- Use `--key=value` syntax for command options.
- Pass absolute paths for `--workspace-root`, `--file-path`, and
  `--request-file`.
- Expect successful results as machine-readable JSON on stdout.
- Expect daemon lifecycle notes, when present, on stderr.
- Keep Java 21 or newer available on your path or under `JAVA_HOME`.
- Let the installer enable Bash or Zsh completions if you want tab completion
  immediately.

## A typical Kast session

Most sessions follow the same short loop, even when the specific analysis task
changes.

1. Install the published CLI with the copyable installer or `./install.sh`
   from a checkout, and enable shell completion if the installer offers it.
2. If you plan to use the packaged skill, run `kast-skilled` once from the
   workspace root to create the symlinked `kast` skill directory.
3. Open `kast --help` to confirm the grouped command view and your first
   completion-enabled shell.
4. Start or reuse a workspace runtime with `kast workspace ensure`.
5. Inspect `kast capabilities`, then run the analysis command you need.
6. Stop the workspace daemon with `kast daemon stop` when you are done.

## Next steps

If you are new to Kast, start with the guided setup page. If you already have a
running workspace daemon, jump straight to the task and reference pages.

- [How Kast works](how-it-works.md)
- [Get started](get-started.md)
- [Run analysis commands](run-analysis-commands.md)
- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md)
- [LLM scaffolding reference](llm-scaffolding-reference.md)
- [Command reference](command-reference.md)
