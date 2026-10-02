# Agent marketplaces

Kast keeps repository instructions and navigation in `AGENTS.md` files and
source-bound concepts and Claims in `openwiki/`. Reusable tooling comes from marketplaces;
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

- OpenWiki Codex integration: the user-installed `openwiki` skill and MCP server
  own wiki search/read and the resumable page/Claim lifecycle. `openwiki/` replaces
  the previous `knowledge/` concept bundle. The repository keeps its exact symbol
  and source-impact checks through the existing Gradle knowledge tasks.
- `code-knowledge-base@slopsentral`: retained repository signatures and scoped
  `AGENTS.md` navigation; use OpenWiki for concept generation and upkeep.
- `engineering-baseline@slopsentral`: proof-preserving engineering rules,
  repository onboarding, and turn-level instruction refresh hooks.

Installed plugin state belongs to the active runtime. Navigation migration is authored
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

Refresh configured marketplaces and update installed plugins through the
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

OpenWiki privacy and model settings belong in the private user environment, not
in Git. For the supported subscription flow use `OPENWIKI_PROVIDER=openai-chatgpt`
and the OpenWiki browser login. The configured native model is `gpt-6-luna`. Keep
`OPENWIKI_TELEMETRY_DISABLED=1`, `DO_NOT_TRACK=1`, `LANGCHAIN_TRACING_V2=false` and
`LANGSMITH_TRACING=false`. Codex-hosted page authoring uses the current Codex chat
model; the native OpenWiki CLI uses its own model setting.
