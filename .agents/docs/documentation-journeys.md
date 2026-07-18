# Documentation journeys

This agent-only map keeps the published Zensical site shaped around reader
jobs instead of loose prose accumulation. Use it with
`.agents/skills/writing-documentation-with-diataxis/SKILL.md` before creating,
moving, or substantially rewriting pages under `docs/`.

The public site is journey-first CLI documentation. ADR 0011 owns that
boundary: published pages start from reader jobs, then route to command
reference when readers need lookup accuracy. Raw RPC references, generated
protocol pages, and detached product essays stay out of `docs/` unless a
superseding ADR changes the surface.

## Reader journeys

Start by naming the reader and the action they need to complete. A page can
serve more than one reader only when the route is obvious from headings, tabs,
or navigation.

| Reader | Entry point | Reader job | Next page |
| --- | --- | --- | --- |
| macOS developer | `docs/install/macos.md` | Install the matched CLI and plugin, then open the project | `docs/learn/evidence-model.md` |
| Linux or hosted-agent operator | `docs/install/headless-linux.md` | Install a headless bundle for CI, hosted agents, or server images | `docs/distribute/runtime-artifact-contract.md` |
| Developer evaluator | `docs/learn/repository-demo.md` | Explore semantic evidence from the Kotlin repository already open | `docs/learn/evidence-model.md` |
| Agent or script author | `docs/use/automate-with-agents.md` | Understand the typed semantic operations agents use behind the scenes | `docs/reference/agent-commands.md` |
| CLI operator | `docs/use/choose-a-command.md` | Choose the high-level command family for inspection, editing, automation, or release work | `docs/reference/commands.md` |
| Kotlin maintainer | `docs/use/inspect-kotlin.md` | Understand how agents resolve Kotlin symbols, references, callers, diagnostics, and impact before acting | `docs/use/plan-safe-edits.md` |
| Release or mirror engineer | `docs/distribute/release-and-mirror.md` | Package, verify, mirror, or activate release artifacts | `docs/distribute/runtime-artifact-contract.md` |
| Kast contributor | `docs/distribute/local-development-refresh.md` | Build and exercise one exact checkout without publishing a release | `docs/reference/runtime-and-output.md` |
| Stuck reader | `docs/troubleshoot.md` | Separate install drift, backend state, indexing, semantic failures, and mutation planning | Relevant install, use, or reference page |
| Architecture-curious reader | `docs/design/operating-model.md` | Understand why Kast separates distribution, setup, runtime, semantic commands, and evidence | Relevant journey page |

## Diataxis Page Map

Classify each page by the reader need it primarily serves. If a future edit
adds a second need, split the content or link to the owning page instead of
turning one page into a mixed narrative.

| Page | Diataxis role | Reader job | Watch point |
| --- | --- | --- | --- |
| `docs/index.md` | Landing/index with short explanation | Route readers by journey and show the operating layers | Keep context brief and command-facing |
| `docs/install/macos.md` | How-to guide | Install the macOS machine support with one visible path | Keep setup, readiness, repair, and support commands collapsed |
| `docs/install/headless-linux.md` | How-to guide | Install, mirror, or image a Linux headless runtime | Keep repository guidance and backend checks collapsed |
| `docs/learn/first-semantic-workflow.md` | Explanation with collapsed execution detail | Show the semantic workflow agents run behind the scenes | Do not present agent commands as normal developer steps |
| `docs/learn/repository-demo.md` | Tutorial | Learn Kast by exploring read-only evidence from the current repository | Keep the path deterministic and state degraded evidence explicitly |
| `docs/learn/evidence-model.md` | Explanation | Explain semantic evidence, bounded results, and plan-first edits | Keep instructions in use/reference pages |
| `docs/use/choose-a-command.md` | How-to guide | Pick the right command family for a task | Keep exact command families collapsed when they are agent/operator detail |
| `docs/use/inspect-kotlin.md` | Explanation/how-to hybrid | Understand semantic lookup before code changes | Keep command examples collapsed |
| `docs/use/plan-safe-edits.md` | Explanation/how-to hybrid | Understand safe rename and scope mutation planning | Keep mutation commands collapsed |
| `docs/use/automate-with-agents.md` | How-to guide | Use typed semantic operations in scripts and agents | Keep raw transport out and setup commands collapsed |
| `docs/reference/commands.md` | Reference | Look up curated public command groups | Mention only readable and JSON output publicly |
| `docs/reference/agent-commands.md` | Reference | Understand typed `kast agent` capabilities | Keep command names and examples collapsed |
| `docs/reference/mutation-selectors.md` | Reference | Understand mutation selector concepts | Keep exact flags collapsed |
| `docs/reference/runtime-and-output.md` | Reference | Look up runtime and public output behavior | Mention only readable and JSON output publicly |
| `docs/troubleshoot.md` | Diagnostic how-to/reference matrix | Diagnose visible install, backend, indexing, semantic, and mutation failures | Keep read-only command sequences collapsed |
| `docs/distribute/release-and-mirror.md` | How-to guide | Build, verify, mirror, and activate release artifacts | Link runtime manifest facts to contract reference |
| `docs/distribute/local-development-refresh.md` | How-to guide | Refresh, verify, roll back, or remove one revision-coherent checkout authority | Keep internal attestation commands behind the one Gradle entrypoint |
| `docs/distribute/runtime-artifact-contract.md` | Reference | Look up bundle, manifest, checksum, and ledger contracts | Keep schema link current |
| `docs/design/operating-model.md` | Explanation | Understand system boundaries that affect operations | Do not turn into broad architecture essay |

## Gap register

These are the current epistemic gaps exposed by the reader-journey pass. They
are not mandatory pages; treat them as prompts for the next docs change that
touches the same area.

| Gap | Why it matters | Good next action |
| --- | --- | --- |
| Verified tutorial fixture | The first semantic workflow is intentionally explanatory because agent commands are normally hidden | Add or link a small real Kotlin fixture only if the page becomes a real tutorial |
| Command reference completeness | Reference pages are curated; readers may not know whether missing flags are unsupported or just undocumented | Generate/check command coverage from `kast --help`, or keep the explicit curated-scope note |
| Example provenance | Placeholder names such as `OrderService` are readable but not verifiable | Prefer examples backed by fixtures, tests, command output, or clearly marked placeholders |
| Public explanation boundary | The site needs short conceptual context, but broad product decisions still belong in ADRs | Keep public explanation tied to operating Kast, and link deep reasoning to ADRs only when useful |
| External path churn | Navigation restructuring changes published URLs | Update README and internal links; add redirect support later only if the docs toolchain supports it |

## Authoring rules

Before editing `docs/`, write down the reader, reader job, and page role. Use
the Diataxis compass from the installed skill when the role is not obvious:

- Guided first-run workflow: one reliable path, one environment per path,
  expected checks, and no optional branches unless they are routed out.
- How-to guide: a concrete reader goal, practical sequence, conditionals for
  real-world variation, and links to reference for option detail.
- Reference: neutral description, consistent structure, source ownership, and
  completeness relative to the command or artifact being described.
- Explanation: short public context only. Put durable reasoning in ADRs unless
  the public documentation operating model changes.
- Diagnostic matrix: symptom, likely cause, read-only check, and fix path.

Claims must be backed by at least one source: implementation, `kast --help`,
schema files, contract scripts, generated artifacts, README, or accepted ADRs.
If the source is missing, narrow the claim or mark the behavior as planned or
unsupported.

## Validation

Use the narrowest checks that match the change, then broaden when navigation,
command coverage, or release contracts move:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

When examples depend on CLI behavior, also run the focused command, fixture, or
contract test that proves the example still matches the product.
