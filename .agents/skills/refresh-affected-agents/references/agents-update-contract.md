# AGENTS update contract

Use this reference when the helper script has already identified the
`AGENTS.md` files that sit on changed paths. The goal is to refresh only the
instruction files whose scope is touched by the diff, and only when the diff
actually changes what those instructions should say.

## What to include

Keep each edited `AGENTS.md` actionable for the subtree it governs.

- State the scope if it is not obvious from the directory.
- Record local build, test, lint, or codegen commands when they differ from the
  parent.
- Record edit boundaries: what is safe to edit, what is generated, and what
  must be regenerated instead.
- Record realistic verification steps for that subtree.
- Record assumptions only when the repository does not prove the workflow.

## What to avoid

Keep the refresh narrow. The diff is the trigger, not permission to rewrite the
entire hierarchy.

- Do not repeat repo-wide guidance that already belongs in a parent
  `AGENTS.md`.
- Do not restate unchanged sections just because the file is in scope.
- Do not invent commands, ownership claims, or validation steps.
- Do not add vague advice with no concrete action attached.

## Root file expectations

Treat the root `AGENTS.md` as repository-wide operating guidance, even when it
appears in scope because any changed path descends from the repo root.

- Update it only when the diff changes shared constraints, common entry points,
  or global validation posture.
- Leave module-specific detail in child `AGENTS.md` files.

## Child file expectations

Treat child `AGENTS.md` files as local deltas from the nearest parent.

- Describe only the local toolchain, commands, edit restrictions, and verify
  steps that differ from the parent.
- Point to nearest source-of-truth inputs when the subtree contains generated
  outputs.
- Omit commands that are already clear at the parent level unless the local
  invocation or prerequisite differs.

## Update decision rule

Use the diff-covered paths to decide whether a target file needs a change at
all.

1. Read the target `AGENTS.md` and its nearest parent.
2. Read the changed files, manifests, or generators inside the target's
   `covered_paths`.
3. If the evidence does not change the local instructions, leave the file
   untouched.
4. If the evidence changes instructions, update only the affected sections.
