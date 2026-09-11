# Agent marketplaces

Kast keeps repository instructions and navigation in `AGENTS.md` files and
source-bound concepts in `knowledge/`. Reusable tooling comes from marketplaces;
installed plugin payloads and runtime caches are not repository authority.

## Configured sources

Setup retains the runtime's full configured marketplace set. No marketplace is
excluded by this repository.

| Provider | Marketplace | Source | Entrypoint |
|---|---|---|---|
| Codex | slopsentral | `https://github.com/amichne/slopsentral.git`, `harness/codex` | `.agents/plugins/marketplace.json` |
| Codex | worktrunk | `https://github.com/max-sixty/worktrunk.git` | Runtime marketplace configuration |
| Codex | openai-primary-runtime | Runtime-managed local marketplace | Runtime marketplace configuration |
| Codex | openai-bundled | App-managed local marketplace | Runtime marketplace configuration |

The local marketplace locations belong to the active runtime. Discover them
through its configuration rather than copying a developer's absolute cache paths
into this repository. Additional runtime-provided catalogs remain available.

## Expected plugins

- `code-knowledge-base@slopsentral`: source-bound Open Knowledge Format concepts,
  impact checks, repository signatures, and navigation through the marketplace's
  `local-repository-navigation` skill and `codebase-navigator` agent.
- `engineering-baseline@slopsentral`: proof-preserving engineering rules,
  repository onboarding, and turn-level instruction refresh hooks.

Both plugins are enabled in the current setup. Navigation migration is authored
in Slopsentral's `source/agents/codebase-navigator` and
`source/skills/local-repository-navigation`; runtime copies receive it through
normal marketplace publication and refresh. Do not patch installed caches.

## Repository setup

Keep every authored and generated `AGENTS.md` checked in. Preserve authored
instructions when refreshing maps, and keep the Engineering Dictum authoritative.
Generated maps route to knowledge concepts and concrete source paths; they do not
prove semantic correctness. `OUTDATED.local.md` and `.agent-turn/` remain local.

Run the marketplace agent's `scripts/setup-local-nav.sh --repo-root <checkout>`
to install the advisory post-commit hook. The default `collect` mode queues the
nearest existing guide for changed source and leaves maps for reviewed refresh.
Setup removes the old agent-file rules from Git's local exclude file, preserves
unrelated exclusions and hooks, and supports linked worktrees. Review any
repository or global ignore rules separately. It never stages or commits files.

## Refresh and validation

Refresh configured marketplaces and update the two expected plugins through the
runtime marketplace manager. Re-run navigation setup when its scripts change.
For source development, edit Slopsentral's canonical source, run its source graph
validator and navigation tests, then publish through its harness projection flow.

After reviewing changed guide directories, refresh their maps and clear the
local marker only when all reviews succeed. After changing cited source, run:

```shell
./gradlew knowledgeImpact
./gradlew verifyKnowledgeBase
```

Confirm new agent files appear in `git status --short`, stage them with their
source changes, and run `git diff --cached --check`. No installed plugin or cache
payload belongs in that diff.
