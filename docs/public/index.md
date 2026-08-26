---
okf_version: 1
---

# Compiler-grounded Kotlin operations for coding agents

Install Kast once. Add one global agent rule. Then mention Kast in any Kotlin or Gradle repository.

[Configure an agent](guides/agent-setup.md){ .md-button .md-button--primary }
[Browse capabilities](operations/symbols/symbol-discover.md){ .md-button }

## 1. Install

Kast currently requires Apple Silicon macOS and Java 21 or newer.

```sh
curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh | bash
kast --version
```

The installer places the control product on the user path. The first semantic command acquires the exact semantic runtime.

## 2. Configure the agent

Give the agent the prompt on [Agent setup](guides/agent-setup.md). It updates the user-level, always-on instruction file and preserves unrelated instructions.

## 3. Use Kast

Open a Kotlin or Gradle repository. Then ask:

> Use Kast to inspect this repository, find the exact declaration for `HealthController`, and describe it. Report each outcome as Complete, Qualified, or Rejected.

Kast discovers or reuses the exact-root runtime as part of semantic demand. Manual lifecycle control is not an onboarding step.

## Capabilities

<div class="grid cards" markdown>

-   **Find exact symbols**

    Discover candidates, resolve one exact selector, and describe the selected declaration.

    [Start with discovery](operations/symbols/symbol-discover.md)

-   **Read semantic relations**

    Read one compiler-grounded hop or compose bounded hops through traversal.

    [Read a relation](operations/relations/relation-read.md)

-   **Check compiler diagnostics**

    Read bounded diagnostics without interpreting incomplete coverage as absence.

    [Check diagnostics](operations/diagnostics/diagnostic-check.md)

-   **Make verified changes**

    Plan, apply, and verify through distinct identities. Applied does not mean verified.

    [Plan a change](operations/changes/change-plan.md)

</div>
