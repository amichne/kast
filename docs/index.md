---
title: Kast docs
description: IDE-grade Kotlin code intelligence for terminals, CI, and IDEs.
icon: lucide/network
---

Kast gives you IDE-grade Kotlin code intelligence wherever you need it. It
uses the Kotlin K2 Analysis API to answer semantic questions about real
workspaces and returns structured JSON that scripts, CI jobs, and LLM agents
can consume directly. Kast runs as a standalone CLI daemon or as an IntelliJ
IDEA plugin — both expose the same JSON-RPC protocol.

Kast is built to answer questions such as:

- What symbol is at this location, and where is it declared?
- Where is this symbol used, and what calls this function?
- What changes if I rename this, and can I apply those edits safely?
- What declarations does this file contain, and how are they nested?
- Where is a symbol by name when you don't already know the file and offset?

Start with the page that matches how you want to approach the tool.

<div class="grid cards" markdown>

-   __Why Kast__

    ---

    See where Kast fits between text search, IDE workflows, and automation.

    [Open the guide](why-kast.md)

-   __What you can do__

    ---

    Learn the questions Kast can answer before you think about commands or
    request payloads.

    [Open the guide](what-you-can-do.md)

-   __How Kast works__

    ---

    Understand the high-level flow from the CLI to the daemon and into the K2
    analysis engine.

    [Open the guide](how-it-works.md)

-   __Things to know__

    ---

    Read the behavioral model and result boundaries that matter when you
    interpret Kast output.

    [Open the guide](things-to-know.md)

-   __Get started__

    ---

    Install Kast, attach it to a workspace, and verify that the runtime is
    ready.

    [Open the guide](get-started.md)

-   __Use Kast from an LLM agent__

    ---

    Start from a human description of a symbol and let the packaged skill
    bridge the lookup.

    [Open the guide](use-kast-from-an-llm-agent.md)

</div>

If you already know the flow and need the operational surface, move to
[Run analysis commands](run-analysis-commands.md),
[Command reference](command-reference.md), or the
[LLM scaffolding reference](llm-scaffolding-reference.md).
