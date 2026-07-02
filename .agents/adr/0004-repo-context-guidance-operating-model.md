# ADR 0004: Repository context guidance operating model

Status: Accepted

Date: 2026-07-02

This ADR supersedes the repo-local agent guidance default in ADR 0002. ADR 0002
still owns manifest-backed resources, package verification, and active-binary
trust. This ADR owns how Kast chooses and patches the repository root context
file that an agent host loads.

## Context

`kast agent setup` currently installs the thin Kast skill and writes compact
Kast routing guidance to `AGENTS.local.md` by default. The file is added to
`.git/info/exclude` so it stays clone-local. That minimizes tracked repository
churn, but several agent hosts only load well-known root context files. In
those repositories, the generated guidance can be present but invisible to the
agent that needs it.

The installer also renders the guidance with a conventional skill path. That is
wrong once setup installs the skill somewhere else, such as `.codex/skills` or
an explicit external skill directory. The guidance should point at the known
real `SKILL.md` installed by the active binary.

A local prototype proved a workable Git model: keep a small managed
`<kast>...</kast>` region in the working tree and use clone-local Git clean,
smudge, and textconv filters so the managed region does not become normal
tracked review noise.

## Decision

Kast will manage repository agent guidance as a fenced region in the repository
context file most likely to be loaded by the active agent host.

The default context target resolver is:

1. Existing `AGENTS.md`
2. Existing `CODEX.md`
3. Existing `CLAUDE.md`
4. Existing `.github/copilot-instructions.md`
5. Existing `AGENTS.local.md`
6. Create `AGENTS.local.md`

Only the first default target is patched. Users may pass explicit context
targets when more than one file should receive Kast guidance.

Kast owns only this managed region:

```markdown
<kast files="*.kt, *.kts" type="instructions" replaceTools="grep,search,write">
...
</kast>
```

All user-authored content outside that region is preserved. If a legacy
`<!-- BEGIN KAST MANAGED -->` region exists, setup may replace it with the
current `<kast>` region.

The managed region must reference the actual installed skill path. If the skill
is installed below the workspace root, render a workspace-relative path. If the
skill is installed outside the workspace, render an absolute path.

## Source Of Truth

| Surface | Source of truth | Installed or generated output | Validation |
|---------|-----------------|-------------------------------|------------|
| Context target resolver | `cli-rs/src/install/agent_guidance.rs` | Selected root context file | CLI smoke tests |
| Managed region text | `cli-rs/src/install/agent_guidance.rs` | `<kast>...</kast>` region | Region checksum verification |
| Packaged skill path | `install_skill` result and manifest state | Guidance reference to `SKILL.md` | Agent setup tests |
| Clone-local Git filter | Rust installer-owned filter generation | `.git/config`, `.git/info/attributes`, `.git/tools/` | Git filter tests |
| Managed resource trust | `$HOME/.local/share/kast/install.json` | Agent guidance resource record | `kast agent workflow package-verify` |

## Implementation Contract

The forward CLI option is `--context-file <path>`. The existing
`--agents-md <path>` option remains as a compatibility alias.

V1 supports these explicit context files:

- `AGENTS.md`
- `CODEX.md`
- `CLAUDE.md`
- `.github/copilot-instructions.md`
- `AGENTS.local.md`

Tracked context files use a clone-local Git filter strategy. Setup writes local
metadata only:

- `.git/config`
- `.git/info/attributes`
- `.git/tools/kast-context-region-filter`

The clean and textconv modes remove only the managed `<kast>...</kast>` region.
The smudge mode preserves an existing managed region and injects the current
region when none is present. The attribute entry is scoped to the selected
context file, not every matching filename in the tree.

`AGENTS.local.md` remains the fallback for repositories without a known context
file. Because that file is intentionally local, setup keeps excluding it through
`.git/info/exclude` instead of requiring the Git filter path.

## Compatibility

Existing `AGENTS.local.md` installations remain valid and may be refreshed in
place. Existing `--agents-md` commands continue to work. Existing managed
`<kast>` regions are replaced in place when current setup output changes.

If the current managed region differs from the last recorded manifest checksum,
setup fails unless `--force` is passed. `--force` replaces only the managed
region and must not rewrite surrounding repository guidance.

Stale active-binary/resource combinations continue to fail closed. The recovery
path is to upgrade or reinstall Kast and rerun setup from the active binary.

## Change Process

When adding a new root context file kind:

1. Add it to the typed resolver registry in `agent_guidance`.
2. Add explicit-target validation and default-order tests.
3. Prove whether the file should use the Git filter strategy or local exclude
   strategy.
4. Update this ADR if the default order or ownership model changes.

Do not add host-specific filenames through ad hoc string checks. Context files
are part of the installer contract and must stay typed.

## Validation

Changes governed by this ADR require the narrowest affected CLI tests plus the
ADR/docs contract check. Normal validation includes:

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_setup_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_target_detection_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked install
.github/scripts/test-docs-content-contract.sh
git diff --check
```

Coverage must prove:

- default setup appends to existing `AGENTS.md`
- default setup selects existing `CODEX.md`
- default setup selects existing `CLAUDE.md`
- default setup selects existing `.github/copilot-instructions.md`
- no known context file creates `AGENTS.local.md`
- guidance points at the real installed `SKILL.md`
- Git clean removes only the managed region
- Git smudge injects the region when absent
- scoped Git attributes affect only the selected context file
- `--agents-md` compatibility still works
- `--context-file` accepts supported v1 filenames and rejects unsupported ones
