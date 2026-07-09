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
| macOS developer | `docs/install/macos.md` | Install the binary and plugin, open a repository, and verify readiness | `docs/learn/first-semantic-workflow.md` |
| Linux or hosted-agent operator | `docs/install/headless-linux.md` | Install a headless bundle, prepare repository guidance, and verify the backend | `docs/learn/first-semantic-workflow.md` |
| Agent or script author | `docs/use/automate-with-agents.md` | Learn the public typed `kast agent` paths and output-mode expectations | `docs/reference/agent-commands.md` |
| CLI operator | `docs/use/choose-a-command.md` | Choose the command group that matches setup, readiness, runtime, inspection, or edit work | `docs/reference/commands.md` |
| Kotlin maintainer | `docs/use/inspect-kotlin.md` | Resolve Kotlin symbols, references, callers, diagnostics, and impact before acting | `docs/use/plan-safe-edits.md` |
| Release or mirror engineer | `docs/distribute/release-and-mirror.md` | Package, verify, mirror, or activate release artifacts | `docs/distribute/runtime-artifact-contract.md` |
| Stuck reader | `docs/troubleshoot.md` | Separate install drift, backend state, indexing, semantic failures, and mutation planning | Relevant install, use, or reference page |
| Architecture-curious reader | `docs/design/operating-model.md` | Understand why Kast separates distribution, setup, runtime, semantic commands, and evidence | Relevant journey page |

## Diataxis Page Map

Classify each page by the reader need it primarily serves. If a future edit
adds a second need, split the content or link to the owning page instead of
turning one page into a mixed narrative.

| Page | Diataxis role | Reader job | Watch point |
| --- | --- | --- | --- |
| `docs/index.md` | Landing/index with short explanation | Route readers by journey and show the operating layers | Keep context brief and command-facing |
| `docs/install/macos.md` | How-to guide | Install or repair a macOS developer-machine setup | Do not absorb headless Linux details |
| `docs/install/headless-linux.md` | How-to guide | Install, mirror, or image a Linux headless runtime | Link deep release contract details instead of duplicating them |
| `docs/learn/first-semantic-workflow.md` | Guided first-run workflow | Give a first semantic command sequence | Do not claim true tutorial reliability without a verified fixture |
| `docs/learn/evidence-model.md` | Explanation | Explain semantic evidence, bounded results, and plan-first edits | Keep instructions in use/reference pages |
| `docs/use/choose-a-command.md` | How-to guide | Pick the right command family for a task | Link to reference for option detail |
| `docs/use/inspect-kotlin.md` | How-to guide | Use semantic lookup before code changes | Keep examples task-oriented |
| `docs/use/plan-safe-edits.md` | How-to guide | Plan and apply safe renames or scope mutations | Preserve plan-before-apply guidance |
| `docs/use/automate-with-agents.md` | How-to guide | Use typed agent commands in scripts and agents | Keep raw transport out of public workflow |
| `docs/reference/commands.md` | Reference | Look up curated public command groups | State that this is curated guidance, not generated exhaustive reference |
| `docs/reference/agent-commands.md` | Reference | Look up typed `kast agent` commands | Mirror live `kast agent --help` command names |
| `docs/reference/mutation-selectors.md` | Reference | Look up mutation selectors and anchors | Keep examples minimal and source-backed |
| `docs/reference/runtime-and-output.md` | Reference | Look up runtime, readiness, repair, backend, and output-mode facts | Keep procedure in how-to pages |
| `docs/troubleshoot.md` | Diagnostic how-to/reference matrix | Diagnose readiness, backend, indexing, semantic, and mutation failures | Use symptom, cause, check, fix shape |
| `docs/distribute/release-and-mirror.md` | How-to guide | Build, verify, mirror, and activate release artifacts | Link runtime manifest facts to contract reference |
| `docs/distribute/runtime-artifact-contract.md` | Reference | Look up bundle, manifest, checksum, and ledger contracts | Keep schema link current |
| `docs/design/operating-model.md` | Explanation | Understand system boundaries that affect operations | Do not turn into broad architecture essay |

## Gap register

These are the current epistemic gaps exposed by the reader-journey pass. They
are not mandatory pages; treat them as prompts for the next docs change that
touches the same area.

| Gap | Why it matters | Good next action |
| --- | --- | --- |
| Verified tutorial fixture | The first semantic workflow uses readable placeholder symbols, so the page cannot guarantee every step works for a new reader | Add or link a small real Kotlin fixture and expected output before calling it a full tutorial |
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
