---
name: "refresh-affected-agents"
description: "Update only the `AGENTS.md` files that sit on modified git paths by deriving scope from `git diff`. Use when a repository already has hierarchical `AGENTS.md` files and Codex needs to refresh local instructions after source files, manifests, build scripts, or codegen boundaries change in the current worktree or in a specified diff range."
---

# Refresh affected AGENTS

Use this skill to maintain `AGENTS.md` files from git evidence instead of
re-auditing an entire repository. Compute the affected `AGENTS.md` set first,
then inspect only the covered paths and nearby manifests before making minimal
updates.

## Quick start

Start with the diff-derived target set so the edit scope stays explicit and
verifiable.

1. Resolve the repository root with `git rev-parse --show-toplevel`.
2. Run `scripts/find_affected_agents.py` from anywhere inside the repository.
3. If the user wants a branch or PR diff, run
   `scripts/find_affected_agents.py --base <ref>` and optionally
   `--head <ref>`.
4. If no `agent_files` are returned, stop and report that no `AGENTS.md` files
   are in scope.
5. Read `references/agents-update-contract.md` before editing any file.

## Workflow

Follow this sequence to keep the updates narrow, evidence-backed, and aligned
with the repository's existing instruction hierarchy.

### Select the diff surface

Make the diff source explicit before reading or editing any `AGENTS.md` file.

- Default to the current worktree. The helper script unions unstaged, staged,
  and untracked paths.
- When the user asks for branch, PR, or commit-range maintenance, pass
  `--base <ref>` and optionally `--head <ref>`. The helper script uses
  `git diff <base>...<head>` so the scope matches the branch delta from the
  merge base.
- Keep the chosen diff surface explicit in your final response.

### Compute the target AGENTS set

Use the helper output as the source of truth for which `AGENTS.md` files are
eligible for refresh.

- Run `scripts/find_affected_agents.py --format json`.
- Use only the `agent_files` returned by the script.
- Use each target's `covered_paths` to decide what code, manifests, and build
  surfaces to inspect.
- If a rename crosses instruction boundaries, keep both the old and new paths
  in play. The helper script emits both sides of a rename.

### Gather local evidence

Inspect only the material needed to decide whether each target file needs an
update.

- Read the target `AGENTS.md`.
- Read the nearest parent `AGENTS.md` to avoid duplicating parent guidance.
- Inspect manifests, build files, codegen inputs, and changed source trees
  within the target's `covered_paths`.
- Prefer repository entry points and local manifests over directory-name
  guesses.
- Do not invent commands, ownership rules, or generated-code boundaries.

### Draft minimal updates

Change as little as possible while keeping the instructions correct for the
affected scope.

- Preserve existing guidance unless the diff proves it is stale.
- Update only the local delta from the parent `AGENTS.md`.
- If the covered paths do not materially change instructions, leave the file
  unchanged.
- Keep instructions concrete: commands, edit boundaries, source-of-truth
  inputs, and verification steps.

### Verify the refresh

Close the loop with lightweight checks that prove the edited instructions still
match the repository.

- Re-run `scripts/find_affected_agents.py` and confirm the target list matches
  the files you considered.
- Sanity-check every path or manifest you mention.
- Run at least one representative local validation command for each edited
  scope when the repository makes that practical.
- In the final response, list the edited `AGENTS.md` files and the verification
  commands used.

## Resources

The bundled resources keep the skill small while still giving it a deterministic
entry point and a drafting contract.

- `scripts/find_affected_agents.py`: Compute the in-scope `AGENTS.md` files
  from git diff data.
- `references/agents-update-contract.md`: Drafting rules for targeted
  `AGENTS.md` refreshes.
